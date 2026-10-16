using System.Diagnostics;
using ConnectQuick.WindowsHost.Streaming;
using SIPSorcery.Net;
using SIPSorceryMedia.Abstractions;

namespace ConnectQuick.WindowsHost.Streaming;

/// <summary>
/// A WebRTC sender over SIPSorcery: RTCPeerConnection (ICE with STUN/TURN, DTLS-SRTP) carrying one
/// VP8 video track fed by a capture -> scale -> encode loop.
///
/// The loop is deliberately simple and cheap on a desktop: it skips frames identical to the last
/// one sent (a still desktop costs almost nothing), always re-sends within a second so the receiver
/// never starves, honours the profile's frame rate, and answers keyframe requests (PLI/FIR) from the
/// receiver. Capture trouble (UAC, lock screen) is reported, not hidden.
/// </summary>
public sealed class SipSorceryVideoPeer : IVideoPeer
{
    private readonly RTCPeerConnection _pc;
    private readonly IScreenCapturer _capturer;
    private readonly IFrameEncoder _encoder;
    private readonly CancellationTokenSource _cts = new();
    private readonly object _statsLock = new();
    private readonly VideoFormat _wireFormat = new(VideoCodecsEnum.VP8, 96);

    private StreamProfile _profile;
    private volatile bool _forceKeyFrame = true;
    private volatile bool _connected;
    private Task? _pump;
    private string? _blocked;
    private double _fractionLost;
    private int _lastWidth, _lastHeight;
    private long _framesWindow, _bytesWindow;
    private long _windowStartTicks = Stopwatch.GetTimestamp();
    private double _fpsMeasured;
    private int _kbpsMeasured;

    public event Action<LocalCandidate>? CandidateFound;
    public event Action<PeerState>? StateChanged;
    public event Action<string?>? CaptureBlocked;

    public SipSorceryVideoPeer(IceConfig ice, StreamProfile profile, IScreenCapturer capturer, IFrameEncoder encoder)
    {
        _profile = profile;
        _capturer = capturer;
        _encoder = encoder;

        var cfg = new RTCConfiguration { iceServers = new List<RTCIceServer>() };
        foreach (var server in ice.Servers)
        {
            foreach (var url in server.Urls)
            {
                cfg.iceServers.Add(new RTCIceServer
                {
                    urls = url,
                    username = server.Username,
                    credential = server.Credential,
                    credentialType = RTCIceCredentialType.password,
                });
            }
        }
        _pc = new RTCPeerConnection(cfg);
        _pc.addTrack(new MediaStreamTrack(new List<VideoFormat> { _wireFormat }, MediaStreamStatusEnum.SendOnly));

        _pc.onicecandidate += c =>
        {
            if (c is not null) CandidateFound?.Invoke(new LocalCandidate(c.candidate, c.sdpMid, c.sdpMLineIndex));
        };
        _pc.onconnectionstatechange += OnConnectionState;
        _pc.OnReceiveReport += OnRtcp;
    }

    private void OnConnectionState(RTCPeerConnectionState s)
    {
        var mapped = s switch
        {
            RTCPeerConnectionState.@new => PeerState.New,
            RTCPeerConnectionState.connecting => PeerState.Connecting,
            RTCPeerConnectionState.connected => PeerState.Connected,
            RTCPeerConnectionState.disconnected => PeerState.Disconnected,
            RTCPeerConnectionState.failed => PeerState.Failed,
            _ => PeerState.Closed,
        };
        if (mapped == PeerState.Connected && !_connected)
        {
            _connected = true;
            _pump = Task.Factory.StartNew(PumpLoop, _cts.Token, TaskCreationOptions.LongRunning, TaskScheduler.Default);
        }
        StateChanged?.Invoke(mapped);
    }

    /// <summary>Receiver reports give the loss that drives adaptation; PLI/FIR feedback asks for a key frame.</summary>
    private void OnRtcp(System.Net.IPEndPoint ep, SDPMediaTypesEnum media, RTCPCompoundPacket packet)
    {
        if (media != SDPMediaTypesEnum.video) return;
        if (packet.ReceiverReport?.ReceptionReports is { Count: > 0 } reports)
        {
            var worst = reports.Max(r => r.FractionLost) / 256.0;
            lock (_statsLock) _fractionLost = worst;
        }
        if (packet.Feedback is { } fb && fb.Header.PayloadFeedbackMessageType is PSFBFeedbackTypesEnum.PLI or PSFBFeedbackTypesEnum.FIR)
            _forceKeyFrame = true;
    }

    public async Task<string> CreateOfferAsync()
    {
        var offer = _pc.createOffer(null);
        await _pc.setLocalDescription(offer).ConfigureAwait(false);
        return offer.sdp;
    }

    public Task SetAnswerAsync(string sdp)
    {
        var result = _pc.setRemoteDescription(new RTCSessionDescriptionInit { type = RTCSdpType.answer, sdp = sdp });
        if (result != SetDescriptionResultEnum.OK) throw new InvalidOperationException($"answer rejected: {result}");
        return Task.CompletedTask;
    }

    public void AddRemoteCandidate(string candidate, string? sdpMid, int? sdpMLineIndex) =>
        _pc.addIceCandidate(new RTCIceCandidateInit { candidate = candidate, sdpMid = sdpMid ?? "0", sdpMLineIndex = (ushort)(sdpMLineIndex ?? 0) });

    public void Adapt(StreamProfile profile)
    {
        var rateChanged = profile.BitrateKbps != _profile.BitrateKbps;
        _profile = profile;
        if (rateChanged) _encoder.SetBitrate(profile.BitrateKbps);
        _forceKeyFrame = true; // a new size or rate starts from a key frame
    }

    public PeerStats GetStats()
    {
        lock (_statsLock) return new PeerStats(_fpsMeasured, _kbpsMeasured, _lastWidth, _lastHeight, _fractionLost);
    }

    private void PumpLoop()
    {
        var clock = Stopwatch.StartNew();
        byte[]? scaled = null;
        byte[]? previous = null;
        long lastSentMs = -10_000;
        long nextDueMs = 0;
        int encodedW = 0, encodedH = 0;
        var token = _cts.Token;
        while (!token.IsCancellationRequested)
        {
            var profile = _profile;
            var intervalMs = 1000L / Math.Max(1, profile.Fps);
            var wait = nextDueMs - clock.ElapsedMilliseconds;
            if (wait > 0) { token.WaitHandle.WaitOne((int)wait); if (token.IsCancellationRequested) break; }
            nextDueMs = clock.ElapsedMilliseconds + intervalMs;

            try
            {
                var cap = _capturer.Capture();
                var (w, h) = FrameScaler.FitWithin(cap.Width, cap.Height, profile.Width, profile.Height);
                if (scaled is null || scaled.Length != w * h * 4) { scaled = new byte[w * h * 4]; previous = null; }

                if (cap.BlockedReason != _blocked)
                {
                    _blocked = cap.BlockedReason;
                    CaptureBlocked?.Invoke(_blocked);
                }
                if (cap.Bgra is null)
                {
                    // Nothing real to show: keep the receiver alive with a plain frame instead of freezing the last one.
                    Array.Clear(scaled);
                }
                else
                {
                    FrameScaler.ScaleBgra(cap.Bgra, cap.Width, cap.Height, scaled, w, h);
                }

                var now = clock.ElapsedMilliseconds;
                var unchanged = previous is not null && previous.AsSpan(0, w * h * 4).SequenceEqual(scaled.AsSpan(0, w * h * 4));
                if (unchanged && !_forceKeyFrame && now - lastSentMs < 1000) continue;

                // A new output size ALWAYS starts with a key frame, decided here from the actual frame rather
                // than from a flag another thread may have set a moment too early or too late.
                var key = _forceKeyFrame || w != encodedW || h != encodedH;
                _forceKeyFrame = false;
                var encoded = _encoder.Encode(scaled, w, h, key);
                if (encoded is null) continue;

                var durationRtp = (uint)(Math.Max(1, now - lastSentMs) * 90);
                if (lastSentMs < 0) durationRtp = (uint)(intervalMs * 90);
                _pc.SendVideo(durationRtp, encoded.Value.Data);
                lastSentMs = now;
                encodedW = w; encodedH = h;
                (previous, scaled) = (scaled, previous ?? new byte[w * h * 4]);
                RecordSent(w, h, encoded.Value.Data.Length);
            }
            catch (OperationCanceledException) { break; }
            catch (Exception) when (token.IsCancellationRequested) { break; }
            catch (Exception)
            {
                // One bad frame (capture hiccup, encoder error) must not kill the stream; the next tick retries.
                _forceKeyFrame = true;
            }
        }
    }

    private void RecordSent(int w, int h, int bytes)
    {
        lock (_statsLock)
        {
            _lastWidth = w; _lastHeight = h;
            _framesWindow++;
            _bytesWindow += bytes;
            var elapsed = Stopwatch.GetElapsedTime(_windowStartTicks).TotalSeconds;
            if (elapsed >= 1.5)
            {
                _fpsMeasured = _framesWindow / elapsed;
                _kbpsMeasured = (int)(_bytesWindow * 8 / 1000 / elapsed);
                _framesWindow = 0; _bytesWindow = 0;
                _windowStartTicks = Stopwatch.GetTimestamp();
            }
        }
    }

    public async ValueTask DisposeAsync()
    {
        _cts.Cancel();
        try { if (_pump is not null) await _pump.WaitAsync(TimeSpan.FromSeconds(3)).ConfigureAwait(false); }
        catch (Exception) { /* a stuck native call must not block releasing the rest */ }
        _pc.onconnectionstatechange -= OnConnectionState;
        try { _pc.close(); } catch { /* already closed */ }
        _encoder.Dispose();
        _capturer.Dispose();
        _cts.Dispose();
    }
}

/// <summary>Builds real streams: GDI capture + libvpx VP8 on Windows. Tests inject their own capturer/encoder.</summary>
public sealed class SipSorceryVideoPeerFactory : IVideoPeerFactory
{
    private readonly Func<IScreenCapturer> _capturer;
    private readonly Func<int, IFrameEncoder> _encoder;

    public SipSorceryVideoPeerFactory(Func<IScreenCapturer> capturer, Func<int, IFrameEncoder> encoder)
    {
        _capturer = capturer;
        _encoder = encoder;
    }

    /// <summary>Production wiring (Windows only).</summary>
    public static SipSorceryVideoPeerFactory ForWindows() =>
        new(() => OperatingSystem.IsWindows() ? new GdiScreenCapturer() : throw new PlatformNotSupportedException("screen capture needs Windows"),
            kbps => new Vp8LibvpxEncoder(kbps));

    public IVideoPeer Create(IceConfig ice, StreamProfile profile) =>
        new SipSorceryVideoPeer(ice, profile, _capturer(), _encoder(profile.BitrateKbps));
}
