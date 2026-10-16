using System.Security.Cryptography;
using System.Text.Json;
using ConnectQuick.WindowsHost.Crypto;
using ConnectQuick.WindowsHost.Diagnostics;
using ConnectQuick.WindowsHost.Session;
using ConnectQuick.WindowsHost.Settings;

namespace ConnectQuick.WindowsHost.Power;

public readonly record struct PowerOutcome(bool Ok, string? Reason = null);

/// <summary>
/// Executes power actions. The Windows implementation uses fixed APIs and a fixed command with
/// fixed arguments — never a command line assembled from remote input.
/// </summary>
public interface IPowerExecutor
{
    PowerOutcome Lock();
    PowerOutcome Sleep();
    PowerOutcome Restart(int delaySeconds);
    PowerOutcome Shutdown(int delaySeconds);
    PowerOutcome CancelPending();
}

/// <summary>
/// Remote power control (Phase 9). Locking is a single step; sleep, restart and shutdown are a
/// two-step exchange: the PC answers <c>power.request</c> with a one-time, session-bound,
/// short-lived token (<c>power.challenge</c>), and nothing happens until the phone — after the
/// user confirmed on screen — returns it in <c>power.confirm</c>. A lost, replayed, expired or
/// cross-session token does nothing. Restart/shutdown also run with a grace delay during which
/// <c>cancel</c> works. No message can carry a command or a path.
/// </summary>
public sealed class PowerService : IHostFeature
{
    public const int ConfirmWindowMs = 20_000;
    public const int GraceSeconds = 15;

    private sealed record Pending(string RequestId, string Action, string Token, DateTimeOffset Expires);

    private readonly IPowerExecutor _executor;
    private readonly HostSettingsStore _settings;
    private readonly HostEventLog _log;
    private readonly Func<DateTimeOffset> _now;
    private readonly Dictionary<string, Pending> _pending = new();
    private readonly Dictionary<string, DateTimeOffset> _lastRequest = new();
    private readonly object _lock = new();

    public string Name => "power";

    public PowerService(IPowerExecutor executor, HostSettingsStore settings, HostEventLog log, Func<DateTimeOffset>? now = null)
    {
        _executor = executor;
        _settings = settings;
        _log = log;
        _now = now ?? (() => DateTimeOffset.UtcNow);
    }

    public IEnumerable<Route> Routes => new[]
    {
        new Route("power.request", "power", Lane.Slow, RequestAsync),
        new Route("power.confirm", "power", Lane.Slow, ConfirmAsync),
    };

    private static int GraceFor(string action) => action is "restart" or "shutdown" ? GraceSeconds : 0;

    private Task Result(HostSession s, string id, string action, PowerOutcome outcome, int delay = 0) =>
        s.SendAsync("power.result", new() { ["id"] = id, ["ok"] = outcome.Ok, ["action"] = action, ["reason"] = outcome.Reason, ["delaySeconds"] = outcome.Ok && delay > 0 ? delay : null });

    private async Task RequestAsync(HostSession s, JsonElement m)
    {
        var id = m.GetProperty("id").GetString()!;
        var action = m.GetProperty("action").GetString()!;
        var policy = _settings.Current.Power;

        if (policy == PowerPolicy.LockOnly && action is "sleep" or "restart" or "shutdown")
        {
            _log.Warn("power", $"{action} refused: PC policy allows lock only");
            await Result(s, id, action, new(false, "policy_lock_only"));
            return;
        }

        // At most one request per second per session: stops double-taps and request floods.
        lock (_lock)
        {
            if (_lastRequest.TryGetValue(s.SessionId, out var last) && _now() - last < TimeSpan.FromSeconds(1))
            {
                _ = Result(s, id, action, new(false, "too_fast"));
                return;
            }
            _lastRequest[s.SessionId] = _now();
        }

        switch (action)
        {
            case "lock":
                _log.Info("power", $"lock requested by {s.DeviceName}");
                await Result(s, id, action, _executor.Lock());
                return;
            case "cancel":
                lock (_lock) _pending.Remove(s.SessionId);
                await Result(s, id, action, _executor.CancelPending());
                return;
        }

        var token = CryptoUtil.ToBase64Url(RandomNumberGenerator.GetBytes(16));
        lock (_lock) _pending[s.SessionId] = new Pending(id, action, token, _now().AddMilliseconds(ConfirmWindowMs));
        _log.Warn("power", $"{action} requested by {s.DeviceName}; waiting for on-screen confirmation");
        await s.SendAsync("power.challenge", new() { ["id"] = id, ["action"] = action, ["token"] = token, ["expiresInMs"] = ConfirmWindowMs, ["delaySeconds"] = GraceFor(action) });
    }

    private async Task ConfirmAsync(HostSession s, JsonElement m)
    {
        var id = m.GetProperty("id").GetString()!;
        var token = m.GetProperty("token").GetString()!;

        Pending? pending;
        lock (_lock)
        {
            _pending.Remove(s.SessionId, out pending); // single use: gone whether it matches or not
        }
        if (pending is null || pending.RequestId != id || _now() > pending.Expires
            || !CryptographicOperations.FixedTimeEquals(System.Text.Encoding.ASCII.GetBytes(pending.Token), System.Text.Encoding.ASCII.GetBytes(token)))
        {
            _log.Warn("power", $"confirmation from {s.DeviceName} rejected (no matching live challenge)");
            await Result(s, id, pending?.Action ?? "cancel", new(false, "invalid_or_expired"));
            return;
        }
        // Policy may have changed while the dialog was open.
        if (_settings.Current.Power != PowerPolicy.Full)
        {
            await Result(s, id, pending.Action, new(false, "policy_changed"));
            return;
        }

        var grace = GraceFor(pending.Action);
        var outcome = pending.Action switch
        {
            "sleep" => _executor.Sleep(),
            "restart" => _executor.Restart(grace),
            "shutdown" => _executor.Shutdown(grace),
            _ => new PowerOutcome(false, "unsupported"),
        };
        _log.Warn("power", $"{pending.Action} by {s.DeviceName}: {(outcome.Ok ? "executing" : outcome.Reason)}");
        await Result(s, id, pending.Action, outcome, grace);
    }

    public void SessionEnded(HostSession session)
    {
        lock (_lock)
        {
            _pending.Remove(session.SessionId);
            _lastRequest.Remove(session.SessionId);
        }
    }
}
