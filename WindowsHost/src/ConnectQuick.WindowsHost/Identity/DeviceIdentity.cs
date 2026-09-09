using ConnectQuick.WindowsHost.Crypto;
using NSec.Cryptography;

namespace ConnectQuick.WindowsHost.Identity;

/// <summary>
/// The Host's long-term Ed25519 identity: generated once on first run, then loaded from
/// <see cref="SecureKeyStore"/> on every subsequent start. <see cref="DeviceId"/> is a
/// self-certifying commitment to <see cref="IdentityPub"/> (Protocol §3) — nobody, including the
/// backend, can claim it without this private key.
/// </summary>
public sealed class DeviceIdentity
{
    public Key IdentityKey { get; }
    public string IdentityPub { get; }
    public string DeviceId { get; }

    private DeviceIdentity(Key identityKey)
    {
        IdentityKey = identityKey;
        IdentityPub = CryptoUtil.ExportPublicB64Url(identityKey);
        DeviceId = CryptoUtil.DeriveDeviceId(IdentityPub);
    }

    public static DeviceIdentity LoadOrCreate(SecureKeyStore store)
    {
        var seed = store.TryLoadSeed();
        Key key;
        if (seed != null)
        {
            key = CryptoUtil.ImportEd25519PrivateKey(seed);
        }
        else
        {
            key = CryptoUtil.CreateEd25519Identity();
            store.SaveSeed(CryptoUtil.ExportPrivateSeed(key));
        }
        return new DeviceIdentity(key);
    }

    public string Sign(byte[] message) => CryptoUtil.SignEd25519(IdentityKey, message);
}
