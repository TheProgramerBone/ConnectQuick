using ConnectQuick.WindowsHost.Session;
using ConnectQuick.WindowsHost.Streaming;
using ConnectQuick.WindowsHost.Testing;

namespace ConnectQuick.WindowsHost.Tests;

public sealed class AdaptivePolicyTests
{
    private static readonly StreamProfile Max = new(1920, 1080, 30, 4000);

    [Fact]
    public void Heavy_loss_cuts_bitrate_hard_and_light_loss_trims_it()
    {
        var (heavy, _) = AdaptivePolicy.Next(Max, Max, default, 0.20);
        Assert.Equal(2400, heavy.BitrateKbps);
        var (light, _) = AdaptivePolicy.Next(Max, Max, default, 0.05);
        Assert.Equal(3400, light.BitrateKbps);
        var (hold, st) = AdaptivePolicy.Next(Max, Max, default, 0.02);
        Assert.Equal(Max.BitrateKbps, hold.BitrateKbps);
        Assert.Equal(0, st.CleanStreak);
    }

    [Fact]
    public void Quality_only_climbs_back_after_several_clean_reports_and_never_above_the_ceiling()
    {
        var current = Max with { BitrateKbps = 2000 };
        var state = default(AdaptivePolicy.State);
        for (var i = 0; i < 2; i++) (current, state) = AdaptivePolicy.Next(current, Max, state, 0.0);
        Assert.Equal(2000, current.BitrateKbps); // two clean reports are not enough
        (current, state) = AdaptivePolicy.Next(current, Max, state, 0.0);
        Assert.Equal(2300, current.BitrateKbps);
        for (var i = 0; i < 100; i++) (current, state) = AdaptivePolicy.Next(current, Max, state, 0.0);
        Assert.Equal(Max.BitrateKbps, current.BitrateKbps);
    }

    [Fact]
    public void A_bad_network_trades_resolution_and_frame_rate_for_bitrate_and_stays_above_the_floor()
    {
        var current = Max;
        var state = default(AdaptivePolicy.State);
        for (var i = 0; i < 30; i++) (current, state) = AdaptivePolicy.Next(current, Max, state, 0.30);
        Assert.Equal(AdaptivePolicy.MinBitrateKbps, current.BitrateKbps);
        Assert.Equal(960, current.Width);   // half resolution
        Assert.Equal(540, current.Height);
        Assert.Equal(15, current.Fps);
        Assert.All(new[] { current.Width, current.Height }, v => Assert.Equal(0, v % 2));
    }

    [Fact]
    public void Resolution_comes_back_one_step_at_a_time_when_the_network_recovers()
    {
        var current = Max;
        var state = default(AdaptivePolicy.State);
        for (var i = 0; i < 30; i++) (current, state) = AdaptivePolicy.Next(current, Max, state, 0.30);
        var widths = new List<int>();
        for (var i = 0; i < 200; i++)
        {
            (current, state) = AdaptivePolicy.Next(current, Max, state, 0.0);
            if (widths.Count == 0 || widths[^1] != current.Width) widths.Add(current.Width);
        }
        Assert.Equal(new[] { 960, 1440, 1920 }, widths);
    }
}

public sealed class StreamingServiceTests
{
    private static (SessionFixture F, FakeVideoPeerFactory Factory, StreamingService Svc) Make(
        IceConfig? ice = null, TimeSpan? connectTimeout = null, IEnumerable<string>? perms = null, Action<Settings.HostSettings>? cfg = null)
    {
        var factory = new FakeVideoPeerFactory();
        var settings = SessionFixture.NewSettings(cfg);
        var svc = new StreamingService(factory, _ => Task.FromResult(ice ?? new IceConfig(Array.Empty<IceServerInfo>(), true, false, 0)),
            settings, new Diagnostics.HostEventLog(), connectTimeout ?? TimeSpan.FromSeconds(30), TimeSpan.FromMilliseconds(200));
        return (new SessionFixture(new IHostFeature[] { svc }, perms, settings: settings), factory, svc);
    }

    private static Dictionary<string, object?> Start(string id = "s1", int w = 1280, int h = 720, int fps = 30, int kbps = 2500) =>
        new() { ["id"] = id, ["maxWidth"] = w, ["maxHeight"] = h, ["maxFps"] = fps, ["maxBitrateKbps"] = kbps };

    [Fact]
    public async Task Starting_a_stream_sends_the_offer_and_relays_candidates_both_ways_inside_the_encrypted_channel()
    {
        var (f, factory, _) = Make();
        using var _f = f;
        f.Send("stream.start", Start());
        Assert.Equal("starting", (await f.ExpectAsync("stream.state")).GetProperty("state").GetString());
        Assert.Equal("v=0\r\nfake-offer", (await f.ExpectAsync("rtc.offer")).GetProperty("sdp").GetString());

        factory.Last.RaiseCandidate("candidate:1 1 udp 2130706431 192.168.1.5 50000 typ host");
        var cand = await f.ExpectAsync("rtc.candidate");
        Assert.StartsWith("candidate:1", cand.GetProperty("candidate").GetString());

        f.Send("rtc.answer", new() { ["sdp"] = "v=0\r\nanswer" });
        f.Send("rtc.candidate", new() { ["candidate"] = "candidate:9 1 udp 1 10.0.0.2 4000 typ host", ["sdpMid"] = "0", ["sdpMLineIndex"] = 0 });
        await Task.Delay(300);
        Assert.Equal("v=0\r\nanswer", factory.Last.AnswerSet);
        Assert.Single(factory.Last.RemoteCandidates);
    }

    [Fact]
    public async Task The_stream_is_capped_by_the_pcs_own_limits_not_just_the_phones_request()
    {
        var (f, factory, _) = Make(cfg: s => { s.MaxStreamFps = 15; s.MaxStreamBitrateKbps = 1000; s.MaxStreamWidth = 1280; s.MaxStreamHeight = 720; });
        using var _f = f;
        f.Send("stream.start", Start(w: 3840, h: 2160, fps: 60, kbps: 20000));
        await f.ExpectAsync("rtc.offer");
        Assert.Equal(new StreamProfile(1280, 720, 15, 1000), factory.Last.Profile);
    }

    [Fact]
    public async Task Reaching_connected_reports_streaming_and_stats_flow_and_loss_makes_the_encoder_adapt()
    {
        var (f, factory, _) = Make();
        using var _f = f;
        f.Send("stream.start", Start(kbps: 2500));
        await f.ExpectAsync("rtc.offer");
        factory.Last.Stats = new PeerStats(24.5, 1800, 1280, 720, 0.20);
        factory.Last.RaiseState(PeerState.Connected);
        var streaming = await f.ExpectAnyAsync(new[] { "stream.state" }, 3000);
        while (streaming.GetProperty("state").GetString() != "streaming") streaming = await f.ExpectAsync("stream.state");
        Assert.Equal(1280, streaming.GetProperty("width").GetInt32());

        var stats = await f.ExpectAsync("stream.stats");
        Assert.Equal(24.5, stats.GetProperty("fps").GetDouble());
        await Task.Delay(500);
        Assert.NotEmpty(factory.Last.Adaptations);
        Assert.True(factory.Last.Adaptations[0].BitrateKbps < 2500, "loss must reduce the bitrate");
    }

    [Theory]
    [InlineData(false, "ice_timeout_no_relay")]
    [InlineData(true, "ice_timeout")]
    public async Task A_stream_that_never_connects_ends_with_a_reason_that_says_whether_a_relay_was_available(bool hasTurn, string expected)
    {
        var (f, factory, _) = Make(ice: new IceConfig(Array.Empty<IceServerInfo>(), true, hasTurn, 0), connectTimeout: TimeSpan.FromMilliseconds(400));
        using var _f = f;
        f.Send("stream.start", Start());
        await f.ExpectAsync("rtc.offer");
        var end = await f.ExpectAsync("rtc.end", 3000);
        Assert.Equal(expected, end.GetProperty("reason").GetString());
        Assert.True(factory.Last.Disposed, "the peer, capture and encoder must be released");
        var state = await f.ExpectAsync("stream.state"); // starting
        while (state.GetProperty("state").GetString() == "starting") state = await f.ExpectAsync("stream.state");
        Assert.Equal("error", state.GetProperty("state").GetString());
    }

    [Fact]
    public async Task A_refused_answer_and_a_missing_video_pipeline_are_reported_not_hidden()
    {
        var (f, factory, _) = Make();
        using var _f = f;
        f.Send("stream.start", Start());
        await f.ExpectAsync("rtc.offer");
        factory.Last.RejectAnswer = true;
        f.Send("rtc.answer", new() { ["sdp"] = "garbage" });
        Assert.Equal("bad_answer", (await f.ExpectAsync("rtc.end")).GetProperty("reason").GetString());

        factory.Throw = true;
        f.Send("stream.start", Start("s2"));
        // Skip the earlier stream's leftovers (starting / error:bad_answer); the one for s2 must explain itself.
        System.Text.Json.JsonElement st;
        do { st = await f.ExpectAsync("stream.state"); } while (!(st.GetProperty("id").GetString() == "s2" && st.GetProperty("state").GetString() == "error"));
        Assert.Equal("video_unavailable", st.GetProperty("reason").GetString());
    }

    [Fact]
    public async Task Stopping_releases_everything_and_a_second_start_replaces_the_first()
    {
        var (f, factory, _) = Make();
        using var _f = f;
        f.Send("stream.start", Start("a"));
        await f.ExpectAsync("rtc.offer");
        f.Send("stream.start", Start("b"));
        Assert.Equal("replaced", (await f.ExpectAsync("rtc.end")).GetProperty("reason").GetString());
        await f.ExpectAsync("rtc.offer");
        Assert.Equal(2, factory.Created.Count);
        Assert.True(factory.Created[0].Disposed);
        Assert.False(factory.Created[1].Disposed);
        f.Send("stream.stop", new() { ["id"] = "b" });
        Assert.Equal("stopped_by_phone", (await f.ExpectAsync("rtc.end")).GetProperty("reason").GetString());
        Assert.True(factory.Created[1].Disposed);
    }

    [Fact]
    public async Task Ending_the_session_releases_the_stream()
    {
        var (f, factory, _) = Make();
        using var _f = f;
        f.Send("stream.start", Start());
        await f.ExpectAsync("rtc.offer");
        f.Session.End("phone_gone");
        await Task.Delay(200);
        Assert.True(factory.Last.Disposed);
    }

    [Fact]
    public async Task Without_the_screen_permission_no_pipeline_is_even_created()
    {
        var (f, factory, _) = Make(perms: new[] { "mouse" });
        using var _f = f;
        f.Send("stream.start", Start());
        Assert.Equal("forbidden", await f.ExpectErrorAsync());
        Assert.Empty(factory.Created);
    }

    [Fact]
    public async Task Candidate_floods_are_bounded_and_blocked_capture_is_reported_to_the_phone()
    {
        var (f, factory, _) = Make();
        using var _f = f;
        f.Send("stream.start", Start());
        await f.ExpectAsync("rtc.offer");
        for (var i = 0; i < 200; i++)
            f.Send("rtc.candidate", new() { ["candidate"] = $"candidate:{i} 1 udp 1 10.0.0.2 {4000 + i} typ host" });
        await Task.Delay(500);
        Assert.InRange(factory.Last.RemoteCandidates.Count, 1, 60);

        factory.Last.RaiseState(PeerState.Connected);
        factory.Last.RaiseBlocked("secure_desktop");
        var seen = new List<string?>();
        for (var i = 0; i < 6; i++)
        {
            try { seen.Add((await f.ExpectAsync("stream.state", 400)).TryGetProperty("reason", out var r) ? r.GetString() : null); }
            catch (TimeoutException) { break; }
        }
        Assert.Contains("secure_desktop", seen);
    }
}
