using System.Security.Cryptography;
using System.Text;
using NSec.Cryptography;

namespace ConnectQuick.WindowsHost.Crypto;

/// <summary>
/// Wire-format crypto primitives shared with the Backend (<c>Backend/src/crypto.ts</c>) and the
/// spec (<c>Protocol/protocol-spec.md</c> §3/§5, "Firma de la decisión de pairing"). Every
/// function here is pinned bit-for-bit by <c>Protocol/test-vectors/crypto-vectors.json</c> — see
/// <c>ConnectQuick.WindowsHost.Tests/CryptoVectorsTests.cs</c>. Do not change encoding, byte
/// order, or field order here without updating the vectors file and the other two platforms.
/// </summary>
public static class CryptoUtil
{
    /// <summary>Raw bytes -&gt; base64url, no padding — the wire format for every key/nonce/signature.</summary>
    public static string ToBase64Url(ReadOnlySpan<byte> bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    public static byte[] FromBase64Url(string s)
    {
        var padded = s.Replace('-', '+').Replace('_', '/');
        switch (padded.Length % 4)
        {
            case 2: padded += "=="; break;
            case 3: padded += "="; break;
        }
        return Convert.FromBase64String(padded);
    }

    public static byte[] Sha256(byte[] data) => SHA256.HashData(data);

    /// <summary>deviceId = base64url(SHA-256("connectquick:device:" || identityPub))[0:22]</summary>
    public static string DeriveDeviceId(string identityPubB64Url)
    {
        var raw = FromBase64Url(identityPubB64Url);
        var prefix = Encoding.UTF8.GetBytes("connectquick:device:");
        var digest = Sha256([.. prefix, .. raw]);
        return ToBase64Url(digest)[..22];
    }

    /// <summary>6-digit SAS shown on the Host's "¿Autorizar?" dialog for out-of-band comparison.</summary>
    public static string ComputeSas(string hostPubB64Url, string deviceEphemeralPubB64Url, string nonceB64Url)
    {
        var digest = Sha256([
            .. FromBase64Url(hostPubB64Url),
            .. FromBase64Url(deviceEphemeralPubB64Url),
            .. FromBase64Url(nonceB64Url),
        ]);
        // Same as the backend: big-endian uint32 of the first 4 bytes, mod 1_000_000.
        uint num = ((uint)digest[0] << 24) | ((uint)digest[1] << 16) | ((uint)digest[2] << 8) | digest[3];
        return (num % 1_000_000).ToString().PadLeft(6, '0');
    }

    /// <summary>
    /// Canonical bytes the Host signs over a pairing decision:
    /// <c>${pairingId}|${deviceId}|${approved?1:0}|${permissions.join(",")}</c> — must match
    /// <c>Backend/src/ws/handlers/util.ts#pairingDecisionMessage</c> exactly.
    /// </summary>
    public static byte[] PairingDecisionMessage(string pairingId, string deviceId, bool approved, IReadOnlyList<string> permissions) =>
        Encoding.UTF8.GetBytes($"{pairingId}|{deviceId}|{(approved ? 1 : 0)}|{string.Join(",", permissions)}");

    public static Key ImportEd25519PrivateKey(byte[] rawSeed32)
    {
        var creation = new KeyCreationParameters { ExportPolicy = KeyExportPolicies.AllowPlaintextExport };
        return Key.Import(SignatureAlgorithm.Ed25519, rawSeed32, KeyBlobFormat.RawPrivateKey, creation);
    }

    public static Key CreateEd25519Identity()
    {
        var creation = new KeyCreationParameters { ExportPolicy = KeyExportPolicies.AllowPlaintextExport };
        return Key.Create(SignatureAlgorithm.Ed25519, creation);
    }

    public static Key CreateX25519Ephemeral()
    {
        var creation = new KeyCreationParameters { ExportPolicy = KeyExportPolicies.AllowPlaintextExport };
        return Key.Create(KeyAgreementAlgorithm.X25519, creation);
    }

    public static string ExportPublicB64Url(Key key) =>
        ToBase64Url(key.PublicKey.Export(KeyBlobFormat.RawPublicKey));

    public static byte[] ExportPrivateSeed(Key key) => key.Export(KeyBlobFormat.RawPrivateKey);

    public static string SignEd25519(Key identityKey, byte[] message) =>
        ToBase64Url(SignatureAlgorithm.Ed25519.Sign(identityKey, message));

    public static bool VerifyEd25519(string pubB64Url, byte[] message, string signatureB64Url)
    {
        try
        {
            var raw = FromBase64Url(pubB64Url);
            if (raw.Length != 32) return false;
            var pub = PublicKey.Import(SignatureAlgorithm.Ed25519, raw, KeyBlobFormat.RawPublicKey);
            var sig = FromBase64Url(signatureB64Url);
            return SignatureAlgorithm.Ed25519.Verify(pub, message, sig);
        }
        catch
        {
            return false;
        }
    }
}
