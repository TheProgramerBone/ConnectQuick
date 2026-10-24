using System.Net.NetworkInformation;
using ConnectQuick.WindowsHost.Clipboard;
using ConnectQuick.WindowsHost.Config;
using ConnectQuick.WindowsHost.Diagnostics;
using ConnectQuick.WindowsHost.Files;
using ConnectQuick.WindowsHost.Identity;
using ConnectQuick.WindowsHost.Input;
using ConnectQuick.WindowsHost.Media;
using ConnectQuick.WindowsHost.Net;
using ConnectQuick.WindowsHost.Pairing;
using ConnectQuick.WindowsHost.Power;
using ConnectQuick.WindowsHost.Session;
using ConnectQuick.WindowsHost.Settings;
using ConnectQuick.WindowsHost.Streaming;

namespace ConnectQuick.WindowsHost.Runtime;

/// <summary>The operating-system adapters. Production wires the Win32 ones; tests and the harness wire in-memory ones.</summary>
public sealed record HostAdapters(IInputInjector Input, IClipboard Clipboard, IMediaKeys Media, IPowerExecutor Power);

public sealed class HostRuntimeOptions
{
    public required Uri RelayUri { get; init; }
    public required string HostName { get; init; }
    public required ISeedStore SeedStore { get; init; }
    public required HostAdapters Adapters { get; init; }
    public string? DevicesPath { get; init; }
    public string? SettingsPath { get; init; }
    public string? LogPath { get; init; }
    public IConnectionApprover? Approver { get; init; }
    /// <summary>Builds the WebRTC sender. Null leaves screen streaming unavailable (the phone is told so, nothing crashes).</summary>
    public IVideoPeerFactory? VideoPeers { get; init; }
    /// <summary>Test seams for partition detection; leave null in production.</summary>
    public TimeSpan? HeartbeatInterval { get; init; }
    public TimeSpan? DeadAfter { get; init; }
}

/// <summary>
/// Composition root of the Host, shared by the WinForms tray app and the headless integration
/// harness so both run exactly the same code. It owns the signalling client, the identity, pairing,
/// the session manager and every feature service, and exposes the few operations a UI needs.
/// </summary>
/// <summary>Used when no video pipeline was wired: asking for a stream fails with a clear reason.</summary>
internal sealed class UnavailableVideoPeers : IVideoPeerFactory
{
    public IVideoPeer Create(IceConfig ice, StreamProfile profile) => throw new NotSupportedException("no video pipeline is installed on this host");
}

public sealed class HostRuntime : IAsyncDisposable
{
    private readonly CancellationTokenSource _cts = new();
    private Task? _run;
    private Timer? _watchdog;

    public HostEventLog Log { get; }
    public HostSettingsStore Settings { get; }
    public DeviceIdentity Identity { get; }
    public AuthorizedDeviceStore Devices { get; }
    public BackendClient Client { get; }
    public HostConfig Config { get; }
    public PairingCoordinator Pairing { get; }
    public SessionManager Sessions { get; }

    public InputService Input { get; }
    public ClipboardService Clipboard { get; }
    public MediaService Media { get; }
    public FileTransferService Files { get; }
    public PowerService Power { get; }
    public StreamingService Streaming { get; }
    public IceConfigClient Ice { get; }

    public HostRuntime(HostRuntimeOptions o)
    {
        Log = new HostEventLog(o.LogPath);
        Settings = new HostSettingsStore(o.SettingsPath);
        Config = new HostConfig(o.RelayUri, o.HostName);
        Identity = DeviceIdentity.LoadOrCreate(o.SeedStore);
        Devices = new AuthorizedDeviceStore(o.DevicesPath);
        Client = new BackendClient(o.RelayUri);
        if (o.HeartbeatInterval is { } hb) Client.HeartbeatInterval = hb;
        if (o.DeadAfter is { } dead) Client.DeadAfter = dead;
        Pairing = new PairingCoordinator(Client, Identity, Config, Devices);

        Input = new InputService(o.Adapters.Input, Log);
        Clipboard = new ClipboardService(o.Adapters.Clipboard, Settings, Log);
        Media = new MediaService(o.Adapters.Media, Log);
        Files = new FileTransferService(Settings, Log);
        Power = new PowerService(o.Adapters.Power, Settings, Log);
        Ice = new IceConfigClient(Client);
        Streaming = new StreamingService(o.VideoPeers ?? new UnavailableVideoPeers(), token => Ice.GetAsync(token), Settings, Log);

        var features = new IHostFeature[] { new ControlFeature(), Input, Clipboard, Media, Files, Power, Streaming };
        Sessions = new SessionManager(Client, Identity, Devices, features, Settings, Log, o.Approver);

        Sessions.SessionStarted += _ => Client.SessionActive = true;
        Sessions.SessionEnded += (_, _) => Client.SessionActive = Sessions.Active is not null;

        Client.Faulted += msg => Log.Warn("signaling", msg);
        Client.StateChanged += s => Log.Info("signaling", s.ToString());
    }

    /// <summary>Starts the signalling loop (connect, authenticate, reconnect) and the idle-session watchdog.</summary>
    public void Start()
    {
        if (_run is null) NetworkChange.NetworkAddressChanged += OnNetworkAddressChanged;
        _run ??= Client.RunForeverAsync(_cts.Token);
        _watchdog ??= new Timer(_ => Sessions.Watchdog(), null, TimeSpan.FromSeconds(5), TimeSpan.FromSeconds(5));
    }

    private long _lastNetNotify;

    // Windows raises this in bursts when an adapter changes; reconnect once per burst.
    private void OnNetworkAddressChanged(object? sender, EventArgs e)
    {
        var now = Environment.TickCount64;
        if (now - Interlocked.Exchange(ref _lastNetNotify, now) < 3000) return;
        Log.Info("signaling", "network change detected; reconnecting");
        Client.NotifyNetworkChanged();
    }

    /// <summary>
    /// Revokes a phone: tells the backend, forgets it locally and cuts its live session at once,
    /// releasing any key it was holding. Safe to call when offline: the local trust is removed
    /// regardless, so the phone is refused on its next attempt even if the backend never hears.
    /// </summary>
    public async Task RevokeAsync(string deviceId)
    {
        Sessions.EndSessionsFor(deviceId, "revoked");
        Devices.Remove(deviceId);
        try { await Pairing.RevokeAsync(deviceId).ConfigureAwait(false); }
        catch (InvalidOperationException) { Log.Warn("pairing", "revoked locally; backend unreachable, it will refuse the device on next contact"); }
        Log.Info("pairing", $"device {deviceId[..Math.Min(6, deviceId.Length)]}... revoked");
    }

    public async ValueTask DisposeAsync()
    {
        NetworkChange.NetworkAddressChanged -= OnNetworkAddressChanged;
        _cts.Cancel();
        Sessions.EndActive("host_shutdown");
        if (_watchdog is not null) await _watchdog.DisposeAsync();
        if (_run is not null) { try { await _run.ConfigureAwait(false); } catch (OperationCanceledException) { } }
        await Client.DisposeAsync();
    }
}
