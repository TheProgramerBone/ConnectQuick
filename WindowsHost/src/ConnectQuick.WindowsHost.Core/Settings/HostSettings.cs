using System.Text.Json;

namespace ConnectQuick.WindowsHost.Settings;

public enum PowerPolicy
{
    /// <summary>No remote power actions at all.</summary>
    Disabled,
    /// <summary>Only "lock the session". Safe default: it destroys no work.</summary>
    LockOnly,
    /// <summary>Lock, sleep, restart and shut down (the last three still need an explicit confirmation round trip).</summary>
    Full,
}

/// <summary>
/// Host-side policy. Effective access for a session is the intersection of what the user granted
/// the device at pairing time and what is enabled here, so turning a switch off takes effect on
/// the next message without re-pairing. Defaults are the conservative ones.
/// </summary>
public sealed class HostSettings
{
    public bool AcceptConnections { get; set; } = true;
    /// <summary>Appearance of the app: "dark" (default) or "light".</summary>
    public string Theme { get; set; } = "dark";
    /// <summary>Ask on the PC every time a paired phone connects (in addition to pairing consent).</summary>
    public bool RequireApprovalPerSession { get; set; } = false;

    public bool AllowMouse { get; set; } = true;
    public bool AllowKeyboard { get; set; } = true;
    public bool AllowScreen { get; set; } = true;
    public bool AllowClipboard { get; set; } = true;
    /// <summary>Push local clipboard changes to the phone without being asked. Off: the phone must pull.</summary>
    public bool ClipboardAutoPushToController { get; set; } = false;
    public bool AllowMedia { get; set; } = true;
    public bool AllowFiles { get; set; } = true;
    public PowerPolicy Power { get; set; } = PowerPolicy.LockOnly;

    public string? InboxDirectory { get; set; }
    public string? OutboxDirectory { get; set; }
    public long MaxFileBytes { get; set; } = 2L * 1024 * 1024 * 1024;

    public int MaxStreamWidth { get; set; } = 1920;
    public int MaxStreamHeight { get; set; } = 1080;
    public int MaxStreamFps { get; set; } = 30;
    public int MaxStreamBitrateKbps { get; set; } = 8000;

    public string DefaultInbox() =>
        InboxDirectory ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "ConnectQuick");

    public string DefaultOutbox() =>
        OutboxDirectory ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "ConnectQuick", "Outbox");

    /// <summary>Pulls hand-edited or corrupted values back into safe ranges.</summary>
    public void Normalize()
    {
        MaxFileBytes = Math.Clamp(MaxFileBytes, 1024, 64L * 1024 * 1024 * 1024);
        MaxStreamWidth = Math.Clamp(MaxStreamWidth, 160, 7680);
        MaxStreamHeight = Math.Clamp(MaxStreamHeight, 120, 4320);
        MaxStreamFps = Math.Clamp(MaxStreamFps, 1, 60);
        MaxStreamBitrateKbps = Math.Clamp(MaxStreamBitrateKbps, 100, 50_000);
        if (!Enum.IsDefined(Power)) Power = PowerPolicy.LockOnly;
        Theme = string.Equals(Theme, "light", StringComparison.OrdinalIgnoreCase) ? "light" : "dark";
    }

    public bool PermissionEnabled(string permission) => permission switch
    {
        "mouse" => AllowMouse,
        "keyboard" => AllowKeyboard,
        "remote_desktop" => AllowScreen,
        "clipboard" => AllowClipboard,
        "media" => AllowMedia,
        "files" => AllowFiles,
        "power" => Power != PowerPolicy.Disabled,
        _ => false, // "apps" has no implementation, so it is never effective
    };
}

/// <summary>Loads/saves <see cref="HostSettings"/> atomically; a corrupt file falls back to defaults.</summary>
public sealed class HostSettingsStore
{
    private static readonly JsonSerializerOptions Options = new() { WriteIndented = true };
    private readonly string _path;
    private readonly object _lock = new();

    public HostSettings Current { get; private set; }
    public event Action<HostSettings>? Changed;

    public HostSettingsStore(string? path = null)
    {
        _path = path ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "ConnectQuick", "settings.json");
        Current = Load();
    }

    private HostSettings Load()
    {
        try
        {
            if (File.Exists(_path))
            {
                var s = JsonSerializer.Deserialize<HostSettings>(File.ReadAllText(_path)) ?? new HostSettings();
                s.Normalize();
                return s;
            }
        }
        catch (Exception ex) when (ex is JsonException or IOException or UnauthorizedAccessException)
        {
            // fall through to defaults: a broken settings file must not stop the app starting
        }
        return new HostSettings();
    }

    public void Update(Action<HostSettings> mutate)
    {
        lock (_lock)
        {
            mutate(Current);
            Current.Normalize();
            Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
            var tmp = _path + ".tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(Current, Options));
            File.Move(tmp, _path, true);
        }
        Changed?.Invoke(Current);
    }
}
