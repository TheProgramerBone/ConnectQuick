using System.Collections.Concurrent;
using ConnectQuick.WindowsHost.Streaming;
using SIPSorcery.Net;
using SIPSorceryMedia.Abstractions;

namespace ConnectQuick.WindowsHost.Tests;

/// <summary>A desktop that can be scripted: static, animated, or blocked (secure desktop).</summary>
internal sealed class ScriptedCapturer : IScreenCapturer
{
    public int Width = 640, Height = 360;
    public volatile bool Animate = true;
    public volatile string? Blocked;
    public int Captures;
    private byte[] _buf = new byte[0];
    public CaptureResult Capture()
    {
        var n = Interlocked.Increment(ref Captures);
        if (Blocked is { } reason) return CaptureResult.Blocked(Width, Height, reason);
        if (_buf.Length != Width * Height * 4) _buf = new byte[Width * Height * 4];
        var shade = (byte)(Animate ? n * 7 : 40);
        for (var i = 0; i < _buf.Length; i += 4) { _buf[i] = shade; _buf[i + 1] = (byte)((i / 4) % 251); _buf[i + 2] = 90; _buf[i + 3] = 255; }
        return new CaptureResult(Width, Height, _buf, null);
    }
    public void Dispose() { }
}

/// <summary>Stands in for libvpx: emits a VP8-shaped key frame / delta frame of a chosen size so RTP fragmentation is real.</summary>
internal sealed class FakeVp8Encoder : IFrameEncoder
{
    public int PayloadBytes = 4200;
    public readonly ConcurrentQueue<(int W, int H, bool Key)> Calls = new();
    public readonly List<int> Bitrates = new();
    public EncodedFrame? Encode(byte[] bgra, int width, int height, bool forceKeyFrame)
    {
        Calls.Enqueue((width, height, forceKeyFrame));
        var data = new byte[PayloadBytes];
        // Key frame tag (bit 0 clear) + start code + dimensions, then a pattern the receiver can check.
        data[0] = forceKeyFrame ? (byte)0x10 : (byte)0x11;
        if (forceKeyFrame) { data[3] = 0x9D; data[4] = 0x01; data[5] = 0x2A; data[6] = (byte)(width & 0xFF); data[7] = (byte)(width >> 8); data[8] = (byte)(height & 0xFF); data[9] = (byte)(height >> 8); }
        for (var i = 10; i < data.Length; i++) data[i] = (byte)(i * 31 + bgra[0]);
        return new EncodedFrame(data, forceKeyFrame);
    }
    public void SetBitrate(int kbps) { lock (Bitrates) Bitrates.Add(kbps); }
    public void Dispose() { }
}

/// <summary>A minimal WebRTC receiver, standing in for the phone's libwebrtc.</summary>
internal sealed class LoopbackViewer : IAsyncDisposable
{
    public readonly RTCPeerConnection Pc = new(new RTCConfiguration());
    public readonly ConcurrentQueue<byte[]> Frames = new();
    public LoopbackViewer()
    {
        Pc.addTrack(new MediaStreamTrack(new List<VideoFormat> { new(VideoCodecsEnum.VP8, 96) }, MediaStreamStatusEnum.RecvOnly));
        Pc.OnVideoFrameReceived += (ep, ts, frame, fmt) => Frames.Enqueue(frame);
    }
    public async Task<string> AnswerAsync(string offerSdp)
    {
        Pc.setRemoteDescription(new RTCSessionDescriptionInit { type = RTCSdpType.offer, sdp = offerSdp });
        var answer = Pc.createAnswer(null);
        await Pc.setLocalDescription(answer);
        return answer.sdp;
    }
    public ValueTask DisposeAsync() { Pc.close(); return ValueTask.CompletedTask; }
}

public sealed class WebRtcLoopbackTests
{
    private static readonly StreamProfile Profile = new(320, 180, 20, 1500);

    private static async Task<(SipSorceryVideoPeer Peer, LoopbackViewer Viewer, ScriptedCapturer Cap, FakeVp8Encoder Enc, List<PeerState> States)> Connect(StreamProfile? profile = null)
    {
        var cap = new ScriptedCapturer();
        var enc = new FakeVp8Encoder();
        var peer = new SipSorceryVideoPeer(IceConfig.None, profile ?? Profile, cap, enc);
        var viewer = new LoopbackViewer();
        var states = new List<PeerState>();
        var connected = new TaskCompletionSource();
        peer.StateChanged += s => { lock (states) states.Add(s); if (s == PeerState.Connected) connected.TrySetResult(); };
        peer.CandidateFound += c => viewer.Pc.addIceCandidate(new RTCIceCandidateInit { candidate = c.Candidate, sdpMid = c.SdpMid ?? "0", sdpMLineIndex = (ushort)(c.SdpMLineIndex ?? 0) });
        viewer.Pc.onicecandidate += c =>
        {
            if (c is not null) peer.AddRemoteCandidate(c.candidate, c.sdpMid, c.sdpMLineIndex);
        };
        var offer = await peer.CreateOfferAsync();
        var answer = await viewer.AnswerAsync(offer);
        await peer.SetAnswerAsync(answer);
        await connected.Task.WaitAsync(TimeSpan.FromSeconds(20));
        return (peer, viewer, cap, enc, states);
    }

    private static async Task<bool> WaitFor(Func<bool> cond, int ms = 8000)
    {
        var end = DateTime.UtcNow.AddMilliseconds(ms);
        while (DateTime.UtcNow < end) { if (cond()) return true; await Task.Delay(40); }
        return cond();
    }

    [Fact]
    public async Task A_real_ICE_DTLS_SRTP_connection_delivers_whole_fragmented_frames_to_the_viewer()
    {
        var (peer, viewer, _, enc, _) = await Connect();
        await using var _p = peer; await using var _v = viewer;
        Assert.True(await WaitFor(() => viewer.Frames.Count >= 5), $"only {viewer.Frames.Count} frames arrived");
        // 4200 bytes cannot fit one RTP packet: they were fragmented and reassembled intact.
        Assert.All(viewer.Frames.Take(5), f => Assert.Equal(4200, f.Length));
        var first = viewer.Frames.First();
        Assert.Equal(0x9D, first[3]);
        Assert.Equal(320, first[6] | (first[7] << 8));
        Assert.Equal(180, first[8] | (first[9] << 8));
        Assert.True(enc.Calls.TryPeek(out var firstCall) && firstCall.Key, "the very first frame must be a key frame");
    }

    [Fact]
    public async Task A_still_desktop_costs_almost_nothing_but_the_stream_never_starves()
    {
        var (peer, viewer, cap, enc, _) = await Connect();
        await using var _p = peer; await using var _v = viewer;
        cap.Animate = false;
        await Task.Delay(500); // let the animated frames drain
        var before = enc.Calls.Count;
        await Task.Delay(3200);
        var encodedWhileStill = enc.Calls.Count - before;
        Assert.InRange(encodedWhileStill, 2, 6); // ~1 per second keep-alive, not 20 per second
        Assert.True(cap.Captures > 40, "capture itself keeps polling at the frame rate");
    }

    [Fact]
    public async Task Adapting_changes_the_encoded_size_and_forces_a_key_frame_without_renegotiating()
    {
        var (peer, viewer, _, enc, _) = await Connect(new StreamProfile(640, 360, 20, 2000));
        await using var _p = peer; await using var _v = viewer;
        Assert.True(await WaitFor(() => viewer.Frames.Count >= 3));
        peer.Adapt(new StreamProfile(320, 180, 10, 600));
        Assert.True(await WaitFor(() => enc.Calls.Any(c => c.W == 320 && c.H == 180)));
        var firstAtNewSize = enc.Calls.First(c => c.W == 320 && c.H == 180);
        Assert.True(firstAtNewSize.Key, "the first frame at a new size must be a key frame (regression: the key frame used to land on the old size)");
        Assert.Contains(600, enc.Bitrates);
        var after = viewer.Frames.Count;
        Assert.True(await WaitFor(() => viewer.Frames.Count > after), "frames keep flowing after adaptation");
    }

    [Fact]
    public async Task Capture_that_cannot_read_the_desktop_is_reported_and_the_stream_keeps_running()
    {
        var (peer, viewer, cap, _, _) = await Connect();
        await using var _p = peer; await using var _v = viewer;
        var reasons = new ConcurrentQueue<string?>();
        peer.CaptureBlocked += r => reasons.Enqueue(r);
        cap.Blocked = "secure_desktop";
        Assert.True(await WaitFor(() => reasons.Contains("secure_desktop")));
        var n = viewer.Frames.Count;
        Assert.True(await WaitFor(() => viewer.Frames.Count > n), "a blank frame still goes out so the viewer does not freeze");
        cap.Blocked = null;
        Assert.True(await WaitFor(() => reasons.Contains(null)), "recovery is reported too");
    }

    [Fact]
    public async Task Disposing_stops_capture_and_closes_the_connection_cleanly()
    {
        var (peer, viewer, cap, _, states) = await Connect();
        await using var _v = viewer;
        await peer.DisposeAsync();
        var captures = cap.Captures;
        await Task.Delay(400);
        Assert.Equal(captures, cap.Captures); // the pump really stopped: nothing keeps capturing the screen after teardown
    }

    [Fact]
    public void Scaler_keeps_aspect_ratio_never_upscales_and_averages_pixels()
    {
        Assert.Equal((1280, 720), FrameScaler.FitWithin(2560, 1440, 1280, 720));
        Assert.Equal((960, 540), FrameScaler.FitWithin(1920, 1080, 1280, 540));
        Assert.Equal((800, 600), FrameScaler.FitWithin(800, 600, 1920, 1080));
        Assert.Equal((2, 2), FrameScaler.FitWithin(2, 2, 1, 1).Width % 2 == 0 ? (2, 2) : (0, 0));
        var src = new byte[4 * 4 * 4];
        for (var i = 0; i < src.Length; i += 4) { src[i] = 100; src[i + 1] = 100; src[i + 2] = 100; src[i + 3] = 255; }
        var dst = new byte[2 * 2 * 4];
        FrameScaler.ScaleBgra(src, 4, 4, dst, 2, 2);
        Assert.All(dst, b => Assert.True(b == 100 || b == 255));
        Assert.Throws<ArgumentException>(() => FrameScaler.ScaleBgra(new byte[10], 4, 4, dst, 2, 2));
    }

    [Fact]
    public void The_codec_numbering_assumptions_behind_the_encoder_shim_are_pinned()
    {
        // SIPSorcery 10.x numbers VP8 as 8; the libvpx wrapper (built for Abstractions 8.x) calls it 7 and
        // identifies it through its own advertised format, which Vp8LibvpxEncoder relies on. If a package
        // update changes either side, this fails loudly instead of silently streaming the wrong codec.
        Assert.Equal(8, (int)VideoCodecsEnum.VP8);
        Assert.Equal(2, (int)VideoPixelFormatsEnum.Bgra);
        Assert.True(Vp8LibvpxEncoder.IsVp8KeyFrame(new byte[] { 0x10, 0, 0, 0x9D, 0x01, 0x2A, 0, 0 }));
        Assert.False(Vp8LibvpxEncoder.IsVp8KeyFrame(new byte[] { 0x11, 0, 0, 0x9D, 0x01, 0x2A, 0, 0 }));
        Assert.False(Vp8LibvpxEncoder.IsVp8KeyFrame(new byte[] { 0x10, 0, 0 }));
    }
}
