using System.Text.Json;
using ConnectQuick.WindowsHost.Diagnostics;
using ConnectQuick.WindowsHost.Session;

namespace ConnectQuick.WindowsHost.Input;

/// <summary>
/// Validated remote mouse and keyboard (Phase 4). The schema already bounds every value; this
/// service adds what a schema cannot: per-session rate limits (so a runaway or hostile phone
/// cannot flood the desktop), the set of currently held keys/buttons, and the guarantee that
/// ending a session releases all of them — a dropped connection can never leave Shift or the left
/// button stuck down.
/// </summary>
public sealed class InputService : IHostFeature
{
    private readonly IInputInjector _injector;
    private readonly HostEventLog _log;
    private readonly object _lock = new();
    private readonly HashSet<string> _heldKeys = new();
    private readonly HashSet<MouseButton> _heldButtons = new();
    private readonly Dictionary<string, TokenBucket> _buckets = new();
    private long _lastBlockedReportTicks;

    public string Name => "input";

    public InputService(IInputInjector injector, HostEventLog log)
    {
        _injector = injector;
        _log = log;
    }

    public IEnumerable<Route> Routes => new[]
    {
        new Route("input.mouse_move", "mouse", Lane.Fast, MouseMove),
        new Route("input.mouse_abs", "mouse", Lane.Fast, MouseAbs),
        new Route("input.mouse_button", "mouse", Lane.Fast, MouseButtonMsg),
        new Route("input.mouse_wheel", "mouse", Lane.Fast, Wheel),
        new Route("input.key", "keyboard", Lane.Fast, KeyMsg),
        new Route("input.text", "keyboard", Lane.Fast, TextMsg),
        // Releasing is always safe and must work even if the permission was revoked mid-session.
        new Route("input.release_all", null, Lane.Fast, (s, _) => { ReleaseAll(); return Task.CompletedTask; }),
    };

    /// <summary>Held keys + buttons right now (diagnostics and tests).</summary>
    public (int Keys, int Buttons) HeldCount()
    {
        lock (_lock) return (_heldKeys.Count, _heldButtons.Count);
    }

    private bool Allow(HostSession s, string kind, double rate, double burst)
    {
        TokenBucket bucket;
        lock (_lock)
        {
            var key = s.SessionId + kind;
            if (!_buckets.TryGetValue(key, out bucket!)) _buckets[key] = bucket = new TokenBucket(burst, rate);
        }
        return bucket.TryConsume();
    }

    private Task Blocked(HostSession s, bool ok)
    {
        if (ok) return Task.CompletedTask;
        // Throttle the notice: a held-down key would otherwise produce a flood of errors.
        var now = System.Diagnostics.Stopwatch.GetTimestamp();
        if (System.Diagnostics.Stopwatch.GetElapsedTime(Interlocked.Read(ref _lastBlockedReportTicks), now).TotalSeconds < 5) return Task.CompletedTask;
        Interlocked.Exchange(ref _lastBlockedReportTicks, now);
        return s.SendErrorAsync(null, "input_blocked", "Windows refused the input (the focused app may be elevated or on the secure desktop)");
    }

    private Task MouseMove(HostSession s, JsonElement m)
    {
        if (!Allow(s, "move", 300, 600)) return Task.CompletedTask; // silently coalesced: pointer motion is lossy by nature
        return Blocked(s, _injector.MouseMoveRelative(m.GetProperty("dx").GetInt32(), m.GetProperty("dy").GetInt32()));
    }

    private Task MouseAbs(HostSession s, JsonElement m)
    {
        if (!Allow(s, "move", 300, 600)) return Task.CompletedTask;
        return Blocked(s, _injector.MouseMoveAbsolute(m.GetProperty("x").GetInt32(), m.GetProperty("y").GetInt32()));
    }

    private Task MouseButtonMsg(HostSession s, JsonElement m)
    {
        if (!Allow(s, "button", 60, 120)) return s.SendErrorAsync(null, "rate_limited", "too many button events");
        var button = m.GetProperty("button").GetString() switch
        {
            "left" => Input.MouseButton.Left,
            "right" => Input.MouseButton.Right,
            "middle" => Input.MouseButton.Middle,
            "x1" => Input.MouseButton.X1,
            _ => Input.MouseButton.X2,
        };
        var down = m.GetProperty("down").GetBoolean();
        // Track BEFORE injecting: if the process dies between the two, release-all still covers it.
        lock (_lock)
        {
            if (down) _heldButtons.Add(button); else _heldButtons.Remove(button);
        }
        return Blocked(s, _injector.MouseButton(button, down));
    }

    private Task Wheel(HostSession s, JsonElement m)
    {
        if (!Allow(s, "wheel", 120, 240)) return Task.CompletedTask;
        return Blocked(s, _injector.MouseWheel(m.GetProperty("dx").GetInt32(), m.GetProperty("dy").GetInt32()));
    }

    private Task KeyMsg(HostSession s, JsonElement m)
    {
        var code = m.GetProperty("code").GetString()!;
        var down = m.GetProperty("down").GetBoolean();
        if (!KeyMap.TryGet(code, out var key)) return s.SendErrorAsync(null, "unknown_key", code);
        if (!Allow(s, "key", 200, 400)) return s.SendErrorAsync(null, "rate_limited", "too many key events");
        lock (_lock)
        {
            if (down) _heldKeys.Add(code); else _heldKeys.Remove(code);
        }
        return Blocked(s, _injector.Key(key, down));
    }

    private Task TextMsg(HostSession s, JsonElement m)
    {
        var text = m.GetProperty("text").GetString()!;
        if (!Allow(s, "text", 20, 40)) return s.SendErrorAsync(null, "rate_limited", "too much text too fast");
        // Control characters other than tab/newline have no business in typed text.
        if (text.Any(c => char.IsControl(c) && c is not ('\t' or '\n' or '\r')))
            return s.SendErrorAsync(null, "invalid_message", "control characters are not allowed in input.text");
        return Blocked(s, _injector.Text(text));
    }

    /// <summary>Lifts every key and button the phone left pressed. Idempotent.</summary>
    public void ReleaseAll()
    {
        string[] keys;
        MouseButton[] buttons;
        lock (_lock)
        {
            keys = _heldKeys.ToArray();
            buttons = _heldButtons.ToArray();
            _heldKeys.Clear();
            _heldButtons.Clear();
        }
        foreach (var code in keys)
            if (KeyMap.TryGet(code, out var key)) _injector.Key(key, false);
        foreach (var b in buttons) _injector.MouseButton(b, false);
        if (keys.Length + buttons.Length > 0)
            _log.Info("input", $"released {keys.Length} key(s) and {buttons.Length} button(s)");
    }

    public void SessionEnded(HostSession session)
    {
        ReleaseAll();
        lock (_lock)
        {
            foreach (var k in _buckets.Keys.Where(k => k.StartsWith(session.SessionId, StringComparison.Ordinal)).ToList()) _buckets.Remove(k);
        }
    }
}
