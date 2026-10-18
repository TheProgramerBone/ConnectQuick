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
        var relay = Environment.GetEnvironmentVariable("CONNECTQUICK_RELAY");
        if (string.IsNullOrWhiteSpace(relay)) relay = ReadRelayFile() ?? "ws://localhost:8443";
        var hostName = Environment.GetEnvironmentVariable("CONNECTQUICK_HOST_NAME");
        if (string.IsNullOrWhiteSpace(hostName)) hostName = Environment.MachineName;
        return new HostConfig(new Uri(relay), hostName);
    }

    /// <summary>First line of <c>relay.txt</c> next to the executable (written by the installer); null if absent or empty.</summary>
    private static string? ReadRelayFile()
    {
        try
        {
            var path = Path.Combine(AppContext.BaseDirectory, "relay.txt");
            if (!File.Exists(path)) return null;
            var line = File.ReadLines(path).FirstOrDefault()?.Trim();
            return string.IsNullOrWhiteSpace(line) ? null : line;
        }
        catch (IOException)
        {
            return null;
        }
    }
}
