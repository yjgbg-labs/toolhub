namespace GrootWidget;

internal sealed partial class WidgetForm
{
    private WidgetSnapshot? queuedSnapshot;
    private int snapshotQueued;

    private async Task SubscribeLoop()
    {
        var retry = 1;
        while (!stop.IsCancellationRequested)
        {
            try
            {
                await client.Subscribe(snapshot =>
                {
                    retry = 1;
                    Interlocked.Exchange(ref queuedSnapshot, snapshot);
                    if (Interlocked.Exchange(ref snapshotQueued, 1) != 0) return;
                    OnUi(() =>
                    {
                        Interlocked.Exchange(ref snapshotQueued, 0);
                        var next = Interlocked.Exchange(ref queuedSnapshot, null);
                        if (next is not null) Receive(next);
                    });
                }, stop.Token);
            }
            catch (Exception error) when (!stop.IsCancellationRequested)
            {
                OnUi(() => Connection(false));
                Program.Log($"SSE disconnected: {error.Message}; retry={retry}s");
                try { await Task.Delay(TimeSpan.FromSeconds(retry * (0.8 + Random.Shared.NextDouble() * 0.4)), stop.Token); }
                catch (OperationCanceledException) { return; }
                retry = Math.Min(30, retry * 2);
            }
            catch (OperationCanceledException) { return; }
        }
    }

    private void Receive(WidgetSnapshot snapshot)
    {
        var changed = latest?.Revision != snapshot.Revision || !canvas.Connected;
        latest = snapshot;
        ready = true;
        Connection(true);
        if (pendingSettings is null && !dragging && settings != snapshot.Settings) { settings = snapshot.Settings; changed = true; }
        canvas.Apply(snapshot.Items, settings);
        if (!changed) return;
        if (!dragging) ApplyLayout();
        Program.Log($"Applied revision={snapshot.Revision} items={snapshot.Items.Length} visible={Visible} bounds={Bounds} dpi={DeviceDpi}");
        if (Program.Diagnostics && Visible)
        {
            try
            {
                using var image = new Bitmap(Width, Height);
                DrawToBitmap(image, new Rectangle(0, 0, Width, Height));
                image.Save(Path.Combine(Program.DataDirectory, "widget.png"));
            }
            catch (Exception error) { Program.Log($"Diagnostic capture: {error.Message}"); }
        }
    }

    private async Task SaveLoop()
    {
        saving = true;
        var retry = 1;
        try
        {
            while (!stop.IsCancellationRequested && pendingSettings is not null)
            {
                try
                {
                    await Task.Delay(250, stop.Token);
                    var next = pendingSettings;
                    await client.Write(next, stop.Token);
                    Program.Log("Window settings saved");
                    retry = 1;
                    if (pendingSettings == next) pendingSettings = null;
                }
                catch (Exception error) when (!stop.IsCancellationRequested)
                {
                    Program.Log($"Settings write failed: {error.Message}; retry={retry}s");
                    await Task.Delay(TimeSpan.FromSeconds(retry), stop.Token);
                    retry = Math.Min(30, retry * 2);
                }
            }
        }
        catch (OperationCanceledException) { }
        finally { saving = false; }
    }
}
