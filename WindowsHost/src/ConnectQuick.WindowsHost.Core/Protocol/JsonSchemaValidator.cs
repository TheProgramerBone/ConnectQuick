using System.Text.Json;
using System.Text.RegularExpressions;

namespace ConnectQuick.WindowsHost.Protocol;

/// <summary>
/// A deliberately small JSON Schema validator covering exactly the keywords used by
/// <c>Protocol/schemas/session-messages.schema.json</c>: type, const, enum, pattern, minimum,
/// maximum, minLength, maxLength, required, properties, additionalProperties:false, items,
/// minItems, maxItems, uniqueItems. Unknown keywords other than annotations make it throw at load
/// time so a schema edit can never be silently ignored by this implementation.
///
/// Using the shared schema file (rather than hand-coded checks) keeps one contract for Backend,
/// Windows and Android; the shared inner-message vectors prove the three validators agree.
/// </summary>
public sealed class JsonSchemaValidator
{
    private static readonly HashSet<string> Annotations = new() { "$comment", "title", "description", "$schema", "$id" };
    private static readonly HashSet<string> Known = new()
    {
        "type", "const", "enum", "pattern", "minimum", "maximum", "minLength", "maxLength",
        "required", "properties", "additionalProperties", "items", "minItems", "maxItems", "uniqueItems",
    };

    private readonly JsonElement _schema;

    public JsonSchemaValidator(JsonElement schema)
    {
        AssertSupported(schema, "$");
        _schema = schema;
    }

    private static void AssertSupported(JsonElement schema, string path)
    {
        if (schema.ValueKind != JsonValueKind.Object) return;
        foreach (var p in schema.EnumerateObject())
        {
            if (Annotations.Contains(p.Name)) continue;
            if (!Known.Contains(p.Name)) throw new NotSupportedException($"schema keyword '{p.Name}' at {path} is not supported");
            if (p.Name == "properties")
            {
                foreach (var prop in p.Value.EnumerateObject()) AssertSupported(prop.Value, $"{path}.properties.{prop.Name}");
            }
            else if (p.Name == "items" || (p.Name == "additionalProperties" && p.Value.ValueKind == JsonValueKind.Object))
            {
                AssertSupported(p.Value, $"{path}.{p.Name}");
            }
        }
    }

    public bool IsValid(JsonElement value) => Check(_schema, value);

    private static bool Check(JsonElement schema, JsonElement v)
    {
        foreach (var kw in schema.EnumerateObject())
        {
            switch (kw.Name)
            {
                case "type":
                    if (!TypeMatches(kw.Value.GetString()!, v)) return false;
                    break;
                case "const":
                    if (!JsonEquals(kw.Value, v)) return false;
                    break;
                case "enum":
                    if (!kw.Value.EnumerateArray().Any(e => JsonEquals(e, v))) return false;
                    break;
                case "pattern":
                    if (v.ValueKind != JsonValueKind.String || !PatternMatches(kw.Value.GetString()!, v.GetString()!)) return false;
                    break;
                case "minimum":
                    if (v.ValueKind == JsonValueKind.Number && v.GetDouble() < kw.Value.GetDouble()) return false;
                    break;
                case "maximum":
                    if (v.ValueKind == JsonValueKind.Number && v.GetDouble() > kw.Value.GetDouble()) return false;
                    break;
                case "minLength":
                    if (v.ValueKind == JsonValueKind.String && CodePointLength(v.GetString()!) < kw.Value.GetInt32()) return false;
                    break;
                case "maxLength":
                    if (v.ValueKind == JsonValueKind.String && CodePointLength(v.GetString()!) > kw.Value.GetInt32()) return false;
                    break;
                case "required":
                    if (v.ValueKind == JsonValueKind.Object)
                        foreach (var r in kw.Value.EnumerateArray())
                            if (!v.TryGetProperty(r.GetString()!, out _)) return false;
                    break;
                case "properties":
                    if (v.ValueKind == JsonValueKind.Object)
                        foreach (var prop in kw.Value.EnumerateObject())
                            if (v.TryGetProperty(prop.Name, out var child) && !Check(prop.Value, child)) return false;
                    break;
                case "additionalProperties":
                    if (v.ValueKind == JsonValueKind.Object && kw.Value.ValueKind == JsonValueKind.False)
                    {
                        var declared = schema.TryGetProperty("properties", out var props) ? props : default;
                        foreach (var actual in v.EnumerateObject())
                            if (declared.ValueKind != JsonValueKind.Object || !declared.TryGetProperty(actual.Name, out _)) return false;
                    }
                    break;
                case "items":
                    if (v.ValueKind == JsonValueKind.Array)
                        foreach (var item in v.EnumerateArray())
                            if (!Check(kw.Value, item)) return false;
                    break;
                case "minItems":
                    if (v.ValueKind == JsonValueKind.Array && v.GetArrayLength() < kw.Value.GetInt32()) return false;
                    break;
                case "maxItems":
                    if (v.ValueKind == JsonValueKind.Array && v.GetArrayLength() > kw.Value.GetInt32()) return false;
                    break;
                case "uniqueItems":
                    if (kw.Value.ValueKind == JsonValueKind.True && v.ValueKind == JsonValueKind.Array)
                    {
                        var items = v.EnumerateArray().ToList();
                        for (var i = 0; i < items.Count; i++)
                            for (var j = i + 1; j < items.Count; j++)
                                if (JsonEquals(items[i], items[j])) return false;
                    }
                    break;
            }
        }
        return true;
    }

    private static bool TypeMatches(string type, JsonElement v) => type switch
    {
        "object" => v.ValueKind == JsonValueKind.Object,
        "array" => v.ValueKind == JsonValueKind.Array,
        "string" => v.ValueKind == JsonValueKind.String,
        "boolean" => v.ValueKind is JsonValueKind.True or JsonValueKind.False,
        "number" => v.ValueKind == JsonValueKind.Number,
        "integer" => v.ValueKind == JsonValueKind.Number && IsIntegral(v),
        _ => throw new NotSupportedException($"type '{type}'"),
    };

    private static bool IsIntegral(JsonElement v)
    {
        if (v.TryGetInt64(out _)) return true;
        var d = v.GetDouble();
        return !double.IsInfinity(d) && Math.Floor(d) == d;
    }

    /// <summary>JSON Schema counts string length in Unicode code points, not UTF-16 units.</summary>
    private static int CodePointLength(string s)
    {
        var n = 0;
        for (var i = 0; i < s.Length; i++)
        {
            if (char.IsHighSurrogate(s[i]) && i + 1 < s.Length && char.IsLowSurrogate(s[i + 1])) i++;
            n++;
        }
        return n;
    }

    /// <summary>
    /// ECMAScript's <c>$</c> only matches at the very end, whereas .NET's also matches before a
    /// trailing "\n". Translating a final <c>$</c> to <c>\z</c> keeps "KeyA\n" invalid here exactly
    /// as it is in the TypeScript validator.
    /// </summary>
    private static bool PatternMatches(string pattern, string value)
    {
        var p = pattern.EndsWith('$') && !pattern.EndsWith("\\$") ? pattern[..^1] + "\\z" : pattern;
        return Regex.IsMatch(value, p, RegexOptions.CultureInvariant, TimeSpan.FromMilliseconds(200));
    }

    private static bool JsonEquals(JsonElement a, JsonElement b)
    {
        if (a.ValueKind != b.ValueKind) return false;
        return a.ValueKind switch
        {
            JsonValueKind.String => a.GetString() == b.GetString(),
            JsonValueKind.Number => a.GetDouble() == b.GetDouble(),
            JsonValueKind.True or JsonValueKind.False or JsonValueKind.Null => true,
            _ => a.GetRawText() == b.GetRawText(),
        };
    }
}
