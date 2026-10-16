using System.Security.Cryptography;
using System.Text.Json;
using System.Threading.Channels;
using ConnectQuick.WindowsHost.Diagnostics;
using ConnectQuick.WindowsHost.Protocol;
using ConnectQuick.WindowsHost.Settings;

namespace ConnectQuick.WindowsHost.Session;

/// <summary>
/// One end-to-end encrypted control session with one phone. Everything the phone can make the PC
/// do passes through <see cref="OnFrame"/>: decrypt (authenticated, anti-replay) -> validate
/// against the shared schema -> check permission and host policy -> rate limit -> dispatch.
/// Whatever happens, <see cref="End"/> runs every feature's cleanup so no key, button, stream or
/// transfer outlives the session.
/// </summary>
public sealed class HostSession
{
    private const int MaxViolations = 25;
    private const int QueueCapacity = 4096;

    private readonly SessionChannel _channel;
    private readonly IReadOnlyDictionary<string, Route> _routes;
    private readonly IReadOnlyList<IHostFeature> _features;
    private readonly Func<long, string, Task> _sendFrame;
    private readonly HostSettingsStore _settings;
    private readonly HostEventLog _log;
    private readonly Channel<InnerParseResult> _fast = Channel.CreateBounded<InnerParseResult>(new BoundedChannelOptions(QueueCapacity) { SingleReader = true, FullMode = BoundedChannelFullMode.Wait });
    private readonly Channel<InnerParseResult> _slow = Channel.CreateBounded<InnerParseResult>(new BoundedChannelOptions(QueueCapacity) { SingleReader = true, FullMode = BoundedChannelFullMode.Wait });
    private readonly CancellationTokenSource _cts = new();
    private readonly TokenBucket _messages = new(800, 400);
    private int _violations;
    private int _ended;
    private long _lastRxTicks = System.Diagnostics.Stopwatch.GetTimestamp();

    public string SessionId { get; }
    public string DeviceId { get; }
    public string DeviceName { get; }
    /// <summary>What the user granted this device at pairing (already intersected with host policy at accept time).</summary>
    public IReadOnlySet<string> Permissions { get; }
    public CancellationToken Token => _cts.Token;
    public bool IsEnded => _ended != 0;
    public DateTimeOffset StartedAt { get; } = DateTimeOffset.UtcNow;

    public event Action<HostSession, string>? Ended;

    public HostSession(
        string sessionId, string deviceId, string deviceName, IEnumerable<string> permissions,
        SessionChannel channel, IReadOnlyList<IHostFeature> features, Func<long, string, Task> sendFrame,
        HostSettingsStore settings, HostEventLog log)
    {
        SessionId = sessionId;
        DeviceId = deviceId;
        DeviceName = deviceName;
        Permissions = permissions.ToHashSet();
        _channel = channel;
        _features = features;
        _sendFrame = sendFrame;
        _settings = settings;
        _log = log;
        _routes = features.SelectMany(f => f.Routes).ToDictionary(r => r.Type);
        _ = PumpAsync(_fast.Reader);
        _ = PumpAsync(_slow.Reader);
    }

    /// <summary>Seconds since the phone last sent anything valid; used by the idle watchdog.</summary>
    public double SecondsSinceLastFrame => System.Diagnostics.Stopwatch.GetElapsedTime(Interlocked.Read(ref _lastRxTicks)).TotalSeconds;

    /// <summary>Called for each <c>session.data</c> frame relayed by the backend. Never throws.</summary>
    public void OnFrame(long seq, string ct)
    {
        if (IsEnded) return;
        byte[] plain;
        try
        {
            plain = _channel.Open(seq, ct);
        }
        catch (Exception ex) when (ex is CryptographicException or FormatException or ArgumentException)
        {
            // Forged, replayed, or corrupt frame: drop it. Repeated garbage ends the session.
            Violation($"undecryptable frame ({ex.GetType().Name})");
            return;
        }
        Interlocked.Exchange(ref _lastRxTicks, System.Diagnostics.Stopwatch.GetTimestamp());

        if (!_messages.TryConsume())
        {
            Violation("message rate exceeded");
            return;
        }
        var parsed = InnerMessages.Parse(plain);
        if (!parsed.Ok)
        {
            _ = SendErrorAsync(null, "invalid_message", parsed.Rejection.ToString());
            Violation($"invalid inner message: {parsed.Rejection}");
            return;
        }
        if (!_routes.TryGetValue(parsed.Type!, out var route))
        {
            // Valid per schema but not something a Host handles (e.g. a Host->Controller type).
            _ = SendErrorAsync(null, "unsupported", parsed.Type!);
            Violation($"inbound message of host-bound-only type {parsed.Type}");
            return;
        }
        var lane = route.Lane == Lane.Fast ? _fast : _slow;
        if (!lane.Writer.TryWrite(parsed))
        {
            End("overloaded");
        }
    }

    private async Task PumpAsync(ChannelReader<InnerParseResult> reader)
    {
        try
        {
            await foreach (var msg in reader.ReadAllAsync(_cts.Token).ConfigureAwait(false))
            {
                if (IsEnded) break;
                await DispatchAsync(msg).ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException) { }
    }

    private async Task DispatchAsync(InnerParseResult msg)
    {
        var route = _routes[msg.Type!];
        if (route.Permission is not null)
        {
            if (!Permissions.Contains(route.Permission))
            {
                await SendErrorAsync(IdOf(msg.Root), "forbidden", $"device has no '{route.Permission}' permission").ConfigureAwait(false);
                Violation($"{msg.Type} without permission {route.Permission}");
                return;
            }
            if (!_settings.Current.PermissionEnabled(route.Permission))
            {
                await SendErrorAsync(IdOf(msg.Root), "disabled_by_host", $"'{route.Permission}' is turned off on the PC").ConfigureAwait(false);
                return;
            }
        }
        try
        {
            await route.Handler(this, msg.Root).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            _log.Error("session", $"handler for {msg.Type} failed: {ex.GetType().Name}");
            await SendErrorAsync(IdOf(msg.Root), "internal_error", "the PC could not complete that").ConfigureAwait(false);
        }
    }

    /// <summary>The correlation id of a request (`id`, or `transferId` for file messages) so the phone can fail the right operation at once.</summary>
    private static string? IdOf(JsonElement root)
    {
        foreach (var name in new[] { "id", "transferId" })
            if (root.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String) return v.GetString();
        return null;
    }

    private void Violation(string why)
    {
        _log.Warn("session", $"{DeviceName}: {why}");
        if (Interlocked.Increment(ref _violations) >= MaxViolations) End("protocol_violations");
    }

    public Task SendErrorAsync(string? id, string code, string message) =>
        SendAsync("ctl.error", new() { ["id"] = id, ["code"] = code, ["message"] = message.Length > 200 ? message[..200] : message });

    /// <summary>Encrypts and relays one inner message to the phone. Failures end the session.</summary>
    public async Task SendAsync(string type, Dictionary<string, object?> fields)
    {
        if (IsEnded) return;
        try
        {
            var plain = InnerMessages.Serialize(type, fields);
            var (seq, ct) = _channel.Seal(plain);
            await _sendFrame(seq, ct).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            End("send_failed");
        }
    }

    public IEnumerable<string> FeatureNames => _features.Select(f => f.Name);

    /// <summary>Idempotent. Runs every feature's cleanup, then wipes the keys.</summary>
    public void End(string reason)
    {
        if (Interlocked.Exchange(ref _ended, 1) != 0) return;
        _cts.Cancel();
        _fast.Writer.TryComplete();
        _slow.Writer.TryComplete();
        foreach (var f in _features)
        {
            try { f.SessionEnded(this); }
            catch (Exception ex) { _log.Error("session", $"cleanup of {f.Name} failed: {ex.GetType().Name}"); }
        }
        _channel.Dispose();
        _log.Info("session", $"{DeviceName} disconnected ({reason})");
        Ended?.Invoke(this, reason);
    }
}
