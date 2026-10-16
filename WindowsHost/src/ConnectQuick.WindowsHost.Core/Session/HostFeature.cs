using System.Text.Json;

namespace ConnectQuick.WindowsHost.Session;

/// <summary>
/// Fast lane: tiny, latency-critical messages (pointer, keys, pings). Slow lane: anything that may
/// touch disk or take a while (files, clipboard, power). A large upload must never delay the mouse.
/// </summary>
public enum Lane
{
    Fast,
    Slow,
}

public delegate Task MessageHandler(HostSession session, JsonElement message);

/// <summary>One routable inner message type with the permission it needs and where it runs.</summary>
public sealed record Route(string Type, string? Permission, Lane Lane, MessageHandler Handler);

/// <summary>A group of related routes (input, clipboard, files, ...) plus its cleanup hook.</summary>
public interface IHostFeature
{
    /// <summary>Short name advertised to the phone in ctl.caps (e.g. "input", "files").</summary>
    string Name { get; }
    IEnumerable<Route> Routes { get; }
    /// <summary>Called once, right after the session is accepted and the first frame is queued.</summary>
    void SessionStarted(HostSession session) { }
    /// <summary>Called exactly once when a session ends for ANY reason; must release held resources.</summary>
    void SessionEnded(HostSession session);
}
