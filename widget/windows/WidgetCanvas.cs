using System.Drawing.Drawing2D;
using System.Drawing.Imaging;

namespace GrootWidget;

internal sealed class WidgetCanvas : Control
{
    internal static readonly Color PanelColor = Color.FromArgb(13, 17, 20);
    private readonly DirectWriteRenderer renderer = new();
    private Bitmap? frame;
    private WidgetSettings settings = new();
    private WidgetItem[] items = Array.Empty<WidgetItem>();
    private int scrollOffset;
    internal bool Connected { get; set; }
    internal bool Acrylic { get; set; }
    internal event Action? DragRequested;

    internal WidgetCanvas()
    {
        SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer |
            ControlStyles.ResizeRedraw | ControlStyles.UserPaint, true);
        BackColor = PanelColor;
        MouseDown += (_, e) => { if (e.Button == MouseButtons.Left) DragRequested?.Invoke(); };
        MouseWheel += (_, e) =>
        {
            scrollOffset = Math.Clamp(scrollOffset - Math.Sign(e.Delta) * Scale(40), 0, Math.Max(0, ContentHeight - Height));
            Invalidate();
        };
    }

    internal int Scale(int value) => Math.Max(1, (int)Math.Round(value * DeviceDpi / 96d));
    internal int ContentHeight => Scale(8) + Scale(settings.RowHeight) * Math.Max(1, items.Length);

    internal void Apply(WidgetItem[] nextItems, WidgetSettings nextSettings)
    {
        settings = nextSettings;
        items = nextItems;
        scrollOffset = Math.Clamp(scrollOffset, 0, Math.Max(0, ContentHeight - Height));
        Invalidate();
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var width = ClientSize.Width;
        var height = ClientSize.Height;
        if (width <= 0 || height <= 0) return;

        if (frame is null || frame.Width != width || frame.Height != height)
        {
            frame?.Dispose();
            frame = new Bitmap(width, height, PixelFormat.Format32bppPArgb);
        }

        var locked = frame.LockBits(new Rectangle(0, 0, width, height), ImageLockMode.WriteOnly, PixelFormat.Format32bppPArgb);
        try
        {
            renderer.Render(width, height, DeviceDpi, items, settings, scrollOffset, Connected, Acrylic, locked.Scan0, locked.Stride);
        }
        finally
        {
            frame.UnlockBits(locked);
        }

        e.Graphics.CompositingMode = CompositingMode.SourceCopy;
        e.Graphics.DrawImageUnscaled(frame, 0, 0);
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            frame?.Dispose();
            frame = null;
            renderer.Dispose();
        }
        base.Dispose(disposing);
    }
}
