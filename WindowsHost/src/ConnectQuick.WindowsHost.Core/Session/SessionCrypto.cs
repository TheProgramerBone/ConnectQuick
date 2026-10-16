using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;
using ConnectQuick.WindowsHost.Crypto;
using NSec.Cryptography;

namespace ConnectQuick.WindowsHost.Session;

public enum Direction
{
    ControllerToHost,
    HostToController,
}

/// <summary>
/// End-to-end session layer, byte-for-byte equal to <c>Backend/test-support/sessionCrypto.ts</c> and
/// pinned by <c>Protocol/test-vectors/session-vectors.json</c> (spec: protocol-spec.md section 9).
/// Primitives are the platform's maintained ones: X25519 (NSec/libsodium), HKDF-SHA256 and
/// AES-256-GCM (.NET BCL). Nothing here is a home-made algorithm.
/// </summary>
public static class SessionCrypto
{
    public const uint MaxSeq = uint.MaxValue;

    public static string ConnectMessage(string sessionId, string hostId, string deviceId, string controllerEphPub, long issuedAt) =>
        $"connectquick:session.connect:v1|{sessionId}|{hostId}|{deviceId}|{controllerEphPub}|{issuedAt}";

    public static string AcceptMessage(string sessionId, string hostId, string deviceId, string controllerEphPub, string hostEphPub, IReadOnlyList<string> permissions) =>
        $"connectquick:session.accept:v1|{sessionId}|{hostId}|{deviceId}|{controllerEphPub}|{hostEphPub}|{string.Join(",", permissions)}";

    public static byte[] TranscriptHash(string sessionId, string hostId, string deviceId, string controllerEphPub, string hostEphPub, IReadOnlyList<string> permissions) =>
        SHA256.HashData(Encoding.UTF8.GetBytes(
            $"connectquick:session.transcript:v1|{sessionId}|{hostId}|{deviceId}|{controllerEphPub}|{hostEphPub}|{string.Join(",", permissions)}"));

    /// <summary>X25519 shared secret. Rejects the all-zero output a low-order peer key would produce.</summary>
    public static byte[] SharedSecret(Key ownEphemeral, string peerEphPubB64Url)
    {
        var raw = CryptoUtil.FromBase64Url(peerEphPubB64Url);
        if (raw.Length != 32) throw new CryptographicException("peer ephemeral key must be 32 bytes");
        var peer = PublicKey.Import(KeyAgreementAlgorithm.X25519, raw, KeyBlobFormat.RawPublicKey);
        var parameters = new SharedSecretCreationParameters { ExportPolicy = KeyExportPolicies.AllowPlaintextExport };
        using var secret = KeyAgreementAlgorithm.X25519.Agree(ownEphemeral, peer, parameters)
            ?? throw new CryptographicException("degenerate X25519 shared secret");
        var bytes = secret.Export(SharedSecretBlobFormat.RawSharedSecret);
        if (bytes.All(b => b == 0)) throw new CryptographicException("degenerate X25519 shared secret");
        return bytes;
    }

    public static (byte[] C2H, byte[] H2C) DeriveKeys(byte[] sharedSecret, byte[] transcriptHash) =>
    (
        HKDF.DeriveKey(HashAlgorithmName.SHA256, sharedSecret, 32, transcriptHash, Encoding.UTF8.GetBytes("connectquick:session:v1:c2h")),
        HKDF.DeriveKey(HashAlgorithmName.SHA256, sharedSecret, 32, transcriptHash, Encoding.UTF8.GetBytes("connectquick:session:v1:h2c"))
    );

    public static string DirectionName(Direction d) => d == Direction.ControllerToHost ? "c2h" : "h2c";
}

/// <summary>
/// One side of an established session. Sequence numbers must strictly increase, so a replayed or
/// re-ordered frame is refused; the GCM tag binds the session id, direction and sequence.
/// </summary>
public sealed class SessionChannel : IDisposable
{
    private readonly string _sessionId;
    private readonly byte[] _sendKey;
    private readonly byte[] _recvKey;
    private readonly Direction _sendDir;
    private readonly Direction _recvDir;
    private readonly object _lock = new();
    private long _nextSendSeq;
    private long _lastRecvSeq = -1;

    public SessionChannel(string sessionId, byte[] sendKey, byte[] recvKey, Direction sendDir)
    {
        _sessionId = sessionId;
        _sendKey = sendKey;
        _recvKey = recvKey;
        _sendDir = sendDir;
        _recvDir = sendDir == Direction.ControllerToHost ? Direction.HostToController : Direction.ControllerToHost;
    }

    public static SessionChannel ForHost(string sessionId, (byte[] C2H, byte[] H2C) keys) =>
        new(sessionId, keys.H2C, keys.C2H, Direction.HostToController);

    public static SessionChannel ForController(string sessionId, (byte[] C2H, byte[] H2C) keys) =>
        new(sessionId, keys.C2H, keys.H2C, Direction.ControllerToHost);

    private static byte[] Nonce(long seq)
    {
        var nonce = new byte[12];
        BinaryPrimitives.WriteUInt64BigEndian(nonce.AsSpan(4), (ulong)seq);
        return nonce;
    }

    private static byte[] Aad(string sessionId, Direction dir, long seq) =>
        Encoding.UTF8.GetBytes($"cq1|{sessionId}|{SessionCrypto.DirectionName(dir)}|{seq}");

    /// <summary>Encrypts with the next sequence number (or an explicit one, for test vectors).</summary>
    public (long Seq, string Ct) Seal(ReadOnlySpan<byte> plaintext, long? explicitSeq = null)
    {
        long seq;
        lock (_lock)
        {
            seq = explicitSeq ?? _nextSendSeq;
            if (seq < 0 || seq > SessionCrypto.MaxSeq) throw new InvalidOperationException("sequence space exhausted: start a new session");
            if (explicitSeq is null) _nextSendSeq++;
        }
        var body = new byte[plaintext.Length];
        var tag = new byte[16];
        using var aes = new AesGcm(_sendKey, 16);
        aes.Encrypt(Nonce(seq), plaintext, body, tag, Aad(_sessionId, _sendDir, seq));
        return (seq, CryptoUtil.ToBase64Url([.. body, .. tag]));
    }

    /// <summary>Decrypts and authenticates; throws <see cref="CryptographicException"/> on any failure (replay, tamper, wrong direction).</summary>
    public byte[] Open(long seq, string ctB64Url)
    {
        if (seq < 0 || seq > SessionCrypto.MaxSeq) throw new CryptographicException("bad sequence number");
        lock (_lock)
        {
            if (seq <= _lastRecvSeq) throw new CryptographicException("replayed or reordered frame");
        }
        byte[] raw;
        try { raw = CryptoUtil.FromBase64Url(ctB64Url); }
        catch (FormatException) { throw new CryptographicException("malformed frame"); }
        if (raw.Length < 16) throw new CryptographicException("frame too short");
        var plain = new byte[raw.Length - 16];
        using var aes = new AesGcm(_recvKey, 16);
        aes.Decrypt(Nonce(seq), raw.AsSpan(0, raw.Length - 16), raw.AsSpan(raw.Length - 16), plain, Aad(_sessionId, _recvDir, seq));
        lock (_lock)
        {
            if (seq <= _lastRecvSeq) throw new CryptographicException("replayed or reordered frame");
            _lastRecvSeq = seq; // advance only after the tag verified
        }
        return plain;
    }

    public void Dispose()
    {
        CryptographicOperations.ZeroMemory(_sendKey);
        CryptographicOperations.ZeroMemory(_recvKey);
    }
}
