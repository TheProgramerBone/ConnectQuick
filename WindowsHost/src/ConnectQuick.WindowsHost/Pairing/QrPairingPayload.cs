using System.Text.Json;
using System.Text.Json.Serialization;

namespace ConnectQuick.WindowsHost.Pairing;

/// <summary>QR content per <c>Protocol/protocol-spec.md</c> §5 — never carries private material.</summary>
public sealed class QrPairingPayload
{
    public int V { get; init; } = 1;
    public required string PairingId { get; init; }
    public required string Relay { get; init; }
    public required string HostId { get; init; }
    public required string HostName { get; init; }
    public required string HostPub { get; init; }
    public required string HostEphPub { get; init; }
    public required string Nonce { get; init; }
    /// <summary>Unix epoch seconds. Informative for the UI only — the backend enforces the TTL authoritatively.</summary>
    public required long Exp { get; init; }

    private static readonly JsonSerializerOptions Options = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase };

    public string ToJson() => JsonSerializer.Serialize(this, Options);
}
