using System.Collections.Concurrent;
using System.Text;
using System.Text.Json;
using ConnectQuick.WindowsHost.Crypto;
using ConnectQuick.WindowsHost.Diagnostics;
using ConnectQuick.WindowsHost.Protocol;
using ConnectQuick.WindowsHost.Session;
using ConnectQuick.WindowsHost.Settings;

namespace ConnectQuick.WindowsHost.Tests;

/// <summary>
/// A real <see cref="HostSession"/> wired to a fake phone through genuine end-to-end encryption:
/// everything the phone sends is sealed with the Controller's channel keys and goes through the
/// production decrypt -> validate -> permission -> dispatch path. Only the WebSocket relay is
/// replaced by an in-memory pipe (that leg is covered by the cross-platform integration suite).
/// </summary>
internal sealed class SessionFixture : IDisposable
{
    public HostSession Session { get; }
    public SessionChannel Controller { get; }
    public HostSettingsStore Settings { get; }
    public HostEventLog Log { get; } = new();
    public string TempDir { get; }

    private readonly BlockingCollection<JsonElement> _fromHost = new();
    private readonly List<JsonElement> _stash = new();
    private readonly SessionChannel _hostSide;
    public int EndedCount;
    public string? EndReason;

    /// <summary>Creates the settings store a service under test must share with its session (same instance, as in production).</summary>
    public static HostSettingsStore NewSettings(Action<HostSettings>? configure = null)
    {
        var dir = Directory.CreateTempSubdirectory("cq-settings-").FullName;
        var store = new HostSettingsStore(Path.Combine(dir, "settings.json"));
        store.Update(s =>
        {
            s.InboxDirectory = Path.Combine(dir, "inbox");
            s.OutboxDirectory = Path.Combine(dir, "outbox");
            configure?.Invoke(s);
        });
        return store;
    }

    public SessionFixture(IReadOnlyList<IHostFeature> features, IEnumerable<string>? permissions = null, Action<HostSettings>? configure = null, HostSettingsStore? settings = null)
    {
        Settings = settings ?? NewSettings(configure);
        TempDir = Path.GetDirectoryName(Settings.Current.InboxDirectory)!;

        using var a = CryptoUtil.CreateX25519Ephemeral();
        using var b = CryptoUtil.CreateX25519Ephemeral();
        var sid = Guid.NewGuid().ToString();
        var transcript = SessionCrypto.TranscriptHash(sid, "host", "dev", CryptoUtil.ExportPublicB64Url(a), CryptoUtil.ExportPublicB64Url(b), []);
        var keys = SessionCrypto.DeriveKeys(SessionCrypto.SharedSecret(a, CryptoUtil.ExportPublicB64Url(b)), transcript);
        Controller = SessionChannel.ForController(sid, keys);
        _hostSide = SessionChannel.ForHost(sid, keys);
        var controllerRx = SessionChannel.ForController(sid, keys);

        Session = new HostSession(sid, "dev-1", "Test Phone", permissions ?? new[] { "mouse", "keyboard", "clipboard", "files", "media", "power", "remote_desktop" },
            _hostSide, features,
            (seq, ct) =>
            {
                var plain = controllerRx.Open(seq, ct);
                _fromHost.Add(JsonDocument.Parse(plain).RootElement.Clone());
                return Task.CompletedTask;
            }, Settings, Log);
        Session.Ended += (_, reason) => { Interlocked.Increment(ref EndedCount); EndReason = reason; };
        foreach (var f in features) f.SessionStarted(Session);
    }

    /// <summary>Phone -> PC: seals and delivers one inner message exactly as the relay would.</summary>
    public void Send(string type, Dictionary<string, object?>? fields = null)
    {
        var bytes = InnerMessages.Serialize(type, fields ?? new());
        var (seq, ct) = Controller.Seal(bytes);
        Session.OnFrame(seq, ct);
    }

    public void SendRaw(string json)
    {
        var (seq, ct) = Controller.Seal(Encoding.UTF8.GetBytes(json));
        Session.OnFrame(seq, ct);
    }

    /// <summary>Waits for the next message of <paramref name="type"/> from the PC (others are kept for later).</summary>
    public Task<JsonElement> ExpectAsync(string type, int timeoutMs = 3000) => Task.Run(() => ExpectCore(type, timeoutMs));

    private JsonElement ExpectCore(string type, int timeoutMs)
    {
        var deadline = DateTime.UtcNow.AddMilliseconds(timeoutMs);
        while (true)
        {
            lock (_stash)
            {
                var i = _stash.FindIndex(e => e.GetProperty("t").GetString() == type);
                if (i >= 0) { var e = _stash[i]; _stash.RemoveAt(i); return e; }
            }
            var remaining = deadline - DateTime.UtcNow;
            if (remaining <= TimeSpan.Zero) throw new TimeoutException($"no '{type}' from the PC within {timeoutMs} ms");
            if (_fromHost.TryTake(out var item, (int)remaining.TotalMilliseconds)) lock (_stash) _stash.Add(item);
        }
    }

    /// <summary>Waits for whichever of <paramref name="types"/> arrives first.</summary>
    public Task<JsonElement> ExpectAnyAsync(string[] types, int timeoutMs = 3000) => Task.Run(() => ExpectAnyCore(types, timeoutMs));

    private JsonElement ExpectAnyCore(string[] types, int timeoutMs)
    {
        var deadline = DateTime.UtcNow.AddMilliseconds(timeoutMs);
        while (true)
        {
            lock (_stash)
            {
                var i = _stash.FindIndex(e => types.Contains(e.GetProperty("t").GetString()));
                if (i >= 0) { var e = _stash[i]; _stash.RemoveAt(i); return e; }
            }
            var remaining = deadline - DateTime.UtcNow;
            if (remaining <= TimeSpan.Zero) throw new TimeoutException($"none of [{string.Join(",", types)}] within {timeoutMs} ms");
            if (_fromHost.TryTake(out var item, (int)remaining.TotalMilliseconds)) lock (_stash) _stash.Add(item);
        }
    }

    /// <summary>Asserts that no message of <paramref name="type"/> arrives within the window.</summary>
    public async Task ExpectNoneAsync(string type, int windowMs = 300)
    {
        try
        {
            var e = await ExpectAsync(type, windowMs);
            throw new Xunit.Sdk.XunitException($"unexpected '{type}': {e}");
        }
        catch (TimeoutException) { }
    }

    public async Task<string> ExpectErrorAsync() => (await ExpectAsync("ctl.error")).GetProperty("code").GetString()!;

    public void Dispose()
    {
        Session.End("test_done");
        try { Directory.Delete(TempDir, true); } catch (IOException) { }
    }
}
