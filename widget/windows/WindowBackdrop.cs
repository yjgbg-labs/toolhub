using System.Runtime.InteropServices;

namespace GrootWidget;

internal static class WindowBackdrop
{
    // Windows 11 22H2+ Desktop Acrylic; DWM owns the blur, tint and noise.
    internal static bool Apply(IntPtr handle)
    {
        var dark = 1;
        var round = 2;
        var acrylic = 3;
        DwmSetWindowAttribute(handle, 20, ref dark, sizeof(int));
        DwmSetWindowAttribute(handle, 33, ref round, sizeof(int));
        DwmSetWindowAttribute(handle, 17, ref dark, sizeof(int));
        var result = DwmSetWindowAttribute(handle, 38, ref acrylic, sizeof(int));
        var margins = new Margins { Left = -1, Right = -1, Top = -1, Bottom = -1 };
        var extended = result == 0 && DwmExtendFrameIntoClientArea(handle, ref margins) == 0;
        Program.Log($"Desktop Acrylic enabled={extended} result=0x{result:X8}");
        return extended;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Margins { internal int Left, Right, Top, Bottom; }
    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr window, int attribute, ref int value, int size);
    [DllImport("dwmapi.dll")]
    private static extern int DwmExtendFrameIntoClientArea(IntPtr window, ref Margins margins);
}
