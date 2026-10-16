using ConnectQuick.WindowsHost.Pairing;
using QRCoder;

namespace ConnectQuick.WindowsHost.Ui;

/// <summary>Spanish names and one-line explanations of each permission, shared by every screen so wording never drifts.</summary>
internal static class PermissionText
{
    public static string Name(string permission) => permission switch
    {
        "remote_desktop" => "Ver la pantalla",
        "mouse" => "Controlar el ratón",
        "keyboard" => "Escribir con el teclado",
        "clipboard" => "Compartir el portapapeles",
        "files" => "Transferir archivos",
        "media" => "Multimedia y volumen",
        "power" => "Bloquear, suspender y apagar",
        _ => permission,
    };

    public static string Hint(string permission) => permission switch
    {
        "remote_desktop" => "El teléfono ve lo que hay en tu pantalla.",
        "mouse" => "Mueve el puntero y hace clic.",
        "keyboard" => "Teclea texto y atajos en este PC.",
        "clipboard" => "Copiar y pegar entre el PC y el teléfono.",
        "files" => "Enviar y recibir archivos. Solo en carpetas de ConnectQuick.",
        "media" => "Reproducir, pausar, siguiente y volumen.",
        "power" => "Útil, pero delicado: apagar puede perder trabajo sin guardar.",
        _ => "",
    };

    public static string Summary(IEnumerable<string> permissions)
    {
        var names = PairingPermissions.Canonical(permissions).Select(Name).ToList();
        return names.Count == 0 ? "Sin permisos" : string.Join(" · ", names);
    }
}

/// <summary>Base for every window: glass backdrop, theme fonts, per-monitor DPI, the app icon.</summary>
internal class GlassForm : Form
{
    public GlassForm()
    {
        SetStyle(ControlStyles.OptimizedDoubleBuffer | ControlStyles.AllPaintingInWmPaint | ControlStyles.UserPaint | ControlStyles.ResizeRedraw, true);
        AutoScaleDimensions = new SizeF(96F, 96F);
        AutoScaleMode = AutoScaleMode.Dpi;
        BackColor = Theme.Bg0;
        ForeColor = Theme.Text;
        Font = Theme.Body(10f);
        StartPosition = FormStartPosition.CenterScreen;
        Icon = TrayIcons.Render(LinkState.Ready);
        ShowIcon = true;
    }

    protected override void OnPaintBackground(PaintEventArgs e) => Theme.PaintBackdrop(e.Graphics, ClientRectangle);

    protected override void OnSystemColorsChanged(EventArgs e)
    {
        // High Contrast can be switched on while the window is open.
        BackColor = Theme.Bg0;
        ForeColor = Theme.Text;
        Invalidate(true);
        base.OnSystemColorsChanged(e);
    }
}

/// <summary>
/// "Autorizar" dialog (Protocol §7 step 5): who is asking, the code to compare with the phone, and the
/// permissions the person at the PC is willing to grant. Defaults to the conservative set and never
/// pre-selects remote power control. Enter does NOT approve: the safe button has the initial focus.
/// </summary>
internal sealed class AuthorizeForm : GlassForm
{
    private readonly Dictionary<string, ToggleSwitch> _toggles = new();

    public bool Approved { get; private set; }

    public IReadOnlyList<string> SelectedPermissions =>
        PairingPermissions.Offerable.Where(p => _toggles.TryGetValue(p, out var t) && t.Checked).ToList();

    public AuthorizeForm(IncomingPairingRequest request)
    {
        Text = "ConnectQuick · Solicitud de vinculación";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        ShowInTaskbar = true;
        TopMost = true;
        ClientSize = new Size(520, 700);

        var card = new GlassPanel { Dock = DockStyle.Fill, Margin = new Padding(0) };
        var host = new Panel { Dock = DockStyle.Fill, Padding = new Padding(20), BackColor = Color.Transparent };
        host.Controls.Add(card);
        Controls.Add(host);

        var buttons = new TableLayoutPanel { Dock = DockStyle.Bottom, Height = 56, ColumnCount = 2, BackColor = Color.Transparent };
        buttons.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        buttons.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        var reject = new GlassButton { Text = "Rechazar", Dock = DockStyle.Fill, Margin = new Padding(0, 8, 6, 0), AccessibleName = "Rechazar la vinculación", AccessibleDescription = "No permite que este dispositivo se vincule" };
        var approve = new GlassButton { Text = "Autorizar", Style = GlassButtonStyle.Primary, Dock = DockStyle.Fill, Margin = new Padding(6, 8, 0, 0), AccessibleName = "Autorizar la vinculación con los permisos marcados" };
        reject.Click += (_, _) => { Approved = false; DialogResult = DialogResult.No; Close(); };
        approve.Click += (_, _) => { Approved = true; DialogResult = DialogResult.Yes; Close(); };
        buttons.Controls.Add(reject, 0, 0);
        buttons.Controls.Add(approve, 1, 0);
        CancelButton = reject;

        var list = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, WrapContents = false, AutoScroll = true, BackColor = Color.Transparent };
        foreach (var permission in PairingPermissions.Offerable)
        {
            var toggle = new ToggleSwitch
            {
                Text = PermissionText.Name(permission),
                Hint = PermissionText.Hint(permission),
                Checked = PairingPermissions.Recommended.Contains(permission),
                Width = 440,
                AccessibleName = PermissionText.Name(permission),
                AccessibleDescription = PermissionText.Hint(permission),
            };
            _toggles[permission] = toggle;
            list.Controls.Add(toggle);
        }

        var sas = new Label
        {
            Text = request.SasCode.Length == 6 ? $"{request.SasCode[..3]} {request.SasCode[3..]}" : request.SasCode,
            Dock = DockStyle.Top,
            Height = 72,
            Font = Theme.Title(34f),
            ForeColor = Theme.Accent,
            BackColor = Color.Transparent,
            TextAlign = ContentAlignment.MiddleCenter,
            AccessibleName = "Código de comparación " + string.Join(" ", request.SasCode.ToCharArray()),
        };
        var ask = new ThemedLabel("¿Es el mismo código que ves en el teléfono? Si no coincide, rechaza.", ThemedLabel.Kind.Body) { Dock = DockStyle.Top, Height = 46, TextAlign = ContentAlignment.MiddleCenter };
        var name = new ThemedLabel($"“{request.DeviceName}” quiere controlar este PC", ThemedLabel.Kind.Heading) { Dock = DockStyle.Top, Height = 56, TextAlign = ContentAlignment.MiddleCenter, Font = Theme.Strong(13f) };
        var perms = new ThemedLabel("Permisos que concedes (puedes cambiarlos o revocar cuando quieras):", ThemedLabel.Kind.Dim) { Dock = DockStyle.Top, Height = 34, TextAlign = ContentAlignment.BottomLeft };

        card.Controls.Add(list);
        card.Controls.Add(perms);
        card.Controls.Add(ask);
        card.Controls.Add(sas);
        card.Controls.Add(name);
        card.Controls.Add(buttons);
        Shown += (_, _) => reject.Focus();
    }
}

/// <summary>
/// Asks, each time a paired phone connects (when the user turned that option on), whether to let it in.
/// Times out as a refusal; closing the window is a refusal too.
/// </summary>
internal sealed class SessionApprovalForm : GlassForm
{
    private readonly System.Windows.Forms.Timer _tick = new() { Interval = 1000 };
    private readonly ThemedLabel _countdown;
    private int _remaining = 25;

    public bool Allowed { get; private set; }

    public SessionApprovalForm(string deviceName)
    {
        Text = "ConnectQuick · Conexión entrante";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        TopMost = true;
        ClientSize = new Size(460, 250);

        var card = new GlassPanel { Dock = DockStyle.Fill };
        var host = new Panel { Dock = DockStyle.Fill, Padding = new Padding(20), BackColor = Color.Transparent };
        host.Controls.Add(card);
        Controls.Add(host);

        var buttons = new TableLayoutPanel { Dock = DockStyle.Bottom, Height = 56, ColumnCount = 2, BackColor = Color.Transparent };
        buttons.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        buttons.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        var deny = new GlassButton { Text = "Denegar", Dock = DockStyle.Fill, Margin = new Padding(0, 8, 6, 0), AccessibleName = "Denegar la conexión" };
        var allow = new GlassButton { Text = "Permitir", Style = GlassButtonStyle.Primary, Dock = DockStyle.Fill, Margin = new Padding(6, 8, 0, 0), AccessibleName = "Permitir la conexión" };
        deny.Click += (_, _) => { Allowed = false; Close(); };
        allow.Click += (_, _) => { Allowed = true; Close(); };
        buttons.Controls.Add(deny, 0, 0);
        buttons.Controls.Add(allow, 1, 0);
        CancelButton = deny;

        _countdown = new ThemedLabel("", ThemedLabel.Kind.Dim) { Dock = DockStyle.Top, TextAlign = ContentAlignment.MiddleCenter };
        var body = new ThemedLabel($"“{deviceName}” quiere conectarse ahora.", ThemedLabel.Kind.Heading) { Dock = DockStyle.Top, Height = 70, TextAlign = ContentAlignment.MiddleCenter, Font = Theme.Strong(13f) };
        card.Controls.Add(_countdown);
        card.Controls.Add(body);
        card.Controls.Add(buttons);

        UpdateCountdown();
        _tick.Tick += (_, _) =>
        {
            if (--_remaining <= 0) { Allowed = false; Close(); return; }
            UpdateCountdown();
        };
        _tick.Start();
        FormClosed += (_, _) => _tick.Dispose();
        Shown += (_, _) => deny.Focus();
    }

    private void UpdateCountdown()
    {
        _countdown.Text = $"Se denegará automáticamente en {_remaining} s";
        _countdown.AccessibleName = _countdown.Text;
    }
}

/// <summary>Shows the pairing QR (Protocol §5) with a live countdown to its expiry and a way to get a fresh one.</summary>
internal sealed class PairingWindow : GlassForm
{
    private readonly PictureBox _qrBox;
    private readonly ThemedLabel _status;
    private readonly GlassButton _renew;
    private readonly System.Windows.Forms.Timer _countdown = new() { Interval = 1000 };
    private DateTimeOffset _expiresAt;

    public event Action? RenewRequested;

    public PairingWindow(QrPairingPayload payload)
    {
        Text = "ConnectQuick · Vincular un teléfono";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        ClientSize = new Size(480, 640);

        var card = new GlassPanel { Dock = DockStyle.Fill };
        var host = new Panel { Dock = DockStyle.Fill, Padding = new Padding(20), BackColor = Color.Transparent };
        host.Controls.Add(card);
        Controls.Add(host);

        _renew = new GlassButton { Text = "Generar un código nuevo", Dock = DockStyle.Bottom, Height = 44, Visible = false, AccessibleName = "Generar un código QR nuevo" };
        _renew.Click += (_, _) => RenewRequested?.Invoke();

        _status = new ThemedLabel("", ThemedLabel.Kind.Body) { Dock = DockStyle.Bottom, Height = 40, TextAlign = ContentAlignment.MiddleCenter };
        _qrBox = new PictureBox
        {
            SizeMode = PictureBoxSizeMode.Zoom,
            Dock = DockStyle.Fill,
            BackColor = Color.White, // a QR needs a light quiet zone around it; this is not decoration
            Margin = new Padding(0),
            AccessibleName = "Código QR para vincular un teléfono",
            AccessibleRole = AccessibleRole.Graphic,
        };
        var qrHolder = new Panel { Dock = DockStyle.Fill, Padding = new Padding(24), BackColor = Color.Transparent };
        qrHolder.Controls.Add(_qrBox);

        var title = new ThemedLabel("Escanea con la app de ConnectQuick", ThemedLabel.Kind.Heading) { Dock = DockStyle.Top, Height = 40, TextAlign = ContentAlignment.MiddleCenter };
        var sub = new ThemedLabel("Después compara el código de 6 cifras en ambos aparatos.", ThemedLabel.Kind.Dim) { Dock = DockStyle.Top, TextAlign = ContentAlignment.MiddleCenter };

        card.Controls.Add(qrHolder);
        card.Controls.Add(_renew);
        card.Controls.Add(_status);
        card.Controls.Add(sub);
        card.Controls.Add(title);

        _countdown.Tick += (_, _) => UpdateCountdown();
        _countdown.Start();
        FormClosed += (_, _) => { _countdown.Dispose(); _qrBox.Image?.Dispose(); };
        Render(payload);
    }

    public void Render(QrPairingPayload payload)
    {
        _expiresAt = DateTimeOffset.FromUnixTimeSeconds(payload.Exp);
        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(payload.ToJson(), QRCodeGenerator.ECCLevel.M);
        var png = new PngByteQRCode(data).GetGraphic(10); // PNG bytes: no System.Drawing dependency inside the QR library
        using var stream = new MemoryStream(png);
        using var decoded = Image.FromStream(stream);
        var old = _qrBox.Image;
        _qrBox.Image = new Bitmap(decoded); // copy: the decoded image needs its stream alive, the copy does not
        old?.Dispose();
        _renew.Visible = false;
        if (!_countdown.Enabled) _countdown.Start();
        UpdateCountdown();
    }

    private void UpdateCountdown()
    {
        var remaining = _expiresAt - DateTimeOffset.UtcNow;
        if (remaining <= TimeSpan.Zero)
        {
            _status.Text = "Este código caducó.";
            _status.ForeColor = Theme.Warn;
            _renew.Visible = true;
            _countdown.Stop();
            _qrBox.Visible = false;
        }
        else
        {
            _qrBox.Visible = true;
            _status.ForeColor = Theme.Text;
            _status.Text = $"Caduca en {(int)remaining.TotalSeconds} s";
        }
        _status.AccessibleName = _status.Text;
    }
}
