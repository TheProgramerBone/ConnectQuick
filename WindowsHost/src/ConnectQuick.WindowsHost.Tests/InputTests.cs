using ConnectQuick.WindowsHost.Testing;
using System.Runtime.InteropServices;
using ConnectQuick.WindowsHost.Input;
using ConnectQuick.WindowsHost.Session;

namespace ConnectQuick.WindowsHost.Tests;

public sealed class InputTests
{
    private static (SessionFixture F, RecordingInjector Inj, InputService Svc) Make(IEnumerable<string>? perms = null, Action<ConnectQuick.WindowsHost.Settings.HostSettings>? cfg = null)
    {
        var inj = new RecordingInjector();
        var svc = new InputService(inj, new Diagnostics.HostEventLog());
        var f = new SessionFixture(new IHostFeature[] { svc }, perms, cfg);
        return (f, inj, svc);
    }

    /// <summary>Polls until <paramref name="condition"/> holds (or fails the test after a generous limit): event-driven, not a guessed sleep.</summary>
    private static async Task WaitUntil(Func<bool> condition, int timeoutMs = 10_000)
    {
        var deadline = Environment.TickCount64 + timeoutMs;
        while (!condition())
        {
            if (Environment.TickCount64 > deadline) throw new Xunit.Sdk.XunitException("timed out waiting for the host to process the input");
            await Task.Delay(10);
        }
    }

    /// <summary>Waits until the injector has gone quiet (no new calls for a while) instead of guessing a fixed sleep, so a loaded machine cannot make assertions run early.</summary>
    private static async Task Settle(RecordingInjector inj, int quietMs = 250, int atLeast = 0)
    {
        await WaitUntil(() => inj.Snapshot().Count >= atLeast);
        var last = -1;
        var stableSince = Environment.TickCount64;
        var deadline = Environment.TickCount64 + 10_000;
        while (Environment.TickCount64 < deadline)
        {
            var n = inj.Snapshot().Count;
            if (n != last) { last = n; stableSince = Environment.TickCount64; }
            else if (Environment.TickCount64 - stableSince >= quietMs) return;
            await Task.Delay(15);
        }
    }

    [Fact]
    public async Task Pointer_buttons_wheel_and_text_reach_the_injector_exactly_as_sent()
    {
        var (f, inj, _) = Make();
        using var _f = f;
        f.Send("input.mouse_move", new() { ["dx"] = -12, ["dy"] = 7 });
        f.Send("input.mouse_abs", new() { ["x"] = 65535, ["y"] = 0 });
        f.Send("input.mouse_button", new() { ["button"] = "right", ["down"] = true });
        f.Send("input.mouse_button", new() { ["button"] = "right", ["down"] = false });
        f.Send("input.mouse_wheel", new() { ["dx"] = 0, ["dy"] = -120 });
        f.Send("input.text", new() { ["text"] = "ñandú ✓ 🙂" });
        await Settle(inj, 250, 6);
        Assert.Equal(new[] { "move -12,7", "abs 65535,0", "btn Right down", "btn Right up", "wheel 0,-120", "text ñandú ✓ 🙂" }, inj.Snapshot());
    }

    [Fact]
    public async Task Keys_use_scancodes_with_the_extended_flag_where_windows_needs_it()
    {
        var (f, inj, _) = Make();
        using var _f = f;
        f.Send("input.key", new() { ["code"] = "KeyA", ["down"] = true });
        f.Send("input.key", new() { ["code"] = "KeyA", ["down"] = false });
        f.Send("input.key", new() { ["code"] = "ArrowLeft", ["down"] = true });
        f.Send("input.key", new() { ["code"] = "ArrowLeft", ["down"] = false });
        f.Send("input.key", new() { ["code"] = "F13", ["down"] = true });
        f.Send("input.key", new() { ["code"] = "F13", ["down"] = false });
        await Settle(inj, 250, 6);
        Assert.Equal(new[] { "key 1E down", "key 1E up", "key 4BE down", "key 4BE up", "key 00v7C down", "key 00v7C up" }, inj.Snapshot());
    }

    [Fact]
    public async Task An_unknown_key_code_is_refused_not_guessed()
    {
        var (f, inj, _) = Make();
        using var _f = f;
        f.Send("input.key", new() { ["code"] = "KeyÑ", ["down"] = true });
        // "KeyÑ" violates the schema pattern, so it is rejected before reaching the service
        Assert.Equal("invalid_message", await f.ExpectErrorAsync());
        f.Send("input.key", new() { ["code"] = "KeyNope", ["down"] = true });
        Assert.Equal("unknown_key", await f.ExpectErrorAsync());
        Assert.Empty(inj.Snapshot());
    }

    [Fact]
    public async Task Ending_the_session_releases_every_held_key_and_button()
    {
        var (f, inj, svc) = Make();
        using var _f = f;
        f.Send("input.key", new() { ["code"] = "ShiftLeft", ["down"] = true });
        f.Send("input.key", new() { ["code"] = "ControlLeft", ["down"] = true });
        f.Send("input.mouse_button", new() { ["button"] = "left", ["down"] = true });
        await WaitUntil(() => svc.HeldCount() == (2, 1));
        Assert.Equal((2, 1), svc.HeldCount());

        f.Session.End("network_lost"); // the phone vanished without releasing anything

        Assert.Equal((0, 0), svc.HeldCount());
        var events = inj.Snapshot();
        Assert.Contains("key 2A up", events);
        Assert.Contains("key 1D up", events);
        Assert.Contains("btn Left up", events);
    }

    [Fact]
    public async Task Release_all_lifts_held_input_on_demand_and_is_idempotent()
    {
        var (f, inj, svc) = Make();
        using var _f = f;
        f.Send("input.key", new() { ["code"] = "AltLeft", ["down"] = true });
        await Settle(inj, 250, 1);
        f.Send("input.release_all");
        f.Send("input.release_all");
        await Settle(inj, 250, 2);
        Assert.Equal((0, 0), svc.HeldCount());
        Assert.Single(inj.Snapshot(), e => e == "key 38 up");
    }

    [Fact]
    public async Task Without_the_mouse_permission_nothing_is_injected_and_the_phone_is_told()
    {
        var (f, inj, _) = Make(new[] { "keyboard" });
        using var _f = f;
        f.Send("input.mouse_move", new() { ["dx"] = 5, ["dy"] = 5 });
        Assert.Equal("forbidden", await f.ExpectErrorAsync());
        f.Send("input.key", new() { ["code"] = "KeyA", ["down"] = true });
        await Settle(inj, 250, 1);
        Assert.Equal(new[] { "key 1E down" }, inj.Snapshot());
    }

    [Fact]
    public async Task Turning_the_feature_off_on_the_pc_takes_effect_immediately()
    {
        var (f, inj, _) = Make(cfg: s => s.AllowKeyboard = false);
        using var _f = f;
        f.Send("input.key", new() { ["code"] = "KeyA", ["down"] = true });
        Assert.Equal("disabled_by_host", await f.ExpectErrorAsync());
        f.Settings.Update(s => s.AllowKeyboard = true);
        f.Send("input.key", new() { ["code"] = "KeyA", ["down"] = false });
        await Settle(inj, 250, 1);
        Assert.Single(inj.Snapshot());
    }

    [Fact]
    public async Task Out_of_range_or_malformed_input_is_rejected_before_injection()
    {
        var (f, inj, _) = Make();
        using var _f = f;
        f.SendRaw("{\"t\":\"input.mouse_move\",\"dx\":999999,\"dy\":0}");
        f.SendRaw("{\"t\":\"input.mouse_move\",\"dx\":\"3\",\"dy\":0}");
        f.SendRaw("{\"t\":\"input.mouse_abs\",\"x\":70000,\"y\":0}");
        f.SendRaw("{\"t\":\"input.mouse_wheel\",\"dx\":0,\"dy\":9999}");
        f.SendRaw("{\"t\":\"exec.shell\",\"cmd\":\"calc\"}");
        f.SendRaw("not json at all");
        for (var i = 0; i < 6; i++) Assert.Equal("invalid_message", await f.ExpectErrorAsync());
        Assert.Empty(inj.Snapshot());
    }

    [Fact]
    public async Task Control_characters_are_not_typeable()
    {
        var (f, inj, _) = Make();
        using var _f = f;
        f.Send("input.text", new() { ["text"] = "ok\u0007bell" });
        Assert.Equal("invalid_message", await f.ExpectErrorAsync());
        f.Send("input.text", new() { ["text"] = "line1\nline2\tx" });
        await Settle(inj, 250, 1);
        Assert.Equal(new[] { "text line1\nline2\tx" }, inj.Snapshot());
    }

    [Fact]
    public async Task A_flood_of_pointer_events_is_rate_limited()
    {
        var (f, inj, _) = Make();
        using var _f = f;
        for (var i = 0; i < 3000; i++) f.Send("input.mouse_move", new() { ["dx"] = 1, ["dy"] = 1 });
        await Settle(inj, 400, 1);
        var injected = inj.Snapshot().Count;
        Assert.InRange(injected, 1, 1500); // capped by the burst budget; the rest is dropped, not queued forever
    }

    [Fact]
    public async Task Injector_refusal_is_reported_once_not_per_event()
    {
        var (f, inj, _) = Make();
        using var _f = f;
        inj.Succeed = false;
        for (var i = 0; i < 20; i++) f.Send("input.mouse_move", new() { ["dx"] = 1, ["dy"] = 1 });
        Assert.Equal("input_blocked", await f.ExpectErrorAsync());
        await f.ExpectNoneAsync("ctl.error", 400);
    }

    [Fact]
    public async Task Forged_replayed_and_garbage_frames_are_dropped_and_persistent_abuse_ends_the_session()
    {
        var (f, inj, _) = Make();
        using var _f = f;
        var (seq, ct) = f.Controller.Seal("{\"t\":\"input.mouse_move\",\"dx\":1,\"dy\":1}"u8);
        f.Session.OnFrame(seq, ct);
        await WaitUntil(() => inj.Snapshot().Count == 1); // the genuine frame lands before the attack starts
        f.Session.OnFrame(seq, ct);                       // replay
        f.Session.OnFrame(seq + 5, ct);                   // right ciphertext, wrong sequence
        for (var i = 0; i < 30; i++) f.Session.OnFrame(1000 + i, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"); // garbage
        await WaitUntil(() => f.EndedCount == 1 && inj.Snapshot().Count >= 1);
        await Settle(inj);
        Assert.Single(inj.Snapshot());
        Assert.Equal(1, f.EndedCount);
        Assert.Equal("protocol_violations", f.EndReason);
    }

    [Fact]
    public void Every_mapped_key_is_unique_and_the_common_ones_exist()
    {
        var seen = new HashSet<(ushort, bool, ushort)>();
        foreach (var code in KeyMap.Codes)
        {
            Assert.True(KeyMap.TryGet(code, out var k));
            Assert.True(seen.Add((k.Scan, k.Extended, k.Vk)), $"{code} duplicates another key");
        }
        foreach (var c in "ABCDEFGHIJKLMNOPQRSTUVWXYZ") Assert.True(KeyMap.TryGet("Key" + c, out _));
        foreach (var c in "0123456789") Assert.True(KeyMap.TryGet("Digit" + c, out _));
        foreach (var n in Enumerable.Range(1, 24)) Assert.True(KeyMap.TryGet("F" + n, out _));
        foreach (var code in new[] { "Enter", "Escape", "Tab", "Space", "Backspace", "Delete", "ArrowUp", "ArrowDown", "MetaLeft", "ControlRight", "AltRight", "PrintScreen", "NumpadEnter" })
            Assert.True(KeyMap.TryGet(code, out _), code);
    }

    [Fact]
#pragma warning disable CA1416 // layout of the Win32 struct is what is under test; it is the same on every OS
    public void The_SendInput_struct_has_the_ABI_size_windows_expects()
    {
        // A wrong size makes SendInput fail silently, so pin it (40 bytes on 64-bit, 28 on 32-bit).
        var expected = IntPtr.Size == 8 ? 40 : 28;
        Assert.Equal(expected, Marshal.SizeOf<Win32InputInjector.INPUT>());
    }
#pragma warning restore CA1416
}

public sealed class ControlTests
{
    [Fact]
    public async Task Ping_is_answered_with_a_pong_carrying_the_same_counter_and_is_not_a_violation()
    {
        using var f = new SessionFixture(new IHostFeature[] { new ControlFeature() });
        for (var n = 1; n <= 40; n++) f.Send("ctl.ping", new() { ["n"] = n });
        for (var n = 1; n <= 40; n++) Assert.Equal(n, (await f.ExpectAsync("ctl.pong")).GetProperty("n").GetInt64());
        Assert.Equal(0, f.EndedCount); // 40 pings must never trip the violation limit
    }
}
