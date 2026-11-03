using System.Diagnostics;
using System.Globalization;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Reflection.Emit;
using System.Reflection.PortableExecutable;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;

namespace Sprout.Wine;

// A worker owns exactly one EXE or DLL session. The control socket never carries stdout/stderr.
internal static class Program
{
    static readonly Dictionary<string, object> Objects = new();
    static readonly Dictionary<string, Type> Delegates = new();
    static Assembly? assembly;
    static IntPtr library;
    static string kind = "", path = "";
    static object? component;
    static IntPtr job;
    const int MaxFrame = 1024 * 1024;
    static readonly JsonSerializerOptions JsonOptions = new() { IncludeFields = true };

    [DllImport("kernel32", CharSet = CharSet.Unicode, SetLastError = true)] static extern IntPtr LoadLibraryEx(string name, IntPtr file, uint flags);
    [DllImport("kernel32", CharSet = CharSet.Ansi, ExactSpelling = true, SetLastError = true)] static extern IntPtr GetProcAddress(IntPtr module, string name);
    [DllImport("kernel32")] static extern bool FreeLibrary(IntPtr module);
    [DllImport("kernel32", CharSet = CharSet.Unicode)] static extern IntPtr CreateJobObject(IntPtr attributes, string? name);
    [DllImport("kernel32", SetLastError = true)] static extern bool SetInformationJobObject(IntPtr job, int type, IntPtr info, uint size);
    [DllImport("kernel32", SetLastError = true)] static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
    [DllImport("kernel32")] static extern IntPtr GetCurrentProcess();
    [DllImport("kernel32")] static extern uint SetErrorMode(uint mode);
    [DllImport("kernel32", CharSet = CharSet.Unicode)] static extern uint GetModuleFileName(IntPtr module, StringBuilder name, int size);

    [StructLayout(LayoutKind.Sequential)] struct BasicLimits { public long ProcessTime, JobTime; public uint Flags; public UIntPtr Minimum, Maximum; public uint Active; public UIntPtr Affinity; public uint Priority, Scheduling; }
    [StructLayout(LayoutKind.Sequential)] struct ExtendedLimits { public BasicLimits Basic; public ulong ReadOps, WriteOps, OtherOps, ReadBytes, WriteBytes, OtherBytes; public UIntPtr ProcessMemory, JobMemory, PeakProcess, PeakJob; }

    static void OwnProcessTree()
    {
        job = CreateJobObject(IntPtr.Zero, null);
        var limits = new ExtendedLimits { Basic = new BasicLimits { Flags = 0x2000 } }; // KILL_ON_JOB_CLOSE
        var size = Marshal.SizeOf<ExtendedLimits>();
        var memory = Marshal.AllocHGlobal(size);
        try {
            Marshal.StructureToPtr(limits, memory, false);
            if (job == IntPtr.Zero || !SetInformationJobObject(job, 9, memory, (uint)size) || !AssignProcessToJobObject(job, GetCurrentProcess()))
                throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error(), "Cannot establish Windows job ownership");
        } finally { Marshal.FreeHGlobal(memory); }
    }

    [STAThread]
    static int Main(string[] args)
    {
        try {
            Console.OutputEncoding = new UTF8Encoding(false);
            SetErrorMode(0x0001 | 0x0002 | 0x8000);
            OwnProcessTree();
            using var socket = new TcpClient();
            socket.Connect(IPAddress.Loopback, int.Parse(args[0], CultureInfo.InvariantCulture));
            using var stream = socket.GetStream();
            using var reader = new BinaryReader(stream, Encoding.UTF8, true);
            using var writer = new BinaryWriter(stream, Encoding.UTF8, true);
            Send(writer, new { token = Environment.GetEnvironmentVariable("SPROUT_WINE_TOKEN"), protocol = 1, bits = IntPtr.Size * 8, runtime = RuntimeInformation.FrameworkDescription });
            Environment.SetEnvironmentVariable("SPROUT_WINE_TOKEN", null);
            while (true) {
                int length;
                try { length = reader.ReadInt32(); } catch (EndOfStreamException) { return 0; }
                if (length <= 0 || length > MaxFrame) throw new InvalidDataException("Invalid request frame length");
                var bytes = reader.ReadBytes(length);
                if (bytes.Length != length) return 0;
                using var doc = JsonDocument.Parse(bytes);
                try { Send(writer, new { ok = true, result = Dispatch(doc.RootElement) }); }
                catch (Exception error) {
                    while (error is TargetInvocationException && error.InnerException != null) error = error.InnerException;
                    Send(writer, new { ok = false, error = new { code = error.GetType().Name, message = error.Message, hresult = error.HResult } });
                }
            }
        } catch (Exception error) { Console.Error.WriteLine(error); return 1; }
        finally {
            // OS teardown owns remaining handles; explicit release disposes objects during a session.
            if (library != IntPtr.Zero) FreeLibrary(library);
            // Keep the non-inheritable job handle alive until process termination, including descendants.
            GC.KeepAlive(job);
        }
    }

    static void Send(BinaryWriter writer, object response)
    {
        var bytes = JsonSerializer.SerializeToUtf8Bytes(response, JsonOptions);
        if (bytes.Length > MaxFrame) bytes = Encoding.UTF8.GetBytes("{\"ok\":false,\"error\":{\"code\":\"result_limit\",\"message\":\"Result exceeds 1 MiB\"}}");
        writer.Write(bytes.Length); writer.Write(bytes); writer.Flush();
    }
    static string Text(JsonElement value, string key, string fallback = "") => value.TryGetProperty(key, out var field) && field.ValueKind == JsonValueKind.String ? field.GetString()! : fallback;
    static JsonElement[] Args(JsonElement request) => request.TryGetProperty("args", out var args) ? args.EnumerateArray().ToArray() : Array.Empty<JsonElement>();
    static object? Dispatch(JsonElement request) => Text(request, "op") switch {
        "load" => Load(request), "call" => Call(request), "inspect" => Inspect(request), "release" => Release(Text(request, "handle")),
        "exec" => Execute(request), _ => throw new ArgumentException("Unknown worker operation")
    };
    static object Load(JsonElement request)
    {
        if (kind.Length != 0) throw new InvalidOperationException("This worker already owns a DLL session");
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
                break;
            default: throw new ArgumentException("kind must be native, dotnet or com");
        }
        kind = selected;
        return new { kind, path, runtime = RuntimeInformation.FrameworkDescription, bits = IntPtr.Size * 8 };
    }
    static object? Call(JsonElement request)
    {
        if (kind.Length == 0) throw new InvalidOperationException("Load a DLL first");
        if (kind == "native") return NativeCall(request);
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
        return new { value = Pack(result), outputs = Array.Empty<object>() };
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
        if (!argument.TryGetProperty("value", out var value) || value.ValueKind == JsonValueKind.Null) return null;
        if (value.ValueKind == JsonValueKind.Object && value.TryGetProperty("handle", out var handle)) return Objects[handle.GetString()!];
        if (type == typeof(byte[])) return Convert.FromBase64String(value.GetString()!);
        if (type == typeof(object)) return value.ValueKind switch {
            JsonValueKind.String => value.GetString(), JsonValueKind.True => true, JsonValueKind.False => false,
            JsonValueKind.Number => value.TryGetInt32(out var integer) ? (object)integer : value.GetDouble(), _ => throw new ArgumentException("Use a typed value or object handle")
        };
        if (value.ValueKind == JsonValueKind.String && type != typeof(string)) return Convert.ChangeType(value.GetString(), type, CultureInfo.InvariantCulture);
        return JsonSerializer.Deserialize(value.GetRawText(), type, JsonOptions);
    }
    static object? Pack(object? value)
    {
        if (value == null) return null;
        if (value is long || value is ulong) return Convert.ToString(value, CultureInfo.InvariantCulture);
        if (value is byte[] bytes) return Convert.ToBase64String(bytes);
        if (value is not IntPtr && (value.GetType().IsPrimitive || value is string || value is decimal)) return value;
        if (Objects.Count >= 4096) throw new InvalidOperationException("Release unused object handles before creating more");
        var id = "h_" + Guid.NewGuid().ToString("N");
        Objects.Add(id, value);
        return new { handle = id, type = value.GetType().FullName };
    }
    static bool Release(string id)
    {
        if (!Objects.TryGetValue(id, out var value)) return false;
        Objects.Remove(id);
        if (!Objects.Values.Any(other => ReferenceEquals(other, value)) && !ReferenceEquals(component, value)) {
            if (Marshal.IsComObject(value)) Marshal.FinalReleaseComObject(value);
            else if (value is IDisposable disposable) disposable.Dispose();
        }
        return true;
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
    static object NativeCall(JsonElement request)
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
                if (type == "ptr") {
                    if (value.ValueKind == JsonValueKind.Null || value.ValueKind == JsonValueKind.Undefined) return (object)IntPtr.Zero;
                    return (IntPtr)Objects[value.GetProperty("handle").GetString()!];
                }
                if (type == "utf8" || type == "utf16" || type == "buffer") {
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
                packed = p == IntPtr.Zero ? null : own >= 0 ? new { argument = borrowed[own].Index, offset = p.ToInt64() - borrowed[own].Pointer.ToInt64(), ephemeral = true } : Pack(p);
            } else packed = Pack(result);
            return new { value = packed, outputs = buffers.Select(b => { var bytes = new byte[b.Size]; Marshal.Copy(b.Pointer, bytes, 0, b.Size); return new { index = b.Index, base64 = Convert.ToBase64String(bytes) }; }).ToArray() };
        } finally { foreach (var pointer in allocations) Marshal.FreeHGlobal(pointer); }
    }
    static string Quote(string argument)
    {
        if (argument.Length > 0 && !argument.Any(ch => char.IsWhiteSpace(ch) || ch == '"')) return argument;
        var output = new StringBuilder("\""); int slashes = 0;
        foreach (char ch in argument) {
            if (ch == '\\') { slashes++; continue; }
            output.Append('\\', ch == '"' ? slashes * 2 + 1 : slashes); output.Append(ch); slashes = 0;
        }
        output.Append('\\', slashes * 2); return output.Append('"').ToString();
    }
    static object Execute(JsonElement request)
    {
        var start = new ProcessStartInfo(Text(request, "exe")) { UseShellExecute = false, CreateNoWindow = true,
            Arguments = string.Join(" ", request.GetProperty("arguments").EnumerateArray().Select(a => Quote(a.GetString()!))),
            WorkingDirectory = Text(request, "cwd"), RedirectStandardInput = true };
        if (request.TryGetProperty("env", out var env)) foreach (var item in env.EnumerateObject()) start.EnvironmentVariables[item.Name] = item.Value.GetString();
        using var process = Process.Start(start) ?? throw new InvalidOperationException("Process did not start");
        var input = Convert.FromBase64String(Text(request, "stdinBase64"));
        var write = Task.Run(async () => { try { await process.StandardInput.BaseStream.WriteAsync(input, 0, input.Length); } finally { process.StandardInput.Close(); } });
        process.WaitForExit(); write.GetAwaiter().GetResult();
        return new { exitCode = unchecked((uint)process.ExitCode) };
    }
}
