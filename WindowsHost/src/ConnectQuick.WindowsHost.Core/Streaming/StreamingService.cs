using System.Text.Json;
using ConnectQuick.WindowsHost.Diagnostics;
using ConnectQuick.WindowsHost.Net;
using ConnectQuick.WindowsHost.Session;
using ConnectQuick.WindowsHost.Settings;

namespace ConnectQuick.WindowsHost.Streaming;

/// <summary>
/// Screen streaming over WebRTC (Phase 5). The relay never carries video: the phone asks for a
/// stream over the end-to-end encrypted control channel, the PC creates an offer, and both sides
/// exchange SDP and ICE candidates INSIDE that channel (so the relay cannot even read the offer).
/// Media then flows peer-to-peer, or through the TURN relay when the networks do not allow it.
/// Every stream is capped by the PC's own limits, adapts to measured loss, and is torn down when
/// the session ends or the phone stops it.
/// </summary>
public sealed class StreamingService : IHostFeature
{
    private sealed class Active
    {
        public required string StreamId;
        public required IVideoPeer Peer;
        public required StreamProfile Max;
        public required IceConfig Ice;
        public StreamProfile Current = null!;
        public AdaptivePolicy.State Adapt;
        public int RemoteCandidates;
        public int LocalCandidates;
        public bool Connected;
        public PeerState LastState = PeerState.New;
        public string? Blocked;
        public CancellationTokenSource Cts = new();
    }

    private readonly IVideoPeerFactory _factory;
    private readonly Func<CancellationToken, Task<IceConfig>> _ice;
    private readonly HostSettingsStore _settings;
    private readonly HostEventLog _log;
    private readonly TimeSpan _connectTimeout;
    private readonly TimeSpan _statsEvery;
    private readonly object _lock = new();
    private readonly Dictionary<string, Active> _active = new();

    public string Name => "screen";

    public StreamingService(
        IVideoPeerFactory factory, Func<CancellationToken, Task<IceConfig>> iceProvider,
        HostSettingsStore settings, HostEventLog log, TimeSpan? connectTimeout = null, TimeSpan? statsEvery = null)
    {
        _factory = factory;
        _ice = iceProvider;
        _settings = settings;
        _log = log;
        _connectTimeout = connectTimeout ?? TimeSpan.FromSeconds(20);
        _statsEvery = statsEvery ?? TimeSpan.FromSeconds(2);
    }

    /// <summary>The profile currently being sent to the phone, or null when nothing is streaming. For the PC's status display.</summary>
    public StreamProfile? ActiveProfile()
    {
        lock (_lock) return _active.Values.FirstOrDefault(a => a.Connected)?.Current;
    }

    public IEnumerable<Route> Routes => new[]
    {
        new Route("stream.start", "remote_desktop", Lane.Slow, StartAsync),
        new Route("stream.stop", "remote_desktop", Lane.Slow, (s, _) => StopAsync(s, "stopped_by_phone")),
        new Route("rtc.answer", "remote_desktop", Lane.Slow, AnswerAsync),
        new Route("rtc.candidate", "remote_desktop", Lane.Slow, CandidateAsync),
        new Route("rtc.end", "remote_desktop", Lane.Slow, (s, _) => StopAsync(s, "stopped_by_phone")),
    };

    private StreamProfile Clamp(JsonElement m)
    {
        var cfg = _settings.Current;
        var w = Math.Min(m.GetProperty("maxWidth").GetInt32(), cfg.MaxStreamWidth);
        var h = Math.Min(m.GetProperty("maxHeight").GetInt32(), cfg.MaxStreamHeight);
        return new StreamProfile(w & ~1, h & ~1,
            Math.Min(m.GetProperty("maxFps").GetInt32(), cfg.MaxStreamFps),
            Math.Min(m.GetProperty("maxBitrateKbps").GetInt32(), cfg.MaxStreamBitrateKbps));
    }

    private Task State(HostSession s, string id, string state, StreamProfile? p = null, string? reason = null) =>
        s.SendAsync("stream.state", new()
        {
            ["id"] = id, ["state"] = state, ["reason"] = reason,
            ["width"] = p?.Width, ["height"] = p?.Height, ["fps"] = p?.Fps, ["bitrateKbps"] = p?.BitrateKbps,
        });

    private async Task StartAsync(HostSession s, JsonElement m)
    {
        var id = m.GetProperty("id").GetString()!;
        await StopAsync(s, "replaced").ConfigureAwait(false);
        var max = Clamp(m);
        await State(s, id, "starting", max).ConfigureAwait(false);

        IceConfig ice;
        try { ice = await _ice(s.Token).ConfigureAwait(false); }
        catch (OperationCanceledException) { return; }

        IVideoPeer peer;
        try { peer = _factory.Create(ice, max); }
        catch (Exception ex)
        {
            _log.Error("stream", $"video pipeline unavailable: {ex.GetType().Name}");
            await State(s, id, "error", reason: "video_unavailable").ConfigureAwait(false);
            return;
        }

        var a = new Active { StreamId = id, Peer = peer, Max = max, Ice = ice, Current = max };
        lock (_lock) _active[s.SessionId] = a;

        peer.CandidateFound += c =>
        {
            if (Interlocked.Increment(ref a.LocalCandidates) > 40) return;
            _ = s.SendAsync("rtc.candidate", new() { ["candidate"] = c.Candidate, ["sdpMid"] = c.SdpMid, ["sdpMLineIndex"] = c.SdpMLineIndex });
        };
        peer.CaptureBlocked += reason =>
        {
            a.Blocked = reason;
            if (a.Connected) _ = State(s, id, "streaming", a.Current, reason);
        };
        peer.StateChanged += st => _ = OnPeerStateAsync(s, a, st);

        try
        {
            var offer = await peer.CreateOfferAsync().ConfigureAwait(false);
            await s.SendAsync("rtc.offer", new() { ["sdp"] = offer }).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            _log.Error("stream", $"could not create the offer: {ex.GetType().Name}");
            await StopAsync(s, "offer_failed", error: "offer_failed").ConfigureAwait(false);
            return;
        }
        _log.Info("stream", $"streaming to {s.DeviceName} requested ({max.Width}x{max.Height}@{max.Fps}, up to {max.BitrateKbps} kbps; turn={(ice.HasTurn ? "yes" : "no")})");
        _ = WatchConnectAsync(s, a);
    }

    private async Task WatchConnectAsync(HostSession s, Active a)
    {
        try
        {
            await Task.Delay(_connectTimeout, a.Cts.Token).ConfigureAwait(false);
            if (!a.Connected)
            {
                // Say WHY, because "it did not connect" is useless to a person trying to fix their network.
                var reason = a.Ice.HasTurn ? "ice_timeout" : "ice_timeout_no_relay";
                _log.Warn("stream", $"no media path to {s.DeviceName} within {_connectTimeout.TotalSeconds:0}s ({reason})");
                await StopAsync(s, reason, error: reason).ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException) { }
    }

    private async Task OnPeerStateAsync(HostSession s, Active a, PeerState st)
    {
        if (a.Cts.IsCancellationRequested) return;
        a.LastState = st;
        switch (st)
        {
            case PeerState.Connected when !a.Connected:
                a.Connected = true;
                _log.Info("stream", $"media connected to {s.DeviceName}");
                await State(s, a.StreamId, "streaming", a.Current, a.Blocked).ConfigureAwait(false);
                _ = StatsLoopAsync(s, a);
                break;
            case PeerState.Failed:
                await StopAsync(s, "connection_lost", error: "connection_lost").ConfigureAwait(false);
                break;
            case PeerState.Disconnected when a.Connected:
                // Often a short blip while ICE restarts; give it a moment before giving up.
                _ = Task.Delay(TimeSpan.FromSeconds(10), a.Cts.Token).ContinueWith(async t =>
                {
                    if (t.IsCanceled) return;
                    // Still disconnected after the grace period (it did not recover on its own).
                    if (a.LastState == PeerState.Disconnected && !a.Cts.IsCancellationRequested)
                        await StopAsync(s, "connection_lost", error: "connection_lost").ConfigureAwait(false);
                }, TaskScheduler.Default);
                break;
        }
    }

    private async Task StatsLoopAsync(HostSession s, Active a)
    {
        try
        {
            while (!a.Cts.IsCancellationRequested)
            {
                await Task.Delay(_statsEvery, a.Cts.Token).ConfigureAwait(false);
                var stats = a.Peer.GetStats();
                await s.SendAsync("stream.stats", new()
                {
                    ["fps"] = Math.Round(stats.Fps, 1), ["bitrateKbps"] = stats.BitrateKbps, ["width"] = stats.Width, ["height"] = stats.Height,
                }).ConfigureAwait(false);
                var (next, state) = AdaptivePolicy.Next(a.Current, a.Max, a.Adapt, stats.FractionLost);
                a.Adapt = state;
                if (next != a.Current)
                {
                    _log.Info("stream", $"adapting {a.Current.Width}x{a.Current.Height}@{a.Current.Fps}/{a.Current.BitrateKbps}k -> {next.Width}x{next.Height}@{next.Fps}/{next.BitrateKbps}k (loss {stats.FractionLost:P0})");
                    a.Current = next;
                    a.Peer.Adapt(next);
                }
            }
        }
        catch (OperationCanceledException) { }
    }

    private async Task AnswerAsync(HostSession s, JsonElement m)
    {
        Active? a;
        lock (_lock) _active.TryGetValue(s.SessionId, out a);
        if (a is null) { await s.SendErrorAsync(null, "no_stream", "there is no stream waiting for an answer").ConfigureAwait(false); return; }
        try
        {
            await a.Peer.SetAnswerAsync(m.GetProperty("sdp").GetString()!).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            _log.Warn("stream", $"the phone's answer was refused: {ex.GetType().Name}");
            await StopAsync(s, "bad_answer", error: "bad_answer").ConfigureAwait(false);
        }
    }

    private Task CandidateAsync(HostSession s, JsonElement m)
    {
        Active? a;
        lock (_lock) _active.TryGetValue(s.SessionId, out a);
        if (a is null || Interlocked.Increment(ref a.RemoteCandidates) > 60) return Task.CompletedTask; // bounded: no candidate floods
        try
        {
            a.Peer.AddRemoteCandidate(
                m.GetProperty("candidate").GetString()!,
                m.TryGetProperty("sdpMid", out var mid) ? mid.GetString() : null,
                m.TryGetProperty("sdpMLineIndex", out var idx) ? idx.GetInt32() : null);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            _log.Warn("stream", $"ignored a bad ICE candidate: {ex.GetType().Name}");
        }
        return Task.CompletedTask;
    }

    private async Task StopAsync(HostSession s, string reason, string? error = null)
    {
        Active? a;
        lock (_lock) _active.Remove(s.SessionId, out a);
        if (a is null) return;
        a.Cts.Cancel();
        try { await a.Peer.DisposeAsync().ConfigureAwait(false); }
        catch (Exception ex) { _log.Warn("stream", $"closing the video peer failed: {ex.GetType().Name}"); }
        _log.Info("stream", $"stream to {s.DeviceName} ended ({reason})");
        if (!s.IsEnded)
        {
            await s.SendAsync("rtc.end", new() { ["reason"] = reason }).ConfigureAwait(false);
            await State(s, a.StreamId, error is null ? "stopped" : "error", reason: reason).ConfigureAwait(false);
        }
    }

    public void SessionEnded(HostSession session)
    {
        Active? a;
        lock (_lock) _active.Remove(session.SessionId, out a);
        if (a is null) return;
        a.Cts.Cancel();
        _ = a.Peer.DisposeAsync().AsTask(); // releases capture, encoder and sockets; the session is gone so nobody is told
    }
}
