using ConnectQuick.WindowsHost.Clipboard;
using ConnectQuick.WindowsHost.Input;
using ConnectQuick.WindowsHost.Media;
using ConnectQuick.WindowsHost.Power;

namespace ConnectQuick.WindowsHost.Testing;

public sealed class RecordingInjector : IInputInjector
{
    public readonly List<string> Events = new();
    public bool Succeed = true;
    private void Rec(string s) { lock (Events) Events.Add(s); }
    public List<string> Snapshot() { lock (Events) return Events.ToList(); }

    public bool MouseMoveRelative(int dx, int dy) { Rec($"move {dx},{dy}"); return Succeed; }
    public bool MouseMoveAbsolute(int x, int y) { Rec($"abs {x},{y}"); return Succeed; }
    public bool MouseButton(MouseButton button, bool down) { Rec($"btn {button} {(down ? "down" : "up")}"); return Succeed; }
    public bool MouseWheel(int dx, int dy) { Rec($"wheel {dx},{dy}"); return Succeed; }
    public bool Key(KeyInfo key, bool down) { Rec($"key {key.Scan:X2}{(key.Extended ? "E" : "")}{(key.Vk != 0 ? $"v{key.Vk:X2}" : "")} {(down ? "down" : "up")}"); return Succeed; }
    public bool Text(string text) { Rec($"text {text}"); return Succeed; }
}

public sealed class FakeClipboard : IClipboard
{
    private long _seq;
    private string? _text;
    public int Writes;
    public bool Excluded;
    public bool FailWrites;
    public long SequenceNumber => Interlocked.Read(ref _seq);
    public bool IsExcludedFromMonitoring => Excluded;
    public string? GetText() { lock (this) return _text; }
    public bool SetText(string text)
    {
        if (FailWrites) return false;
        lock (this) { _text = text; Writes++; }
        Interlocked.Increment(ref _seq);
        return true;
    }
    /// <summary>The user copies something on the PC.</summary>
    public void UserCopies(string? text) { lock (this) _text = text; Interlocked.Increment(ref _seq); }
}

public sealed class RecordingMediaKeys : IMediaKeys
{
    public readonly List<MediaKey> Pressed = new();
    public bool Succeed = true;
    public bool Press(MediaKey key) { lock (Pressed) Pressed.Add(key); return Succeed; }
}

public sealed class RecordingPower : IPowerExecutor
{
    public readonly List<string> Calls = new();
    public PowerOutcome Next = new(true);
    private PowerOutcome Rec(string s) { lock (Calls) Calls.Add(s); return Next; }
    public PowerOutcome Lock() => Rec("lock");
    public PowerOutcome Sleep() => Rec("sleep");
    public PowerOutcome Restart(int delaySeconds) => Rec($"restart/{delaySeconds}");
    public PowerOutcome Shutdown(int delaySeconds) => Rec($"shutdown/{delaySeconds}");
    public PowerOutcome CancelPending() => Rec("cancel");
}

public sealed class FakeVideoPeer : ConnectQuick.WindowsHost.Streaming.IVideoPeer
{
    public event Action<ConnectQuick.WindowsHost.Streaming.LocalCandidate>? CandidateFound;
    public event Action<ConnectQuick.WindowsHost.Streaming.PeerState>? StateChanged;
    public event Action<string?>? CaptureBlocked;

    public string Offer = "v=0\r\nfake-offer";
    public string? AnswerSet;
    public bool RejectAnswer;
    public readonly List<(string Candidate, string? Mid, int? Index)> RemoteCandidates = new();
    public readonly List<ConnectQuick.WindowsHost.Streaming.StreamProfile> Adaptations = new();
    public ConnectQuick.WindowsHost.Streaming.PeerStats Stats = new(30, 2000, 1280, 720, 0);
    public bool Disposed;
    public ConnectQuick.WindowsHost.Streaming.StreamProfile Profile = null!;
    public ConnectQuick.WindowsHost.Streaming.IceConfig Ice = null!;

    public Task<string> CreateOfferAsync() => Task.FromResult(Offer);
    public Task SetAnswerAsync(string sdp)
    {
        if (RejectAnswer) throw new InvalidOperationException("bad sdp");
        AnswerSet = sdp;
        return Task.CompletedTask;
    }
    public void AddRemoteCandidate(string candidate, string? sdpMid, int? sdpMLineIndex) => RemoteCandidates.Add((candidate, sdpMid, sdpMLineIndex));
    public void Adapt(ConnectQuick.WindowsHost.Streaming.StreamProfile profile) => Adaptations.Add(profile);
    public ConnectQuick.WindowsHost.Streaming.PeerStats GetStats() => Stats;
    public ValueTask DisposeAsync() { Disposed = true; return ValueTask.CompletedTask; }

    public void RaiseCandidate(string c) => CandidateFound?.Invoke(new(c, "0", 0));
    public void RaiseState(ConnectQuick.WindowsHost.Streaming.PeerState s) => StateChanged?.Invoke(s);
    public void RaiseBlocked(string? reason) => CaptureBlocked?.Invoke(reason);
}

public sealed class FakeVideoPeerFactory : ConnectQuick.WindowsHost.Streaming.IVideoPeerFactory
{
    public readonly List<FakeVideoPeer> Created = new();
    public bool Throw;
    public FakeVideoPeer Last => Created[^1];
    public ConnectQuick.WindowsHost.Streaming.IVideoPeer Create(ConnectQuick.WindowsHost.Streaming.IceConfig ice, ConnectQuick.WindowsHost.Streaming.StreamProfile profile)
    {
        if (Throw) throw new InvalidOperationException("no encoder");
        var p = new FakeVideoPeer { Ice = ice, Profile = profile };
        Created.Add(p);
        return p;
    }
}
