using ConnectQuick.WindowsHost.Diagnostics;
using ConnectQuick.WindowsHost.Net;
using ConnectQuick.WindowsHost.Pairing;
using ConnectQuick.WindowsHost.Runtime;
using ConnectQuick.WindowsHost.Session;

namespace ConnectQuick.WindowsHost.Ui;

/// <summary>
/// Asks the person at the PC, on the UI thread, whether a paired phone may connect right now.
/// Anything other than an explicit "Permitir" is a refusal.
/// </summary>
internal sealed class UiConnectionApprover : IConnectionApprover
{
    private Control? _ui;

    public void Attach(Control ui) => _ui = ui;

    public Task<bool> ApproveAsync(string deviceName, CancellationToken token)
    {
        var ui = _ui;
        if (ui is null || !ui.IsHandleCreated) return Task.FromResult(false);
        var result = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        ui.BeginInvoke(new Action(() =>
        {
            using var form = new SessionApprovalForm(deviceName);
            using var cancel = token.Register(() => { try { form.BeginInvoke(new Action(form.Close)); } catch (InvalidOperationException) { } });
            form.ShowDialog();
            result.TrySetResult(form.Allowed);
        }));
        return result.Task;
    }
}

/// <summary>
/// Owns the tray icon and every window. Runtime events arrive on background threads (WebSocket loops,
/// session pumps), so everything here marshals onto the UI thread before touching a control.
/// </summary>
internal sealed class TrayApplicationContext : ApplicationContext, IShellActions
{
    private readonly HostRuntime _rt;
    private readonly UiConnectionApprover _approver;
    private readonly NotifyIcon _tray;
    private readonly Control _ui;
    private readonly MainWindow _main;
    private readonly ToolStripMenuItem _statusItem = new() { Enabled = false };
    private readonly ToolStripMenuItem _pauseItem = new() { CheckOnClick = false };
    private readonly ToolStripMenuItem _disconnectItem = new("Desconectar el teléfono");

    private PairingWindow? _pairingWindow;
    private bool _connected;
    private LinkState _lastIcon = LinkState.Offline;
    private bool _firstHide = true;

    public TrayApplicationContext(HostRuntime rt, UiConnectionApprover approver)
    {
        _rt = rt;
        _approver = approver;

        Theme.Apply(rt.Settings.Current.Theme); // before any window exists, so nothing flashes in the wrong mode

        _ui = new Control();
        _ui.CreateControl();
        _approver.Attach(_ui);

        _main = new MainWindow(rt, this);
        _main.VisibleChanged += (_, _) => { if (!_main.Visible && _firstHide) { _firstHide = false; Notify("ConnectQuick sigue funcionando", "Está en la bandeja del sistema. Haz clic en el icono para abrirlo.", ToolTipIcon.Info); } };

        _tray = new NotifyIcon { Icon = TrayIcons.Render(LinkState.Connecting), Text = "ConnectQuick", Visible = true, ContextMenuStrip = BuildMenu() };
        _tray.MouseClick += (_, e) => { if (e.Button == MouseButtons.Left) _main.ShowPage(0); };

        _rt.Pairing.ConnectionChanged += connected => OnUi(() => { _connected = connected; UpdateTray(); });
        _rt.Client.StateChanged += _ => OnUi(UpdateTray);
        _rt.Pairing.PairingQrReady += qr => OnUi(() => ShowQr(qr));
        _rt.Pairing.IncomingRequest += request => OnUi(() => AskToAuthorize(request));
        _rt.Pairing.ErrorReceived += (code, message) => OnUi(() => Notify("ConnectQuick", FriendlyError(code, message), ToolTipIcon.Warning));
        _rt.Sessions.SessionStarted += s => OnUi(() => { UpdateTray(); Notify("Teléfono conectado", $"«{s.DeviceName}» está controlando este PC.", ToolTipIcon.Info); });
        _rt.Sessions.SessionEnded += (s, reason) => OnUi(() => { UpdateTray(); Notify("Sesión terminada", $"«{s.DeviceName}» se desconectó ({FriendlyReason(reason)}).", ToolTipIcon.Info); });
        _rt.Settings.Changed += s => OnUi(() => { Theme.Apply(s.Theme); UpdateTray(); });
        _rt.Client.Replaced += () => OnUi(() => Notify("ConnectQuick", "Otra copia de ConnectQuick con la misma identidad se conectó al servidor; esta se detuvo.", ToolTipIcon.Warning));
        _rt.Log.Added += entry =>
        {
            // Power actions executing on this PC must never be silent.
            if (entry is { Category: "power", Level: LogLevel.Warn } && entry.Message.Contains("executing", StringComparison.Ordinal))
                OnUi(() => Notify("Acción de energía", "Un teléfono autorizado ha ordenado " + entry.Message, ToolTipIcon.Warning));
        };

        UpdateTray();
    }

    // ---- IShellActions ----

    public LinkState CurrentState
    {
        get
        {
            if (!_rt.Settings.Current.AcceptConnections) return LinkState.Paused;
            if (_rt.Sessions.Active is not null) return LinkState.InSession;
            return _rt.Client.State switch
            {
                SignalingState.Ready => LinkState.Ready,
                SignalingState.Disconnected => LinkState.Offline,
                _ => LinkState.Connecting,
            };
        }
    }

    public async void StartPairing()
    {
        try { await _rt.Pairing.StartNewPairingAsync(); }
        catch (InvalidOperationException) { Notify("ConnectQuick", "Todavía no hay conexión con el servidor. Inténtalo en unos segundos.", ToolTipIcon.Warning); }
    }

    public void DisconnectPhone() => _rt.Sessions.EndActive("ended_by_user");

    public async Task RevokeAsync(AuthorizedDevice device) => await _rt.RevokeAsync(device.DeviceId);

    public bool Confirm(string title, string body, string confirmText, bool danger)
    {
        using var form = new ConfirmForm(title, body, confirmText, danger);
        return form.ShowDialog(_main.Visible ? _main : null) == DialogResult.OK;
    }

    // ---- tray ----

    private ContextMenuStrip BuildMenu()
    {
        var menu = new ContextMenuStrip();
        menu.Items.Add(_statusItem);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(new ToolStripMenuItem("Abrir ConnectQuick", null, (_, _) => _main.ShowPage(0)));
        menu.Items.Add(new ToolStripMenuItem("Vincular un teléfono…", null, (_, _) => StartPairing()));
        menu.Items.Add(new ToolStripMenuItem("Dispositivos vinculados", null, (_, _) => _main.ShowPage(1)));
        menu.Items.Add(new ToolStripMenuItem("Permisos y ajustes", null, (_, _) => _main.ShowPage(2)));
        menu.Items.Add(new ToolStripSeparator());
        _pauseItem.Click += (_, _) => _rt.Settings.Update(s => s.AcceptConnections = !s.AcceptConnections);
        menu.Items.Add(_pauseItem);
        _disconnectItem.Click += (_, _) => DisconnectPhone();
        menu.Items.Add(_disconnectItem);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(new ToolStripMenuItem("Salir", null, (_, _) => Quit()));
        menu.Opening += (_, _) => UpdateMenu();
        return menu;
    }

    private void UpdateMenu()
    {
        var state = CurrentState;
        _statusItem.Text = $"{_rt.Config.HostName} · {LinkStateInfo.Label(state)}";
        _pauseItem.Text = _rt.Settings.Current.AcceptConnections ? "Pausar conexiones" : "Reanudar conexiones";
        _disconnectItem.Visible = _rt.Sessions.Active is not null;
    }

    private void UpdateTray()
    {
        var state = CurrentState;
        if (state != _lastIcon)
        {
            _lastIcon = state;
            _tray.Icon = TrayIcons.Render(state);
        }
        var text = $"ConnectQuick · {LinkStateInfo.Label(state)}";
        _tray.Text = text.Length > 63 ? text[..63] : text; // NotifyIcon.Text is capped at 63 characters
        UpdateMenu();
    }

    private void ShowQr(QrPairingPayload payload)
    {
        if (_pairingWindow is { IsDisposed: false })
        {
            _pairingWindow.Render(payload);
            _pairingWindow.Activate();
            return;
        }
        _pairingWindow = new PairingWindow(payload);
        _pairingWindow.RenewRequested += StartPairing;
        _pairingWindow.FormClosed += (_, _) => _pairingWindow = null;
        _pairingWindow.Show();
    }

    private async void AskToAuthorize(IncomingPairingRequest request)
    {
        try
        {
            // The QR is single-use: show the 6-digit code in its window right away, then ask for consent on top of it.
            var owner = _pairingWindow is { IsDisposed: false } w ? w : null;
            owner?.ShowIncoming(request.DeviceName, request.SasCode);
            Notify("Solicitud de vinculación", $"«{request.DeviceName}» quiere vincularse. Compara el código {request.SasCode}.", ToolTipIcon.Info);

            using var dialog = new AuthorizeForm(request);
            dialog.ShowDialog(owner);

            if (dialog.Approved)
            {
                await _rt.Pairing.ApproveAsync(request, dialog.SelectedPermissions);
                _pairingWindow?.Close();
                Notify("Dispositivo autorizado", $"«{request.DeviceName}» ya puede conectarse.", ToolTipIcon.Info);
            }
            else
            {
                await _rt.Pairing.RejectAsync(request);
                if (_pairingWindow is { IsDisposed: false } rejected) rejected.ShowOutcome("Vinculación rechazada. Puedes generar un código nuevo.", offerNewCode: true);
            }
        }
        catch (InvalidOperationException)
        {
            if (_pairingWindow is { IsDisposed: false } lost) lost.ShowOutcome("Se perdió la conexión con el servidor antes de terminar.", offerNewCode: true);
            Notify("ConnectQuick", "Se perdió la conexión con el servidor antes de terminar. Vuelve a vincular.", ToolTipIcon.Warning);
        }
        catch (Exception ex)
        {
            _rt.Log.Warn("pairing", $"authorize dialog failed: {ex.GetType().Name}: {ex.Message}");
            Notify("ConnectQuick", "No se pudo completar la vinculación. Inténtalo de nuevo.", ToolTipIcon.Warning);
        }
    }

    private void Notify(string title, string text, ToolTipIcon icon)
    {
        if (_tray.Visible) _tray.ShowBalloonTip(5000, title, text, icon);
    }

    private void OnUi(Action action)
    {
        if (!_ui.IsHandleCreated || _ui.IsDisposed) return;
        try { _ui.BeginInvoke(action); }
        catch (InvalidOperationException) { /* shutting down */ }
    }

    private static string FriendlyReason(string reason) => reason switch
    {
        "ended_by_user" => "cortada desde este PC",
        "revoked" => "acceso revocado",
        "signaling_lost" => "se perdió la conexión con el servidor",
        "idle_timeout" => "sin actividad",
        "network_lost" => "red perdida",
        "host_shutdown" => "ConnectQuick se cerró",
        "protocol_violations" => "mensajes no válidos",
        "replaced" => "otra sesión la sustituyó",
        _ => "finalizada",
    };

    private static string FriendlyError(string code, string message) => code switch
    {
        "rate_limited" => "Demasiados intentos seguidos. Espera un momento.",
        "unauthorized" => "El servidor no reconoce este PC. Reinicia ConnectQuick.",
        _ => string.IsNullOrWhiteSpace(message) ? code : message,
    };

    public void Quit()
    {
        _main.QuitRequested = true;
        ExitThread();
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _tray.Visible = false;
            _tray.Dispose();
            _pairingWindow?.Dispose();
            _main.Dispose();
            _ui.Dispose();
        }
        base.Dispose(disposing);
    }
}
