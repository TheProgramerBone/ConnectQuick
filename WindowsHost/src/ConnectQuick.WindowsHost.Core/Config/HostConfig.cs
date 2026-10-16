namespace ConnectQuick.WindowsHost.Config;

/// <summary>Runtime configuration — relay endpoint and display name, overridable via environment variables for local dev / testing.</summary>
public sealed class HostConfig
{
    public const string ClientVersion = "0.1.0";

    public Uri RelayUri { get; }
    public string HostName { get; }

    public HostConfig(Uri relayUri, string hostName)
    {
        RelayUri = relayUri;
        HostName = hostName;
    }

    public static HostConfig LoadDefault()
    {
        var relay = Environment.GetEnvironmentVariable("CONNECTQUICK_RELAY") ?? "ws://localhost:8443";
        var hostName = Environment.GetEnvironmentVariable("CONNECTQUICK_HOST_NAME");
        if (string.IsNullOrWhiteSpace(hostName)) hostName = Environment.MachineName;
        return new HostConfig(new Uri(relay), hostName);
    }
}
