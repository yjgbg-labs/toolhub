using System.Text.Json;

namespace GrootWidget;

internal sealed record WidgetSettings(int? X = null, int? Y = null, int Width = 440,
    bool TopMost = true, double Opacity = 0.96, int FontSize = 14, int RowHeight = 40);
internal sealed record WidgetItem(string Key, string Label, string? Value, string Color, int Order, string UpdatedAt);
internal sealed record WidgetSnapshot(string Revision, string ServerTime, WidgetItem[] Items, WidgetSettings Settings)
{
    internal static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    internal static WidgetSnapshot Parse(byte[] bytes)
    {
        var value = JsonSerializer.Deserialize<WidgetSnapshot>(bytes, Json) ?? throw new InvalidDataException("Empty snapshot");
        var s = value.Settings;
        if (string.IsNullOrEmpty(value.Revision) || value.Revision.Any(c => c < '0' || c > '9') ||
            value.Items is null || value.Items.Length > 256 || s is null ||
            s.Width < 240 || s.Width > 1600 || s.FontSize < 8 || s.FontSize > 32 ||
            s.RowHeight < Math.Max(24, s.FontSize + 8) || s.RowHeight > 120 ||
            !double.IsFinite(s.Opacity) || s.Opacity < 0.2 || s.Opacity > 1 ||
            s.X.HasValue != s.Y.HasValue || s.X is < -65536 or > 65536 || s.Y is < -65536 or > 65536 ||
            value.Items.Any(i => i is null || string.IsNullOrWhiteSpace(i.Key) || string.IsNullOrWhiteSpace(i.Label) ||
                i.Color is not ("red" or "yellow" or "green"))) throw new InvalidDataException("Invalid snapshot");
        return value;
    }
}
