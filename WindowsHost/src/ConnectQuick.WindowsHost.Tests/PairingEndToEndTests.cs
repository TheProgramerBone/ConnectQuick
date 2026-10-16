using System.Diagnostics;
using System.Net.Sockets;
using System.Runtime.CompilerServices;
using System.Text.Json;
using ConnectQuick.WindowsHost.Config;
using ConnectQuick.WindowsHost.Crypto;
using ConnectQuick.WindowsHost.Identity;
using ConnectQuick.WindowsHost.Net;
using ConnectQuick.WindowsHost.Pairing;

namespace ConnectQuick.WindowsHost.Tests;

/// <summary>
/// Runs the real §6-7 pairing handshake end-to-end against the actual Node backend (spawned as a
/// real subprocess, in-memory DB, no mocks) — mirrors what <c>Backend/test-support/flows.ts</c>
/// does for the TypeScript side, except the Host half here is the *real*
/// <see cref="PairingCoordinator"/> this app ships, not a test double. The only thing standing in
/// for the not-yet-built Android app is a minimal Controller simulated with the same
/// <see cref="CryptoUtil"/> primitives already proven bit-exact against the shared test vectors
/// (<see cref="CryptoVectorsTests"/>) — so this test proves the WindowsHost's actual wire
/// behavior, not just that its crypto matches in isolation.
/// </summary>
public sealed class PairingEndToEndTests : IAsyncLifetime
{
    private Process? _backend;
    private int _port;

    public async Task InitializeAsync()
    {
        _port = GetFreeTcpPort();
        var backendDir = FindBackendDir();

        var psi = new ProcessStartInfo("node", "src/server.ts")
        {
            WorkingDirectory = backendDir,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
        };
        psi.Environment["PORT"] = _port.ToString();
        psi.Environment["DB_PATH"] = ":memory:";
        psi.Environment["LOG_LEVEL"] = "WARN";

        _backend = Process.Start(psi) ?? throw new InvalidOperationException("could not start backend process");
        await WaitForPortAsync(_port, TimeSpan.FromSeconds(15));
    }

    public async Task DisposeAsync()
    {
        if (_backend is { HasExited: false })
        {
            _backend.Kill(entireProcessTree: true);
            await _backend.WaitForExitAsync();
        }
        _backend?.Dispose();
    }

    /// <summary>DPAPI-backed store on Windows (the production one); an in-memory store elsewhere, where DPAPI does not exist.</summary>
    private ISeedStore NewSeedStore() =>
        OperatingSystem.IsWindows() ? new SecureKeyStore(TempFilePath("host_identity.bin")) : new InMemorySeedStore();

    [Fact]
    public async Task Host_pairing_flow_completes_against_the_real_backend()
    {
        var relay = new Uri($"ws://localhost:{_port}");
        using var lifetimeCts = new CancellationTokenSource();

        // --- Host side: the real production code this app ships. ---
        var hostConfig = new HostConfig(relay, "E2E Test PC");
        var hostIdentity = DeviceIdentity.LoadOrCreate(NewSeedStore());
        var hostClient = new BackendClient(relay);
        var coordinator = new PairingCoordinator(hostClient, hostIdentity, hostConfig, new AuthorizedDeviceStore(TempFilePath("host_devices.json")));

        var qrReady = new TaskCompletionSource<QrPairingPayload>(TaskCreationOptions.RunContinuationsAsynchronously);
        coordinator.PairingQrReady += qr => qrReady.TrySetResult(qr);
        // Stands in for the user clicking "Sí" on AuthorizeForm — approve whatever request arrives.
        coordinator.IncomingRequest += async request => await coordinator.ApproveAsync(request);

        var hostRunTask = hostClient.RunForeverAsync(lifetimeCts.Token);
        // Wait for the link to be genuinely ready (connected + hello/hello.ack) rather than guessing a delay.
        for (var waited = 0; hostClient.State != SignalingState.Ready && waited < 10_000; waited += 20) await Task.Delay(20);
        Assert.Equal(SignalingState.Ready, hostClient.State);

        await coordinator.StartNewPairingAsync();
        var qr = await WithTimeout(qrReady.Task, TimeSpan.FromSeconds(5));

        // --- Controller side: simulated (Android app doesn't exist yet), same crypto primitives already vector-verified. ---
        using var controllerIdentityKey = CryptoUtil.CreateEd25519Identity();
        var controllerPub = CryptoUtil.ExportPublicB64Url(controllerIdentityKey);
        var controllerDeviceId = CryptoUtil.DeriveDeviceId(controllerPub);
        using var controllerEphemeral = CryptoUtil.CreateX25519Ephemeral();
        var controllerEphPub = CryptoUtil.ExportPublicB64Url(controllerEphemeral);

        var controllerClient = new BackendClient(relay);
        var controllerHelloAck = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var pairingResult = new TaskCompletionSource<JsonElement>(TaskCreationOptions.RunContinuationsAsynchronously);

        controllerClient.On("hello.challenge", payload =>
        {
            var challengeBytes = CryptoUtil.FromBase64Url(payload.GetProperty("serverChallenge").GetString()!);
            var sig = CryptoUtil.SignEd25519(controllerIdentityKey, challengeBytes);
            _ = controllerClient.SendAsync("hello", new Dictionary<string, object?>
            {
                ["role"] = "controller",
                ["deviceId"] = controllerDeviceId,
                ["identityPub"] = controllerPub,
                ["challengeSig"] = sig,
                ["clientVersion"] = "0.1.0-e2e",
            });
        });
        controllerClient.On("hello.ack", _ => controllerHelloAck.TrySetResult());
        controllerClient.On("pairing.result", payload => pairingResult.TrySetResult(payload));

        var controllerRunTask = controllerClient.RunForeverAsync(lifetimeCts.Token);
        await WithTimeout(controllerHelloAck.Task, TimeSpan.FromSeconds(5));

        await controllerClient.SendAsync("pairing.init", new Dictionary<string, object?>
        {
            ["pairingId"] = qr.PairingId,
            ["deviceId"] = controllerDeviceId,
            ["deviceName"] = "E2E Fake Android",
            ["identityPub"] = controllerPub,
            ["ephemeralPub"] = controllerEphPub,
        });

        var result = await WithTimeout(pairingResult.Task, TimeSpan.FromSeconds(5));

        Assert.True(result.GetProperty("success").GetBoolean(), result.ToString());
        Assert.Equal(hostIdentity.DeviceId, result.GetProperty("hostId").GetString());
        Assert.Equal(hostConfig.HostName, result.GetProperty("hostName").GetString());
        var permissions = result.GetProperty("permissions").EnumerateArray().Select(p => p.GetString()!).ToArray();
        // Secure default: approving without ticking anything grants the recommended set, NOT remote power control.
        Assert.Equal(PairingPermissions.Recommended, permissions);

        // The result carries the Host's signature over the decision — a real Controller must be
        // able to verify it independently (it's not just trusting the backend's word).
        var message = CryptoUtil.PairingDecisionMessage(qr.PairingId, controllerDeviceId, approved: true, PairingPermissions.Recommended);
        Assert.True(CryptoUtil.VerifyEd25519(result.GetProperty("hostPub").GetString()!, message, result.GetProperty("hostSignature").GetString()!));

        lifetimeCts.Cancel();
        try { await Task.WhenAll(hostRunTask, controllerRunTask); } catch (OperationCanceledException) { }
    }

    private static async Task<T> WithTimeout<T>(Task<T> task, TimeSpan timeout)
    {
        var winner = await Task.WhenAny(task, Task.Delay(timeout));
        if (winner != task) throw new TimeoutException($"timed out after {timeout}");
        return await task;
    }

    private static async Task WithTimeout(Task task, TimeSpan timeout)
    {
        var winner = await Task.WhenAny(task, Task.Delay(timeout));
        if (winner != task) throw new TimeoutException($"timed out after {timeout}");
        await task;
    }

    private static async Task WaitForPortAsync(int port, TimeSpan timeout)
    {
        var deadline = DateTime.UtcNow + timeout;
        Exception? last = null;
        while (DateTime.UtcNow < deadline)
        {
            try
            {
                using var client = new TcpClient();
                await client.ConnectAsync("localhost", port);
                return;
            }
            catch (Exception ex)
            {
                last = ex;
                await Task.Delay(200);
            }
        }
        throw new TimeoutException($"backend did not open port {port} in time", last);
    }

    private static int GetFreeTcpPort()
    {
        var listener = new TcpListener(System.Net.IPAddress.Loopback, 0);
        listener.Start();
        var port = ((System.Net.IPEndPoint)listener.LocalEndpoint).Port;
        listener.Stop();
        return port;
    }

    private static string TempFilePath(string name) =>
        Path.Combine(Path.GetTempPath(), "ConnectQuickE2E", Guid.NewGuid().ToString(), name);

    private static string FindBackendDir([CallerFilePath] string thisFile = "")
    {
        var dir = Path.GetDirectoryName(thisFile)!;
        return Path.GetFullPath(Path.Combine(dir, "..", "..", "..", "Backend"));
    }
}
