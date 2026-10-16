namespace ConnectQuick.WindowsHost.Streaming;

/// <summary>What a stream should look like. Width/height are the encoded size, already aspect-correct.</summary>
public sealed record StreamProfile(int Width, int Height, int Fps, int BitrateKbps);

public sealed record IceServerInfo(IReadOnlyList<string> Urls, string? Username, string? Credential);

/// <summary>ICE servers from the backend plus what they let us promise: with no TURN, peers behind symmetric NAT or CGNAT may be unreachable.</summary>
public sealed record IceConfig(IReadOnlyList<IceServerInfo> Servers, bool HasStun, bool HasTurn, int TtlSeconds)
{
    public static IceConfig None { get; } = new(Array.Empty<IceServerInfo>(), false, false, 0);
}

public enum PeerState
{
    New,
    Connecting,
    Connected,
    Disconnected,
    Failed,
    Closed,
}

/// <summary>Measured outgoing stream quality, as reported by the receiver (RTCP) and the encoder.</summary>
public sealed record PeerStats(double Fps, int BitrateKbps, int Width, int Height, double FractionLost);

public sealed record LocalCandidate(string Candidate, string? SdpMid, int? SdpMLineIndex);

/// <summary>One WebRTC connection that sends the screen. Implemented over SIPSorcery in the Streaming project.</summary>
public interface IVideoPeer : IAsyncDisposable
{
    event Action<LocalCandidate>? CandidateFound;
    event Action<PeerState>? StateChanged;
    /// <summary>Why the capture is not producing real pixels (e.g. "secure_desktop"), or null while it is. Raised on change.</summary>
    event Action<string?>? CaptureBlocked;
    Task<string> CreateOfferAsync();
    Task SetAnswerAsync(string sdp);
    void AddRemoteCandidate(string candidate, string? sdpMid, int? sdpMLineIndex);
    /// <summary>Changes bitrate / resolution / frame rate on the fly, without renegotiating.</summary>
    void Adapt(StreamProfile profile);
    PeerStats GetStats();
}

public interface IVideoPeerFactory
{
    IVideoPeer Create(IceConfig ice, StreamProfile profile);
}
