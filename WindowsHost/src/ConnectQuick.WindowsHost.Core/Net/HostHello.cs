using ConnectQuick.WindowsHost.Config;
using ConnectQuick.WindowsHost.Crypto;
using ConnectQuick.WindowsHost.Identity;

namespace ConnectQuick.WindowsHost.Net;

/// <summary>Answers a relay's <c>hello.challenge</c> as this PC (signs it with the device identity). Used for the extra relays; the main one is answered by the pairing coordinator.</summary>
public sealed class HostHello : IHelloAuthenticator
{
    private readonly DeviceIdentity _identity;
    private readonly HostConfig _config;

    public HostHello(DeviceIdentity identity, HostConfig config)
    {
        _identity = identity;
        _config = config;
    }

    public Dictionary<string, object?> BuildHello(string serverChallengeB64Url) => new()
    {
        ["role"] = "host",
        ["deviceId"] = _identity.DeviceId,
        ["identityPub"] = _identity.IdentityPub,
        ["challengeSig"] = _identity.Sign(CryptoUtil.FromBase64Url(serverChallengeB64Url)),
        ["clientVersion"] = HostConfig.ClientVersion,
        ["hostName"] = _config.HostName,
    };
}
