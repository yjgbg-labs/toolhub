using System.Net.Http;
using System.Net.Http.Json;

namespace GrootWidget;

internal sealed class WidgetClient : IDisposable
{
    private readonly HttpClient http;
    private readonly TimeSpan idleTimeout;

    internal WidgetClient(string baseUrl, HttpMessageHandler? handler = null, TimeSpan? idleTimeout = null)
    {
        if (!Uri.TryCreate(baseUrl, UriKind.Absolute, out var uri) || uri.Scheme is not ("http" or "https") ||
            uri.Query.Length != 0 || uri.Fragment.Length != 0 || uri.UserInfo.Length != 0)
            throw new ArgumentException("Expected an HTTP(S) server URL");
        http = new HttpClient(handler ?? new SocketsHttpHandler { ConnectTimeout = TimeSpan.FromSeconds(10), UseProxy = false })
        {
            BaseAddress = new Uri(baseUrl.TrimEnd('/') + "/"), Timeout = Timeout.InfiniteTimeSpan
        };
        this.idleTimeout = idleTimeout ?? TimeSpan.FromSeconds(45);
    }

    internal async Task Subscribe(Action<WidgetSnapshot> receive, CancellationToken cancel)
    {
        using var idle = CancellationTokenSource.CreateLinkedTokenSource(cancel);
        idle.CancelAfter(idleTimeout);
        using var request = new HttpRequestMessage(HttpMethod.Get, "widget/subscribe");
        request.Headers.Accept.ParseAdd("text/event-stream");
        using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, idle.Token);
        response.EnsureSuccessStatusCode();
        if (response.Content.Headers.ContentType?.MediaType != "text/event-stream")
            throw new InvalidDataException("Expected text/event-stream");
        using var stream = await response.Content.ReadAsStreamAsync(idle.Token);
        var parser = new SseParser();
        var buffer = new byte[8192];
        while (true)
        {
            idle.CancelAfter(idleTimeout);
            var count = await stream.ReadAsync(buffer.AsMemory(), idle.Token);
            if (count == 0) throw new EndOfStreamException("SSE disconnected");
            for (var index = 0; index < count; index++)
                if (parser.Feed(buffer[index]) is { } data) receive(WidgetSnapshot.Parse(data));
        }
    }

    internal async Task Write(WidgetSettings settings, CancellationToken cancel)
    {
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancel);
        deadline.CancelAfter(TimeSpan.FromSeconds(10));
        using var response = await http.PostAsJsonAsync("widget/config/write", settings, WidgetSnapshot.Json, deadline.Token);
        response.EnsureSuccessStatusCode();
    }

    public void Dispose() => http.Dispose();
}
