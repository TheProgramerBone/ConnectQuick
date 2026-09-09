using ConnectQuick.WindowsHost.Config;
using ConnectQuick.WindowsHost.Identity;
using ConnectQuick.WindowsHost.Pairing;

namespace ConnectQuick.WindowsHost.Ui;

/// <summary>
/// Owns the tray icon and every window this app shows. Coordinator events arrive on background
/// threads (WebSocket receive loop), so everything here marshals onto the UI thread via
/// <see cref="_uiSync"/> before touching WinForms controls.
/// </summary>
public sealed class TrayApplicationContext : ApplicationContext
{
    private readonly PairingCoordinator _coordinator;
    private readonly AuthorizedDeviceStore _deviceStore;
    private readonly HostConfig _config;
    private readonly DeviceIdentity _identity;
    private readonly NotifyIcon _trayIcon;
    private readonly Control _uiSync; // invisible control purely to get an Invoke/BeginInvoke onto the UI thread

    private PairingWindow? _pairingWindow;

    public TrayApplicationContext(PairingCoordinator coordinator, AuthorizedDeviceStore deviceStore, HostConfig config, DeviceIdentity identity)
    {
        _coordinator = coordinator;
        _deviceStore = deviceStore;
        _config = config;
        _identity = identity;

        _uiSync = new Control();
        _uiSync.CreateControl();

        _trayIcon = new NotifyIcon
        {
            Icon = SystemIcons.Application,
            Text = $"ConnectQuick — {_config.HostName} (conectando...)",
            Visible = true,
            ContextMenuStrip = BuildMenu(),
        };
        _trayIcon.DoubleClick += (_, _) => OnLinkNewDevice();

        _coordinator.ConnectionChanged += OnConnectionChanged;
        _coordinator.PairingQrReady += OnPairingQrReady;
        _coordinator.IncomingRequest += OnIncomingRequest;
        _coordinator.ErrorReceived += OnErrorReceived;
    }

    private ContextMenuStrip BuildMenu()
    {
        var menu = new ContextMenuStrip();
        menu.Items.Add(new ToolStripMenuItem($"ConnectQuick — {_config.HostName}") { Enabled = false });
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(new ToolStripMenuItem("Vincular nuevo dispositivo", null, (_, _) => OnLinkNewDevice()));
        var devicesItem = new ToolStripMenuItem("Dispositivos vinculados");
        devicesItem.DropDownOpening += (_, _) => RefreshDevicesMenu(devicesItem);
        menu.Items.Add(devicesItem);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(new ToolStripMenuItem("Salir", null, (_, _) => ExitThread()));
        return menu;
    }

    private void RefreshDevicesMenu(ToolStripMenuItem devicesItem)
    {
        devicesItem.DropDownItems.Clear();
        var devices = _deviceStore.All;
        if (devices.Count == 0)
        {
            devicesItem.DropDownItems.Add(new ToolStripMenuItem("(ninguno todavía)") { Enabled = false });
            return;
        }
        foreach (var device in devices)
        {
            var item = new ToolStripMenuItem(device.DeviceName);
            item.DropDownItems.Add(new ToolStripMenuItem("Revocar acceso", null, async (_, _) => await _coordinator.RevokeAsync(device.DeviceId)));
            devicesItem.DropDownItems.Add(item);
        }
    }

    private async void OnLinkNewDevice()
    {
        try
        {
            await _coordinator.StartNewPairingAsync();
        }
        catch (InvalidOperationException)
        {
            _trayIcon.ShowBalloonTip(4000, "ConnectQuick", "No hay conexión con el servidor todavía. Inténtalo de nuevo en unos segundos.", ToolTipIcon.Warning);
        }
    }

    private void OnConnectionChanged(bool connected)
    {
        _uiSync.BeginInvoke((MethodInvoker)(() =>
        {
            _trayIcon.Text = connected
                ? $"ConnectQuick — {_config.HostName} (conectado)"
                : $"ConnectQuick — {_config.HostName} (reconectando...)";
        }));
    }

    private void OnPairingQrReady(QrPairingPayload payload)
    {
        _uiSync.BeginInvoke((MethodInvoker)(() =>
        {
            if (_pairingWindow is { IsDisposed: false })
            {
                _pairingWindow.Render(payload);
                _pairingWindow.Activate();
                return;
            }
            _pairingWindow = new PairingWindow(payload);
            _pairingWindow.FormClosed += (_, _) => _pairingWindow = null;
            _pairingWindow.Show();
        }));
    }

    private void OnIncomingRequest(IncomingPairingRequest request)
    {
        _uiSync.BeginInvoke((MethodInvoker)(async () =>
        {
            using var dialog = new AuthorizeForm(request);
            dialog.ShowDialog();
            if (dialog.Approved)
            {
                await _coordinator.ApproveAsync(request);
                _trayIcon.ShowBalloonTip(4000, "ConnectQuick", $"\"{request.DeviceName}\" ha sido autorizado.", ToolTipIcon.Info);
            }
            else
            {
                await _coordinator.RejectAsync(request);
            }
        }));
    }

    private void OnErrorReceived(string code, string message)
    {
        _uiSync.BeginInvoke((MethodInvoker)(() =>
            _trayIcon.ShowBalloonTip(4000, "ConnectQuick", $"{code}: {message}", ToolTipIcon.Error)));
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _trayIcon.Visible = false;
            _trayIcon.Dispose();
            _uiSync.Dispose();
        }
        base.Dispose(disposing);
    }
}
