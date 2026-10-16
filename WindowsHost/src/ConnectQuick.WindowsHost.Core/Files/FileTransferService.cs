using System.Security.Cryptography;
using System.Text.Json;
using ConnectQuick.WindowsHost.Crypto;
using ConnectQuick.WindowsHost.Diagnostics;
using ConnectQuick.WindowsHost.Session;
using ConnectQuick.WindowsHost.Settings;

namespace ConnectQuick.WindowsHost.Files;

public enum TransferDirection
{
    /// <summary>Phone to PC.</summary>
    Incoming,
    /// <summary>PC to phone.</summary>
    Outgoing,
}

public enum TransferState
{
    Running,
    Done,
    Failed,
    Cancelled,
}

public sealed record TransferProgress(string TransferId, TransferDirection Direction, string Name, long Bytes, long Total, TransferState State, string? Reason, string? SavedAs);

/// <summary>
/// Secure two-way file transfer (Phase 8).
///
/// Receiving: the phone offers (name, size, SHA-256); the PC decides — size cap, free space,
/// duplicate check, destination safety — and answers with the offset to resume from. Data arrives
/// as strictly sequential chunks into a private ".part" file inside the inbox; more bytes than
/// announced, out-of-order chunks, a truncated finish or a hash mismatch all abort and discard.
/// Only after the full SHA-256 matches is the part file moved to its final, non-colliding name.
///
/// Sending: the PC offers either a file the USER just picked on the PC, or a file the phone asked
/// for BY NAME from the dedicated Outbox folder (the name is matched against the folder's own
/// listing, never concatenated into a path). Nothing outside Inbox/Outbox is reachable, and no
/// message carries a path.
/// </summary>
public sealed class FileTransferService : IHostFeature
{
    public const int ChunkSize = 16 * 1024;
    private const int AckEveryBytes = 128 * 1024;
    private const int WindowChunks = 8;
    private const long SpaceMargin = 64L * 1024 * 1024;
    private const int MaxPartials = 8;

    private sealed class Incoming
    {
        public required string TransferId;
        public required string SafeName;
        public required long Size;
        public required string Sha256;
        public required int ChunkSize;
        public required string Inbox;
        public required string PartPath;
        public required FileStream Stream;
        public long Received;
        public long LastAcked;
        public long LastActivityTicks = System.Diagnostics.Stopwatch.GetTimestamp();
        public bool Finishing;
        public CancellationTokenSource Idle = new();
    }

    private sealed class Outgoing
    {
        public required string TransferId;
        public required string Path;
        public required string Name;
        public required long Size;
        public required string Sha256;
        public long Acked;
        public TaskCompletionSource<bool> AckSignal = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public TaskCompletionSource<bool> Accepted = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public CancellationTokenSource Cts = new();
        public long Offset;
        public bool Started;
    }

    private sealed class PerSession
    {
        public Incoming? In;
        public Outgoing? Out;
    }

    private readonly HostSettingsStore _settings;
    private readonly HostEventLog _log;
    private readonly TimeSpan _idleTimeout;
    private readonly object _lock = new();
    private readonly Dictionary<string, PerSession> _sessions = new();

    public string Name => "files";

    public event Action<TransferProgress>? Progress;

    public FileTransferService(HostSettingsStore settings, HostEventLog log, TimeSpan? idleTimeout = null)
    {
        _settings = settings;
        _log = log;
        _idleTimeout = idleTimeout ?? TimeSpan.FromSeconds(30);
    }

    public IEnumerable<Route> Routes => new[]
    {
        new Route("file.offer", "files", Lane.Slow, OnOfferAsync),
        new Route("file.chunk", "files", Lane.Slow, OnChunkAsync),
        new Route("file.done", "files", Lane.Slow, OnDoneAsync),
        new Route("file.cancel", "files", Lane.Slow, OnCancelAsync),
        new Route("file.list", "files", Lane.Slow, OnListAsync),
        new Route("file.request", "files", Lane.Slow, OnRequestAsync),
        new Route("file.accept", "files", Lane.Slow, OnAcceptAsync),
        new Route("file.reject", "files", Lane.Slow, OnRejectAsync),
        new Route("file.ack", "files", Lane.Slow, OnAckAsync),
        new Route("file.result", "files", Lane.Slow, OnResultAsync),
    };

    private PerSession State(HostSession s)
    {
        lock (_lock)
        {
            if (!_sessions.TryGetValue(s.SessionId, out var st)) _sessions[s.SessionId] = st = new PerSession();
            return st;
        }
    }

    private void Report(string id, TransferDirection dir, string name, long bytes, long total, TransferState state, string? reason = null, string? savedAs = null) =>
        Progress?.Invoke(new TransferProgress(id, dir, name, bytes, total, state, reason, savedAs));

    private static bool SameId(string? a, string b) => a is not null && string.Equals(a, b, StringComparison.OrdinalIgnoreCase);

    // ------------------------------------------------------------------ receiving (phone -> PC)

    private async Task OnOfferAsync(HostSession s, JsonElement m)
    {
        var id = m.GetProperty("transferId").GetString()!;
        var name = m.GetProperty("name").GetString()!;
        var size = m.GetProperty("size").GetInt64();
        var sha = m.GetProperty("sha256").GetString()!;
        var chunk = m.GetProperty("chunkSize").GetInt32();
        var st = State(s);

        Task Reject(string reason) => s.SendAsync("file.reject", new() { ["transferId"] = id, ["reason"] = reason });

        lock (_lock)
        {
            if (st.In is not null) { _ = Reject("busy"); return; }
        }
        var settings = _settings.Current;
        if (size > settings.MaxFileBytes) { await Reject("too_large"); return; }

        var safeName = FileSafety.SanitizeFileName(name);
        var inbox = Path.GetFullPath(settings.DefaultInbox());
        try
        {
            Directory.CreateDirectory(inbox);
            if (FileSafety.IsReparsePoint(inbox)) { _log.Error("files", "inbox is a link; refusing to write"); await Reject("unsafe_destination"); return; }
            var root = Path.GetPathRoot(inbox);
            if (root is not null && new DriveInfo(root).AvailableFreeSpace < size + SpaceMargin) { await Reject("no_space"); return; }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException)
        {
            await Reject("unsafe_destination");
            return;
        }

        if (await Task.Run(() => AlreadyHave(inbox, size, sha)))
        {
            _log.Info("files", $"'{safeName}' from {s.DeviceName} already exists; skipped");
            await Reject("already_exists");
            return;
        }

        PruneOldPartials(inbox);
        var deviceTag = Convert.ToHexString(SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(s.DeviceId)))[..8].ToLowerInvariant();
        var part = Path.Combine(inbox, $".cq-{deviceTag}-{sha[..16]}-{size}.part");
        if (FileSafety.IsReparsePoint(part)) { await Reject("unsafe_destination"); return; }

        FileStream stream;
        long offset = 0;
        try
        {
            if (File.Exists(part))
            {
                var len = new FileInfo(part).Length;
                offset = len <= size ? len : 0;
            }
            stream = new FileStream(part, FileMode.OpenOrCreate, FileAccess.Write, FileShare.None, 64 * 1024, FileOptions.Asynchronous);
            stream.SetLength(offset);
            stream.Seek(offset, SeekOrigin.Begin);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            await Reject("unsafe_destination");
            return;
        }

        var incoming = new Incoming
        {
            TransferId = id, SafeName = safeName, Size = size, Sha256 = sha, ChunkSize = chunk,
            Inbox = inbox, PartPath = part, Stream = stream, Received = offset, LastAcked = offset,
        };
        lock (_lock) st.In = incoming;
        _ = WatchIdleAsync(s, st, incoming);

        _log.Info("files", $"receiving '{safeName}' ({size} bytes) from {s.DeviceName}{(offset > 0 ? $", resuming at {offset}" : "")}");
        Report(id, TransferDirection.Incoming, safeName, offset, size, TransferState.Running);
        await s.SendAsync("file.accept", new() { ["transferId"] = id, ["offset"] = offset });
    }

    private async Task OnChunkAsync(HostSession s, JsonElement m)
    {
        var st = State(s);
        var id = m.GetProperty("transferId").GetString()!;
        Incoming? inc;
        lock (_lock) inc = st.In;
        if (inc is null || !SameId(inc.TransferId, id) || inc.Finishing)
        {
            // A chunk for nothing we are receiving: tell the sender to stop wasting bandwidth.
            await s.SendAsync("file.cancel", new() { ["transferId"] = id, ["reason"] = "unknown_transfer" });
            return;
        }
        var offset = m.GetProperty("offset").GetInt64();
        if (offset != inc.Received) { await AbortIncomingAsync(s, st, inc, "bad_offset", keepPartial: true); return; }

        byte[] data;
        try { data = CryptoUtil.FromBase64Url(m.GetProperty("data").GetString()!); }
        catch (FormatException) { await AbortIncomingAsync(s, st, inc, "bad_data", keepPartial: true); return; }
        if (data.Length == 0 || data.Length > inc.ChunkSize) { await AbortIncomingAsync(s, st, inc, "bad_chunk_size", keepPartial: true); return; }
        // Never accept more than the announced size, however the sender frames it.
        if (inc.Received + data.Length > inc.Size) { await AbortIncomingAsync(s, st, inc, "overflow", keepPartial: false); return; }

        try
        {
            await inc.Stream.WriteAsync(data);
        }
        catch (IOException)
        {
            await AbortIncomingAsync(s, st, inc, "write_failed", keepPartial: true);
            return;
        }
        inc.Received += data.Length;
        Interlocked.Exchange(ref inc.LastActivityTicks, System.Diagnostics.Stopwatch.GetTimestamp());
        Report(inc.TransferId, TransferDirection.Incoming, inc.SafeName, inc.Received, inc.Size, TransferState.Running);

        if (inc.Received - inc.LastAcked >= AckEveryBytes || inc.Received == inc.Size)
        {
            inc.LastAcked = inc.Received;
            await s.SendAsync("file.ack", new() { ["transferId"] = inc.TransferId, ["received"] = inc.Received });
        }
    }

    private async Task OnDoneAsync(HostSession s, JsonElement m)
    {
        var st = State(s);
        var id = m.GetProperty("transferId").GetString()!;
        Incoming? inc;
        lock (_lock) { inc = st.In; if (inc is not null && SameId(inc.TransferId, id) && !inc.Finishing) inc.Finishing = true; else inc = null; }
        if (inc is null) return;

        if (inc.Received != inc.Size) { inc.Finishing = false; await AbortIncomingAsync(s, st, inc, "truncated", keepPartial: true); return; }

        await inc.Stream.FlushAsync();
        await inc.Stream.DisposeAsync();
        inc.Idle.Cancel();

        var actual = await Task.Run(() => HashFile(inc.PartPath));
        if (!string.Equals(actual, inc.Sha256, StringComparison.OrdinalIgnoreCase))
        {
            TryDelete(inc.PartPath);
            lock (_lock) st.In = null;
            _log.Warn("files", $"'{inc.SafeName}' failed integrity check and was discarded");
            Report(inc.TransferId, TransferDirection.Incoming, inc.SafeName, inc.Received, inc.Size, TransferState.Failed, "hash_mismatch");
            await s.SendAsync("file.result", new() { ["transferId"] = inc.TransferId, ["ok"] = false, ["reason"] = "hash_mismatch" });
            return;
        }

        string? finalName = null;
        for (var attempt = 0; attempt < 1000 && finalName is null; attempt++)
        {
            var candidate = FileSafety.UniqueName(inc.Inbox, inc.SafeName);
            var target = FileSafety.ResolveChild(inc.Inbox, candidate);
            if (target is null) break;
            try
            {
                File.Move(inc.PartPath, target, overwrite: false); // fails if something appeared meanwhile: never overwrite
                finalName = candidate;
                MarkDownloaded(target);
            }
            catch (IOException) { /* name taken in the meantime: pick the next one */ }
        }
        lock (_lock) st.In = null;
        if (finalName is null)
        {
            TryDelete(inc.PartPath);
            Report(inc.TransferId, TransferDirection.Incoming, inc.SafeName, inc.Received, inc.Size, TransferState.Failed, "promote_failed");
            await s.SendAsync("file.result", new() { ["transferId"] = inc.TransferId, ["ok"] = false, ["reason"] = "promote_failed" });
            return;
        }
        _log.Info("files", $"received '{finalName}' ({inc.Size} bytes) from {s.DeviceName}");
        Report(inc.TransferId, TransferDirection.Incoming, inc.SafeName, inc.Size, inc.Size, TransferState.Done, null, finalName);
        await s.SendAsync("file.result", new() { ["transferId"] = inc.TransferId, ["ok"] = true, ["savedAs"] = finalName });
    }

    private async Task OnCancelAsync(HostSession s, JsonElement m)
    {
        var st = State(s);
        var id = m.GetProperty("transferId").GetString()!;
        Incoming? inc;
        Outgoing? outg;
        lock (_lock) { inc = st.In; outg = st.Out; }
        if (inc is not null && SameId(inc.TransferId, id))
        {
            await AbortIncomingAsync(s, st, inc, m.TryGetProperty("reason", out var r) ? r.GetString() ?? "cancelled" : "cancelled", keepPartial: false, notify: false);
        }
        else if (outg is not null && SameId(outg.TransferId, id))
        {
            outg.Cts.Cancel();
            outg.Accepted.TrySetResult(false);
            lock (_lock) st.Out = null;
            Report(outg.TransferId, TransferDirection.Outgoing, outg.Name, outg.Acked, outg.Size, TransferState.Cancelled, "peer_cancelled");
        }
    }

    private async Task AbortIncomingAsync(HostSession s, PerSession st, Incoming inc, string reason, bool keepPartial, bool notify = true)
    {
        lock (_lock) { if (!ReferenceEquals(st.In, inc)) return; st.In = null; }
        inc.Idle.Cancel();
        try { await inc.Stream.DisposeAsync(); } catch (IOException) { }
        if (!keepPartial || inc.Received == 0) TryDelete(inc.PartPath);
        _log.Warn("files", $"transfer of '{inc.SafeName}' aborted: {reason}");
        Report(inc.TransferId, TransferDirection.Incoming, inc.SafeName, inc.Received, inc.Size, reason == "cancelled" ? TransferState.Cancelled : TransferState.Failed, reason);
        if (notify) await s.SendAsync("file.result", new() { ["transferId"] = inc.TransferId, ["ok"] = false, ["reason"] = reason });
    }

    private async Task WatchIdleAsync(HostSession s, PerSession st, Incoming inc)
    {
        try
        {
            while (true)
            {
                await Task.Delay(TimeSpan.FromMilliseconds(Math.Max(50, _idleTimeout.TotalMilliseconds / 4)), inc.Idle.Token).ConfigureAwait(false);
                var idle = System.Diagnostics.Stopwatch.GetElapsedTime(Interlocked.Read(ref inc.LastActivityTicks));
                if (idle > _idleTimeout && !inc.Finishing)
                {
                    await AbortIncomingAsync(s, st, inc, "idle_timeout", keepPartial: true).ConfigureAwait(false);
                    return;
                }
            }
        }
        catch (OperationCanceledException) { }
    }

    private static bool AlreadyHave(string inbox, long size, string sha)
    {
        try
        {
            foreach (var file in Directory.EnumerateFiles(inbox).Take(2000))
            {
                var info = new FileInfo(file);
                if (info.Name.StartsWith(".cq-", StringComparison.Ordinal) || info.Length != size) continue;
                if ((info.Attributes & FileAttributes.ReparsePoint) != 0) continue;
                if (string.Equals(HashFile(file), sha, StringComparison.OrdinalIgnoreCase)) return true;
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { }
        return false;
    }

    /// <summary>Bound the disk an unfinished-transfer stash can use: old or excess partials are removed.</summary>
    private static void PruneOldPartials(string inbox)
    {
        try
        {
            var parts = new DirectoryInfo(inbox).EnumerateFiles(".cq-*.part").OrderByDescending(f => f.LastWriteTimeUtc).ToList();
            foreach (var p in parts.Where((p, i) => i >= MaxPartials - 1 || p.LastWriteTimeUtc < DateTime.UtcNow.AddDays(-1)))
                TryDelete(p.FullName);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { }
    }

    /// <summary>Flags the file as downloaded from the network (Zone.Identifier) so SmartScreen/Office protect the user. Never executes it.</summary>
    private static void MarkDownloaded(string path)
    {
        if (!OperatingSystem.IsWindows()) return;
        try { File.WriteAllText(path + ":Zone.Identifier", "[ZoneTransfer]\r\nZoneId=3\r\n"); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or NotSupportedException) { }
    }

    internal static string HashFile(string path)
    {
        using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 128 * 1024);
        return Convert.ToHexString(SHA256.HashData(fs)).ToLowerInvariant();
    }

    private static void TryDelete(string path)
    {
        try { File.Delete(path); } catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { }
    }

    // ------------------------------------------------------------------ sending (PC -> phone)

    private (string Path, string Name, long Size)[] OutboxFiles()
    {
        var outbox = Path.GetFullPath(_settings.Current.DefaultOutbox());
        try
        {
            Directory.CreateDirectory(outbox);
            if (FileSafety.IsReparsePoint(outbox)) return [];
            return new DirectoryInfo(outbox).EnumerateFiles()
                .Where(f => (f.Attributes & (FileAttributes.ReparsePoint | FileAttributes.Hidden | FileAttributes.System)) == 0)
                .Where(f => f.Name.Length <= 255 && f.Length <= _settings.Current.MaxFileBytes)
                .OrderByDescending(f => f.LastWriteTimeUtc)
                .Take(200)
                .Select(f => (f.FullName, f.Name, f.Length))
                .ToArray();
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { return []; }
    }

    private Task OnListAsync(HostSession s, JsonElement m)
    {
        var files = OutboxFiles().Select(f => new Dictionary<string, object?>
        {
            ["name"] = f.Name,
            ["size"] = f.Size,
            ["modifiedMs"] = new DateTimeOffset(File.GetLastWriteTimeUtc(f.Path), TimeSpan.Zero).ToUnixTimeMilliseconds(),
        }).ToList();
        return s.SendAsync("file.listing", new() { ["id"] = m.GetProperty("id").GetString(), ["files"] = files });
    }

    private async Task OnRequestAsync(HostSession s, JsonElement m)
    {
        var transferId = m.GetProperty("transferId").GetString()!;
        var requested = m.GetProperty("name").GetString()!;
        // The path comes from OUR directory listing; the phone's string is only compared against it.
        var match = OutboxFiles().FirstOrDefault(f => string.Equals(f.Name, requested, StringComparison.Ordinal));
        if (match.Path is null)
        {
            await s.SendAsync("file.reject", new() { ["transferId"] = transferId, ["reason"] = "not_found" });
            return;
        }
        await OfferFileAsync(s, match.Path, transferId);
    }

    /// <summary>
    /// Offers a file to the phone. Used for files the user picked on the PC and for Outbox requests.
    /// Returns once the offer is sent; progress is reported through <see cref="Progress"/>.
    /// </summary>
    public async Task<bool> OfferFileAsync(HostSession s, string fullPath, string? transferId = null)
    {
        var st = State(s);
        transferId ??= Guid.NewGuid().ToString();
        FileInfo info;
        try
        {
            info = new FileInfo(fullPath);
            if (!info.Exists || (info.Attributes & (FileAttributes.ReparsePoint | FileAttributes.Directory)) != 0) throw new FileNotFoundException();
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            await s.SendAsync("file.reject", new() { ["transferId"] = transferId, ["reason"] = "not_found" });
            return false;
        }
        if (info.Length > _settings.Current.MaxFileBytes)
        {
            await s.SendAsync("file.reject", new() { ["transferId"] = transferId, ["reason"] = "too_large" });
            return false;
        }
        lock (_lock)
        {
            if (st.Out is not null) { _ = s.SendAsync("file.reject", new() { ["transferId"] = transferId, ["reason"] = "busy" }); return false; }
        }

        var sha = await Task.Run(() => HashFile(fullPath));
        var safeName = FileSafety.SanitizeFileName(info.Name);
        var outg = new Outgoing { TransferId = transferId, Path = fullPath, Name = safeName, Size = info.Length, Sha256 = sha };
        lock (_lock)
        {
            if (st.Out is not null) return false;
            st.Out = outg;
        }
        Report(transferId, TransferDirection.Outgoing, safeName, 0, info.Length, TransferState.Running);
        await s.SendAsync("file.offer", new()
        {
            ["transferId"] = transferId, ["name"] = safeName, ["size"] = info.Length, ["sha256"] = sha, ["chunkSize"] = ChunkSize,
        });
        _ = Task.Delay(TimeSpan.FromSeconds(60)).ContinueWith(_ =>
        {
            // The phone never answered the offer: free the slot.
            if (!outg.Started) { outg.Accepted.TrySetResult(false); lock (_lock) { if (ReferenceEquals(st.Out, outg)) st.Out = null; } }
        });
        return true;
    }

    private Task OnAcceptAsync(HostSession s, JsonElement m)
    {
        var st = State(s);
        var id = m.GetProperty("transferId").GetString()!;
        Outgoing? outg;
        lock (_lock) outg = st.Out;
        if (outg is null || !SameId(outg.TransferId, id) || outg.Started) return Task.CompletedTask;
        var offset = m.GetProperty("offset").GetInt64();
        if (offset < 0 || offset > outg.Size) return AbortOutgoingAsync(s, st, outg, "bad_offset");
        outg.Offset = offset;
        outg.Acked = offset;
        outg.Started = true;
        outg.Accepted.TrySetResult(true);
        _ = PumpOutgoingAsync(s, st, outg);
        return Task.CompletedTask;
    }

    private async Task PumpOutgoingAsync(HostSession s, PerSession st, Outgoing outg)
    {
        try
        {
            await using var fs = new FileStream(outg.Path, FileMode.Open, FileAccess.Read, FileShare.Read, 64 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);
            fs.Seek(outg.Offset, SeekOrigin.Begin);
            var sent = outg.Offset;
            var buffer = new byte[ChunkSize];
            while (sent < outg.Size)
            {
                outg.Cts.Token.ThrowIfCancellationRequested();
                // Flow control: never run more than a window ahead of what the phone confirmed.
                while (sent - Interlocked.Read(ref outg.Acked) >= (long)WindowChunks * ChunkSize)
                {
                    var signal = outg.AckSignal;
                    var done = await Task.WhenAny(signal.Task, Task.Delay(TimeSpan.FromSeconds(30), outg.Cts.Token)).ConfigureAwait(false);
                    if (done != signal.Task) throw new TimeoutException();
                    outg.AckSignal = new(TaskCreationOptions.RunContinuationsAsynchronously);
                }
                var read = await fs.ReadAsync(buffer.AsMemory(0, (int)Math.Min(ChunkSize, outg.Size - sent)), outg.Cts.Token).ConfigureAwait(false);
                if (read == 0) throw new EndOfStreamException();
                await s.SendAsync("file.chunk", new()
                {
                    ["transferId"] = outg.TransferId, ["offset"] = sent, ["data"] = CryptoUtil.ToBase64Url(buffer.AsSpan(0, read)),
                }).ConfigureAwait(false);
                sent += read;
                Report(outg.TransferId, TransferDirection.Outgoing, outg.Name, sent, outg.Size, TransferState.Running);
            }
            await s.SendAsync("file.done", new() { ["transferId"] = outg.TransferId }).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            // cancelled by the peer or the session ended: reported by whoever cancelled
        }
        catch (Exception ex) when (ex is IOException or TimeoutException or UnauthorizedAccessException)
        {
            await AbortOutgoingAsync(s, st, outg, ex is TimeoutException ? "peer_stalled" : "read_failed").ConfigureAwait(false);
        }
    }

    private Task OnAckAsync(HostSession s, JsonElement m)
    {
        var st = State(s);
        Outgoing? outg;
        lock (_lock) outg = st.Out;
        if (outg is null || !SameId(outg.TransferId, m.GetProperty("transferId").GetString()!)) return Task.CompletedTask;
        var received = m.GetProperty("received").GetInt64();
        // Acks only move forward and can never exceed what we actually sent.
        if (received > Interlocked.Read(ref outg.Acked) && received <= outg.Size) Interlocked.Exchange(ref outg.Acked, received);
        outg.AckSignal.TrySetResult(true);
        return Task.CompletedTask;
    }

    private Task OnRejectAsync(HostSession s, JsonElement m)
    {
        var st = State(s);
        Outgoing? outg;
        lock (_lock) { outg = st.Out; if (outg is not null && SameId(outg.TransferId, m.GetProperty("transferId").GetString()!)) st.Out = null; else outg = null; }
        if (outg is not null)
        {
            outg.Cts.Cancel();
            var reason = m.GetProperty("reason").GetString();
            _log.Info("files", $"{s.DeviceName} declined '{outg.Name}': {reason}");
            Report(outg.TransferId, TransferDirection.Outgoing, outg.Name, 0, outg.Size, TransferState.Failed, reason);
        }
        return Task.CompletedTask;
    }

    private Task OnResultAsync(HostSession s, JsonElement m)
    {
        var st = State(s);
        Outgoing? outg;
        lock (_lock) { outg = st.Out; if (outg is not null && SameId(outg.TransferId, m.GetProperty("transferId").GetString()!)) st.Out = null; else outg = null; }
        if (outg is null) return Task.CompletedTask;
        var ok = m.GetProperty("ok").GetBoolean();
        var reason = m.TryGetProperty("reason", out var r) ? r.GetString() : null;
        outg.Cts.Cancel();
        _log.Info("files", $"'{outg.Name}' to {s.DeviceName}: {(ok ? "delivered" : reason)}");
        Report(outg.TransferId, TransferDirection.Outgoing, outg.Name, outg.Size, outg.Size, ok ? TransferState.Done : TransferState.Failed, reason);
        return Task.CompletedTask;
    }

    private async Task AbortOutgoingAsync(HostSession s, PerSession st, Outgoing outg, string reason)
    {
        lock (_lock) { if (!ReferenceEquals(st.Out, outg)) return; st.Out = null; }
        outg.Cts.Cancel();
        Report(outg.TransferId, TransferDirection.Outgoing, outg.Name, Interlocked.Read(ref outg.Acked), outg.Size, TransferState.Failed, reason);
        await s.SendAsync("file.cancel", new() { ["transferId"] = outg.TransferId, ["reason"] = reason });
    }

    // ------------------------------------------------------------------ lifecycle

    public void SessionEnded(HostSession session)
    {
        PerSession? st;
        lock (_lock) _sessions.Remove(session.SessionId, out st);
        if (st is null) return;
        if (st.In is { } inc)
        {
            inc.Idle.Cancel();
            try { inc.Stream.Dispose(); } catch (IOException) { }
            // Keep a partial that made progress so the same file can resume after reconnecting.
            if (inc.Received == 0) TryDelete(inc.PartPath);
            Report(inc.TransferId, TransferDirection.Incoming, inc.SafeName, inc.Received, inc.Size, TransferState.Failed, "session_ended");
        }
        if (st.Out is { } outg)
        {
            outg.Cts.Cancel();
            Report(outg.TransferId, TransferDirection.Outgoing, outg.Name, Interlocked.Read(ref outg.Acked), outg.Size, TransferState.Failed, "session_ended");
        }
    }
}
