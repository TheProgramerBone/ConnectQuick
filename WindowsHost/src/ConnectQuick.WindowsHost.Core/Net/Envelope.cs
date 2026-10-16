using System.Text.Json;

namespace ConnectQuick.WindowsHost.Net;

/// <summary>Mirrors the envelope shape in <c>Protocol/schemas/envelope.schema.json</c>.</summary>
public sealed class Envelope
{
    public int V { get; init; }
    public string Id { get; init; } = "";
    public long Ts { get; init; }
    public string Type { get; init; } = "";
    public JsonElement Payload { get; init; }
}
