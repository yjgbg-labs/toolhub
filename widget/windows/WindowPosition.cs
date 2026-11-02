using System.Text.Json;

namespace GrootWidget;

internal sealed record WindowPosition(int X, int Y)
{
    internal static WindowPosition? Read(string path)
    {
        if (!File.Exists(path)) return null;
        using var data = JsonDocument.Parse(File.ReadAllText(path));
        var position = new WindowPosition(data.RootElement.GetProperty("x").GetInt32(), data.RootElement.GetProperty("y").GetInt32());
        if (position.X is < -65536 or > 65536 || position.Y is < -65536 or > 65536)
            throw new InvalidDataException("Invalid local window position");
        return position;
    }

    internal void Write(string path)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var temporary = path + ".tmp";
        try
        {
            File.WriteAllText(temporary, JsonSerializer.Serialize(this, WidgetSnapshot.Json));
            File.Move(temporary, path, true);
        }
        finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }
}
