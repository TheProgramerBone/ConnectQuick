using System.Text.Json;
using System.Text.Json.Nodes;
using ConnectQuick.WindowsHost.Crypto;
using ConnectQuick.WindowsHost.Files;
using ConnectQuick.WindowsHost.Identity;
using ConnectQuick.WindowsHost.Pairing;
using ConnectQuick.WindowsHost.Power;
using ConnectQuick.WindowsHost.Runtime;
using ConnectQuick.WindowsHost.Settings;
using ConnectQuick.WindowsHost.Testing;

// Protocol with the orchestrator: one JSON object per line.
//   stdin : {"cmd": "...", "id": "<correlation>", ...args}
//   stdout: {"evt": "...", ...}   asynchronous events, and {"evt":"reply","id":..., ...} for each command.
var relay = Environment.GetEnvironmentVariable("CQ_RELAY") ?? "ws://127.0.0.1:8443";
var dataDir = Environment.GetEnvironmentVariable("CQ_DATA_DIR") ?? Directory.CreateTempSubdirectory("cq-host-").FullName;
var hostName = Environment.GetEnvironmentVariable("CQ_HOST_NAME") ?? "Harness PC";
Directory.CreateDirectory(dataDir);

var seedStore = new InMemorySeedStore();
var seedFromEnv = Environment.GetEnvironmentVariable("CQ_SEED_B64URL");
if (!string.IsNullOrEmpty(seedFromEnv)) seedStore.SaveSeed(CryptoUtil.FromBase64Url(seedFromEnv));

var injector = new RecordingInjector();
var clipboard = new FakeClipboard();
var media = new RecordingMediaKeys();
var power = new RecordingPower();
var videoPeers = new FakeVideoPeerFactory();

var settingsPath = Path.Combine(dataDir, "settings.json");
var settingsPreset = new HostSettingsStore(settingsPath);
settingsPreset.Update(s =>
{
    s.InboxDirectory = Path.Combine(dataDir, "inbox");
    s.OutboxDirectory = Path.Combine(dataDir, "outbox");
    s.Power = PowerPolicy.Full; // the scenario exercises the confirmation flow; policy tests flip it
});

await using var host = new HostRuntime(new HostRuntimeOptions
{
    RelayUri = new Uri(relay),
    ExtraRelays = Environment.GetEnvironmentVariable("CQ_EXTRA_RELAYS")?.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries),
    HostName = hostName,
    SeedStore = seedStore,
    Adapters = new HostAdapters(injector, clipboard, media, power),
    DevicesPath = Path.Combine(dataDir, "devices.json"),
    SettingsPath = settingsPath,
    VideoPeers = videoPeers,
    HeartbeatInterval = int.TryParse(Environment.GetEnvironmentVariable("CQ_HEARTBEAT_MS"), out var hbMs) ? TimeSpan.FromMilliseconds(hbMs) : null,
    DeadAfter = int.TryParse(Environment.GetEnvironmentVariable("CQ_DEAD_AFTER_MS"), out var deadMs) ? TimeSpan.FromMilliseconds(deadMs) : null,
});

// The protocol is UTF-8 JSON lines regardless of the platform locale.
Console.OutputEncoding = new System.Text.UTF8Encoding(false);
Console.InputEncoding = new System.Text.UTF8Encoding(false);
var stdout = Console.Out;
var outLock = new object();
void Emit(JsonObject o) { lock (outLock) { stdout.WriteLine(o.ToJsonString()); stdout.Flush(); } }
JsonObject Evt(string name, Action<JsonObject>? fill = null) { var o = new JsonObject { ["evt"] = name }; fill?.Invoke(o); return o; }

IReadOnlyList<string> approveWith = PairingPermissions.Recommended;
host.Client.StateChanged += s => Emit(Evt("signaling", o => o["state"] = s.ToString()));
host.Log.Added += e => Emit(Evt("log", o => { o["level"] = e.Level.ToString(); o["category"] = e.Category; o["message"] = e.Message; }));
host.Sessions.SessionStarted += s => Emit(Evt("session", o => { o["state"] = "started"; o["deviceId"] = s.DeviceId; o["permissions"] = new JsonArray(s.Permissions.Select(p => (JsonNode)p).ToArray()); }));
host.Sessions.SessionEnded += (s, reason) => Emit(Evt("session", o => { o["state"] = "ended"; o["reason"] = reason; o["deviceId"] = s.DeviceId; }));
host.Files.Progress += p => Emit(Evt("transfer", o => { o["id"] = p.TransferId; o["dir"] = p.Direction.ToString(); o["name"] = p.Name; o["bytes"] = p.Bytes; o["total"] = p.Total; o["state"] = p.State.ToString(); o["reason"] = p.Reason; o["savedAs"] = p.SavedAs; }));
host.Pairing.PairingQrReady += qr => Emit(Evt("qr", o => o["payload"] = JsonNode.Parse(qr.ToJson())));
host.Pairing.IncomingRequest += async req =>
{
    // Stands in for the person clicking "Sí" in the authorize dialog.
    await host.Pairing.ApproveAsync(req, approveWith);
    Emit(Evt("paired", o => { o["deviceId"] = req.DeviceId; o["deviceName"] = req.DeviceName; o["sas"] = req.SasCode; }));
};

host.Start();
Emit(Evt("ready", o => { o["hostId"] = host.Identity.DeviceId; o["hostPub"] = host.Identity.IdentityPub; o["dataDir"] = dataDir; }));

static string Str(JsonNode? n) => n?.GetValue<string>() ?? "";

async Task<JsonObject> HandleAsync(JsonObject cmd)
{
    switch (Str(cmd["cmd"]))
    {
        case "pair":
            if (cmd["permissions"] is JsonArray perms) approveWith = perms.Select(p => p!.GetValue<string>()).ToList();
            await host.Pairing.StartNewPairingAsync();
            return new();
        case "state":
        {
            var s = host.Sessions.Active;
            var inbox = host.Settings.Current.DefaultInbox();
            var outbox = host.Settings.Current.DefaultOutbox();
            JsonArray Files(string dir) => new(Directory.Exists(dir)
                ? new DirectoryInfo(dir).EnumerateFiles().Select(f => (JsonNode)new JsonObject
                {
                    ["name"] = f.Name,
                    ["size"] = f.Length,
                    ["sha256"] = f.Length < 64 * 1024 * 1024 ? Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(File.ReadAllBytes(f.FullName))).ToLowerInvariant() : "",
                }).ToArray()
                : []);
            return new()
            {
                ["injected"] = new JsonArray(injector.Snapshot().Select(e => (JsonNode)e).ToArray()),
                ["held"] = new JsonObject { ["keys"] = host.Input.HeldCount().Keys, ["buttons"] = host.Input.HeldCount().Buttons },
                ["clipboard"] = clipboard.GetText(),
                ["clipboardWrites"] = clipboard.Writes,
                ["media"] = new JsonArray(media.Pressed.ToArray().Select(k => (JsonNode)k.ToString()).ToArray()),
                ["power"] = new JsonArray(power.Calls.ToArray().Select(c => (JsonNode)c).ToArray()),
                ["inbox"] = Files(inbox),
                ["outbox"] = Files(outbox),
                ["devices"] = new JsonArray(host.Devices.All.Select(d => (JsonNode)new JsonObject { ["deviceId"] = d.DeviceId, ["name"] = d.DeviceName, ["permissions"] = new JsonArray(d.Permissions.Select(p => (JsonNode)p).ToArray()) }).ToArray()),
                ["session"] = s is null ? null : new JsonObject { ["deviceId"] = s.DeviceId, ["permissions"] = new JsonArray(s.Permissions.Select(p => (JsonNode)p).ToArray()) },
                ["signaling"] = host.Client.State.ToString(),
            };
        }
        case "copy": clipboard.UserCopies(Str(cmd["text"])); return new();
        case "injectorSucceeds": injector.Succeed = cmd["value"]!.GetValue<bool>(); return new();
        case "settings":
            host.Settings.Update(s =>
            {
                if (cmd["power"] is { } p) s.Power = Enum.Parse<PowerPolicy>(Str(p));
                if (cmd["clipboardAutoPush"] is { } c) s.ClipboardAutoPushToController = c.GetValue<bool>();
                if (cmd["maxFileBytes"] is { } m) s.MaxFileBytes = m.GetValue<long>();
                if (cmd["allowMouse"] is { } am) s.AllowMouse = am.GetValue<bool>();
                if (cmd["allowKeyboard"] is { } ak) s.AllowKeyboard = ak.GetValue<bool>();
                if (cmd["acceptConnections"] is { } ac) s.AcceptConnections = ac.GetValue<bool>();
                if (cmd["allowScreen"] is { } asc) s.AllowScreen = asc.GetValue<bool>();
            });
            return new();
        case "revoke": await host.RevokeAsync(Str(cmd["deviceId"])); return new();
        case "seedOutbox":
        {
            var dir = host.Settings.Current.DefaultOutbox();
            Directory.CreateDirectory(dir);
            await File.WriteAllBytesAsync(Path.Combine(dir, Str(cmd["name"])), Convert.FromBase64String(Str(cmd["dataB64"])));
            return new();
        }
        case "offerFile":
        {
            var path = Path.Combine(dataDir, "pc-picked-" + Guid.NewGuid().ToString("N")[..6] + ".bin");
            await File.WriteAllBytesAsync(path, Convert.FromBase64String(Str(cmd["dataB64"])));
            var s = host.Sessions.Active ?? throw new InvalidOperationException("no active session");
            return new() { ["offered"] = await host.Files.OfferFileAsync(s, path) };
        }
        case "peerState":
        {
            // Drives the fake video peer the way a real ICE connection would, so signalling can be tested without media.
            var peer = videoPeers.Created.LastOrDefault() ?? throw new InvalidOperationException("no stream was requested");
            var st = Enum.Parse<ConnectQuick.WindowsHost.Streaming.PeerState>(Str(cmd["state"]));
            if (cmd["stats"] is JsonObject so) peer.Stats = new(so["fps"]!.GetValue<double>(), so["kbps"]!.GetValue<int>(), so["w"]!.GetValue<int>(), so["h"]!.GetValue<int>(), so["loss"]!.GetValue<double>());
            if (cmd["candidate"] is { } cand) peer.RaiseCandidate(Str(cand));
            if (cmd["blocked"] is { } blocked) peer.RaiseBlocked(Str(blocked));
            peer.RaiseState(st);
            return new();
        }
        case "streamInfo":
        {
            var peer = videoPeers.Created.LastOrDefault();
            return new()
            {
                ["peers"] = videoPeers.Created.Count,
                ["answer"] = peer?.AnswerSet,
                ["remoteCandidates"] = peer?.RemoteCandidates.Count ?? 0,
                ["disposed"] = peer?.Disposed ?? false,
                ["profile"] = peer is null ? null : new JsonObject { ["w"] = peer.Profile.Width, ["h"] = peer.Profile.Height, ["fps"] = peer.Profile.Fps, ["kbps"] = peer.Profile.BitrateKbps },
                ["adaptations"] = peer?.Adaptations.Count ?? 0,
            };
        }
        case "endSession": host.Sessions.EndActive("harness_requested"); return new();
        case "quit": return new() { ["quit"] = true };
        default: return new() { ["error"] = "unknown_command" };
    }
}

string? line;
while ((line = await Console.In.ReadLineAsync()) is not null)
{
    JsonObject cmd;
    try { cmd = JsonNode.Parse(line)!.AsObject(); }
    catch (Exception) { Emit(Evt("error", o => o["message"] = "bad command line")); continue; }
    JsonObject reply;
    try { reply = await HandleAsync(cmd); }
    catch (Exception ex) { reply = new() { ["error"] = ex.GetType().Name + ": " + ex.Message }; }
    reply["evt"] = "reply";
    reply["id"] = cmd["id"]?.DeepClone();
    Emit(reply);
    if (reply["quit"] is not null) break;
}
