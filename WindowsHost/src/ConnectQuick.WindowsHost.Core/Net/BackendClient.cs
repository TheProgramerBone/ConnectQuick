using System.Net.WebSockets;
using System.Text;
using System.Text.Json;

namespace ConnectQuick.WindowsHost.Net;

/// <summary>
/// WebSocket client for the ConnectQuick backend. Owns the connect/reconnect loop (Protocol §6
/// state machine: CONNECTING → ... → RECONNECTING → CONNECTED, exponential backoff + jitter,
/// capped at 30s) and the 15s heartbeat.ping the spec requires from both peers. Message dispatch
/// is by envelope <c>type</c>, via <see cref="On"/> handlers registered before <see cref="RunForeverAsync"/>.
/// </summary>
public sealed class BackendClient : IAsyncDisposable
{
    private static readonly JsonSerializerOptions DecodeOptions = new() { PropertyNameCaseInsensitive = true };

    private readonly Uri _uri;
    private readonly Dictionary<string, List<Action<JsonElement>>> _handlers = new();
    private readonly SemaphoreSlim _sendLock = new(1, 1);
    private ClientWebSocket? _ws;
    private int _heartbeatSeq;

    public event Action<bool>? ConnectionChanged;
    public event Action<string>? Faulted;

    public BackendClient(Uri uri)
    {
        _uri = uri;
    }

    public void On(string type, Action<JsonElement> handler)
    {
        if (!_handlers.TryGetValue(type, out var list))
        {
            list = new List<Action<JsonElement>>();
            _handlers[type] = list;
        }
        list.Add(handler);
    }

    /// <summary>Connects, reconnects with backoff on failure, and never returns until <paramref name="token"/> is cancelled.</summary>
    public async Task RunForeverAsync(CancellationToken token)
    {
        var attempt = 0;
        while (!token.IsCancellationRequested)
        {
            try
            {
                await ConnectOnceAsync(token).ConfigureAwait(false);
                attempt = 0; // clean disconnect after a successful session: reset backoff
            }
            catch (OperationCanceledException) when (token.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                Faulted?.Invoke(ex.Message);
            }

            ConnectionChanged?.Invoke(false);
            if (token.IsCancellationRequested) break;

            attempt++;
            var delay = ComputeBackoff(attempt);
            try { await Task.Delay(delay, token).ConfigureAwait(false); }
            catch (OperationCanceledException) { break; }
        }
    }

    private static TimeSpan ComputeBackoff(int attempt)
    {
        var baseMs = Math.Min(30_000, 1000 * Math.Pow(2, attempt - 1));
        var jitterMs = Random.Shared.NextDouble() * baseMs * 0.3;
        return TimeSpan.FromMilliseconds(baseMs + jitterMs);
    }

    private async Task ConnectOnceAsync(CancellationToken outerToken)
    {
        using var ws = new ClientWebSocket();
        await ws.ConnectAsync(_uri, outerToken).ConfigureAwait(false);
        _ws = ws;
        ConnectionChanged?.Invoke(true);

        using var cts = CancellationTokenSource.CreateLinkedTokenSource(outerToken);
        var heartbeatTask = HeartbeatLoopAsync(cts.Token);
        try
        {
            await ReceiveLoopAsync(ws, cts.Token).ConfigureAwait(false);
        }
        finally
        {
            cts.Cancel();
            try { await heartbeatTask.ConfigureAwait(false); } catch { /* cancellation */ }
            _ws = null;
        }
    }

    private async Task ReceiveLoopAsync(ClientWebSocket ws, CancellationToken token)
    {
        var buffer = new byte[16 * 1024];
        while (ws.State == WebSocketState.Open && !token.IsCancellationRequested)
        {
            using var ms = new MemoryStream();
            WebSocketReceiveResult result;
            do
            {
                result = await ws.ReceiveAsync(buffer, token).ConfigureAwait(false);
                if (result.MessageType == WebSocketMessageType.Close) return;
                ms.Write(buffer, 0, result.Count);
            } while (!result.EndOfMessage);

            Envelope? envelope;
            try
            {
                envelope = JsonSerializer.Deserialize<Envelope>(ms.ToArray(), DecodeOptions);
            }
            catch (JsonException)
            {
                continue; // malformed frame from a well-behaved backend should never happen; ignore defensively
            }
            if (envelope is null) continue;

            if (_handlers.TryGetValue(envelope.Type, out var list))
            {
                foreach (var handler in list.ToArray()) handler(envelope.Payload);
            }
        }
    }

    private async Task HeartbeatLoopAsync(CancellationToken token)
    {
        try
        {
            while (!token.IsCancellationRequested)
            {
                await Task.Delay(TimeSpan.FromSeconds(15), token).ConfigureAwait(false);
                await SendAsync("heartbeat.ping", new Dictionary<string, object?> { ["seq"] = _heartbeatSeq++ }).ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException)
        {
            // normal shutdown of this connection's heartbeat loop
        }
    }

    /// <summary>Sends one envelope. Throws if not currently connected.</summary>
    public async Task SendAsync(string type, Dictionary<string, object?> payload)
    {
        var ws = _ws ?? throw new InvalidOperationException("not connected to backend");
        var envelope = new Dictionary<string, object?>
        {
            ["v"] = 1,
            ["id"] = Guid.NewGuid().ToString(),
            ["ts"] = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            ["type"] = type,
            ["payload"] = payload,
        };
        var bytes = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(envelope));

        await _sendLock.WaitAsync().ConfigureAwait(false);
        try
        {
            await ws.SendAsync(bytes, WebSocketMessageType.Text, true, CancellationToken.None).ConfigureAwait(false);
        }
        finally
        {
            _sendLock.Release();
        }
    }

    public ValueTask DisposeAsync()
    {
        _sendLock.Dispose();
        return ValueTask.CompletedTask;
    }
}
