using System.Text.Json;
using ConnectQuick.WindowsHost.Streaming;

namespace ConnectQuick.WindowsHost.Net;

/// <summary>
/// Asks the backend for ICE servers (<c>ice.request</c> / <c>ice.config</c>). TURN credentials are
/// short-lived and per device, so the answer is cached only for as long as it is valid. If the
/// backend cannot be reached the caller gets <see cref="IceConfig.None"/>: host candidates only,
/// which works on a LAN and is reported honestly instead of failing silently.
/// </summary>
public sealed class IceConfigClient
{
    private readonly BackendClient _client;
    private readonly object _lock = new();
    private TaskCompletionSource<IceConfig>? _pending;
    private IceConfig? _cached;
    private DateTimeOffset _cachedUntil;

    public IceConfigClient(BackendClient client)
    {
        _client = client;
        _client.On("ice.config", OnConfig);
    }

    private void OnConfig(JsonElement p)
    {
        var servers = new List<IceServerInfo>();
        foreach (var s in p.GetProperty("iceServers").EnumerateArray())
        {
            servers.Add(new IceServerInfo(
                s.GetProperty("urls").EnumerateArray().Select(u => u.GetString()!).ToList(),
                s.TryGetProperty("username", out var u) ? u.GetString() : null,
                s.TryGetProperty("credential", out var c) ? c.GetString() : null));
        }
        var ttl = p.GetProperty("ttlSeconds").GetInt32();
        var cfg = new IceConfig(servers, p.GetProperty("stun").GetBoolean(), p.GetProperty("turn").GetBoolean(), ttl);
        lock (_lock)
        {
            // Refresh a minute before the TURN credential expires; with no TURN there is nothing that expires.
            _cached = cfg;
            _cachedUntil = DateTimeOffset.UtcNow + (ttl > 0 ? TimeSpan.FromSeconds(Math.Max(30, ttl - 60)) : TimeSpan.FromMinutes(10));
            _pending?.TrySetResult(cfg);
        }
    }

    public async Task<IceConfig> GetAsync(CancellationToken token, TimeSpan? timeout = null)
    {
        TaskCompletionSource<IceConfig> waiting;
        lock (_lock)
        {
            if (_cached is not null && DateTimeOffset.UtcNow < _cachedUntil) return _cached;
            _pending ??= new TaskCompletionSource<IceConfig>(TaskCreationOptions.RunContinuationsAsynchronously);
            waiting = _pending;
        }
        try
        {
            await _client.SendAsync("ice.request", new Dictionary<string, object?>()).ConfigureAwait(false);
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(token);
            cts.CancelAfter(timeout ?? TimeSpan.FromSeconds(5));
            return await waiting.Task.WaitAsync(cts.Token).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is InvalidOperationException or OperationCanceledException)
        {
            if (token.IsCancellationRequested) throw;
            return IceConfig.None;
        }
        finally
        {
            lock (_lock) { if (ReferenceEquals(_pending, waiting)) _pending = null; }
        }
    }
}
