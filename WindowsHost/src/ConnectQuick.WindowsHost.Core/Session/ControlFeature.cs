using System.Text.Json;

namespace ConnectQuick.WindowsHost.Session;

/// <summary>
/// Session housekeeping messages every phone sends: <c>ctl.ping</c> (keepalive and round-trip
/// measurement) is answered with <c>ctl.pong</c>; a stray <c>ctl.pong</c> is ignored. Needs no
/// permission: it exposes nothing and changes nothing.
/// </summary>
public sealed class ControlFeature : IHostFeature
{
    public string Name => "control";

    public IEnumerable<Route> Routes => new[]
    {
        new Route("ctl.ping", null, Lane.Fast, (s, m) => s.SendAsync("ctl.pong", new() { ["n"] = m.GetProperty("n").GetInt64() })),
        new Route("ctl.pong", null, Lane.Fast, (_, _) => Task.CompletedTask),
    };

    public void SessionEnded(HostSession session) { }
}
