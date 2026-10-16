using ConnectQuick.WindowsHost.Testing;
using System.Text;
using ConnectQuick.WindowsHost.Clipboard;
using ConnectQuick.WindowsHost.Media;
using ConnectQuick.WindowsHost.Power;
using ConnectQuick.WindowsHost.Session;
using ConnectQuick.WindowsHost.Settings;

namespace ConnectQuick.WindowsHost.Tests;

public sealed class ClipboardTests
{
    private static (SessionFixture F, FakeClipboard Cb) Make(Action<HostSettings>? cfg = null, IEnumerable<string>? perms = null)
    {
        var cb = new FakeClipboard();
        var settings = SessionFixture.NewSettings(cfg);
        var svc = new ClipboardService(cb, settings, new Diagnostics.HostEventLog());
        return (new SessionFixture(new IHostFeature[] { svc }, perms, settings: settings), cb);
    }

    private static Dictionary<string, object?> Push(string id, string text, string? hash = null) =>
        new() { ["id"] = id, ["text"] = text, ["sha256"] = hash ?? ClipboardService.HashOf(text) };

    [Fact]
    public async Task Text_pushed_by_the_phone_lands_on_the_clipboard_and_is_acknowledged()
    {
        var (f, cb) = Make();
        using var _f = f;
        f.Send("clipboard.push", Push("c1", "hola ñandú 🙂"));
        var ack = await f.ExpectAsync("clipboard.ack");
        Assert.True(ack.GetProperty("ok").GetBoolean());
        Assert.Equal("hola ñandú 🙂", cb.GetText());
    }

    [Fact]
    public async Task A_hash_that_does_not_match_the_text_is_refused()
    {
        var (f, cb) = Make();
        using var _f = f;
        f.Send("clipboard.push", Push("c1", "real", new string('a', 64)));
        var ack = await f.ExpectAsync("clipboard.ack");
        Assert.False(ack.GetProperty("ok").GetBoolean());
        Assert.Equal("hash_mismatch", ack.GetProperty("reason").GetString());
        Assert.Null(cb.GetText());
    }

    [Fact]
    public async Task Oversized_text_is_refused_by_byte_length_not_character_count()
    {
        var (f, cb) = Make();
        using var _f = f;
        var text = new string('€', 6000); // 6000 chars but 18000 UTF-8 bytes (> 16 KiB)
        f.Send("clipboard.push", Push("c1", text));
        var ack = await f.ExpectAsync("clipboard.ack");
        Assert.Equal("too_large", ack.GetProperty("reason").GetString());
        Assert.Null(cb.GetText());
    }

    [Fact]
    public async Task Sending_the_same_content_twice_writes_the_clipboard_once()
    {
        var (f, cb) = Make();
        using var _f = f;
        f.Send("clipboard.push", Push("c1", "same"));
        f.Send("clipboard.push", Push("c2", "same"));
        Assert.True((await f.ExpectAsync("clipboard.ack")).GetProperty("ok").GetBoolean());
        Assert.True((await f.ExpectAsync("clipboard.ack")).GetProperty("ok").GetBoolean());
        Assert.Equal(1, cb.Writes);
    }

    [Fact]
    public async Task The_phone_can_pull_what_is_on_the_pc_clipboard()
    {
        var (f, cb) = Make();
        using var _f = f;
        cb.UserCopies("copiado en el PC");
        f.Send("clipboard.request", new() { ["id"] = "r1" });
        var push = await f.ExpectAsync("clipboard.push");
        Assert.Equal("r1", push.GetProperty("id").GetString());
        Assert.Equal("copiado en el PC", push.GetProperty("text").GetString());
        Assert.Equal(ClipboardService.HashOf("copiado en el PC"), push.GetProperty("sha256").GetString());
    }

    [Fact]
    public async Task Pulling_an_empty_or_non_text_clipboard_says_so()
    {
        var (f, cb) = Make();
        using var _f = f;
        cb.UserCopies(null);
        f.Send("clipboard.request", new() { ["id"] = "r1" });
        var ack = await f.ExpectAsync("clipboard.ack");
        Assert.False(ack.GetProperty("ok").GetBoolean());
        Assert.Equal("empty_or_not_text", ack.GetProperty("reason").GetString());
    }

    [Fact]
    public async Task Auto_push_is_off_by_default_so_local_copies_stay_private()
    {
        var (f, cb) = Make();
        using var _f = f;
        cb.UserCopies("secreto del usuario");
        await f.ExpectNoneAsync("clipboard.push", 900);
    }

    [Fact]
    public async Task With_auto_push_enabled_local_copies_reach_the_phone_but_never_echo_back_what_it_sent()
    {
        var (f, cb) = Make(s => s.ClipboardAutoPushToController = true);
        using var _f = f;
        cb.UserCopies("copiado localmente");
        var push = await f.ExpectAsync("clipboard.push", 3000);
        Assert.Equal("copiado localmente", push.GetProperty("text").GetString());

        // Now the phone pushes text; the watcher sees the clipboard change but must not send it back.
        f.Send("clipboard.push", Push("c9", "viene del movil"));
        await f.ExpectAsync("clipboard.ack");
        await f.ExpectNoneAsync("clipboard.push", 1200);
    }

    [Fact]
    public async Task Content_flagged_as_excluded_from_monitoring_is_never_auto_pushed()
    {
        var (f, cb) = Make(s => s.ClipboardAutoPushToController = true);
        using var _f = f;
        cb.Excluded = true; // what password managers set
        cb.UserCopies("hunter2");
        await f.ExpectNoneAsync("clipboard.push", 1200);
    }

    [Fact]
    public async Task Without_the_clipboard_permission_nothing_happens()
    {
        var (f, cb) = Make(perms: new[] { "mouse" });
        using var _f = f;
        f.Send("clipboard.push", Push("c1", "x"));
        Assert.Equal("forbidden", await f.ExpectErrorAsync());
        Assert.Equal(0, cb.Writes);
    }

    [Fact]
    public async Task A_failed_write_is_reported_not_swallowed()
    {
        var (f, cb) = Make();
        using var _f = f;
        cb.FailWrites = true;
        f.Send("clipboard.push", Push("c1", "x"));
        var ack = await f.ExpectAsync("clipboard.ack");
        Assert.False(ack.GetProperty("ok").GetBoolean());
        Assert.Equal("write_failed", ack.GetProperty("reason").GetString());
    }
}

public sealed class MediaTests
{
    private static (SessionFixture F, RecordingMediaKeys K) Make(IEnumerable<string>? perms = null)
    {
        var keys = new RecordingMediaKeys();
        return (new SessionFixture(new IHostFeature[] { new MediaService(keys, new Diagnostics.HostEventLog()) }, perms), keys);
    }

    [Theory]
    [InlineData("play_pause", MediaKey.PlayPause)]
    [InlineData("next", MediaKey.Next)]
    [InlineData("prev", MediaKey.Previous)]
    [InlineData("stop", MediaKey.Stop)]
    [InlineData("vol_up", MediaKey.VolumeUp)]
    [InlineData("vol_down", MediaKey.VolumeDown)]
    [InlineData("mute", MediaKey.Mute)]
    public async Task Each_action_presses_the_matching_global_media_key(string action, MediaKey expected)
    {
        var (f, keys) = Make();
        using var _f = f;
        f.Send("media.control", new() { ["id"] = "m1", ["action"] = action });
        var res = await f.ExpectAsync("media.result");
        Assert.True(res.GetProperty("ok").GetBoolean());
        Assert.Equal(new[] { expected }, keys.Pressed);
    }

    [Fact]
    public async Task The_result_never_claims_a_player_acted()
    {
        var (f, _) = Make();
        using var _f = f;
        f.Send("media.control", new() { ["id"] = "m1", ["action"] = "play_pause" });
        Assert.False((await f.ExpectAsync("media.result")).GetProperty("confirmed").GetBoolean());
    }

    [Fact]
    public async Task Volume_steps_repeat_the_key()
    {
        var (f, keys) = Make();
        using var _f = f;
        f.Send("media.control", new() { ["id"] = "m1", ["action"] = "vol_up", ["steps"] = 5 });
        await f.ExpectAsync("media.result");
        Assert.Equal(5, keys.Pressed.Count(k => k == MediaKey.VolumeUp));
    }

    [Fact]
    public async Task An_os_refusal_is_reported_as_failure()
    {
        var (f, keys) = Make();
        using var _f = f;
        keys.Succeed = false;
        f.Send("media.control", new() { ["id"] = "m1", ["action"] = "next" });
        var res = await f.ExpectAsync("media.result");
        Assert.False(res.GetProperty("ok").GetBoolean());
        Assert.Equal("os_refused", res.GetProperty("reason").GetString());
    }

    [Fact]
    public async Task Rapid_fire_requests_are_rate_limited()
    {
        var (f, keys) = Make();
        using var _f = f;
        for (var i = 0; i < 100; i++) f.Send("media.control", new() { ["id"] = "m" + i, ["action"] = "next" });
        await Task.Delay(500);
        Assert.InRange(keys.Pressed.Count, 1, 40);
    }

    [Fact]
    public async Task Unknown_actions_and_missing_permission_do_nothing()
    {
        var (f, keys) = Make(new[] { "mouse" });
        using var _f = f;
        f.SendRaw("{\"t\":\"media.control\",\"id\":\"m1\",\"action\":\"format_c\"}");
        Assert.Equal("invalid_message", await f.ExpectErrorAsync());
        f.Send("media.control", new() { ["id"] = "m2", ["action"] = "next" });
        Assert.Equal("forbidden", await f.ExpectErrorAsync());
        Assert.Empty(keys.Pressed);
    }
}

public sealed class PowerTests
{
    private sealed class Clock { public DateTimeOffset Now = DateTimeOffset.UtcNow; }

    private static (SessionFixture F, RecordingPower P, Clock C) Make(PowerPolicy policy = PowerPolicy.Full, IEnumerable<string>? perms = null)
    {
        var power = new RecordingPower();
        var clock = new Clock();
        var settings = SessionFixture.NewSettings(s => s.Power = policy);
        var svc = new PowerService(power, settings, new Diagnostics.HostEventLog(), () => clock.Now);
        return (new SessionFixture(new IHostFeature[] { svc }, perms, settings: settings), power, clock);
    }

    private static async Task<string> Challenge(SessionFixture f, string action, string id = "p1")
    {
        f.Send("power.request", new() { ["id"] = id, ["action"] = action });
        var ch = await f.ExpectAsync("power.challenge");
        Assert.Equal(action, ch.GetProperty("action").GetString());
        return ch.GetProperty("token").GetString()!;
    }

    [Fact]
    public async Task Lock_runs_immediately_without_a_challenge()
    {
        var (f, p, _) = Make();
        using var _f = f;
        f.Send("power.request", new() { ["id"] = "p1", ["action"] = "lock" });
        Assert.True((await f.ExpectAsync("power.result")).GetProperty("ok").GetBoolean());
        Assert.Equal(new[] { "lock" }, p.Calls);
    }

    [Theory]
    [InlineData("sleep", "sleep", 0)]
    [InlineData("restart", "restart/15", 15)]
    [InlineData("shutdown", "shutdown/15", 15)]
    public async Task Destructive_actions_only_run_after_the_matching_confirmation(string action, string call, int delay)
    {
        var (f, p, _) = Make();
        using var _f = f;
        var token = await Challenge(f, action);
        Assert.Empty(p.Calls); // asking is not doing
        f.Send("power.confirm", new() { ["id"] = "p1", ["token"] = token });
        var res = await f.ExpectAsync("power.result");
        Assert.True(res.GetProperty("ok").GetBoolean());
        Assert.Equal(new[] { call }, p.Calls);
        if (delay > 0) Assert.Equal(delay, res.GetProperty("delaySeconds").GetInt32());
    }

    [Fact]
    public async Task A_wrong_token_does_nothing_and_burns_the_challenge()
    {
        var (f, p, _) = Make();
        using var _f = f;
        var token = await Challenge(f, "shutdown");
        f.Send("power.confirm", new() { ["id"] = "p1", ["token"] = new string('A', 22) });
        var res = await f.ExpectAsync("power.result");
        Assert.False(res.GetProperty("ok").GetBoolean());
        f.Send("power.confirm", new() { ["id"] = "p1", ["token"] = token }); // even the right one is now spent
        Assert.False((await f.ExpectAsync("power.result")).GetProperty("ok").GetBoolean());
        Assert.Empty(p.Calls);
    }

    [Fact]
    public async Task A_token_works_once_only()
    {
        var (f, p, _) = Make();
        using var _f = f;
        var token = await Challenge(f, "restart");
        f.Send("power.confirm", new() { ["id"] = "p1", ["token"] = token });
        await f.ExpectAsync("power.result");
        f.Send("power.confirm", new() { ["id"] = "p1", ["token"] = token });
        Assert.False((await f.ExpectAsync("power.result")).GetProperty("ok").GetBoolean());
        Assert.Single(p.Calls);
    }

    [Fact]
    public async Task An_expired_challenge_is_refused()
    {
        var (f, p, clock) = Make();
        using var _f = f;
        var token = await Challenge(f, "shutdown");
        clock.Now = clock.Now.AddMilliseconds(PowerService.ConfirmWindowMs + 1);
        f.Send("power.confirm", new() { ["id"] = "p1", ["token"] = token });
        Assert.Equal("invalid_or_expired", (await f.ExpectAsync("power.result")).GetProperty("reason").GetString());
        Assert.Empty(p.Calls);
    }

    [Fact]
    public async Task A_confirmation_for_a_different_request_id_is_refused()
    {
        var (f, p, _) = Make();
        using var _f = f;
        var token = await Challenge(f, "shutdown", "p1");
        f.Send("power.confirm", new() { ["id"] = "p2", ["token"] = token });
        Assert.False((await f.ExpectAsync("power.result")).GetProperty("ok").GetBoolean());
        Assert.Empty(p.Calls);
    }

    [Fact]
    public async Task A_confirmation_without_any_request_does_nothing()
    {
        var (f, p, _) = Make();
        using var _f = f;
        f.Send("power.confirm", new() { ["id"] = "p1", ["token"] = new string('B', 22) });
        Assert.False((await f.ExpectAsync("power.result")).GetProperty("ok").GetBoolean());
        Assert.Empty(p.Calls);
    }

    [Fact]
    public async Task Ending_the_session_voids_a_pending_challenge()
    {
        var (f, p, _) = Make();
        using var _f = f;
        await Challenge(f, "shutdown");
        f.Session.End("phone_gone");
        Assert.Empty(p.Calls);
    }

    [Fact]
    public async Task Lock_only_policy_refuses_sleep_restart_and_shutdown()
    {
        var (f, p, clock) = Make(PowerPolicy.LockOnly);
        using var _f = f;
        foreach (var action in new[] { "sleep", "restart", "shutdown" })
        {
            f.Send("power.request", new() { ["id"] = "p" + action, ["action"] = action });
            Assert.Equal("policy_lock_only", (await f.ExpectAsync("power.result")).GetProperty("reason").GetString());
        }
        clock.Now = clock.Now.AddSeconds(2);
        f.Send("power.request", new() { ["id"] = "pl", ["action"] = "lock" });
        Assert.True((await f.ExpectAsync("power.result")).GetProperty("ok").GetBoolean());
        Assert.Equal(new[] { "lock" }, p.Calls);
    }

    [Fact]
    public async Task A_policy_downgrade_while_the_dialog_is_open_cancels_the_action()
    {
        var (f, p, _) = Make();
        using var _f = f;
        var token = await Challenge(f, "shutdown");
        f.Settings.Update(s => s.Power = PowerPolicy.LockOnly);
        f.Send("power.confirm", new() { ["id"] = "p1", ["token"] = token });
        var res = await f.ExpectAsync("power.result");
        Assert.False(res.GetProperty("ok").GetBoolean());
        Assert.Equal("policy_changed", res.GetProperty("reason").GetString());
        Assert.Empty(p.Calls);
    }

    [Fact]
    public async Task Disabled_policy_and_missing_permission_both_block_everything()
    {
        var (f, p, _) = Make(PowerPolicy.Disabled);
        using var _f = f;
        f.Send("power.request", new() { ["id"] = "p1", ["action"] = "lock" });
        Assert.Equal("disabled_by_host", await f.ExpectErrorAsync());
        var (g, q, _) = Make(PowerPolicy.Full, new[] { "mouse" });
        using var _g = g;
        g.Send("power.request", new() { ["id"] = "p1", ["action"] = "shutdown" });
        Assert.Equal("forbidden", await g.ExpectErrorAsync());
        Assert.Empty(p.Calls);
        Assert.Empty(q.Calls);
    }

    [Fact]
    public async Task Double_tapping_a_request_is_throttled()
    {
        var (f, p, _) = Make();
        using var _f = f;
        f.Send("power.request", new() { ["id"] = "a", ["action"] = "lock" });
        f.Send("power.request", new() { ["id"] = "b", ["action"] = "lock" });
        var first = await f.ExpectAsync("power.result");
        var second = await f.ExpectAsync("power.result");
        Assert.True(first.GetProperty("ok").GetBoolean());
        Assert.Equal("too_fast", second.GetProperty("reason").GetString());
        Assert.Single(p.Calls);
    }

    [Fact]
    public async Task Cancel_aborts_a_pending_shutdown_and_reports_when_there_is_nothing_to_cancel()
    {
        var (f, p, clock) = Make();
        using var _f = f;
        f.Send("power.request", new() { ["id"] = "c1", ["action"] = "cancel" });
        Assert.True((await f.ExpectAsync("power.result")).GetProperty("ok").GetBoolean());
        p.Next = new PowerOutcome(false, "nothing_to_cancel");
        clock.Now = clock.Now.AddSeconds(2); // past the per-second request limit
        f.Send("power.request", new() { ["id"] = "c2", ["action"] = "cancel" });
        Assert.Equal("nothing_to_cancel", (await f.ExpectAsync("power.result")).GetProperty("reason").GetString());
    }

    [Fact]
    public async Task An_executor_failure_is_reported_to_the_phone()
    {
        var (f, p, _) = Make();
        using var _f = f;
        var token = await Challenge(f, "sleep");
        p.Next = new PowerOutcome(false, "os_refused");
        f.Send("power.confirm", new() { ["id"] = "p1", ["token"] = token });
        var res = await f.ExpectAsync("power.result");
        Assert.False(res.GetProperty("ok").GetBoolean());
        Assert.Equal("os_refused", res.GetProperty("reason").GetString());
    }
}
