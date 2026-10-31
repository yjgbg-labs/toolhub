using System.Text.Json;

namespace GrootWidget;

internal static class Program
{
    internal static readonly string DataDirectory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "GrootWidget");
    private static readonly object LogLock = new();
    internal static bool Diagnostics;

    internal static void Log(string message)
    {
        lock (LogLock)
        {
            try
            {
                Directory.CreateDirectory(DataDirectory);
                var path = Path.Combine(DataDirectory, "app.log");
                if (File.Exists(path) && new FileInfo(path).Length > 1024 * 1024) File.Move(path, path + ".1", true);
                File.AppendAllText(path, $"{DateTimeOffset.UtcNow:O} {message}{Environment.NewLine}");
            }
            catch { /* Logging must not interrupt the UI. */ }
        }
    }

    [STAThread]
    private static void Main(string[] args)
    {
        using var mutex = new Mutex(true, @"Local\GrootWidget.SingleInstance", out var created);
        if (!created) return;
        try
        {
            Diagnostics = args.Contains("--diagnostics");
            using var config = JsonDocument.Parse(File.ReadAllText(Path.Combine(DataDirectory, "connection.json")));
            var baseUrl = config.RootElement.GetProperty("baseURL").GetString() ?? throw new InvalidDataException("Missing baseURL");
            Log($"Starting HTTP/SSE widget server={baseUrl}");
            Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.ThreadException += (_, e) => Log($"UI error: {e.Exception}");
            using var client = new WidgetClient(baseUrl);
            using var form = new WidgetForm(client);
            Application.Run(form);
        }
        catch (Exception error) { Log($"Fatal: {error}"); Environment.ExitCode = 1; }
    }
}
