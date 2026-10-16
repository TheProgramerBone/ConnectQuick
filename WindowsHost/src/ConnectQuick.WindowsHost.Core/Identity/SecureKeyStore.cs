using System.Security.Cryptography;

namespace ConnectQuick.WindowsHost.Identity;

/// <summary>
/// Persists the Host's long-term Ed25519 private key seed to disk, encrypted with DPAPI
/// (current-user scope) as specified in <c>Protocol/protocol-spec.md</c> §3 ("Windows: cifrada en
/// disco con DPAPI"). Only the current Windows user account (and only on this machine) can
/// decrypt the file — it is meaningless if copied elsewhere.
/// </summary>
public sealed class SecureKeyStore
{
    private readonly string _filePath;

    public SecureKeyStore(string? filePath = null)
    {
        _filePath = filePath ?? Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "ConnectQuick", "host_identity.bin");
    }

    public bool Exists() => File.Exists(_filePath);

    /// <summary>Returns the raw 32-byte Ed25519 seed, or null if none has been saved yet.</summary>
    public byte[]? TryLoadSeed()
    {
        if (!File.Exists(_filePath)) return null;
        var encrypted = File.ReadAllBytes(_filePath);
        return ProtectedData.Unprotect(encrypted, optionalEntropy: null, DataProtectionScope.CurrentUser);
    }

    public void SaveSeed(byte[] rawSeed32)
    {
        var dir = Path.GetDirectoryName(_filePath)!;
        Directory.CreateDirectory(dir);
        var encrypted = ProtectedData.Protect(rawSeed32, optionalEntropy: null, DataProtectionScope.CurrentUser);
        File.WriteAllBytes(_filePath, encrypted);
    }
}
