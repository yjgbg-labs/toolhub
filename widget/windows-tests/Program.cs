using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using GrootWidget;

var passed = 0;
void Check(bool ok, string description)
{
    if (!ok) throw new Exception(description);
    passed++;
    Console.WriteLine("PASS " + description);
}
async Task Throws<T>(Func<Task> action, string description) where T : Exception
{
    try { await action(); }
    catch (T) { Check(true, description); return; }
    throw new Exception("Expected " + typeof(T).Name + ": " + description);
}
List<string> Parse(string source, int limit = 1024 * 1024)
{
    var parser = new SseParser(limit);
    var events = new List<string>();
    foreach (var b in Encoding.UTF8.GetBytes(source))
        if (parser.Feed(b) is { } value) events.Add(Encoding.UTF8.GetString(value));
    return events;
}

Check(Parse("\uFEFF:comment\r\ndata: 价格💚\r\ndata: next\r\n\r\ndata: partial").SequenceEqual(new[] { "价格💚\nnext" }), "byte-split UTF8, BOM, CRLF, multiline and incomplete event");
Check(Parse("data: a\r\rdata\n\ndata: b\n\n").SequenceEqual(new[] { "a", "", "b" }), "CR and LF framing, empty data event");
await Throws<InvalidDataException>(() => { Parse("data: " + new string('x', 64), 32); return Task.CompletedTask; }, "bound individual lines");
await Throws<InvalidDataException>(() => { Parse(string.Concat(Enumerable.Repeat("data: 1234567890\n", 20)), 32); return Task.CompletedTask; }, "bound accumulated multiline event");
await Throws<DecoderFallbackException>(() => { var p = new SseParser(); p.Feed(255); p.Feed(10); return Task.CompletedTask; }, "reject invalid UTF8");

var settings = new WidgetSettings(1046, 172, 440, true, 1, 15, 40);
var snapshot = new WidgetSnapshot("9007199254740993", "2026-11-01T00:00:00Z",
    new[] { new WidgetItem("btc", "BTC/USDT", "77,500.00 +0.10%", "green", 0, "2026-11-01T00:00:00Z") }, settings);
var json = JsonSerializer.Serialize(snapshot, WidgetSnapshot.Json);
var parsed = WidgetSnapshot.Parse(Encoding.UTF8.GetBytes(json));
Check(parsed.Revision == "9007199254740993" && parsed.Settings == settings && parsed.Items[0].Value == snapshot.Items[0].Value, "shared settings and lossless revision wire format");
await Throws<InvalidDataException>(() => { WidgetSnapshot.Parse(Encoding.UTF8.GetBytes("{}")); return Task.CompletedTask; }, "reject incomplete snapshots");
await Throws<InvalidDataException>(() => { WidgetSnapshot.Parse(Encoding.UTF8.GetBytes(json.Replace("\"rowHeight\":40", "\"rowHeight\":1"))); return Task.CompletedTask; }, "reject invalid layout");
await Throws<ArgumentException>(() => { using var c = new WidgetClient("file:///tmp/test"); return Task.CompletedTask; }, "reject non-HTTP connections");

var frame = Encoding.UTF8.GetBytes("data: " + json + "\n\n");
var stream = new BlockingStream(frame);
using (var cancel = new CancellationTokenSource())
using (var client = new WidgetClient("http://example.test/", new Handler((request, _) =>
{
    Check(request.RequestUri!.AbsolutePath == "/widget/subscribe", "subscribe endpoint");
    var response = new HttpResponseMessage(HttpStatusCode.OK) { Content = new StreamContent(stream) };
    response.Content.Headers.ContentType = new MediaTypeHeaderValue("text/event-stream");
    return response;
})))
{
    var received = new TaskCompletionSource<WidgetSnapshot>(TaskCreationOptions.RunContinuationsAsynchronously);
    var subscription = client.Subscribe(s => received.TrySetResult(s), cancel.Token);
    var actual = await received.Task.WaitAsync(TimeSpan.FromSeconds(2));
    Check(actual.Revision == snapshot.Revision && !subscription.IsCompleted, "dispatch snapshot before stream EOF");
    cancel.Cancel();
    await Throws<OperationCanceledException>(() => subscription.WaitAsync(TimeSpan.FromSeconds(2)), "cancellation closes blocked read");
}
Check(stream.Disposed, "stream disposed on cancellation");

using (var client = new WidgetClient("http://example.test", new Handler((_, _) =>
{
    var response = new HttpResponseMessage(HttpStatusCode.OK) { Content = new StreamContent(new BlockingStream(Array.Empty<byte>())) };
    response.Content.Headers.ContentType = new MediaTypeHeaderValue("text/event-stream");
    return response;
}), TimeSpan.FromMilliseconds(100)))
    await Throws<OperationCanceledException>(() => client.Subscribe(_ => { }, CancellationToken.None).WaitAsync(TimeSpan.FromSeconds(2)), "idle stream times out");

using (var client = new WidgetClient("http://example.test", new Handler((_, _) => new HttpResponseMessage(HttpStatusCode.ServiceUnavailable))))
    await Throws<HttpRequestException>(() => client.Subscribe(_ => { }, CancellationToken.None), "reject HTTP errors");
using (var client = new WidgetClient("http://example.test", new Handler((_, _) => new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent("not SSE") })))
    await Throws<InvalidDataException>(() => client.Subscribe(_ => { }, CancellationToken.None), "reject non-SSE responses");

using (var client = new WidgetClient("http://example.test", new Handler((request, _) =>
{
    var body = request.Content!.ReadAsStringAsync().GetAwaiter().GetResult();
    using var data = JsonDocument.Parse(body);
    Check(request.Method == HttpMethod.Post && request.RequestUri!.AbsolutePath == "/widget/config/write", "ordinary complete-config write endpoint");
    Check(data.RootElement.EnumerateObject().Count() == 7 && data.RootElement.GetProperty("opacity").GetDouble() == 1 &&
        JsonSerializer.Deserialize<WidgetSettings>(body, WidgetSnapshot.Json) == settings, "write includes all shared settings, correct opacity name");
    return new HttpResponseMessage(HttpStatusCode.OK);
}))) await client.Write(settings, CancellationToken.None);

Console.WriteLine($"{passed} checks passed");

sealed class Handler : HttpMessageHandler
{
    private readonly Func<HttpRequestMessage, CancellationToken, HttpResponseMessage> handle;
    internal Handler(Func<HttpRequestMessage, CancellationToken, HttpResponseMessage> handle) => this.handle = handle;
    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancel) => Task.FromResult(handle(request, cancel));
}
sealed class BlockingStream : Stream
{
    private readonly byte[] data;
    private int offset;
    internal bool Disposed;
    internal BlockingStream(byte[] data) => this.data = data;
    public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancel = default)
    {
        if (offset < data.Length) { buffer.Span[0] = data[offset++]; return 1; }
        await Task.Delay(Timeout.Infinite, cancel);
        return 0;
    }
    protected override void Dispose(bool disposing) { Disposed = true; base.Dispose(disposing); }
    public override bool CanRead => true;
    public override bool CanSeek => false;
    public override bool CanWrite => false;
    public override long Length => throw new NotSupportedException();
    public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
    public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    public override void Flush() => throw new NotSupportedException();
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
    public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
}
