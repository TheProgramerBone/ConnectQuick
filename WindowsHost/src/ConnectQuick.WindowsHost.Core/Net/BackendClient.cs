using System.Diagnostics;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;

namespace ConnectQuick.WindowsHost.Net;

/// <summary>
/// What the client is actually connected to. "Ready" means the signalling server accepted our
/// identity (hello.ack) — a bare TCP/WebSocket connection is NOT enough to call the app connected.
/// This is still only the signalling leg: a control session with a phone is a separate state.
/// </summary>
public enum SignalingState
{
    Disconnected,
    Connecting,
    Authenticating,
    Ready,
}

/// <summary>Answers the server's <c>hello.challenge</c>; supplied by whoever owns the device identity.</summary>
public interface IHelloAuthenticator
{
    Dictionary<string, object?> BuildHello(string serverChallengeB64Url);
}

/// <summary>
/// WebSocket client for the ConnectQuick backend (Protocol §6 state machine). Owns:
/// <list type="bullet">
/// <item>connect / reconnect with exponential backoff + jitter (capped at 30 s) that only resets after a
/// successful authentication, so a server that accepts sockets but rejects us is not hammered;</item>
/// <item>the <c>hello</c> handshake when an <see cref="IHelloAuthenticator"/> is set;</item>
/// <item>a 15 s heartbeat and dead-connection detection (nothing received for 40 s => abort and reconnect), 5 s / 12 s while a session is live;</item>
/// <item>isolation of handler failures: a throwing handler is reported through <see cref="Faulted"/>, it never
/// kills the receive loop or the process;</item>
/// <item>immediate reconnection on <see cref="NotifyNetworkChanged"/> (Wi-Fi &lt;-&gt; mobile data).</item>
/// </list>
/// </summary>
public sealed class BackendClient : IAsyncDisposable
{
    public const int MaxInboundBytes = 128 * 1024;
    public static readonly TimeSpan DefaultHeartbeatInterval = TimeSpan.FromSeconds(15);
    public static readonly TimeSpan DefaultDeadAfter = TimeSpan.FromSeconds(40);
    public static readonly TimeSpan DefaultActiveHeartbeatInterval = TimeSpan.FromSeconds(5);
    public static readonly TimeSpan DefaultActiveDeadAfter = TimeSpan.FromSeconds(12);
    public static readonly TimeSpan AuthTimeout = TimeSpan.FromSeconds(15);

    private static readonly JsonSerializerOptions DecodeOptions = new() { PropertyNameCaseInsensitive = true };

    private readonly Uri _uri;
    private readonly object _handlersLock = new();
    private readonly Dictionary<string, List<Func<JsonElement, Task>>> _handlers = new();
    private readonly SemaphoreSlim _sendLock = new(1, 1);
    private readonly CancellationTokenSource _disposeCts = new();

    private ClientWebSocket? _ws;
    private CancellationTokenSource? _connectionCts;
    private TaskCompletionSource _wake = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private long _lastRxTicks;
    private long _pingSentTicks;
    private int _heartbeatSeq;
    private SignalingState _state = SignalingState.Disconnected;
    private bool _stateWasReady;

    /// <summary>How often a ping is sent when the link is otherwise quiet. Tests shorten it; production keeps the default.</summary>
    public TimeSpan HeartbeatInterval { get; set; } = DefaultHeartbeatInterval;
    /// <summary>No traffic for this long means the link is dead (a partition leaves the socket open but silent) and it is torn down.</summary>
    public TimeSpan DeadAfter { get; set; } = DefaultDeadAfter;

    /// <summary>Faster ping used while a control session is live, so a silent cut freezes the phone for seconds, not 40 s.</summary>
    public TimeSpan ActiveHeartbeatInterval { get; set; } = DefaultActiveHeartbeatInterval;
    public TimeSpan ActiveDeadAfter { get; set; } = DefaultActiveDeadAfter;
    /// <summary>true while a control session is live; idle links keep the slower timings (battery, traffic).</summary>
    public volatile bool SessionActive;

    private TimeSpan EffectiveHeartbeat => SessionActive && ActiveHeartbeatInterval < HeartbeatInterval ? ActiveHeartbeatInterval : HeartbeatInterval;
    private TimeSpan EffectiveDeadAfter => SessionActive && ActiveDeadAfter < DeadAfter ? ActiveDeadAfter : DeadAfter;

    /// <summary>Set before <see cref="RunForeverAsync"/>. Without it the caller must answer the challenge itself.</summary>
    public IHelloAuthenticator? Authenticator { get; set; }

    public SignalingState State => _state;
    public int LastRttMs { get; private set; } = -1;

    public event Action<SignalingState>? StateChanged;
    /// <summary>true once authenticated (Ready); false whenever the signalling link is lost.</summary>
    public event Action<bool>? ConnectionChanged;
    public event Action<string>? Faulted;
    /// <summary>Another instance with the same identity connected and the relay closed this one (code 4010): reconnecting would only fight it.</summary>
    public event Action? Replaced;

    public const int CloseReplaced = 4010;
    private bool _replaced;

    public BackendClient(Uri uri)
    {
        _uri = uri;
    }

    public void On(string type, Action<JsonElement> handler) =>
        OnAsync(type, payload =>
        {
            handler(payload);
            return Task.CompletedTask;
        });

    public void OnAsync(string type, Func<JsonElement, Task> handler)
    {
        lock (_handlersLock)
        {
            if (!_handlers.TryGetValue(type, out var list))
            {
                list = new List<Func<JsonElement, Task>>();
                _handlers[type] = list;
            }
            list.Add(handler);
        }
    }

    private void SetState(SignalingState next)
    {
        if (_state == next) return;
        _state = next;
        StateChanged?.Invoke(next);
        var ready = next == SignalingState.Ready;
        if (ready != _stateWasReady)
        {
            _stateWasReady = ready;
            ConnectionChanged?.Invoke(ready);
        }
    }

    /// <summary>Connects, reconnects with backoff on failure, and never returns until <paramref name="token"/> is cancelled.</summary>
    public async Task RunForeverAsync(CancellationToken token)
    {
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(token, _disposeCts.Token);
        var ct = linked.Token;
        var attempt = 0;
        while (!ct.IsCancellationRequested)
        {
            var authenticated = false;
            try
            {
                authenticated = await ConnectOnceAsync(ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                Faulted?.Invoke(ex.Message);
            }

            SetState(SignalingState.Disconnected);
            if (_replaced)
            {
                Faulted?.Invoke("replaced by another instance of this device; not reconnecting");
                Replaced?.Invoke();
                break;
            }
            if (ct.IsCancellationRequested) break;

            attempt = authenticated ? 1 : attempt + 1;
            var delay = ComputeBackoff(attempt);
            var wake = _wake.Task;
            try
            {
                await Task.WhenAny(Task.Delay(delay, ct), wake).ConfigureAwait(false);
                ct.ThrowIfCancellationRequested();
                if (wake.IsCompleted)
                {
                    _wake = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
                    attempt = 0;
                }
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
        SetState(SignalingState.Disconnected);
    }

    /// <summary>Capped exponential backoff with +0..30% jitter: 1s, 2s, 4s ... 30s.</summary>
    public static TimeSpan ComputeBackoff(int attempt, double? jitterSample = null)
    {
        var baseMs = Math.Min(30_000, 1000 * Math.Pow(2, Math.Max(0, attempt - 1)));
        var jitterMs = (jitterSample ?? Random.Shared.NextDouble()) * baseMs * 0.3;
        return TimeSpan.FromMilliseconds(baseMs + jitterMs);
    }

    /// <summary>The OS reported a different network: drop the current socket and reconnect right away.</summary>
    public void NotifyNetworkChanged()
    {
        _wake.TrySetResult();
        try { _connectionCts?.Cancel(); } catch (ObjectDisposedException) { }
        try { _ws?.Abort(); } catch { /* already gone */ }
    }

    private async Task<bool> ConnectOnceAsync(CancellationToken outerToken)
    {
        using var ws = new ClientWebSocket();
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(outerToken);
        _connectionCts = cts;
        SetState(SignalingState.Connecting);
        using (var connectTimeout = CancellationTokenSource.CreateLinkedTokenSource(cts.Token))
        {
            connectTimeout.CancelAfter(TimeSpan.FromSeconds(15));
            await ws.ConnectAsync(_uri, connectTimeout.Token).ConfigureAwait(false);
        }
        _ws = ws;
        Interlocked.Exchange(ref _lastRxTicks, Stopwatch.GetTimestamp());
        var authenticated = false;
        void OnState(SignalingState s) { if (s == SignalingState.Ready) authenticated = true; }
        StateChanged += OnState;
        SetState(Authenticator is null ? SignalingState.Ready : SignalingState.Authenticating);

        var heartbeatTask = HeartbeatLoopAsync(ws, cts.Token, () => cts.Cancel());
        try
        {
            await ReceiveLoopAsync(ws, cts.Token).ConfigureAwait(false);
        }
        finally
        {
            StateChanged -= OnState;
            cts.Cancel();
            try { await heartbeatTask.ConfigureAwait(false); } catch { /* cancellation */ }
            _ws = null;
            _connectionCts = null;
        }
        return authenticated;
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
                if (result.MessageType == WebSocketMessageType.Close)
                {
                    if ((int?)ws.CloseStatus == CloseReplaced) _replaced = true;
                    return;
                }
                ms.Write(buffer, 0, result.Count);
                if (ms.Length > MaxInboundBytes)
                {
                    ws.Abort();
                    throw new InvalidDataException("inbound message exceeds the protocol limit");
                }
            } while (!result.EndOfMessage);

            Interlocked.Exchange(ref _lastRxTicks, Stopwatch.GetTimestamp());

            Envelope? envelope;
            try
            {
                envelope = JsonSerializer.Deserialize<Envelope>(ms.ToArray(), DecodeOptions);
            }
            catch (JsonException)
            {
                continue; // a well-behaved backend never sends this; ignore defensively
            }
            if (envelope is null || string.IsNullOrEmpty(envelope.Type)) continue;

            Dispatch(envelope);
        }
    }

    private void Dispatch(Envelope envelope)
    {
        switch (envelope.Type)
        {
            case "hello.challenge" when Authenticator is not null:
                _ = RunHandlerAsync(async () =>
                {
                    var challenge = envelope.Payload.GetProperty("serverChallenge").GetString()!;
                    await SendAsync("hello", Authenticator.BuildHello(challenge)).ConfigureAwait(false);
                });
                break;
            case "hello.ack" when Authenticator is not null:
                if (envelope.Payload.TryGetProperty("accepted", out var accepted) && accepted.ValueKind == JsonValueKind.True)
                    SetState(SignalingState.Ready);
                break;
            case "heartbeat.pong":
                if (_pingSentTicks != 0)
                    LastRttMs = (int)Stopwatch.GetElapsedTime(_pingSentTicks).TotalMilliseconds;
                break;
        }

        List<Func<JsonElement, Task>> snapshot;
        lock (_handlersLock)
        {
            if (!_handlers.TryGetValue(envelope.Type, out var list)) return;
            snapshot = list.ToList();
        }
        foreach (var handler in snapshot)
        {
            var payload = envelope.Payload;
            // Handlers start synchronously (so per-session frame ordering is preserved) but an
            // exception, sync or async, only ever lands in Faulted.
            _ = RunHandlerAsync(() => handler(payload));
        }
    }

    private async Task RunHandlerAsync(Func<Task> action)
    {
        try
        {
            await action().ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            Faulted?.Invoke($"handler failed: {ex.GetType().Name}: {ex.Message}");
        }
    }

    private async Task HeartbeatLoopAsync(ClientWebSocket ws, CancellationToken token, Action onDead)
    {
        var authDeadline = Stopwatch.GetTimestamp() + (long)(AuthTimeout.TotalSeconds * Stopwatch.Frequency);
        try
        {
            while (!token.IsCancellationRequested)
            {
                await Task.Delay(TimeSpan.FromSeconds(1), token).ConfigureAwait(false);
                if (_state == SignalingState.Authenticating && Stopwatch.GetTimestamp() > authDeadline)
                {
                    Faulted?.Invoke("authentication with the backend timed out");
                    ws.Abort();
                    onDead();
                    return;
                }
                var sinceRx = Stopwatch.GetElapsedTime(Interlocked.Read(ref _lastRxTicks));
                if (sinceRx > EffectiveDeadAfter)
                {
                    Faulted?.Invoke("backend connection is dead (no traffic); reconnecting");
                    ws.Abort();
                    onDead();
                    return;
                }
                if (_state == SignalingState.Ready && sinceRx >= EffectiveHeartbeat - TimeSpan.FromSeconds(1))
                {
                    _pingSentTicks = Stopwatch.GetTimestamp();
                    try
                    {
                        await SendAsync("heartbeat.ping", new Dictionary<string, object?> { ["seq"] = _heartbeatSeq++ }).ConfigureAwait(false);
                    }
                    catch (Exception) when (!token.IsCancellationRequested)
                    {
                        ws.Abort();
                        onDead();
                        return;
                    }
                }
            }
        }
        catch (OperationCanceledException)
        {
            // normal shutdown of this connection's heartbeat loop
        }
    }

    /// <summary>Sends one envelope. Throws if the socket is not currently open.</summary>
    public async Task SendAsync(string type, Dictionary<string, object?> payload)
    {
        var ws = _ws;
        if (ws is null || ws.State != WebSocketState.Open) throw new InvalidOperationException("not connected to backend");
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

    /// <summary>Like <see cref="SendAsync"/> but reports failure instead of throwing (fire-and-forget callers).</summary>
    public async Task<bool> TrySendAsync(string type, Dictionary<string, object?> payload)
    {
        try
        {
            await SendAsync(type, payload).ConfigureAwait(false);
            return true;
        }
        catch (Exception)
        {
            return false;
        }
    }

    public ValueTask DisposeAsync()
    {
        _disposeCts.Cancel();
        try { _ws?.Abort(); } catch { /* already gone */ }
        _sendLock.Dispose();
        _disposeCts.Dispose();
        return ValueTask.CompletedTask;
    }
}
