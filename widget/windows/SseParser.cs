using System.Text;

namespace GrootWidget;

// Byte framing handles split UTF-8, BOM, CR/LF/CRLF and multiline data without unbounded ReadLine calls.
internal sealed class SseParser
{
    private readonly int limit;
    private readonly List<byte> line = new();
    private readonly List<byte> data = new();
    private static readonly UTF8Encoding Utf8 = new(false, true);
    private bool afterCr, firstLine = true, hasData;

    internal SseParser(int limit = 1024 * 1024) => this.limit = limit;

    internal byte[]? Feed(byte value)
    {
        if (afterCr)
        {
            afterCr = false;
            if (value == 10) return null;
        }
        if (value != 10 && value != 13)
        {
            if (line.Count + data.Count >= limit) throw new InvalidDataException("SSE event too large");
            line.Add(value);
            return null;
        }
        afterCr = value == 13;
        if (firstLine)
        {
            firstLine = false;
            if (line.Take(3).SequenceEqual(new byte[] { 239, 187, 191 })) line.RemoveRange(0, 3);
        }
        _ = Utf8.GetString(line.ToArray());
        byte[]? result = null;
        if (line.Count == 0)
        {
            if (hasData) result = data.Take(data.Count - 1).ToArray();
            data.Clear();
            hasData = false;
        }
        else
        {
            var colon = line.IndexOf(58);
            if (colon < 0) colon = line.Count;
            if (line.Take(colon).SequenceEqual(new byte[] { 100, 97, 116, 97 }))
            {
                var start = Math.Min(colon + 1, line.Count);
                if (start < line.Count && line[start] == 32) start++;
                if (data.Count + line.Count - start + 1 > limit) throw new InvalidDataException("SSE event too large");
                data.AddRange(line.Skip(start));
                data.Add(10);
                hasData = true;
            }
        }
        line.Clear();
        return result;
    }
}
