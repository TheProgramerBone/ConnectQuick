using System.Reflection;
using System.Text;
using System.Text.Json;

namespace ConnectQuick.WindowsHost.Protocol;

public enum InnerRejection
{
    None,
    TooLarge,
    MalformedJson,
    NotAnObject,
    MissingType,
    UnknownType,
    Schema,
}

public readonly record struct InnerParseResult(InnerRejection Rejection, string? Type, JsonElement Root)
{
    public bool Ok => Rejection == InnerRejection.None;
}

/// <summary>
/// Validates the decrypted end-to-end messages against the shared schema. EVERY inbound inner
/// message must pass through here before any code acts on it: the relay cannot validate them
/// (it only sees ciphertext), so the Host is the trust boundary for everything the phone sends.
/// </summary>
public static class InnerMessages
{
    public const int MaxInnerBytes = 40_000;

    private static readonly Lazy<Dictionary<string, JsonSchemaValidator>> Validators = new(LoadValidators);

    private static Dictionary<string, JsonSchemaValidator> LoadValidators()
    {
        using var stream = typeof(InnerMessages).Assembly.GetManifestResourceStream("session-messages.schema.json")
            ?? throw new InvalidOperationException("embedded session-messages.schema.json is missing");
        using var doc = JsonDocument.Parse(stream);
        var map = new Dictionary<string, JsonSchemaValidator>();
        foreach (var def in doc.RootElement.GetProperty("$defs").EnumerateObject())
        {
            // "input_mouse_move" -> "input.mouse_move": only the FIRST underscore stands for the dot.
            var idx = def.Name.IndexOf('_');
            map[def.Name[..idx] + "." + def.Name[(idx + 1)..]] = new JsonSchemaValidator(def.Value.Clone());
        }
        return map;
    }

    public static IReadOnlyCollection<string> KnownTypes => Validators.Value.Keys;

    public static InnerParseResult Parse(ReadOnlySpan<byte> utf8)
    {
        if (utf8.Length > MaxInnerBytes) return new(InnerRejection.TooLarge, null, default);
        JsonDocument doc;
        try
        {
            doc = JsonDocument.Parse(utf8.ToArray(), new JsonDocumentOptions { MaxDepth = 16 });
        }
        catch (JsonException)
        {
            return new(InnerRejection.MalformedJson, null, default);
        }
        var root = doc.RootElement.Clone();
        doc.Dispose();
        if (root.ValueKind != JsonValueKind.Object) return new(InnerRejection.NotAnObject, null, default);
        if (!root.TryGetProperty("t", out var t) || t.ValueKind != JsonValueKind.String) return new(InnerRejection.MissingType, null, default);
        var type = t.GetString()!;
        if (!Validators.Value.TryGetValue(type, out var validator)) return new(InnerRejection.UnknownType, type, default);
        return validator.IsValid(root) ? new(InnerRejection.None, type, root) : new(InnerRejection.Schema, type, default);
    }

    public static InnerParseResult Parse(string json) => Parse(Encoding.UTF8.GetBytes(json));

    /// <summary>Serialises an outbound inner message and enforces the size limit before it is encrypted.</summary>
    public static byte[] Serialize(string type, Dictionary<string, object?> fields)
    {
        var all = new Dictionary<string, object?> { ["t"] = type };
        foreach (var kv in fields) if (kv.Value is not null) all[kv.Key] = kv.Value;
        var bytes = JsonSerializer.SerializeToUtf8Bytes(all);
        if (bytes.Length > MaxInnerBytes) throw new InvalidOperationException($"{type} is {bytes.Length} bytes, over the {MaxInnerBytes} limit");
        return bytes;
    }
}
