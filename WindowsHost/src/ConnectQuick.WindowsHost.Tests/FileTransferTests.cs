using System.Security.Cryptography;
using System.Text;
using ConnectQuick.WindowsHost.Crypto;
using ConnectQuick.WindowsHost.Files;
using ConnectQuick.WindowsHost.Session;
using ConnectQuick.WindowsHost.Settings;

namespace ConnectQuick.WindowsHost.Tests;

public sealed class FileSafetyTests
{
    [Theory]
    [InlineData("../../evil.txt")]
    [InlineData("..\\..\\Windows\\win.ini")]
    [InlineData("C:\\Windows\\System32\\evil.exe")]
    [InlineData("/etc/passwd")]
    [InlineData("\\\\server\\share\\x")]
    [InlineData("file.txt:hidden")]
    [InlineData("CON")]
    [InlineData("aux.txt")]
    [InlineData("nul.")]
    [InlineData("COM1.log")]
    [InlineData("lpt9")]
    [InlineData("report\u202Efdp.exe")]
    [InlineData("  spaces and dots ... ")]
    [InlineData("")]
    [InlineData(".")]
    [InlineData("..")]
    [InlineData("...")]
    [InlineData("a\0b.txt")]
    [InlineData("tab\tname.txt")]
    public void Hostile_names_become_a_single_safe_segment(string raw)
    {
        var safe = FileSafety.SanitizeFileName(raw);
        Assert.NotEmpty(safe);
        Assert.DoesNotContain('/', safe);
        Assert.DoesNotContain('\\', safe);
        Assert.DoesNotContain(':', safe);
        Assert.DoesNotContain('\0', safe);
        Assert.NotEqual(".", safe);
        Assert.NotEqual("..", safe);
        Assert.False(safe.EndsWith('.') || safe.EndsWith(' '), safe);
        Assert.DoesNotContain(safe.Where(c => c is >= '\u202A' and <= '\u202E'), c => true);
        var stem = safe.Split('.')[0].ToUpperInvariant();
        Assert.DoesNotContain(stem, new[] { "CON", "PRN", "AUX", "NUL", "COM1", "LPT9" });
        Assert.Equal(safe, FileSafety.SanitizeFileName(safe)); // idempotent
    }

    [Fact]
    public void Long_names_are_cut_but_keep_their_extension_and_never_split_a_surrogate_pair()
    {
        var safe = FileSafety.SanitizeFileName(new string('a', 500) + ".pdf");
        Assert.True(safe.Length <= FileSafety.MaxNameLength);
        Assert.EndsWith(".pdf", safe);
        var emoji = FileSafety.SanitizeFileName(string.Concat(Enumerable.Repeat("😀", 150)) + ".txt");
        Assert.True(emoji.Length <= FileSafety.MaxNameLength);
        for (var i = 0; i < emoji.Length; i++)
            if (char.IsHighSurrogate(emoji[i])) Assert.True(i + 1 < emoji.Length && char.IsLowSurrogate(emoji[i + 1]));
    }

    [Fact]
    public void Unicode_names_are_normalised_so_visually_identical_names_collide_instead_of_coexisting()
    {
        Assert.Equal(FileSafety.SanitizeFileName("Caf\u00E9.txt"), FileSafety.SanitizeFileName("Cafe\u0301.txt"));
        Assert.Equal("ñandú ✓ 🙂.txt", FileSafety.SanitizeFileName("ñandú ✓ 🙂.txt"));
    }

    [Fact]
    public void ResolveChild_only_ever_returns_direct_children_of_the_root()
    {
        var root = Directory.CreateTempSubdirectory("cq-root-").FullName;
        Assert.Equal(Path.Combine(root, "ok.txt"), FileSafety.ResolveChild(root, "ok.txt"));
        Assert.Null(FileSafety.ResolveChild(root, "../x"));       // not a sanitized name
        Assert.Null(FileSafety.ResolveChild(root, "sub/x"));
        Assert.Null(FileSafety.ResolveChild(root, ".."));
        Directory.Delete(root);
    }

    [Fact]
    public void UniqueName_never_returns_a_name_that_exists()
    {
        var dir = Directory.CreateTempSubdirectory("cq-uniq-").FullName;
        File.WriteAllText(Path.Combine(dir, "a.txt"), "1");
        File.WriteAllText(Path.Combine(dir, "a (1).txt"), "2");
        Assert.Equal("a (2).txt", FileSafety.UniqueName(dir, "a.txt"));
        Assert.Equal("b.txt", FileSafety.UniqueName(dir, "b.txt"));
        Directory.Delete(dir, true);
    }
}

public sealed class FileTransferTests : IDisposable
{
    private readonly List<IDisposable> _disposables = new();
    public void Dispose() { foreach (var d in _disposables) d.Dispose(); }

    private (SessionFixture F, FileTransferService Svc) Make(Action<HostSettings>? cfg = null, TimeSpan? idle = null, HostSettingsStore? shared = null, IEnumerable<string>? perms = null)
    {
        var settings = shared ?? SessionFixture.NewSettings(cfg);
        if (shared is not null && cfg is not null) settings.Update(cfg);
        var svc = new FileTransferService(settings, new Diagnostics.HostEventLog(), idle);
        var f = new SessionFixture(new IHostFeature[] { svc }, perms, settings: settings);
        _disposables.Add(f);
        return (f, svc);
    }

    private static string Sha(byte[] data) => Convert.ToHexString(SHA256.HashData(data)).ToLowerInvariant();
    private static byte[] Random(int n) { var b = new byte[n]; RandomNumberGenerator.Fill(b); return b; }
    private static string Id() => Guid.NewGuid().ToString();
    private static string Inbox(SessionFixture f) => f.Settings.Current.InboxDirectory!;
    private static string[] InboxFiles(SessionFixture f) => Directory.Exists(Inbox(f)) ? Directory.GetFileSystemEntries(Inbox(f)).Select(Path.GetFileName).Select(n => n!).OrderBy(n => n).ToArray() : [];

    private static void SendOffer(SessionFixture f, string tid, string name, byte[] data, string? sha = null, long? size = null, int chunk = 16384) =>
        f.Send("file.offer", new() { ["transferId"] = tid, ["name"] = name, ["size"] = size ?? data.Length, ["sha256"] = sha ?? Sha(data), ["chunkSize"] = chunk });

    private static void SendChunk(SessionFixture f, string tid, long offset, byte[] data) =>
        f.Send("file.chunk", new() { ["transferId"] = tid, ["offset"] = offset, ["data"] = CryptoUtil.ToBase64Url(data) });

    /// <summary>The phone side of a complete upload. Returns the host's file.result payload (or the reject).</summary>
    private static async Task<System.Text.Json.JsonElement> Upload(SessionFixture f, string name, byte[] data, int chunk = 16384, string? sha = null)
    {
        var tid = Id();
        SendOffer(f, tid, name, data, sha, chunk: chunk);
        var first = await f.ExpectAnyAsync(new[] { "file.accept", "file.reject" });
        if (first.GetProperty("t").GetString() == "file.reject") return first;
        for (var off = first.GetProperty("offset").GetInt64(); off < data.Length; off += chunk)
            SendChunk(f, tid, off, data.AsSpan((int)off, (int)Math.Min(chunk, data.Length - off)).ToArray());
        f.Send("file.done", new() { ["transferId"] = tid });
        return await f.ExpectAsync("file.result");
    }

    [Fact]
    public async Task A_file_uploaded_from_the_phone_arrives_intact_under_its_own_name()
    {
        var (f, _) = Make();
        var data = Random(100_000);
        var res = await Upload(f, "informe ñ.pdf", data);
        Assert.True(res.GetProperty("ok").GetBoolean());
        Assert.Equal("informe ñ.pdf", res.GetProperty("savedAs").GetString());
        Assert.Equal(data, await File.ReadAllBytesAsync(Path.Combine(Inbox(f), "informe ñ.pdf")));
        Assert.Equal(new[] { "informe ñ.pdf" }, InboxFiles(f)); // no leftover .part file
    }

    [Fact]
    public async Task An_empty_file_is_transferred_too()
    {
        var (f, _) = Make();
        var res = await Upload(f, "vacio.txt", []);
        Assert.True(res.GetProperty("ok").GetBoolean());
        Assert.Empty(await File.ReadAllBytesAsync(Path.Combine(Inbox(f), "vacio.txt")));
    }

    [Theory]
    [InlineData("../../escape.txt")]
    [InlineData("..\\..\\escape.txt")]
    [InlineData("/tmp/absolute-escape.txt")]
    [InlineData("C:\\Windows\\escape.exe")]
    [InlineData("sub/dir/file.txt")]
    [InlineData("CON")]
    [InlineData("evil\u202Etxt.exe")]
    public async Task Malicious_names_cannot_place_anything_outside_the_inbox(string name)
    {
        var (f, _) = Make();
        var res = await Upload(f, name, Encoding.UTF8.GetBytes("payload"));
        Assert.True(res.GetProperty("ok").GetBoolean());
        var saved = res.GetProperty("savedAs").GetString()!;
        Assert.Equal(Inbox(f), Path.GetDirectoryName(Path.GetFullPath(Path.Combine(Inbox(f), saved))));
        Assert.Single(InboxFiles(f));
        // Nothing was created anywhere else in the sandbox.
        Assert.Equal(new[] { "inbox", "settings.json" }, Directory.GetFileSystemEntries(f.TempDir).Select(Path.GetFileName).OrderBy(n => n).ToArray());
        Assert.False(File.Exists("/tmp/absolute-escape.txt"));
    }

    [Fact]
    public async Task More_data_than_announced_aborts_and_discards_everything()
    {
        var (f, _) = Make();
        var tid = Id();
        var announced = Random(10);
        SendOffer(f, tid, "small.bin", announced);
        await f.ExpectAsync("file.accept");
        SendChunk(f, tid, 0, Random(20)); // 20 bytes against a 10 byte promise
        var res = await f.ExpectAsync("file.result");
        Assert.False(res.GetProperty("ok").GetBoolean());
        Assert.Equal("overflow", res.GetProperty("reason").GetString());
        Assert.Empty(InboxFiles(f));
    }

    [Fact]
    public async Task A_truncated_upload_never_becomes_a_file_but_keeps_its_partial_for_resuming()
    {
        var (f, _) = Make();
        var data = Random(40_000);
        var tid = Id();
        SendOffer(f, tid, "cut.bin", data);
        await f.ExpectAsync("file.accept");
        SendChunk(f, tid, 0, data[..16384]);
        f.Send("file.done", new() { ["transferId"] = tid }); // claims to be finished far too early
        var res = await f.ExpectAsync("file.result");
        Assert.Equal("truncated", res.GetProperty("reason").GetString());
        Assert.DoesNotContain("cut.bin", InboxFiles(f));
        Assert.Single(InboxFiles(f), n => n.EndsWith(".part"));
    }

    [Fact]
    public async Task A_corrupted_file_is_discarded_by_the_integrity_check()
    {
        var (f, _) = Make();
        var data = Random(20_000);
        var res = await Upload(f, "bad.bin", data, sha: Sha(Random(20_000))); // hash of different bytes
        Assert.False(res.GetProperty("ok").GetBoolean());
        Assert.Equal("hash_mismatch", res.GetProperty("reason").GetString());
        Assert.Empty(InboxFiles(f));
    }

    [Fact]
    public async Task The_same_content_twice_is_recognised_as_a_duplicate()
    {
        var (f, _) = Make();
        var data = Random(5000);
        Assert.True((await Upload(f, "a.bin", data)).GetProperty("ok").GetBoolean());
        var second = await Upload(f, "renamed.bin", data);
        Assert.Equal("file.reject", second.GetProperty("t").GetString());
        Assert.Equal("already_exists", second.GetProperty("reason").GetString());
        Assert.Equal(new[] { "a.bin" }, InboxFiles(f));
    }

    [Fact]
    public async Task Different_content_with_the_same_name_never_overwrites()
    {
        var (f, _) = Make();
        var one = Encoding.UTF8.GetBytes("uno");
        var two = Encoding.UTF8.GetBytes("dos!");
        await Upload(f, "nota.txt", one);
        var res = await Upload(f, "nota.txt", two);
        Assert.Equal("nota (1).txt", res.GetProperty("savedAs").GetString());
        Assert.Equal(one, await File.ReadAllBytesAsync(Path.Combine(Inbox(f), "nota.txt")));
        Assert.Equal(two, await File.ReadAllBytesAsync(Path.Combine(Inbox(f), "nota (1).txt")));
    }

    [Fact]
    public async Task Files_over_the_configured_limit_are_refused_before_any_data_is_sent()
    {
        var (f, _) = Make(s => s.MaxFileBytes = 2048);
        var res = await Upload(f, "big.bin", Random(4096));
        Assert.Equal("too_large", res.GetProperty("reason").GetString());
        Assert.Empty(InboxFiles(f));
    }

    [Fact]
    public async Task An_interrupted_upload_resumes_from_where_it_stopped()
    {
        var shared = SessionFixture.NewSettings();
        var data = Random(60_000);
        var (first, _) = Make(shared: shared);
        var tid = Id();
        SendOffer(first, tid, "resume.bin", data);
        await first.ExpectAsync("file.accept");
        SendChunk(first, tid, 0, data[..16384]);
        SendChunk(first, tid, 16384, data[16384..32768]);
        await Task.Delay(300);
        first.Session.End("network_lost"); // the phone's connection died mid-transfer
        Assert.DoesNotContain("resume.bin", InboxFiles(first));
        Assert.Single(InboxFiles(first), n => n.EndsWith(".part"));

        var (second, _) = Make(shared: shared); // phone reconnects: new session, same device
        var tid2 = Id();
        SendOffer(second, tid2, "resume.bin", data);
        var accept = await second.ExpectAsync("file.accept");
        Assert.Equal(32768, accept.GetProperty("offset").GetInt64());
        for (long off = 32768; off < data.Length; off += 16384)
            SendChunk(second, tid2, off, data.AsSpan((int)off, (int)Math.Min(16384, data.Length - off)).ToArray());
        second.Send("file.done", new() { ["transferId"] = tid2 });
        var res = await second.ExpectAsync("file.result");
        Assert.True(res.GetProperty("ok").GetBoolean());
        Assert.Equal(data, await File.ReadAllBytesAsync(Path.Combine(Inbox(second), "resume.bin")));
    }

    [Fact]
    public async Task A_gap_in_the_offsets_aborts_the_transfer()
    {
        var (f, _) = Make();
        var data = Random(40_000);
        var tid = Id();
        SendOffer(f, tid, "gap.bin", data);
        await f.ExpectAsync("file.accept");
        SendChunk(f, tid, 16384, data[16384..32768]); // skipped the first chunk
        var res = await f.ExpectAsync("file.result");
        Assert.Equal("bad_offset", res.GetProperty("reason").GetString());
    }

    [Fact]
    public async Task A_chunk_bigger_than_the_negotiated_size_is_refused()
    {
        var (f, _) = Make();
        var data = Random(30_000);
        var tid = Id();
        SendOffer(f, tid, "fat.bin", data, chunk: 4096);
        await f.ExpectAsync("file.accept");
        SendChunk(f, tid, 0, data[..8192]);
        Assert.Equal("bad_chunk_size", (await f.ExpectAsync("file.result")).GetProperty("reason").GetString());
    }

    [Fact]
    public async Task Cancelling_discards_the_partial_file()
    {
        var (f, _) = Make();
        var data = Random(40_000);
        var tid = Id();
        SendOffer(f, tid, "cancel.bin", data);
        await f.ExpectAsync("file.accept");
        SendChunk(f, tid, 0, data[..16384]);
        await Task.Delay(200);
        f.Send("file.cancel", new() { ["transferId"] = tid, ["reason"] = "user" });
        await Task.Delay(300);
        Assert.Empty(InboxFiles(f));
    }

    [Fact]
    public async Task A_second_transfer_while_one_is_running_is_told_the_pc_is_busy()
    {
        var (f, _) = Make();
        var tid = Id();
        SendOffer(f, tid, "one.bin", Random(40_000));
        await f.ExpectAsync("file.accept");
        SendOffer(f, Id(), "two.bin", Random(1000));
        Assert.Equal("busy", (await f.ExpectAsync("file.reject")).GetProperty("reason").GetString());
    }

    [Fact]
    public async Task A_sender_that_goes_silent_is_timed_out_and_its_partial_kept_for_resuming()
    {
        var (f, _) = Make(idle: TimeSpan.FromMilliseconds(400));
        var tid = Id();
        var data = Random(40_000);
        SendOffer(f, tid, "slow.bin", data);
        await f.ExpectAsync("file.accept");
        SendChunk(f, tid, 0, data[..16384]);
        var res = await f.ExpectAsync("file.result", 4000);
        Assert.Equal("idle_timeout", res.GetProperty("reason").GetString());
        Assert.Single(InboxFiles(f), n => n.EndsWith(".part"));
    }

    [Fact]
    public async Task Chunks_for_a_transfer_that_was_never_offered_are_cancelled()
    {
        var (f, _) = Make();
        SendChunk(f, Id(), 0, Random(100));
        Assert.Equal("unknown_transfer", (await f.ExpectAsync("file.cancel")).GetProperty("reason").GetString());
        Assert.Empty(InboxFiles(f));
    }

    [Fact]
    public async Task An_inbox_that_is_a_symlink_is_refused()
    {
        var (f, _) = Make();
        var target = Directory.CreateDirectory(Path.Combine(f.TempDir, "elsewhere")).FullName;
        Directory.CreateSymbolicLink(Inbox(f), target);
        var res = await Upload(f, "x.bin", Random(100));
        Assert.Equal("unsafe_destination", res.GetProperty("reason").GetString());
        Assert.Empty(Directory.GetFileSystemEntries(target));
    }

    [Fact]
    public async Task A_planted_symlink_in_place_of_the_partial_file_is_not_followed()
    {
        var (f, _) = Make();
        var victim = Path.Combine(f.TempDir, "victim.txt");
        await File.WriteAllTextAsync(victim, "precious");
        Directory.CreateDirectory(Inbox(f));
        var data = Random(2000);
        var deviceTag = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes("dev-1")))[..8].ToLowerInvariant();
        File.CreateSymbolicLink(Path.Combine(Inbox(f), $".cq-{deviceTag}-{Sha(data)[..16]}-{data.Length}.part"), victim);
        var res = await Upload(f, "x.bin", data);
        Assert.Equal("unsafe_destination", res.GetProperty("reason").GetString());
        Assert.Equal("precious", await File.ReadAllTextAsync(victim));
    }

    [Fact]
    public async Task Stale_partials_are_pruned_so_unfinished_uploads_cannot_fill_the_disk()
    {
        var (f, _) = Make();
        Directory.CreateDirectory(Inbox(f));
        for (var i = 0; i < 20; i++) await File.WriteAllBytesAsync(Path.Combine(Inbox(f), $".cq-old{i:D2}-{i}.part"), new byte[10]);
        await Upload(f, "new.bin", Random(100));
        Assert.True(InboxFiles(f).Count(n => n.EndsWith(".part")) < 8);
    }

    [Fact]
    public async Task Without_the_files_permission_the_offer_is_forbidden()
    {
        var (f, _) = Make(perms: new[] { "mouse" });
        SendOffer(f, Id(), "x.bin", Random(10));
        Assert.Equal("forbidden", await f.ExpectErrorAsync());
        Assert.Empty(InboxFiles(f));
    }

    // ---------------------------------------------------------------- PC -> phone

    private static void Seed(SessionFixture f, string name, byte[] data)
    {
        Directory.CreateDirectory(f.Settings.Current.OutboxDirectory!);
        File.WriteAllBytes(Path.Combine(f.Settings.Current.OutboxDirectory!, name), data);
    }

    /// <summary>The phone side of a download: accept the host's offer and reassemble the chunks.</summary>
    private static async Task<(byte[] Data, System.Text.Json.JsonElement Offer)> Download(SessionFixture f, long startAt = 0, byte[]? prefix = null)
    {
        var offer = await f.ExpectAsync("file.offer", 5000);
        var tid = offer.GetProperty("transferId").GetString()!;
        var size = offer.GetProperty("size").GetInt64();
        f.Send("file.accept", new() { ["transferId"] = tid, ["offset"] = startAt });
        var ms = new MemoryStream();
        if (prefix is not null) ms.Write(prefix);
        while (true)
        {
            var m = await f.ExpectAnyAsync(new[] { "file.chunk", "file.done" }, 5000);
            if (m.GetProperty("t").GetString() == "file.done") break;
            Assert.Equal(ms.Length, m.GetProperty("offset").GetInt64());
            ms.Write(CryptoUtil.FromBase64Url(m.GetProperty("data").GetString()!));
            f.Send("file.ack", new() { ["transferId"] = tid, ["received"] = ms.Length });
        }
        Assert.Equal(size, ms.Length);
        var data = ms.ToArray();
        f.Send("file.result", new() { ["transferId"] = tid, ["ok"] = Sha(data) == offer.GetProperty("sha256").GetString() });
        return (data, offer);
    }

    [Fact]
    public async Task The_phone_can_list_and_download_files_from_the_outbox()
    {
        var (f, _) = Make();
        var data = Random(70_000);
        Seed(f, "para-el-movil.bin", data);
        Directory.CreateDirectory(Path.Combine(f.Settings.Current.OutboxDirectory!, "subcarpeta"));

        f.Send("file.list", new() { ["id"] = "l1" });
        var listing = await f.ExpectAsync("file.listing");
        var files = listing.GetProperty("files").EnumerateArray().ToList();
        Assert.Single(files);
        Assert.Equal("para-el-movil.bin", files[0].GetProperty("name").GetString());

        f.Send("file.request", new() { ["id"] = "r1", ["transferId"] = Id(), ["name"] = "para-el-movil.bin" });
        var (got, offer) = await Download(f);
        Assert.Equal(data, got);
        Assert.Equal(Sha(data), offer.GetProperty("sha256").GetString());
    }

    [Theory]
    [InlineData("../secret.txt")]
    [InlineData("..\\secret.txt")]
    [InlineData("subdir/../../secret.txt")]
    [InlineData("secret.txt/")]
    [InlineData("/etc/passwd")]
    [InlineData("C:\\Windows\\win.ini")]
    [InlineData("nope.bin")]
    public async Task Requests_for_anything_but_a_file_listed_in_the_outbox_are_refused(string name)
    {
        var (f, _) = Make();
        Directory.CreateDirectory(f.Settings.Current.OutboxDirectory!);
        await File.WriteAllTextAsync(Path.Combine(f.TempDir, "secret.txt"), "top secret"); // sits right next to the outbox
        Seed(f, "public.txt", Encoding.UTF8.GetBytes("ok"));
        f.Send("file.request", new() { ["id"] = "r1", ["transferId"] = Id(), ["name"] = name });
        var rej = await f.ExpectAsync("file.reject");
        Assert.Equal("not_found", rej.GetProperty("reason").GetString());
        await f.ExpectNoneAsync("file.offer");
    }

    [Fact]
    public async Task Symlinks_in_the_outbox_are_neither_listed_nor_served()
    {
        var (f, _) = Make();
        Directory.CreateDirectory(f.Settings.Current.OutboxDirectory!);
        var secret = Path.Combine(f.TempDir, "secret.txt");
        await File.WriteAllTextAsync(secret, "top secret");
        File.CreateSymbolicLink(Path.Combine(f.Settings.Current.OutboxDirectory!, "link.txt"), secret);
        Seed(f, "real.txt", Encoding.UTF8.GetBytes("fine"));

        f.Send("file.list", new() { ["id"] = "l1" });
        var names = (await f.ExpectAsync("file.listing")).GetProperty("files").EnumerateArray().Select(e => e.GetProperty("name").GetString()).ToArray();
        Assert.Equal(new[] { "real.txt" }, names);

        f.Send("file.request", new() { ["id"] = "r1", ["transferId"] = Id(), ["name"] = "link.txt" });
        Assert.Equal("not_found", (await f.ExpectAsync("file.reject")).GetProperty("reason").GetString());
    }

    [Fact]
    public async Task A_download_can_resume_at_the_offset_the_phone_asks_for()
    {
        var (f, _) = Make();
        var data = Random(50_000);
        Seed(f, "r.bin", data);
        f.Send("file.request", new() { ["id"] = "r1", ["transferId"] = Id(), ["name"] = "r.bin" });
        var (got, _) = await Download(f, startAt: 32768, prefix: data[..32768]);
        Assert.Equal(data, got);
    }

    [Fact]
    public async Task The_pc_can_push_a_file_the_user_picked_and_the_phone_confirms_delivery()
    {
        var (f, svc) = Make();
        var path = Path.Combine(f.TempDir, "elegido.bin");
        var data = Random(33_000);
        await File.WriteAllBytesAsync(path, data);
        var progress = new List<TransferProgress>();
        svc.Progress += progress.Add;

        Assert.True(await svc.OfferFileAsync(f.Session, path));
        var (got, _) = await Download(f);
        Assert.Equal(data, got);
        await Task.Delay(200);
        Assert.Contains(progress, p => p.Direction == TransferDirection.Outgoing && p.State == TransferState.Done);
    }

    [Fact]
    public async Task The_pc_will_not_offer_a_symlink_or_a_directory_or_a_missing_file()
    {
        var (f, svc) = Make();
        var real = Path.Combine(f.TempDir, "real.txt");
        await File.WriteAllTextAsync(real, "x");
        var link = Path.Combine(f.TempDir, "link.txt");
        File.CreateSymbolicLink(link, real);
        Assert.False(await svc.OfferFileAsync(f.Session, link));
        Assert.False(await svc.OfferFileAsync(f.Session, f.TempDir));
        Assert.False(await svc.OfferFileAsync(f.Session, Path.Combine(f.TempDir, "ghost.txt")));
        for (var i = 0; i < 3; i++) Assert.Equal("not_found", (await f.ExpectAsync("file.reject")).GetProperty("reason").GetString());
    }

    [Fact]
    public async Task The_phone_declining_or_cancelling_stops_the_outgoing_transfer()
    {
        var (f, svc) = Make();
        var path = Path.Combine(f.TempDir, "x.bin");
        await File.WriteAllBytesAsync(path, Random(500_000));
        Assert.True(await svc.OfferFileAsync(f.Session, path));
        var offer = await f.ExpectAsync("file.offer");
        var tid = offer.GetProperty("transferId").GetString()!;
        f.Send("file.accept", new() { ["transferId"] = tid, ["offset"] = 0 });
        await f.ExpectAsync("file.chunk");
        f.Send("file.cancel", new() { ["transferId"] = tid, ["reason"] = "user" });
        await Task.Delay(400);
        // After cancelling, a fresh offer is possible again (the slot was freed).
        Assert.True(await svc.OfferFileAsync(f.Session, path));
    }

    [Fact]
    public async Task Ending_the_session_mid_download_stops_the_sender_cleanly()
    {
        var (f, svc) = Make();
        var path = Path.Combine(f.TempDir, "y.bin");
        await File.WriteAllBytesAsync(path, Random(2_000_000));
        Assert.True(await svc.OfferFileAsync(f.Session, path));
        var offer = await f.ExpectAsync("file.offer");
        f.Send("file.accept", new() { ["transferId"] = offer.GetProperty("transferId").GetString(), ["offset"] = 0 });
        await f.ExpectAsync("file.chunk");
        f.Session.End("phone_gone");
        await Task.Delay(300);
        Assert.Equal(1, f.EndedCount);
    }
}
