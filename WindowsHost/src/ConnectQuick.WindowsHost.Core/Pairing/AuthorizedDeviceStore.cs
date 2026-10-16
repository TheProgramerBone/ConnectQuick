using System.Text.Json;

namespace ConnectQuick.WindowsHost.Pairing;

public sealed record AuthorizedDevice(string DeviceId, string DeviceName, IReadOnlyList<string> Permissions, long AuthorizedAtMs);

/// <summary>
/// Local record of devices this Host has approved, so the tray "Dispositivos vinculados" menu has
/// something to show and revoke. The backend is the source of truth for whether a device is
/// actually authorized (<c>authorized_devices</c> table) — this file is a convenience mirror for
/// the UI, not a security boundary; there is no protocol message yet to query the backend's list
/// (see <c>Protocol/protocol-spec.md</c> §7, reserved for future phases), so we track locally the
/// devices *we* approved through this app instance.
/// </summary>
public sealed class AuthorizedDeviceStore
{
    private readonly string _filePath;
    private readonly List<AuthorizedDevice> _devices;

    public AuthorizedDeviceStore(string? filePath = null)
    {
        _filePath = filePath ?? Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "ConnectQuick", "authorized_devices.json");
        _devices = Load();
    }

    public IReadOnlyList<AuthorizedDevice> All => _devices;

    private List<AuthorizedDevice> Load()
    {
        if (!File.Exists(_filePath)) return [];
        try
        {
            var json = File.ReadAllText(_filePath);
            return JsonSerializer.Deserialize<List<AuthorizedDevice>>(json) ?? [];
        }
        catch
        {
            return [];
        }
    }

    private void Save()
    {
        var dir = Path.GetDirectoryName(_filePath)!;
        Directory.CreateDirectory(dir);
        File.WriteAllText(_filePath, JsonSerializer.Serialize(_devices));
    }

    public void Add(AuthorizedDevice device)
    {
        _devices.RemoveAll(d => d.DeviceId == device.DeviceId);
        _devices.Add(device);
        Save();
    }

    public void Remove(string deviceId)
    {
        _devices.RemoveAll(d => d.DeviceId == deviceId);
        Save();
    }
}
