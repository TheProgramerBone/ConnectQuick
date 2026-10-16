namespace ConnectQuick.WindowsHost.Identity;

/// <summary>Where the Host's 32-byte Ed25519 seed lives between runs.</summary>
public interface ISeedStore
{
    byte[]? TryLoadSeed();
    void SaveSeed(byte[] rawSeed32);
}

/// <summary>
/// Non-persistent store for tests and the headless integration harness ONLY. The shipped app uses
/// <see cref="SecureKeyStore"/> (DPAPI); nothing in the production composition root references this.
/// </summary>
public sealed class InMemorySeedStore : ISeedStore
{
    private byte[]? _seed;
    public byte[]? TryLoadSeed() => _seed is null ? null : (byte[])_seed.Clone();
    public void SaveSeed(byte[] rawSeed32) => _seed = (byte[])rawSeed32.Clone();
}
