// Passive HID report reader for the existing VID_1D57/PID_FA65 receiver.
const script = String.raw`$ErrorActionPreference = 'Stop'
$source = @'
using System;
using System.Runtime.InteropServices;

public static class MouseBattery {
    private const uint DigcfPresent = 0x2;
    private const uint DigcfDeviceInterface = 0x10;
    private const uint GenericRead = 0x80000000;
    private const uint GenericWrite = 0x40000000;
    private const uint ShareReadWrite = 0x3;
    private const uint OpenExisting = 3;
    private const uint Overlapped = 0x40000000;
    private const int ErrorIoPending = 997;

    [StructLayout(LayoutKind.Sequential)]
    private struct InterfaceData {
        public int Size;
        public Guid ClassGuid;
        public int Flags;
        public UIntPtr Reserved;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct NativeOverlapped {
        public IntPtr Internal;
        public IntPtr InternalHigh;
        public uint Offset;
        public uint OffsetHigh;
        public IntPtr Event;
    }

    [DllImport("hid.dll")] private static extern void HidD_GetHidGuid(out Guid guid);
    [DllImport("setupapi.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr SetupDiGetClassDevs(ref Guid guid, IntPtr enumerator, IntPtr parent, uint flags);
    [DllImport("setupapi.dll", SetLastError = true)]
    private static extern bool SetupDiEnumDeviceInterfaces(IntPtr set, IntPtr info, ref Guid guid, uint index, ref InterfaceData data);
    [DllImport("setupapi.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool SetupDiGetDeviceInterfaceDetail(IntPtr set, ref InterfaceData data, IntPtr detail, uint size, out uint required, IntPtr info);
    [DllImport("setupapi.dll")] private static extern bool SetupDiDestroyDeviceInfoList(IntPtr set);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateFile(string path, uint access, uint share, IntPtr security, uint creation, uint flags, IntPtr template);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool ReadFile(IntPtr file, byte[] buffer, uint count, out uint read, ref NativeOverlapped overlapped);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool GetOverlappedResult(IntPtr file, ref NativeOverlapped overlapped, out uint read, bool wait);
    [DllImport("kernel32.dll")] private static extern bool CancelIoEx(IntPtr file, ref NativeOverlapped overlapped);
    [DllImport("kernel32.dll")] private static extern IntPtr CreateEvent(IntPtr attributes, bool manualReset, bool initialState, string name);
    [DllImport("kernel32.dll")] private static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);
    [DllImport("kernel32.dll")] private static extern bool CloseHandle(IntPtr handle);

    public static string Read() {
        string path = FindPath();
        if (path == null) throw new InvalidOperationException("mouse HID interface not found");
        IntPtr file = CreateFile(path, GenericRead | GenericWrite, ShareReadWrite, IntPtr.Zero, OpenExisting, Overlapped, IntPtr.Zero);
        if (file == new IntPtr(-1)) throw new InvalidOperationException("cannot open mouse HID interface");
        try {
            byte[] latest = null;
            int samples = 0;
            int firstLevel = -1;
            DateTime deadline = DateTime.UtcNow.AddSeconds(5);
            while (DateTime.UtcNow < deadline) {
                int remaining = Math.Max(1, (int)(deadline - DateTime.UtcNow).TotalMilliseconds);
                byte[] report = ReadReport(file, remaining);
                if (report == null) break;
                if (report.Length < 5 || report[1] != 0x93 || (report[2] != 0x40 && report[2] != 0x41)) continue;
                if (firstLevel < 0) firstLevel = report[4];
                latest = report;
                samples++;
            }
            if (latest == null) throw new TimeoutException("mouse HID report timed out");
            return String.Format("{{\"status\":{0},\"level\":{1},\"samples\":{2},\"firstLevel\":{3}}}", latest[3], latest[4], samples, firstLevel);
        } finally {
            CloseHandle(file);
        }
    }

    private static byte[] ReadReport(IntPtr file, int timeout) {
        var buffer = new byte[65];
        var pending = new NativeOverlapped { Event = CreateEvent(IntPtr.Zero, true, false, null) };
        try {
            uint count;
            bool complete = ReadFile(file, buffer, (uint)buffer.Length, out count, ref pending);
            if (!complete && Marshal.GetLastWin32Error() != ErrorIoPending) throw new InvalidOperationException("mouse HID read failed");
            if (!complete) {
                if (WaitForSingleObject(pending.Event, (uint)timeout) != 0) {
                    CancelIoEx(file, ref pending);
                    WaitForSingleObject(pending.Event, 1000);
                    return null;
                }
                if (!GetOverlappedResult(file, ref pending, out count, false)) throw new InvalidOperationException("mouse HID read failed");
            }
            var result = new byte[count];
            Array.Copy(buffer, result, count);
            return result;
        } finally {
            if (pending.Event != IntPtr.Zero) CloseHandle(pending.Event);
        }
    }

    private static string FindPath() {
        Guid guid;
        HidD_GetHidGuid(out guid);
        IntPtr set = SetupDiGetClassDevs(ref guid, IntPtr.Zero, IntPtr.Zero, DigcfPresent | DigcfDeviceInterface);
        if (set == new IntPtr(-1)) return null;
        try {
            for (uint index = 0; ; index++) {
                var data = new InterfaceData { Size = Marshal.SizeOf(typeof(InterfaceData)) };
                if (!SetupDiEnumDeviceInterfaces(set, IntPtr.Zero, ref guid, index, ref data)) break;
                uint required;
                SetupDiGetDeviceInterfaceDetail(set, ref data, IntPtr.Zero, 0, out required, IntPtr.Zero);
                IntPtr detail = Marshal.AllocHGlobal((int)required);
                try {
                    Marshal.WriteInt32(detail, IntPtr.Size == 8 ? 8 : 6);
                    if (!SetupDiGetDeviceInterfaceDetail(set, ref data, detail, required, out required, IntPtr.Zero)) continue;
                    string path = Marshal.PtrToStringUni(IntPtr.Add(detail, 4));
                    if (path != null && path.IndexOf("vid_1d57&pid_fa65&mi_02&col03", StringComparison.OrdinalIgnoreCase) >= 0)
                        return path;
                } finally {
                    Marshal.FreeHGlobal(detail);
                }
            }
            return null;
        } finally {
            SetupDiDestroyDeviceInfoList(set);
        }
    }
}
'@
Add-Type -TypeDefinition $source
[MouseBattery]::Read()`;
const item = {key: "mouse-battery", label: "MOUSE", order: 35};
try {
  const result = (await tools.powershell({body: script, timeout: "12s"})).body;
  if (result.exitCode !== 0) throw Error(result.stderr || "PowerShell exit " + result.exitCode);
  const battery = JSON.parse(result.stdout);
  if (!Number.isInteger(battery.status) || !Number.isInteger(battery.level) || battery.level < 0 || battery.level > 100
      || !Number.isInteger(battery.samples) || battery.samples < 1) throw Error("Invalid mouse battery report");
  const level = battery.status === 2 ? 100 : battery.level;
  const value = battery.status === 3 ? `${level}% CHARGING` : level === 100 ? "100% FULL" : `${level}%`;
  const color = level <= 15 ? "red" : level <= 30 ? "yellow" : "green";
  await tools.widget.set({...item, value, color});
  console.log(JSON.stringify({...battery, value, color}));
} catch (error) {
  await tools.widget.set({...item, value: "OFFLINE", color: "red"});
  throw Error("Mouse battery: " + String(error));
}
