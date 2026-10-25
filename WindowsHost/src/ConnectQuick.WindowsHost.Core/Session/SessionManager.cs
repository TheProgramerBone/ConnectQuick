using System.Collections.Concurrent;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using ConnectQuick.WindowsHost.Crypto;
using ConnectQuick.WindowsHost.Diagnostics;
using ConnectQuick.WindowsHost.Identity;
using ConnectQuick.WindowsHost.Net;
using ConnectQuick.WindowsHost.Pairing;
using ConnectQuick.WindowsHost.Settings;
using NSec.Cryptography;

namespace ConnectQuick.WindowsHost.Session;

/// <summary>Asks the person at the PC whether a paired phone may connect right now.</summary>
public interface IConnectionApprover
{
    Task<bool> ApproveAsync(string deviceName, CancellationToken token);
}

public sealed class AlwaysApprove : IConnectionApprover
{
    public Task<bool> ApproveAsync(string deviceName, CancellationToken token) => Task.FromResult(true);
}

/// <summary>
/// Host side of the session handshake (protocol-spec section 9). A <c>session.connect</c> relayed by
/// the backend is NOT trusted: the Host independently checks that the device is one the user
/// approved on THIS PC, that the identity key matches the device id, that the Controller's
/// signature is valid and fresh, and only then answers with its own signed ephemeral key.
/// </summary>
public sealed class SessionManager
{
    private static readonly TimeSpan MaxSkew = TimeSpan.FromSeconds(60);
    private static readonly TimeSpan IdleLimit = TimeSpan.FromSeconds(45);

    private readonly DeviceIdentity _identity;
    private readonly AuthorizedDeviceStore _devices;
    private readonly IReadOnlyList<IHostFeature> _features;
    private readonly HostSettingsStore _settings;
    private readonly HostEventLog _log;
    private readonly IConnectionApprover _approver;
    private readonly ConcurrentDictionary<string, DateTimeOffset> _seenSessionIds = new();
    private readonly object _lock = new();
    private HostSession? _active;
    private BackendClient? _activeClient; // the relay carrying the live session

    /// <summary>Relays to tell a connecting phone about (inside the encrypted <c>ctl.caps</c>); null/empty: say nothing.</summary>
    public Func<IReadOnlyList<string>?>? RelaysProvider { get; set; }

    public HostSession? Active { get { lock (_lock) return _active; } }

    public event Action<HostSession>? SessionStarted;
    public event Action<HostSession, string>? SessionEnded;

    public SessionManager(
        BackendClient client, DeviceIdentity identity, AuthorizedDeviceStore devices,
        IReadOnlyList<IHostFeature> features, HostSettingsStore settings, HostEventLog log, IConnectionApprover? approver = null)
    {
        _identity = identity;
        _devices = devices;
        _features = features;
        _settings = settings;
        _log = log;
        _approver = approver ?? new AlwaysApprove();

        AddRelay(client);
    }

    /// <summary>
    /// Serves sessions arriving through one more relay (the PC's own LAN relay next to the cloud one).
    /// There is still a single session at a time, whichever relay it came in by.
    /// </summary>
    public void AddRelay(BackendClient client)
    {
        client.OnAsync("session.connect", p => OnSessionConnectAsync(client, p));
        client.On("session.data", OnSessionData);
        client.On("session.end", OnSessionEnd);
        client.ConnectionChanged += ready =>
        {
            // The relay IS the data path: without the signalling link there is no session, so
            // release everything now instead of leaving keys held for a phone that cannot reach us.
            // A link that is not carrying the live session may drop without disturbing it.
            if (!ready && ReferenceEquals(ActiveClient, client)) EndActive("signaling_lost");
        };
    }

    private BackendClient? ActiveClient { get { lock (_lock) return _activeClient; } }

    /// <summary>User revoked the device on the PC: cut its live session immediately.</summary>
    public void EndSessionsFor(string deviceId, string reason)
    {
        var s = Active;
        if (s is not null && s.DeviceId == deviceId) s.End(reason);
    }

    public void EndActive(string reason) => Active?.End(reason);

    /// <summary>Called periodically by the runtime: drops sessions whose phone went silent.</summary>
    public void Watchdog()
    {
        var s = Active;
        if (s is not null && s.SecondsSinceLastFrame > IdleLimit.TotalSeconds)
        {
            _log.Warn("session", $"{s.DeviceName}: no traffic for {IdleLimit.TotalSeconds}s");
            s.End("idle_timeout");
        }
        var cutoff = DateTimeOffset.UtcNow - 2 * MaxSkew;
        foreach (var kv in _seenSessionIds)
            if (kv.Value < cutoff) _seenSessionIds.TryRemove(kv.Key, out _);
    }

    private async Task OnSessionConnectAsync(BackendClient client, JsonElement p)
    {
        var sessionId = p.GetProperty("sessionId").GetString()!;
        try
        {
            await HandleConnectAsync(client, sessionId, p).ConfigureAwait(false);
        }
        catch (RejectException r)
        {
            _log.Warn("session", $"rejected connect: {r.Reason}");
            await client.TrySendAsync("session.reject", new() { ["sessionId"] = sessionId, ["reason"] = r.Reason }).ConfigureAwait(false);
        }
    }

    private async Task HandleConnectAsync(BackendClient client, string sessionId, JsonElement p)
    {
        var hostId = p.GetProperty("hostId").GetString()!;
        var ephPub = p.GetProperty("ephPub").GetString()!;
        var issuedAt = p.GetProperty("issuedAt").GetInt64();
        var sig = p.GetProperty("sig").GetString()!;
        var deviceId = p.GetProperty("deviceId").GetString()!;
        var deviceName = p.GetProperty("deviceName").GetString()!;
        var identityPub = p.GetProperty("identityPub").GetString()!;

        if (hostId != _identity.DeviceId) throw new RejectException("wrong_host");
        if (!_settings.Current.AcceptConnections) throw new RejectException("host_not_accepting");
        if (DateTimeOffset.UtcNow - DateTimeOffset.FromUnixTimeMilliseconds(issuedAt) is var age && (age > MaxSkew || age < -MaxSkew))
            throw new RejectException("stale_request");
        if (!_seenSessionIds.TryAdd(sessionId, DateTimeOffset.UtcNow)) throw new RejectException("replayed_session");

        // The identity key is only believable if it hashes to the claimed device id...
        string derived;
        try { derived = CryptoUtil.DeriveDeviceId(identityPub); }
        catch (FormatException) { throw new RejectException("bad_identity"); }
        if (derived != deviceId) throw new RejectException("bad_identity");
        // ...and the device is only welcome if the user approved it on THIS machine.
        var paired = _devices.All.FirstOrDefault(d => d.DeviceId == deviceId);
        if (paired is null) throw new RejectException("not_paired");
        var message = Encoding.UTF8.GetBytes(SessionCrypto.ConnectMessage(sessionId, hostId, deviceId, ephPub, issuedAt));
        if (!CryptoUtil.VerifyEd25519(identityPub, message, sig)) throw new RejectException("bad_signature");

        // Phones paired before the host kept their public keys: remember it now (it just proved itself
        // by signature), so the PC's other relays can be told about this phone too.
        if (paired.DevicePub is null) { paired = paired with { DevicePub = identityPub }; _devices.Add(paired); }

        if (_settings.Current.RequireApprovalPerSession)
        {
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(25));
            bool ok;
            try { ok = await _approver.ApproveAsync(paired.DeviceName, timeout.Token).ConfigureAwait(false); }
            catch (OperationCanceledException) { ok = false; }
            if (!ok) throw new RejectException("user_denied");
        }

        var effective = PairingPermissions.Canonical(paired.Permissions).Where(_settings.Current.PermissionEnabled).ToList();

        // A phone reconnecting replaces its own previous session (the backend only relays that case).
        EndActive("replaced");

        using var ephemeral = CryptoUtil.CreateX25519Ephemeral();
        var hostEphPub = CryptoUtil.ExportPublicB64Url(ephemeral);
        var acceptMessage = Encoding.UTF8.GetBytes(SessionCrypto.AcceptMessage(sessionId, hostId, deviceId, ephPub, hostEphPub, effective));
        var acceptSig = _identity.Sign(acceptMessage);
        var shared = SessionCrypto.SharedSecret(ephemeral, ephPub);
        var transcript = SessionCrypto.TranscriptHash(sessionId, hostId, deviceId, ephPub, hostEphPub, effective);
        var keys = SessionCrypto.DeriveKeys(shared, transcript);
        CryptographicOperations.ZeroMemory(shared);

        var channel = SessionChannel.ForHost(sessionId, keys);
        var session = new HostSession(
            sessionId, deviceId, paired.DeviceName, effective, channel, _features,
            (seq, ct) => client.SendAsync("session.data", new() { ["sessionId"] = sessionId, ["seq"] = seq, ["ct"] = ct }),
            _settings, _log);
        session.Ended += (s, reason) =>
        {
            lock (_lock) if (ReferenceEquals(_active, s)) { _active = null; _activeClient = null; }
            _ = client.TrySendAsync("session.end", new() { ["sessionId"] = s.SessionId, ["reason"] = Truncate(reason) });
            SessionEnded?.Invoke(s, reason);
        };
        lock (_lock) { _active = session; _activeClient = client; }

        await client.SendAsync("session.accept", new()
        {
            ["sessionId"] = sessionId,
            ["ephPub"] = hostEphPub,
            ["permissions"] = effective,
            ["sig"] = acceptSig,
        }).ConfigureAwait(false);

        _log.Info("session", $"{paired.DeviceName} connected with [{string.Join(",", effective)}]");
        SessionStarted?.Invoke(session);
        foreach (var f in _features) f.SessionStarted(session);

        // First encrypted frame: tells the phone what this PC offers. A phone that can open it has
        // proven it derived the same keys (key confirmation).
        var caps = new Dictionary<string, object?>
        {
            ["hostVersion"] = Config.HostConfig.ClientVersion,
            ["permissions"] = effective,
            ["features"] = session.FeatureNames.ToList(),
            ["maxFileBytes"] = _settings.Current.MaxFileBytes,
            ["clipboardSync"] = _settings.Current.ClipboardAutoPushToController,
        };
        if (RelaysProvider?.Invoke() is { Count: > 0 } relays) caps["relays"] = relays.ToList();
        await session.SendAsync("ctl.caps", caps).ConfigureAwait(false);
    }

    private void OnSessionData(JsonElement p)
    {
        var s = Active;
        if (s is null || p.GetProperty("sessionId").GetString() != s.SessionId) return;
        s.OnFrame(p.GetProperty("seq").GetInt64(), p.GetProperty("ct").GetString()!);
    }

    private void OnSessionEnd(JsonElement p)
    {
        var s = Active;
        if (s is null || p.GetProperty("sessionId").GetString() != s.SessionId) return;
        var reason = p.TryGetProperty("reason", out var r) ? r.GetString() ?? "ended" : "ended";
        s.End(reason);
    }

    private static string Truncate(string s) => s.Length > 64 ? s[..64] : s;

    private sealed class RejectException : Exception
    {
        public string Reason { get; }
        public RejectException(string reason) : base(reason) => Reason = reason;
    }
}
