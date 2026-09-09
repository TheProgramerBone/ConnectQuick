using System.Text.Json;
using ConnectQuick.WindowsHost.Config;
using ConnectQuick.WindowsHost.Crypto;
using ConnectQuick.WindowsHost.Identity;
using ConnectQuick.WindowsHost.Net;

namespace ConnectQuick.WindowsHost.Pairing;

public sealed record IncomingPairingRequest(string PairingId, string DeviceId, string DeviceName, string IdentityPub, string EphemeralPub, string SasCode);

/// <summary>
/// Wires the raw <see cref="BackendClient"/> envelope traffic to the Host-side pairing flow
/// (Protocol §4 "Pairing", §7 handshake steps 1-6): transport `hello`, `pairing.create` →
/// `pairing.created` → QR, and `pairing.incoming` → user decision → signed `pairing.decision`.
/// UI code (tray/forms) only ever sees the events below — it never touches the wire format.
/// </summary>
public sealed class PairingCoordinator
{
    private readonly BackendClient _client;
    private readonly DeviceIdentity _identity;
    private readonly HostConfig _config;
    private readonly AuthorizedDeviceStore _deviceStore;

    private string? _currentPairingEphPub;

    public event Action<bool>? ConnectionChanged;
    public event Action<QrPairingPayload>? PairingQrReady;
    public event Action<IncomingPairingRequest>? IncomingRequest;
    public event Action<string, string>? ErrorReceived; // (code, message)

    public PairingCoordinator(BackendClient client, DeviceIdentity identity, HostConfig config, AuthorizedDeviceStore deviceStore)
    {
        _client = client;
        _identity = identity;
        _config = config;
        _deviceStore = deviceStore;

        _client.ConnectionChanged += connected => ConnectionChanged?.Invoke(connected);
        _client.On("hello.challenge", OnHelloChallenge);
        _client.On("pairing.created", OnPairingCreated);
        _client.On("pairing.incoming", OnPairingIncoming);
        _client.On("error", OnError);
    }

    private void OnError(JsonElement payload)
    {
        var code = payload.TryGetProperty("code", out var c) ? c.GetString() ?? "" : "";
        var message = payload.TryGetProperty("message", out var m) ? m.GetString() ?? "" : "";
        ErrorReceived?.Invoke(code, message);
    }

    private async void OnHelloChallenge(JsonElement payload)
    {
        var challengeB64 = payload.GetProperty("serverChallenge").GetString()!;
        var challengeBytes = CryptoUtil.FromBase64Url(challengeB64);
        var signature = _identity.Sign(challengeBytes);

        await _client.SendAsync("hello", new Dictionary<string, object?>
        {
            ["role"] = "host",
            ["deviceId"] = _identity.DeviceId,
            ["identityPub"] = _identity.IdentityPub,
            ["challengeSig"] = signature,
            ["clientVersion"] = HostConfig.ClientVersion,
            ["hostName"] = _config.HostName,
        }).ConfigureAwait(false);
    }

    /// <summary>"Vincular nuevo dispositivo" — asks the backend for a fresh pairingId and builds the QR content once it answers.</summary>
    public async Task StartNewPairingAsync()
    {
        using var ephemeral = CryptoUtil.CreateX25519Ephemeral();
        _currentPairingEphPub = CryptoUtil.ExportPublicB64Url(ephemeral);

        await _client.SendAsync("pairing.create", new Dictionary<string, object?>
        {
            ["hostName"] = _config.HostName,
            ["hostEphPub"] = _currentPairingEphPub,
        }).ConfigureAwait(false);
    }

    private void OnPairingCreated(JsonElement payload)
    {
        if (_currentPairingEphPub is null) return; // stray/duplicate response, nothing pending
        var pairingId = payload.GetProperty("pairingId").GetString()!;
        var nonce = payload.GetProperty("nonce").GetString()!;
        var expiresAtMs = payload.GetProperty("expiresAt").GetInt64();

        var qr = new QrPairingPayload
        {
            PairingId = pairingId,
            Relay = _config.RelayUri.ToString(),
            HostId = _identity.DeviceId,
            HostName = _config.HostName,
            HostPub = _identity.IdentityPub,
            HostEphPub = _currentPairingEphPub,
            Nonce = nonce,
            Exp = expiresAtMs / 1000,
        };
        PairingQrReady?.Invoke(qr);
    }

    private void OnPairingIncoming(JsonElement payload)
    {
        var request = new IncomingPairingRequest(
            PairingId: payload.GetProperty("pairingId").GetString()!,
            DeviceId: payload.GetProperty("deviceId").GetString()!,
            DeviceName: payload.GetProperty("deviceName").GetString()!,
            IdentityPub: payload.GetProperty("identityPub").GetString()!,
            EphemeralPub: payload.GetProperty("ephemeralPub").GetString()!,
            SasCode: payload.GetProperty("sasCode").GetString()!);
        IncomingRequest?.Invoke(request);
    }

    /// <summary>User clicked "Sí" on the authorize dialog. Grants the default permission set (Protocol §4).</summary>
    public Task ApproveAsync(IncomingPairingRequest request) => SendDecisionAsync(request, approved: true);

    /// <summary>User clicked "No" / dismissed the dialog.</summary>
    public Task RejectAsync(IncomingPairingRequest request) => SendDecisionAsync(request, approved: false);

    private async Task SendDecisionAsync(IncomingPairingRequest request, bool approved)
    {
        var permissions = approved ? PairingPermissions.Default : [];
        var message = CryptoUtil.PairingDecisionMessage(request.PairingId, request.DeviceId, approved, permissions);
        var signature = _identity.Sign(message);

        var payload = new Dictionary<string, object?>
        {
            ["pairingId"] = request.PairingId,
            ["approved"] = approved,
            ["hostSignature"] = signature,
        };
        if (approved) payload["permissions"] = permissions;

        await _client.SendAsync("pairing.decision", payload).ConfigureAwait(false);

        if (approved)
        {
            _deviceStore.Add(new AuthorizedDevice(request.DeviceId, request.DeviceName, permissions, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()));
        }
    }

    public async Task RevokeAsync(string deviceId)
    {
        await _client.SendAsync("pairing.revoke", new Dictionary<string, object?> { ["deviceId"] = deviceId }).ConfigureAwait(false);
        _deviceStore.Remove(deviceId);
    }
}

public static class PairingPermissions
{
    /// <summary>Must match <c>Backend/src/ws/handlers/util.ts#DEFAULT_PAIRING_PERMISSIONS</c> exactly (order included — it's part of the signed message).</summary>
    public static readonly IReadOnlyList<string> Default =
        ["remote_desktop", "mouse", "keyboard", "clipboard", "files", "media", "apps", "power"];
}
