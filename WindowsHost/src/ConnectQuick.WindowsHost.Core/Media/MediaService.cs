using System.Text.Json;
using ConnectQuick.WindowsHost.Diagnostics;
using ConnectQuick.WindowsHost.Session;

namespace ConnectQuick.WindowsHost.Media;

public enum MediaKey
{
    PlayPause,
    Next,
    Previous,
    Stop,
    VolumeUp,
    VolumeDown,
    Mute,
}

/// <summary>Sends a global media key. Windows gives no confirmation that an application acted on it.</summary>
public interface IMediaKeys
{
    bool Press(MediaKey key);
}

/// <summary>
/// Global media controls (Phase 7): play/pause, next/previous, stop, volume and mute, delivered as
/// the same system-wide media keys a keyboard produces, so they reach whichever player currently
/// owns media focus. The reply says <c>confirmed:false</c> because Windows offers no way to learn
/// whether that player acted; the phone UI shows "sent", never "playing". Independent of streaming.
/// </summary>
public sealed class MediaService : IHostFeature
{
    private readonly IMediaKeys _keys;
    private readonly HostEventLog _log;

    public string Name => "media";

    public MediaService(IMediaKeys keys, HostEventLog log)
    {
        _keys = keys;
        _log = log;
    }

    public IEnumerable<Route> Routes => new[] { new Route("media.control", "media", Lane.Slow, ControlAsync) };

    private readonly Dictionary<string, TokenBucket> _buckets = new();

    private async Task ControlAsync(HostSession s, JsonElement m)
    {
        var id = m.GetProperty("id").GetString()!;
        TokenBucket bucket;
        lock (_buckets)
        {
            if (!_buckets.TryGetValue(s.SessionId, out bucket!)) _buckets[s.SessionId] = bucket = new TokenBucket(20, 10);
        }
        if (!bucket.TryConsume())
        {
            await s.SendAsync("media.result", new() { ["id"] = id, ["ok"] = false, ["confirmed"] = false, ["reason"] = "rate_limited" });
            return;
        }
        var key = m.GetProperty("action").GetString() switch
        {
            "play_pause" => MediaKey.PlayPause,
            "next" => MediaKey.Next,
            "prev" => MediaKey.Previous,
            "stop" => MediaKey.Stop,
            "vol_up" => MediaKey.VolumeUp,
            "vol_down" => MediaKey.VolumeDown,
            _ => MediaKey.Mute,
        };
        var steps = key is MediaKey.VolumeUp or MediaKey.VolumeDown && m.TryGetProperty("steps", out var st) ? st.GetInt32() : 1;
        var ok = true;
        for (var i = 0; i < steps && ok; i++) ok = _keys.Press(key);
        _log.Info("media", $"{key} x{steps} from {s.DeviceName}: {(ok ? "sent" : "refused by Windows")}");
        await s.SendAsync("media.result", new() { ["id"] = id, ["ok"] = ok, ["confirmed"] = false, ["reason"] = ok ? null : "os_refused" });
    }

    public void SessionEnded(HostSession session)
    {
        lock (_buckets) _buckets.Remove(session.SessionId);
    }
}
