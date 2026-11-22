using System.Runtime.InteropServices;

namespace GrootWidget;

internal sealed partial class WidgetForm : Form
{
    private readonly WidgetClient client;
    private readonly CancellationTokenSource stop = new();
    private readonly WidgetCanvas canvas = new() { Dock = DockStyle.Fill };
    private readonly NotifyIcon tray;
    private readonly ContextMenuStrip menu = new();
    private readonly Icon appIcon;
    private WidgetSettings settings = new();
    private WidgetSnapshot? latest;
    private readonly string positionPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "GrootWidget", "position.json");
    private WindowPosition? position;
    private bool dragging;

    internal WidgetForm(WidgetClient client)
    {
        this.client = client;
        try { position = WindowPosition.Read(positionPath); }
        catch (Exception error) { Program.Log($"Local window position ignored: {error.Message}"); }
        using (var iconStream = typeof(WidgetForm).Assembly.GetManifestResourceStream("GrootWidget.Icon")!)
            appIcon = new Icon(iconStream, 32, 32);
        Icon = appIcon;
        Text = "Groot Widget";
        FormBorderStyle = FormBorderStyle.None;
        ShowInTaskbar = false;
        StartPosition = FormStartPosition.Manual;
        AutoScaleMode = AutoScaleMode.None;
        BackColor = WidgetCanvas.PanelColor;
        Size = new Size(440, 48);
        var area = Screen.PrimaryScreen!.WorkingArea;
        Location = new Point(area.Right - Width - 24, area.Top + 24);
        Controls.Add(canvas);
        canvas.DragRequested += BeginDrag;
        menu.Items.Add("显示 / 隐藏", null, (_, _) => { if (Visible) Hide(); else Show(); });
        tray = new NotifyIcon { Icon = appIcon, Text = "Groot Widget · 连接中", Visible = true, ContextMenuStrip = menu };
        tray.DoubleClick += (_, _) => { if (Visible) Hide(); else Show(); };
        Load += (_, _) => { ApplyWindowSettings(); ApplyLayout(); _ = Task.Run(SubscribeLoop); };
        Shown += (_, _) => ApplyBackdrop();
        DpiChanged += (_, _) => { if (!dragging) ApplyLayout(); };
        FormClosing += (_, e) =>
        {
            if (e.CloseReason == CloseReason.UserClosing) { e.Cancel = true; Hide(); }
            else { stop.Cancel(); tray.Visible = false; }
        };
    }

    private void ApplyLayout()
    {
        canvas.Apply(latest?.Items ?? Array.Empty<WidgetItem>(), settings);
        var scale = DeviceDpi / 96d;
        var primary = Screen.PrimaryScreen!.Bounds;
        var width = (int)Math.Round(settings.Width * scale);
        var point = position is not null
            ? new Point(primary.Left + (int)Math.Round(position.X * scale), primary.Top + (int)Math.Round(position.Y * scale))
            : new Point(Screen.PrimaryScreen.WorkingArea.Right - width - canvas.Scale(24), Screen.PrimaryScreen.WorkingArea.Top + canvas.Scale(24));
        var area = Screen.FromRectangle(new Rectangle(point, new Size(width, canvas.ContentHeight))).WorkingArea;
        var size = new Size(Math.Min(width, area.Width), Math.Min(canvas.ContentHeight, area.Height - canvas.Scale(16)));
        var location = new Point(Math.Clamp(point.X, area.Left, area.Right - size.Width), Math.Clamp(point.Y, area.Top, area.Bottom - size.Height));
        if (Bounds != new Rectangle(location, size)) SetBounds(location.X, location.Y, size.Width, size.Height);
        canvas.Invalidate();
    }

    private void ApplyWindowSettings()
    {
        var topMost = (Native.GetWindowLong(Handle, -20) & Native.TopMostStyle) != 0;
        // Form.TopMost calls SetWindowPos without NOACTIVATE, dismissing an open tray menu.
        if (topMost != settings.TopMost && !Native.SetWindowPos(Handle,
            new IntPtr(settings.TopMost ? -1 : -2), 0, 0, 0, 0,
            Native.NoMove | Native.NoSize | Native.NoActivate))
            throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
        if (Opacity != settings.Opacity) Opacity = settings.Opacity;
    }

    private void BeginDrag()
    {
        dragging = true;
        Native.ReleaseCapture();
        Native.SendMessage(Handle, 0x00A1, new IntPtr(2), IntPtr.Zero);
        dragging = false;
        var primary = Screen.PrimaryScreen!.Bounds;
        var scale = DeviceDpi / 96d;
        position = new WindowPosition((int)Math.Round((Left - primary.Left) / scale), (int)Math.Round((Top - primary.Top) / scale));
        ApplyLayout();
        try
        {
            position.Write(positionPath);
            Program.Log("Local window position saved");
        }
        catch (Exception error) { Program.Log($"Local window position save failed: {error.Message}"); }
    }

    private void OnUi(Action action)
    {
        if (stop.IsCancellationRequested || IsDisposed) return;
        try { BeginInvoke(new Action(() => { if (!stop.IsCancellationRequested) action(); })); }
        catch (InvalidOperationException) { /* Window is shutting down. */ }
    }

    private void Connection(bool connected)
    {
        if (canvas.Connected != connected) Program.Log(connected ? "SSE connected" : "SSE offline");
        canvas.Connected = connected;
        tray.Text = connected ? "Groot Widget · 已连接" : "Groot Widget · 重连中";
        canvas.Invalidate();
    }

    protected override CreateParams CreateParams
    {
        get
        {
            var p = base.CreateParams;
            p.ExStyle |= 0x80;
            p.ExStyle &= ~0x40000;
            // Keep the native style when WinForms recreates the window handle.
            p.ExStyle = settings.TopMost ? p.ExStyle | Native.TopMostStyle : p.ExStyle & ~Native.TopMostStyle;
            return p;
        }
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        ApplyBackdrop();
    }

    private void ApplyBackdrop()
    {
        canvas.Acrylic = WindowBackdrop.Apply(Handle);
        BackColor = canvas.Acrylic ? Color.Black : WidgetCanvas.PanelColor;
        canvas.BackColor = BackColor;
        if (canvas.Acrylic) Native.SendMessage(Handle, 0x0086, new IntPtr(1), IntPtr.Zero);
        canvas.Invalidate();
    }

    protected override void WndProc(ref Message message)
    {
        // Keep the DWM material active without taking keyboard focus from the user's application.
        if (message.Msg == 0x0086 && canvas?.Acrylic == true) message.WParam = new IntPtr(1);
        base.WndProc(ref message);
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing) { stop.Cancel(); tray.Dispose(); menu.Dispose(); appIcon.Dispose(); }
        base.Dispose(disposing);
    }

    private static class Native
    {
        internal const int TopMostStyle = 0x8;
        internal const uint NoSize = 0x1, NoMove = 0x2, NoActivate = 0x10;
        [DllImport("user32.dll")] internal static extern bool ReleaseCapture();
        [DllImport("user32.dll")] internal static extern IntPtr SendMessage(IntPtr window, int message, IntPtr wParam, IntPtr lParam);
        [DllImport("user32.dll", EntryPoint = "GetWindowLongW")] internal static extern int GetWindowLong(IntPtr window, int index);
        [DllImport("user32.dll", SetLastError = true)]
        internal static extern bool SetWindowPos(IntPtr window, IntPtr insertAfter, int x, int y, int width, int height, uint flags);
    }
}
