using System.Drawing.Drawing2D;

namespace GrootWidget;

internal sealed class WidgetCanvas : Control
{
    internal static readonly Color PanelColor = Color.FromArgb(13, 17, 20);
    private static readonly Color LabelColor = Color.FromArgb(170, 181, 187);
    private static readonly Color LampOffColor = Color.FromArgb(8, 10, 12);
    private WidgetSettings settings = new();
    private WidgetItem[] items = Array.Empty<WidgetItem>();
    private Font? itemFont;
    private int fontDpi, scrollOffset;
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
        if (settings.FontSize != nextSettings.FontSize) { itemFont?.Dispose(); itemFont = null; }
        settings = nextSettings;
        items = nextItems;
        scrollOffset = Math.Clamp(scrollOffset, 0, Math.Max(0, ContentHeight - Height));
        Invalidate();
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        e.Graphics.CompositingMode = CompositingMode.SourceCopy;
        e.Graphics.Clear(Acrylic ? Color.Transparent : PanelColor);
        e.Graphics.CompositingMode = CompositingMode.SourceOver;
        if (Acrylic)
        {
            using var tint = new SolidBrush(Color.FromArgb(102, 0, 0, 0));
            e.Graphics.FillRectangle(tint, ClientRectangle);
        }
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        e.Graphics.TextRenderingHint = System.Drawing.Text.TextRenderingHint.AntiAliasGridFit;
        if (itemFont is null || fontDpi != DeviceDpi)
        {
            itemFont?.Dispose();
            itemFont = new Font("Cascadia Mono", Scale(settings.FontSize), FontStyle.Regular, GraphicsUnit.Pixel);
            fontDpi = DeviceDpi;
        }
        if (items.Length == 0) DrawRow(e.Graphics, "WIDGET", Connected ? "EMPTY" : "连接中…", Tone("yellow"), 0);
        for (var index = 0; index < items.Length; index++)
        {
            var item = items[index];
            DrawRow(e.Graphics, item.Label, item.Value ?? "", Tone(item.Color), index);
            if (string.IsNullOrEmpty(item.Value)) DrawLamps(e.Graphics, item, index);
        }
        if (!Connected)
        {
            using var brush = new SolidBrush(Tone("yellow"));
            e.Graphics.FillEllipse(brush, Width - Scale(9), Scale(3), Scale(5), Scale(5));
        }
    }

    private Rectangle Row(int index) => new(0, Scale(4) + index * Scale(settings.RowHeight) - scrollOffset, Width, Scale(settings.RowHeight));

    private void DrawRow(Graphics graphics, string label, string value, Color color, int index)
    {
        var row = Row(index);
        if (row.Bottom < 0 || row.Top > Height) return;
        var padding = Scale(16);
        var gap = Scale(16);
        var available = Math.Max(0, Width - 2 * padding - gap);
        using var format = new StringFormat(StringFormat.GenericTypographic)
        {
            LineAlignment = StringAlignment.Center, Trimming = StringTrimming.EllipsisCharacter,
            FormatFlags = StringFormatFlags.NoWrap
        };
        var labelWidth = Math.Min((int)Math.Ceiling(graphics.MeasureString(label, itemFont!, int.MaxValue, format).Width), available / 2);
        var left = new Rectangle(padding, row.Top, labelWidth, row.Height);
        var right = new Rectangle(left.Right + gap, row.Top, Math.Max(0, available - labelWidth), row.Height);
        using var labelBrush = new SolidBrush(LabelColor);
        using var valueBrush = new SolidBrush(color);
        graphics.DrawString(label, itemFont!, labelBrush, left, format);
        format.Alignment = StringAlignment.Far;
        graphics.DrawString(value, itemFont!, valueBrush, right, format);
    }

    private void DrawLamps(Graphics graphics, WidgetItem item, int index)
    {
        var row = Row(index);
        var slot = Scale(16);
        var gap = Scale(8);
        var left = Width - Scale(16) - slot * 3 - gap * 2;
        var colors = new[] { "red", "yellow", "green" };
        for (var i = 0; i < colors.Length; i++)
        {
            var active = item.Color == colors[i];
            var size = active ? slot : Scale(12);
            using var brush = new SolidBrush(active ? Tone(colors[i]) : LampOffColor);
            graphics.FillEllipse(brush, left + i * (slot + gap) + (slot - size) / 2,
                row.Top + (row.Height - size) / 2, size, size);
        }
    }

    private static Color Tone(string color) => color switch
    {
        "red" => Color.FromArgb(255, 102, 92),
        "yellow" => Color.FromArgb(232, 183, 93),
        _ => Color.FromArgb(88, 214, 169)
    };

    protected override void Dispose(bool disposing)
    {
        if (disposing) itemFont?.Dispose();
        base.Dispose(disposing);
    }
}
