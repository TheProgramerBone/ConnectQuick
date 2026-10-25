namespace ConnectQuick.WindowsHost.Config;

/// <summary>Runtime configuration — relay endpoint and display name, overridable via environment variables for local dev / testing.</summary>
public sealed class HostConfig
{
    public const string ClientVersion = "0.1.0";

    /// <summary>Placeholder in an extra relay address for "this PC's address on the local network".</summary>
    public const string LanToken = "{lan}";

    public Uri RelayUri { get; }
    public string HostName { get; }

    /// <summary>
    /// More relays this PC serves besides <see cref="RelayUri"/>, as written in <c>relay.txt</c> (lines 2+) or
    /// <c>CONNECTQUICK_EXTRA_RELAYS</c> (comma separated), e.g. <c>ws://{lan}:8443</c> for the PC's own LAN relay.
    /// </summary>
    public IReadOnlyList<string> ExtraRelays { get; }

    public HostConfig(Uri relayUri, string hostName, IReadOnlyList<string>? extraRelays = null)
    {
        RelayUri = relayUri;
        HostName = hostName;
        ExtraRelays = extraRelays ?? [];
    }

    /// <summary>Where the PC itself connects to each extra relay: {lan} means this machine, so loopback. Duplicates of the main relay are dropped.</summary>
    public IReadOnlyList<Uri> HostExtraRelayUris => ExtraRelays
        .Select(r => TryUri(r.Replace(LanToken, "127.0.0.1")))
        .OfType<Uri>()
        .Where(u => u != RelayUri)
        .DistinctBy(u => u.ToString())
        .ToList();

    /// <summary>
    /// What the QR hands the phone, best first: the LAN relay (fast, works without Internet) before the main one.
    /// Null when there are no extras, so the QR stays exactly as before.
    /// </summary>
    public IReadOnlyList<string>? PhoneRelays
    {
        get
        {
            var lan = LanAddress();
            var extras = ExtraRelays
                .Where(r => !r.Contains(LanToken) || lan is not null)
                .Select(r => TryUri(r.Replace(LanToken, lan ?? "")))
                .OfType<Uri>()
                .Where(u => u != RelayUri)
                .Select(u => u.ToString().TrimEnd('/'))
                .Distinct()
                .ToList();
            if (extras.Count == 0) return null;
            extras.Add(RelayUri.ToString().TrimEnd('/'));
            return extras;
        }
    }

    private static Uri? TryUri(string s) =>
        Uri.TryCreate(s.Trim(), UriKind.Absolute, out var u) && (u.Scheme == "ws" || u.Scheme == "wss") ? u : null;

    /// <summary>First private IPv4 address of an active, non-virtual adapter (what a phone on the same Wi-Fi can reach).</summary>
    public static string? LanAddress()
    {
        try
        {
            foreach (var nic in System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces())
            {
                if (nic.OperationalStatus != System.Net.NetworkInformation.OperationalStatus.Up) continue;
                if (nic.NetworkInterfaceType is System.Net.NetworkInformation.NetworkInterfaceType.Loopback
                    or System.Net.NetworkInformation.NetworkInterfaceType.Tunnel) continue;
                foreach (var ua in nic.GetIPProperties().UnicastAddresses)
                {
                    if (ua.Address.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork) continue;
                    var b = ua.Address.GetAddressBytes();
                    var isPrivate = b[0] == 10 || (b[0] == 172 && b[1] is >= 16 and <= 31) || (b[0] == 192 && b[1] == 168);
                    if (isPrivate) return ua.Address.ToString();
                }
            }
        }
        catch (System.Net.NetworkInformation.NetworkInformationException) { }
        return null;
    }

    public static HostConfig LoadDefault()
    {
        var relay = Environment.GetEnvironmentVariable("CONNECTQUICK_RELAY");
        var fileLines = ReadRelayFile();
        if (string.IsNullOrWhiteSpace(relay)) relay = fileLines.FirstOrDefault() ?? "ws://localhost:8443";
        var extraEnv = Environment.GetEnvironmentVariable("CONNECTQUICK_EXTRA_RELAYS");
        var extras = !string.IsNullOrWhiteSpace(extraEnv)
            ? extraEnv.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).ToList()
            : fileLines.Skip(1).ToList();
        var hostName = Environment.GetEnvironmentVariable("CONNECTQUICK_HOST_NAME");
        if (string.IsNullOrWhiteSpace(hostName)) hostName = Environment.MachineName;
        return new HostConfig(new Uri(relay), hostName, extras);
    }

    /// <summary>Non-empty lines of <c>relay.txt</c> next to the executable (written by the installer): line 1 is the main relay, the rest are extra relays.</summary>
    private static List<string> ReadRelayFile()
    {
        try
        {
            var path = Path.Combine(AppContext.BaseDirectory, "relay.txt");
            if (!File.Exists(path)) return [];
            return File.ReadLines(path).Select(l => l.Trim()).Where(l => l.Length > 0 && !l.StartsWith('#')).ToList();
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return [];
        }
    }
}
