using System.Runtime.CompilerServices;
using System.Text;
using System.Text.Json;
using ConnectQuick.WindowsHost.Crypto;

namespace ConnectQuick.WindowsHost.Tests;

/// <summary>
/// Asserts the C# crypto layer reproduces <c>Protocol/test-vectors/crypto-vectors.json</c>
/// bit-for-bit, exactly like <c>Backend/test/unit/testVectors.test.ts</c> does for the
/// TypeScript side (see that file's README: "any implementation... must reproduce every
/// expected*/signatureB64Url field exactly"). This is what makes it safe to claim the
/// WindowsHost's pairing handshake is wire-compatible with the real backend, not just "compiles".
/// </summary>
public sealed class CryptoVectorsTests
{
    private static readonly JsonElement Vectors = LoadVectors();

    private static JsonElement LoadVectors([CallerFilePath] string thisFile = "")
    {
        var dir = Path.GetDirectoryName(thisFile)!;
        var path = Path.GetFullPath(Path.Combine(dir, "..", "..", "..", "Protocol", "test-vectors", "crypto-vectors.json"));
        using var doc = JsonDocument.Parse(File.ReadAllText(path));
        return doc.RootElement.Clone();
    }

    private static string S(JsonElement e, string prop) => e.GetProperty(prop).GetString()!;

    [Fact]
    public void DeriveDeviceId_matches_named_identities()
    {
        var host = Vectors.GetProperty("identities").GetProperty("host");
        var controller = Vectors.GetProperty("identities").GetProperty("controller");

        Assert.Equal(S(host, "expectedDeviceId"), CryptoUtil.DeriveDeviceId(S(host, "publicKeyB64Url")));
        Assert.Equal(S(controller, "expectedDeviceId"), CryptoUtil.DeriveDeviceId(S(controller, "publicKeyB64Url")));
    }

    [Fact]
    public void DeriveDeviceId_matches_all_listed_cases()
    {
        foreach (var c in Vectors.GetProperty("deriveDeviceId").GetProperty("cases").EnumerateArray())
        {
            Assert.Equal(S(c, "expected"), CryptoUtil.DeriveDeviceId(S(c, "identityPub")));
        }
    }

    [Fact]
    public void ChallengeSignature_matches_for_host_and_controller()
    {
        var v = Vectors.GetProperty("challengeSignature");
        var challenge = CryptoUtil.FromBase64Url(S(v, "serverChallengeB64Url"));

        var host = Vectors.GetProperty("identities").GetProperty("host");
        using var hostKey = CryptoUtil.ImportEd25519PrivateKey(CryptoUtil.FromBase64Url(S(host, "privateJwkD")));
        Assert.Equal(S(v, "hostSignatureB64Url"), CryptoUtil.SignEd25519(hostKey, challenge));

        var controller = Vectors.GetProperty("identities").GetProperty("controller");
        using var controllerKey = CryptoUtil.ImportEd25519PrivateKey(CryptoUtil.FromBase64Url(S(controller, "privateJwkD")));
        Assert.Equal(S(v, "controllerSignatureB64Url"), CryptoUtil.SignEd25519(controllerKey, challenge));

        // A verifier (the backend's role) must also accept these signatures over the public keys alone.
        Assert.True(CryptoUtil.VerifyEd25519(S(host, "publicKeyB64Url"), challenge, S(v, "hostSignatureB64Url")));
        Assert.True(CryptoUtil.VerifyEd25519(S(controller, "publicKeyB64Url"), challenge, S(v, "controllerSignatureB64Url")));
    }

    [Fact]
    public void ComputeSas_matches_vector()
    {
        var v = Vectors.GetProperty("sas");
        var sas = CryptoUtil.ComputeSas(S(v, "hostPub"), S(v, "deviceEphemeralPub"), S(v, "nonceB64Url"));
        Assert.Equal(S(v, "expectedSasCode"), sas);
    }

    [Theory]
    [InlineData("approve")]
    [InlineData("reject")]
    public void PairingDecisionSignature_matches_vector(string caseName)
    {
        var root = Vectors.GetProperty("pairingDecisionSignature");
        var pairingId = S(root, "pairingId");
        var deviceId = S(root, "deviceId");
        var c = root.GetProperty(caseName);

        var approved = c.GetProperty("approved").GetBoolean();
        var permissions = c.GetProperty("permissions").EnumerateArray().Select(p => p.GetString()!).ToArray();

        var message = CryptoUtil.PairingDecisionMessage(pairingId, deviceId, approved, permissions);
        Assert.Equal(S(c, "signedMessage"), Encoding.UTF8.GetString(message));

        var host = Vectors.GetProperty("identities").GetProperty("host");
        using var hostKey = CryptoUtil.ImportEd25519PrivateKey(CryptoUtil.FromBase64Url(S(host, "privateJwkD")));
        Assert.Equal(S(c, "signatureB64Url"), CryptoUtil.SignEd25519(hostKey, message));
    }
}
