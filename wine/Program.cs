using System.Globalization;
using System.Reflection;
using System.Reflection.Emit;
using System.Reflection.PortableExecutable;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;

namespace Sprout.Wine;

// One request file, one process, one result file. Library stdout/stderr is never protocol traffic.
internal static class Program
{
    static readonly Dictionary<string, object?> Objects = new();
    static readonly List<object> Owned = new();
    static readonly Dictionary<string, Type> Delegates = new();
    static Assembly? assembly;
    static IntPtr library;
    static string kind = "", path = "";
    static object? component;
    static int stepIndex = -1;
    const int MaxFrame = 1024 * 1024;
    static readonly JsonSerializerOptions JsonOptions = new() { IncludeFields = true };

    [DllImport("kernel32", CharSet = CharSet.Unicode, SetLastError = true)] static extern IntPtr LoadLibraryEx(string name, IntPtr file, uint flags);
    [DllImport("kernel32", CharSet = CharSet.Ansi, ExactSpelling = true, SetLastError = true)] static extern IntPtr GetProcAddress(IntPtr module, string name);
    [DllImport("kernel32")] static extern bool FreeLibrary(IntPtr module);
    [DllImport("kernel32")] static extern uint SetErrorMode(uint mode);
    [DllImport("kernel32", CharSet = CharSet.Unicode)] static extern uint GetModuleFileName(IntPtr module, StringBuilder name, int size);

    [STAThread]
    static int Main(string[] args)
    {
        if (args.Length != 4 || args[0] != "--request" || args[2] != "--result") {
            Console.Error.WriteLine("Usage: Sprout.Wine.Helper --request <json-file> --result <json-file>");
            return 2;
        }
        try {
            Console.OutputEncoding = new UTF8Encoding(false);
            SetErrorMode(0x0001 | 0x0002 | 0x8000);
            if (new FileInfo(args[1]).Length > MaxFrame) throw new ArgumentException("Request exceeds 1 MiB");
            using var doc = JsonDocument.Parse(File.ReadAllBytes(args[1]));
            var request = doc.RootElement;
            var expected = Text(request, "architecture", "x64");
            if (expected != (IntPtr.Size == 8 ? "x64" : "x86")) throw new ArgumentException("Invoker architecture mismatch");
            Load(request);
            var batch = request.TryGetProperty("steps", out var array) && array.GetArrayLength() > 0;
            var steps = batch ? array.EnumerateArray().ToArray() : new[] { request };
            if (steps.Length > 64) throw new ArgumentException("At most 64 steps");
            var results = new List<object>();
            object? finalValue = null; object[] finalOutputs = Array.Empty<object>();
            foreach (var step in steps) {
                stepIndex++;
                var saveAs = Text(step, "saveAs");
                if (saveAs.Length > 0 && Objects.ContainsKey(saveAs)) throw new ArgumentException("Duplicate saveAs: " + saveAs);
                var result = Invoke(step);
                if (saveAs.Length > 0) Objects.Add(saveAs, result.Value);
                finalValue = Pack(result.Value, saveAs.Length > 0);
                finalOutputs = result.Outputs;
                results.Add(new { value = finalValue, outputs = finalOutputs });
            }
            Write(args[3], new { ok = true, result = new { value = finalValue, outputs = finalOutputs, steps = batch ? results : new List<object>() } });
            return 0;
        } catch (Exception error) {
            while (error is TargetInvocationException && error.InnerException != null) error = error.InnerException;
            Write(args[3], new { ok = false, error = new { code = error.GetType().Name, message = error.Message, step = stepIndex, hresult = error.HResult } });
            return 1;
        }
        finally {
            foreach (var value in Owned.ToArray()) {
                try { Dispose(value); } catch (Exception error) { Console.Error.WriteLine(error.Message); }
            }
            if (library != IntPtr.Zero) FreeLibrary(library);
        }
    }

    static void Write(string destination, object response)
    {
        var bytes = JsonSerializer.SerializeToUtf8Bytes(response, JsonOptions);
        if (bytes.Length > MaxFrame) bytes = Encoding.UTF8.GetBytes("{\"ok\":false,\"error\":{\"code\":\"result_limit\",\"message\":\"Result exceeds 1 MiB\"}}");
        File.WriteAllBytes(destination + ".tmp", bytes);
        File.Move(destination + ".tmp", destination);
    }
    static string Text(JsonElement value, string key, string fallback = "") => value.TryGetProperty(key, out var field) && field.ValueKind == JsonValueKind.String ? field.GetString()! : fallback;
    static JsonElement[] Args(JsonElement request) => request.TryGetProperty("args", out var args) ? args.EnumerateArray().ToArray() : Array.Empty<JsonElement>();
    sealed class Invocation {
        public object? Value;
        public object[] Outputs;
        public Invocation(object? value, object[]? outputs = null) { Value = value; Outputs = outputs ?? Array.Empty<object>(); }
    }
    static Invocation Invoke(JsonElement request) => Text(request, "operation", "invoke") switch {
        "inspect" => new Invocation(JsonSerializer.SerializeToElement(Inspect(request), JsonOptions)),
        "release" => new Invocation(Release(Text(request, "target"))),
        "read" => new Invocation(ReadPointer(request)),
        _ => Call(request)
    };
    static object Load(JsonElement request)
    {
        var selected = Text(request, "kind");
        path = Text(request, "path");
        switch (selected) {
            case "native":
                // Absolute paths load private dependencies beside the DLL; bare names select system DLLs.
                library = LoadLibraryEx(path, IntPtr.Zero, Path.IsPathRooted(path) ? 0x00000008u : 0u);
                if (library == IntPtr.Zero) throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
                break;
            case "dotnet":
                assembly = Assembly.LoadFrom(path);
                AppDomain.CurrentDomain.AssemblyResolve += (_, args) => {
                    var candidate = Path.Combine(Path.GetDirectoryName(path)!, new AssemblyName(args.Name).Name + ".dll");
                    return File.Exists(candidate) ? Assembly.LoadFrom(candidate) : null;
                };
                break;
            case "com":
                var id = Text(request, "classId");
                var type = Guid.TryParse(id, out var guid) ? Type.GetTypeFromCLSID(guid, true) : Type.GetTypeFromProgID(id, true);
                component = Activator.CreateInstance(type!);
                Own(component);
                break;
            default: throw new ArgumentException("kind must be native, dotnet or com");
        }
        kind = selected;
        return new { kind, path, runtime = RuntimeInformation.FrameworkDescription, bits = IntPtr.Size * 8 };
    }
    static Invocation Call(JsonElement request)
    {
        if (kind.Length == 0) throw new InvalidOperationException("Load a DLL first");
        if (kind == "native") {
            if (Text(request,"operation","invoke") != "invoke") throw new ArgumentException("Native functions require operation=invoke");
            return NativeCall(request);
        }
        var arguments = Args(request);
        var values = arguments.Select(ManagedArgument).ToArray();
        var targetId = Text(request, "target");
        var target = targetId.Length > 0 ? Objects[targetId] : component;
        var operation = Text(request, "operation", "invoke");
        var member = Text(request, "member");
        object? result;
        if (kind == "com") {
            var flags = operation switch { "get" => BindingFlags.GetProperty, "set" => BindingFlags.SetProperty, "invoke" => BindingFlags.InvokeMethod, _ => throw new ArgumentException("COM operation must be invoke, get or set") };
            result = target!.GetType().InvokeMember(member, flags | BindingFlags.Public | BindingFlags.Instance, null, target, values, CultureInfo.InvariantCulture);
        } else {
            var type = target?.GetType() ?? assembly!.GetType(Text(request, "typeName"), true)!;
            var types = arguments.Select(a => ManagedType(Text(a, "type"))).ToArray();
            if (operation == "create") {
                var ctor = type.GetConstructor(types) ?? throw new MissingMethodException("Constructor signature not found");
                result = ctor.Invoke(values);
            } else if (operation == "invoke") {
                var method = type.GetMethod(member, BindingFlags.Public | (target == null ? BindingFlags.Static : BindingFlags.Instance), null, types, null)
                    ?? throw new MissingMethodException("Method signature not found; use inspect to see parameter types");
                result = method.Invoke(target, values);
                if (result is Task task) { task.GetAwaiter().GetResult(); result = task.GetType().GetProperty("Result")?.GetValue(task); }
            } else if (operation == "get" || operation == "set") {
                var property = type.GetProperty(member) ?? throw new MissingMemberException(member);
                if (operation == "get") result = property.GetValue(target, values);
                else { if (values.Length != 1) throw new ArgumentException("set expects one value"); property.SetValue(target, values[0]); result = null; }
            } else throw new ArgumentException("Unknown managed operation");
        }
        Own(result);
        return new Invocation(result);
    }
    static Type ManagedType(string name) => name switch {
        "i8" => typeof(sbyte), "u8" => typeof(byte), "i16" => typeof(short), "u16" => typeof(ushort),
        "i32" => typeof(int), "u32" => typeof(uint), "i64" => typeof(long), "u64" => typeof(ulong),
        "f32" => typeof(float), "f64" => typeof(double), "bool" => typeof(bool), "string" => typeof(string),
        "bytes" => typeof(byte[]), "object" => typeof(object),
        _ => Type.GetType(name, false) ?? assembly?.GetType(name, false) ?? throw new ArgumentException("Unknown managed type: " + name)
    };
    static object? ManagedArgument(JsonElement argument)
    {
        var type = ManagedType(Text(argument, "type"));
        var reference = Text(argument, "ref");
        if (reference.Length > 0) return Objects[reference];
        if (!argument.TryGetProperty("value", out var value) || value.ValueKind == JsonValueKind.Null) return null;
        if (type == typeof(byte[])) return Convert.FromBase64String(value.GetString()!);
        if (type == typeof(object)) return value.ValueKind switch {
            JsonValueKind.String => value.GetString(), JsonValueKind.True => true, JsonValueKind.False => false,
            JsonValueKind.Number => value.TryGetInt32(out var integer) ? (object)integer : value.GetDouble(), _ => throw new ArgumentException("Use a typed value or object handle")
        };
        if (value.ValueKind == JsonValueKind.String && type != typeof(string)) return Convert.ChangeType(value.GetString(), type, CultureInfo.InvariantCulture);
        return JsonSerializer.Deserialize(value.GetRawText(), type, JsonOptions);
    }
    static object? Pack(object? value, bool saved)
    {
        if (value == null) return null;
        if (value is long || value is ulong) return Convert.ToString(value, CultureInfo.InvariantCulture);
        if (value is byte[] bytes) return Convert.ToBase64String(bytes);
        if (value is not IntPtr && (value.GetType().IsPrimitive || value is string || value is decimal || value is JsonElement)) return value;
        if (saved) return null; // References are internal to this invocation, never persistent API handles.
        throw new ArgumentException("Object/pointer results require saveAs and subsequent steps to extract a scalar, string or bytes");
    }
    static void Own(object? value) {
        if (value != null && (Marshal.IsComObject(value) || value is IDisposable) && !Owned.Any(v => ReferenceEquals(v,value))) Owned.Add(value);
    }
    static void Dispose(object value) {
        Owned.RemoveAll(v => ReferenceEquals(v,value));
        if (Marshal.IsComObject(value)) Marshal.FinalReleaseComObject(value);
        else if (value is IDisposable disposable) disposable.Dispose();
    }
    static bool Release(string id)
    {
        if (!Objects.TryGetValue(id, out var value)) return false;
        foreach (var key in Objects.Where(p => ReferenceEquals(p.Value,value)).Select(p => p.Key).ToArray()) Objects.Remove(key);
        if (value != null) Dispose(value);
        return true;
    }
    static object ReadPointer(JsonElement request) {
        if (kind != "native") throw new ArgumentException("read requires a native pointer reference");
        var pointer = (IntPtr)Objects[Text(request,"target")]!;
        var size = request.GetProperty("size").GetInt32();
        if (pointer == IntPtr.Zero || size < 0 || size > MaxFrame / 2) throw new ArgumentException("Invalid pointer read size");
        var bytes = new byte[size]; Marshal.Copy(pointer,bytes,0,size);
        var type = Text(request,"returnType");
        if (type == "buffer") return Convert.ToBase64String(bytes);
        if (type == "utf8") return Encoding.UTF8.GetString(bytes.TakeWhile(b => b != 0).ToArray());
        if (type == "utf16") return Encoding.Unicode.GetString(bytes).Split('\0')[0];
        throw new ArgumentException("read returnType must be buffer, utf8 or utf16");
    }
    static object Inspect(JsonElement request)
    {
        if (kind == "native") {
            var filename = new StringBuilder(32768);
            if (GetModuleFileName(library, filename, filename.Capacity) == 0) throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
            using var file = File.OpenRead(filename.ToString());
            using var pe = new PEReader(file);
            byte[] Read(int rva, int size) => pe.GetSectionData(rva).GetContent(0, size).ToArray();
            uint U32(byte[] bytes, int offset) => System.Buffers.Binary.BinaryPrimitives.ReadUInt32LittleEndian(bytes.AsSpan(offset, 4));
            string Name(int rva) {
                var content = pe.GetSectionData(rva).GetContent();
                var bytes = content.Take(1024).TakeWhile(b => b != 0).ToArray();
                return Encoding.ASCII.GetString(bytes);
            }
            var directory = pe.PEHeaders.PEHeader!.ExportTableDirectory;
            var names = new List<string>(); uint count = 0;
            if (directory.Size >= 40) {
                var exports = Read(directory.RelativeVirtualAddress, 40); count = U32(exports, 24);
                var addresses = Read(checked((int)U32(exports, 32)), checked((int)Math.Min(count, 2000) * 4));
                for (int i = 0; i < addresses.Length / 4; i++) names.Add(Name(checked((int)U32(addresses, i * 4))));
            }
            return new { kind, path = filename.ToString(), machine = pe.PEHeaders.CoffHeader.Machine.ToString(), namedExports = names, truncated = count > 2000 };
        }
        if (kind == "dotnet") {
            var name = Text(request, "typeName");
            var types = name.Length > 0 ? new[] { assembly!.GetType(name, true)! } : assembly!.GetExportedTypes();
            return types.Take(128).Select(t => new { name = t.FullName,
                methods = t.GetMethods(BindingFlags.Public | BindingFlags.Instance | BindingFlags.Static | BindingFlags.DeclaredOnly).Take(256).Select(m => new {
                    name = m.Name, isStatic = m.IsStatic, returns = m.ReturnType.FullName, parameters = m.GetParameters().Select(p => new { p.Name, type = p.ParameterType.FullName }) }),
                constructors = t.GetConstructors().Select(c => c.GetParameters().Select(p => p.ParameterType.FullName)) });
        }
        return new { kind, path, bits = IntPtr.Size * 8, className = component?.GetType().FullName };
    }
    static Type NativeType(string type) => type switch {
        "void" => typeof(void), "ptr" or "utf8" or "utf16" or "buffer" => typeof(IntPtr),
        "i8" or "u8" or "i16" or "u16" or "i32" or "u32" or "i64" or "u64" or "f32" or "f64" => ManagedType(type),
        _ => throw new ArgumentException("Unsupported native type: " + type)
    };
    static Invocation NativeCall(JsonElement request)
    {
        var arguments = Args(request);
        if (arguments.Length > 32) throw new ArgumentException("At most 32 arguments");
        var resultType = Text(request, "returnType", "void");
        if (resultType == "utf8" || resultType == "utf16" || resultType == "buffer") throw new ArgumentException("Return pointers as ptr; use an explicit library copy/free function");
        var convention = Text(request, "callingConvention", "winapi") switch {
            "cdecl" => CallingConvention.Cdecl, "stdcall" => CallingConvention.StdCall, "winapi" => CallingConvention.Winapi,
            _ => throw new ArgumentException("callingConvention must be winapi, cdecl or stdcall")
        };
        var signature = resultType + ":" + convention + ":" + string.Join(",", arguments.Select(a => Text(a, "type")));
        if (!Delegates.TryGetValue(signature, out var delegateType)) {
            if (Delegates.Count >= 256) throw new InvalidOperationException("Session signature cache is full");
            var module = AssemblyBuilder.DefineDynamicAssembly(new AssemblyName("FFI_" + Guid.NewGuid().ToString("N")), AssemblyBuilderAccess.Run).DefineDynamicModule("ffi");
            var builder = module.DefineType("Call", TypeAttributes.Public | TypeAttributes.Sealed, typeof(MulticastDelegate));
            builder.SetCustomAttribute(new CustomAttributeBuilder(typeof(UnmanagedFunctionPointerAttribute).GetConstructor(new[] { typeof(CallingConvention) })!, new object[] { convention }));
            builder.DefineConstructor(MethodAttributes.Public | MethodAttributes.RTSpecialName | MethodAttributes.HideBySig, CallingConventions.Standard, new[] { typeof(object), typeof(IntPtr) }).SetImplementationFlags(MethodImplAttributes.Runtime | MethodImplAttributes.Managed);
            builder.DefineMethod("Invoke", MethodAttributes.Public | MethodAttributes.Virtual | MethodAttributes.HideBySig, NativeType(resultType), arguments.Select(a => NativeType(Text(a, "type"))).ToArray()).SetImplementationFlags(MethodImplAttributes.Runtime | MethodImplAttributes.Managed);
            delegateType = builder.CreateType()!;
            Delegates.Add(signature, delegateType);
        }
        var address = GetProcAddress(library, Text(request, "member"));
        if (address == IntPtr.Zero) throw new MissingMethodException("Native export not found");
        var allocations = new List<IntPtr>();
        var borrowed = new List<(int Index, IntPtr Pointer, int Size)>();
        var buffers = new List<(int Index, IntPtr Pointer, int Size)>();
        try {
            var values = arguments.Select((argument, index) => {
                var type = Text(argument, "type");
                var value = argument.TryGetProperty("value", out var v) ? v : default;
                var reference = Text(argument,"ref");
                if (type == "ptr") {
                    if (reference.Length > 0) return Objects[reference] ?? (object)IntPtr.Zero;
                    if (value.ValueKind == JsonValueKind.Null || value.ValueKind == JsonValueKind.Undefined) return (object)IntPtr.Zero;
                    throw new ArgumentException("ptr requires a request-local ref or null");
                }
                if (type == "utf8" || type == "utf16" || type == "buffer") {
                    if (reference.Length > 0) value = JsonSerializer.SerializeToElement(Objects[reference],JsonOptions);
                    var bytes = type == "buffer" ? (value.ValueKind == JsonValueKind.String ? Convert.FromBase64String(value.GetString()!) : Array.Empty<byte>()) :
                        (type == "utf8" ? Encoding.UTF8 : Encoding.Unicode).GetBytes(value.GetString()! + "\0");
                    var size = argument.TryGetProperty("size", out var s) && s.ValueKind == JsonValueKind.Number ? s.GetInt32() : bytes.Length;
                    if (size < bytes.Length || size < 0 || size > MaxFrame / 2) throw new ArgumentException("Invalid buffer size");
                    var pointer = Marshal.AllocHGlobal(Math.Max(1, size)); allocations.Add(pointer);
                    borrowed.Add((index, pointer, Math.Max(1, size)));
                    var initial = new byte[size]; Array.Copy(bytes, initial, bytes.Length); Marshal.Copy(initial, 0, pointer, size);
                    if (type == "buffer") buffers.Add((index, pointer, size));
                    return (object)pointer;
                }
                return ManagedArgument(argument);
            }).ToArray();
            var result = Marshal.GetDelegateForFunctionPointer(address, delegateType).DynamicInvoke(values);
            object? packed;
            if (resultType == "ptr" && result is IntPtr p) {
                var own = borrowed.FindIndex(b => p.ToInt64() >= b.Pointer.ToInt64() && p.ToInt64() < b.Pointer.ToInt64() + b.Size);
                if (own >= 0 && Text(request,"saveAs").Length > 0) throw new ArgumentException("Cannot save a pointer into temporary argument memory");
                packed = p == IntPtr.Zero ? null : own >= 0 ? JsonSerializer.SerializeToElement(new { argument = borrowed[own].Index, offset = p.ToInt64() - borrowed[own].Pointer.ToInt64(), ephemeral = true }) : (object)p;
            } else packed = result;
            return new Invocation(packed, buffers.Select(b => { var bytes = new byte[b.Size]; Marshal.Copy(b.Pointer, bytes, 0, b.Size); return (object)new { index = b.Index, base64 = Convert.ToBase64String(bytes) }; }).ToArray());
        } finally { foreach (var pointer in allocations) Marshal.FreeHGlobal(pointer); }
    }
}
