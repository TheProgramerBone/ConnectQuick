using System.Runtime.CompilerServices;
using System.Text.Json;

namespace ConnectQuick.WindowsHost.Tests;

/// <summary>Loads the shared files in Protocol/test-vectors — never a platform-local copy.</summary>
internal static class VectorFiles
{
    public static JsonElement Load(string name, [CallerFilePath] string thisFile = "")
    {
        var dir = Path.GetDirectoryName(thisFile)!;
        var path = Path.GetFullPath(Path.Combine(dir, "..", "..", "..", "Protocol", "test-vectors", name));
        using var doc = JsonDocument.Parse(File.ReadAllText(path));
        return doc.RootElement.Clone();
    }

    public static string S(this JsonElement e, string prop) => e.GetProperty(prop).GetString()!;
}
