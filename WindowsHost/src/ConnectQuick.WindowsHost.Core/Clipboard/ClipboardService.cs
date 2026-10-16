using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using ConnectQuick.WindowsHost.Diagnostics;
using ConnectQuick.WindowsHost.Session;
using ConnectQuick.WindowsHost.Settings;

namespace ConnectQuick.WindowsHost.Clipboard;

/// <summary>
/// Plain-text clipboard sync (Phase 6). Never silent: the phone either pushes explicitly, pulls
/// explicitly, or — only if the user turned on "auto push" in the PC settings — receives changes,
/// and the event log records that a transfer happened (never its content). Loop prevention: the
/// hash of whatever we just wrote is remembered, so the watcher does not echo it straight back.
/// </summary>
public sealed class ClipboardService : IHostFeature
{
    public const int MaxTextBytes = 16 * 1024;

    private readonly IClipboard _clipboard;
    private readonly HostSettingsStore _settings;
    private readonly HostEventLog _log;
    private readonly object _lock = new();
    private readonly Dictionary<string, CancellationTokenSource> _watchers = new();
    private string? _lastAppliedHash;
    private string? _lastSentHash;
    private int _autoSeq;

    public string Name => "clipboard";

    public ClipboardService(IClipboard clipboard, HostSettingsStore settings, HostEventLog log)
    {
        _clipboard = clipboard;
        _settings = settings;
        _log = log;
    }

    public IEnumerable<Route> Routes => new[]
    {
        new Route("clipboard.push", "clipboard", Lane.Slow, PushAsync),
        new Route("clipboard.request", "clipboard", Lane.Slow, RequestAsync),
    };

    public static string HashOf(string text) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(text))).ToLowerInvariant();

    private async Task PushAsync(HostSession s, JsonElement m)
    {
        var id = m.GetProperty("id").GetString()!;
        var text = m.GetProperty("text").GetString()!;
        var claimed = m.GetProperty("sha256").GetString()!;

        if (Encoding.UTF8.GetByteCount(text) > MaxTextBytes) { await Ack(s, id, false, "too_large"); return; }
        var actual = HashOf(text);
        if (actual != claimed) { await Ack(s, id, false, "hash_mismatch"); return; }

        lock (_lock)
        {
            if (actual == _lastAppliedHash)
            {
                // Same content again: nothing to do, and no reason to touch the clipboard (dedupe).
                _ = Ack(s, id, true, null);
                return;
            }
            _lastAppliedHash = actual; // remember BEFORE writing so the watcher cannot echo it back
        }
        var ok = _clipboard.SetText(text);
        _log.Info("clipboard", ok ? $"received {text.Length} chars from {s.DeviceName}" : "could not write the clipboard");
        await Ack(s, id, ok, ok ? null : "write_failed");
    }

    private async Task RequestAsync(HostSession s, JsonElement m)
    {
        var id = m.GetProperty("id").GetString()!;
        var text = _clipboard.GetText();
        if (string.IsNullOrEmpty(text)) { await Ack(s, id, false, "empty_or_not_text"); return; }
        if (Encoding.UTF8.GetByteCount(text) > MaxTextBytes) { await Ack(s, id, false, "too_large"); return; }
        var hash = HashOf(text);
        lock (_lock) _lastSentHash = hash;
        _log.Info("clipboard", $"sent {text.Length} chars to {s.DeviceName} (requested)");
        await s.SendAsync("clipboard.push", new() { ["id"] = id, ["text"] = text, ["sha256"] = hash });
    }

    private static Task Ack(HostSession s, string id, bool ok, string? reason) =>
        s.SendAsync("clipboard.ack", new() { ["id"] = id, ["ok"] = ok, ["reason"] = reason });

    public void SessionStarted(HostSession session)
    {
        if (!session.Permissions.Contains("clipboard") || !_settings.Current.ClipboardAutoPushToController) return;
        var cts = new CancellationTokenSource();
        lock (_lock) _watchers[session.SessionId] = cts;
        _ = WatchAsync(session, cts.Token);
    }

    /// <summary>Polls the cheap sequence number (no clipboard access) and reads only when something changed.</summary>
    private async Task WatchAsync(HostSession s, CancellationToken token)
    {
        var seen = _clipboard.SequenceNumber;
        try
        {
            while (!token.IsCancellationRequested)
            {
                await Task.Delay(500, token).ConfigureAwait(false);
                var now = _clipboard.SequenceNumber;
                if (now == seen) continue;
                seen = now;
                if (!_settings.Current.ClipboardAutoPushToController) continue;
                if (_clipboard.IsExcludedFromMonitoring) continue; // password managers ask not to be captured
                var text = _clipboard.GetText();
                if (string.IsNullOrEmpty(text) || Encoding.UTF8.GetByteCount(text) > MaxTextBytes) continue;
                var hash = HashOf(text);
                lock (_lock)
                {
                    if (hash == _lastAppliedHash || hash == _lastSentHash) continue;
                    _lastSentHash = hash;
                }
                var id = "h" + Interlocked.Increment(ref _autoSeq);
                _log.Info("clipboard", $"auto-sent {text.Length} chars to {s.DeviceName}");
                await s.SendAsync("clipboard.push", new() { ["id"] = id, ["text"] = text, ["sha256"] = hash });
            }
        }
        catch (OperationCanceledException) { }
    }

    public void SessionEnded(HostSession session)
    {
        CancellationTokenSource? cts;
        lock (_lock) _watchers.Remove(session.SessionId, out cts);
        cts?.Cancel();
        cts?.Dispose();
    }
}
