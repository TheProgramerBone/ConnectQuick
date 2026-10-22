using System.Diagnostics;
using System.Text;
using ConnectQuick.WindowsHost.Diagnostics;
using ConnectQuick.WindowsHost.Files;
using ConnectQuick.WindowsHost.Pairing;
using ConnectQuick.WindowsHost.Runtime;
using ConnectQuick.WindowsHost.Settings;

namespace ConnectQuick.WindowsHost.Ui;

/// <summary>What the pages may ask the shell to do. Keeps pages free of tray/window plumbing.</summary>
internal interface IShellActions
{
    LinkState CurrentState { get; }
    void StartPairing();
    void DisconnectPhone();
    Task RevokeAsync(AuthorizedDevice device);
    bool Confirm(string title, string body, string confirmText, bool danger);
}

/// <summary>A scrolling column of full-width blocks. Pages are built from these so layout survives any DPI or text size.</summary>
internal abstract class Page : Panel
{
    protected readonly FlowLayoutPanel Column = new()
    {
        Dock = DockStyle.Fill,
        FlowDirection = FlowDirection.TopDown,
        WrapContents = false,
        AutoScroll = true,
        BackColor = Color.Transparent,
        Padding = new Padding(0, 0, 8, 0),
    };

    protected Page()
    {
        BackColor = Color.Transparent;
        Dock = DockStyle.Fill;
        Visible = false;
        Controls.Add(Column);
        Resize += (_, _) => FitWidths();
    }

    /// <summary>Called when the page is shown and once per second while visible.</summary>
    public virtual void RefreshView() { }

    protected void Add(Control c)
    {
        c.Margin = new Padding(0, 0, 0, Theme.Gap);
        Column.Controls.Add(c);
        FitWidth(c);
    }

    private void FitWidths()
    {
        foreach (Control c in Column.Controls) FitWidth(c);
    }

    private void FitWidth(Control c) => c.Width = Math.Max(300, Column.ClientSize.Width - Column.Padding.Right - c.Margin.Horizontal - SystemInformation.VerticalScrollBarWidth);

    protected static GlassPanel Card(int height, params Control[] children)
    {
        var card = new GlassPanel { Height = height };
        for (var i = children.Length - 1; i >= 0; i--) card.Controls.Add(children[i]);
        return card;
    }
}

internal static class Format
{
    public static string Duration(TimeSpan t) =>
        t.TotalHours >= 1 ? $"{(int)t.TotalHours} h {t.Minutes:D2} min" : t.TotalMinutes >= 1 ? $"{(int)t.TotalMinutes} min {t.Seconds:D2} s" : $"{t.Seconds} s";

    /// <summary>Groups the first characters of an id so two people can read it to each other.</summary>
    public static string Fingerprint(string id)
    {
        var s = id.Length > 16 ? id[..16] : id;
        var sb = new StringBuilder();
        for (var i = 0; i < s.Length; i++)
        {
            if (i > 0 && i % 4 == 0) sb.Append('-');
            sb.Append(s[i]);
        }
        return sb.ToString();
    }

    public static string Bytes(long n) =>
        n >= 1L << 30 ? $"{n / (double)(1L << 30):0.0} GB" : n >= 1L << 20 ? $"{n / (double)(1L << 20):0.0} MB" : n >= 1L << 10 ? $"{n / (double)(1L << 10):0} KB" : $"{n} B";
}

/// <summary>Home: is the PC reachable, who is connected, what can they do right now, and the two actions that matter.</summary>
internal sealed class StatusPage : Page
{
    private readonly HostRuntime _rt;
    private readonly IShellActions _shell;
    private readonly StatusPill _pill = new() { Dock = DockStyle.Top };
    private readonly ThemedLabel _detail = new("", ThemedLabel.Kind.Dim) { Dock = DockStyle.Top, Height = 28 };
    private readonly ThemedLabel _session = new("", ThemedLabel.Kind.Body) { Dock = DockStyle.Fill, TextAlign = ContentAlignment.TopLeft };
    private readonly GlassButton _disconnect = new() { Text = "Desconectar el teléfono", Style = GlassButtonStyle.Danger, Dock = DockStyle.Bottom, Height = 44, AccessibleName = "Desconectar el teléfono ahora", AccessibleDescription = "Corta la sesión activa y suelta cualquier tecla o botón retenido" };
    private readonly ToggleSwitch _accept = new() { Text = "Aceptar conexiones", Hint = "Apágalo para pausar el acceso sin revocar ningún teléfono.", Dock = DockStyle.Top };
    private readonly ThemedLabel _caps = new("", ThemedLabel.Kind.Body) { Dock = DockStyle.Fill, TextAlign = ContentAlignment.TopLeft };
    private bool _loading;

    public StatusPage(HostRuntime rt, IShellActions shell)
    {
        _rt = rt;
        _shell = shell;

        var title = new ThemedLabel("Estado", ThemedLabel.Kind.Title) { Dock = DockStyle.Top };
        Add(title);

        _pill.Font = Theme.Strong(13f);
        _pill.Height = 40;
        var pair = new GlassButton { Text = "Vincular un teléfono", Style = GlassButtonStyle.Primary, Dock = DockStyle.Bottom, Height = 44, AccessibleName = "Vincular un teléfono nuevo", AccessibleDescription = "Muestra un código QR para escanear con la app" };
        pair.Click += (_, _) => _shell.StartPairing();
        var hostCard = Card(176, _detail, _pill, pair);
        hostCard.Controls.SetChildIndex(_pill, 0);
        Add(hostCard);

        _disconnect.Click += (_, _) => _shell.DisconnectPhone();
        Add(Card(150, _disconnect, _session));

        _accept.Height = 56;
        _accept.CheckedChanged += (_, _) =>
        {
            if (_loading) return;
            _rt.Settings.Update(s => s.AcceptConnections = _accept.Checked);
        };
        Add(Card(84, _accept));

        Add(Card(190, _caps));
    }

    public override void RefreshView()
    {
        var state = _shell.CurrentState;
        _pill.Set(state);
        _detail.Text = $"{_rt.Config.HostName} · huella {Format.Fingerprint(_rt.Identity.DeviceId)}";
        _detail.AccessibleName = "Este PC: " + _detail.Text;

        var s = _rt.Sessions.Active;
        _disconnect.Visible = s is not null;
        _session.Text = s is null
            ? (_rt.Devices.All.Count == 0 ? "Todavía no hay teléfonos vinculados.\nPulsa «Vincular un teléfono» para empezar." : "Ningún teléfono conectado ahora.")
            : $"{s.DeviceName}\nconectado hace {Format.Duration(DateTimeOffset.UtcNow - s.StartedAt)}\nPuede: {PermissionText.Summary(s.Permissions)}\nLa sesión está cifrada de extremo a extremo.";

        _loading = true;
        _accept.Checked = _rt.Settings.Current.AcceptConnections;
        _loading = false;

        var c = _rt.Settings.Current;
        var profile = _rt.Streaming.ActiveProfile();
        _caps.Text = string.Join("\n", new[]
        {
            "Lo que un teléfono autorizado puede hacer ahora en este PC:",
            Line("Ver la pantalla", c.AllowScreen, profile is null ? null : $"emitiendo {profile.Width}×{profile.Height} a {profile.Fps} fps"),
            Line("Ratón", c.AllowMouse), Line("Teclado", c.AllowKeyboard), Line("Portapapeles", c.AllowClipboard),
            Line("Multimedia", c.AllowMedia), Line("Archivos", c.AllowFiles),
            "Energía: " + c.Power switch { PowerPolicy.Disabled => "desactivada", PowerPolicy.LockOnly => "solo bloquear", _ => "bloquear, suspender, reiniciar y apagar (con confirmación)" },
        });
    }

    private static string Line(string name, bool on, string? extra = null) => $"{(on ? "●" : "○")}  {name}: {(on ? "permitido" : "desactivado")}{(extra is null ? "" : " — " + extra)}";
}

/// <summary>Paired phones, what each may do, whether it is connected now, and how to revoke it.</summary>
internal sealed class DevicesPage : Page
{
    private readonly HostRuntime _rt;
    private readonly IShellActions _shell;
    private string _signature = "";

    public DevicesPage(HostRuntime rt, IShellActions shell)
    {
        _rt = rt;
        _shell = shell;
        _rt.Devices.Changed += () => { if (IsHandleCreated) BeginInvoke(new Action(() => { _signature = ""; RefreshView(); })); };
    }

    public override void RefreshView()
    {
        var devices = _rt.Devices.All;
        var active = _rt.Sessions.Active?.DeviceId;
        var sig = string.Join("|", devices.Select(d => d.DeviceId + (d.DeviceId == active ? "*" : ""))) + devices.Count;
        if (sig == _signature) return;
        _signature = sig;

        Column.SuspendLayout();
        foreach (Control c in Column.Controls.Cast<Control>().ToList()) { Column.Controls.Remove(c); c.Dispose(); }
        Add(new ThemedLabel("Dispositivos", ThemedLabel.Kind.Title) { Height = 44 });

        if (devices.Count == 0)
        {
            var empty = new ThemedLabel("Aquí aparecerán los teléfonos que vincules.\nCada uno se autoriza en este PC y puedes revocarlo cuando quieras.", ThemedLabel.Kind.Body) { Dock = DockStyle.Top, Height = 64, TextAlign = ContentAlignment.TopLeft };
            var pair = new GlassButton { Text = "Vincular un teléfono", Style = GlassButtonStyle.Primary, Dock = DockStyle.Bottom, Height = 44 };
            pair.Click += (_, _) => _shell.StartPairing();
            Add(Card(150, empty, pair));
        }
        foreach (var d in devices.OrderByDescending(d => d.DeviceId == active).ThenBy(d => d.DeviceName)) Add(DeviceCard(d, d.DeviceId == active));
        Column.ResumeLayout();
    }

    private Control DeviceCard(AuthorizedDevice d, bool online)
    {
        var name = new ThemedLabel(d.DeviceName, ThemedLabel.Kind.Heading) { Dock = DockStyle.Top };
        var state = new StatusPill { Dock = DockStyle.Top, Height = 28, Font = Theme.Body(10f) };
        state.Set(online ? LinkState.InSession : LinkState.Ready, online ? "Conectado ahora" : "Vinculado");
        var perms = new ThemedLabel(PermissionText.Summary(d.Permissions), ThemedLabel.Kind.Dim) { Dock = DockStyle.Top, Height = 44, TextAlign = ContentAlignment.TopLeft };
        var when = DateTimeOffset.FromUnixTimeMilliseconds(d.AuthorizedAtMs).ToLocalTime();
        var since = new ThemedLabel($"Vinculado el {when:d} · huella {Format.Fingerprint(d.DeviceId)}", ThemedLabel.Kind.Dim) { Dock = DockStyle.Top };

        var revoke = new GlassButton { Text = "Revocar acceso", Style = GlassButtonStyle.Danger, Dock = DockStyle.Bottom, Height = 40, AccessibleName = $"Revocar el acceso de {d.DeviceName}" };
        revoke.Click += async (_, _) =>
        {
            if (!_shell.Confirm("Revocar acceso", $"«{d.DeviceName}» dejará de poder conectarse y se cortará su sesión ahora. Tendrás que vincularlo de nuevo para volver a usarlo.", "Revocar", danger: true)) return;
            await _shell.RevokeAsync(d);
        };
        return Card(214, name, state, perms, since, revoke);
    }
}

/// <summary>Every switch the person at the PC controls. Changes apply on the next message, no restart or re-pairing.</summary>
internal sealed class SettingsPage : Page
{
    private readonly HostRuntime _rt;
    private readonly IShellActions _shell;
    private readonly Dictionary<ToggleSwitch, Func<HostSettings, bool>> _bound = new();
    private readonly ComboBox _power = new() { DropDownStyle = ComboBoxStyle.DropDownList, FlatStyle = FlatStyle.Flat, Dock = DockStyle.Bottom, Font = Theme.Body(10.5f), BackColor = Theme.FieldBack, ForeColor = Theme.FieldText, Tag = "field" };
    private readonly Dictionary<string, NumericUpDown> _limits = new();
    private readonly ThemedLabel _inbox = new("", ThemedLabel.Kind.Dim) { Dock = DockStyle.Top, Height = 26 };
    private readonly ThemedLabel _outbox = new("", ThemedLabel.Kind.Dim) { Dock = DockStyle.Top, Height = 26 };
    private bool _loading;

    public SettingsPage(HostRuntime rt, IShellActions shell)
    {
        _rt = rt;
        _shell = shell;
        Add(new ThemedLabel("Permisos y ajustes", ThemedLabel.Kind.Title) { Height = 44 });

        var dark = new GlassButton { Text = "Oscuro", Location = new Point(0, 0), Width = 150, AccessibleName = "Tema oscuro" };
        var light = new GlassButton { Text = "Claro", Location = new Point(162, 0), Width = 150, AccessibleName = "Tema claro" };
        void Mark() { dark.Style = Theme.IsLight ? GlassButtonStyle.Secondary : GlassButtonStyle.Primary; light.Style = Theme.IsLight ? GlassButtonStyle.Primary : GlassButtonStyle.Secondary; dark.Invalidate(); light.Invalidate(); }
        dark.Click += (_, _) => _rt.Settings.Update(s => s.Theme = "dark");
        light.Click += (_, _) => _rt.Settings.Update(s => s.Theme = "light");
        Theme.Changed += Mark;
        Disposed += (_, _) => Theme.Changed -= Mark;
        Mark();
        var themeButtons = new Panel { Dock = DockStyle.Bottom, Height = 44, BackColor = Color.Transparent };
        themeButtons.Controls.Add(light);
        themeButtons.Controls.Add(dark);
        Add(Card(130, new ThemedLabel("Apariencia", ThemedLabel.Kind.Heading) { Dock = DockStyle.Top },
            new ThemedLabel("Oscuro es el predeterminado. El cambio se aplica al instante.", ThemedLabel.Kind.Dim) { Dock = DockStyle.Top, Height = 28 }, themeButtons));

        Add(Section("Conexión",
            Toggle("Aceptar conexiones", "Pausa o reanuda el acceso de todos los teléfonos.", s => s.AcceptConnections, (s, v) => s.AcceptConnections = v),
            Toggle("Preguntarme en cada conexión", "Muestra un aviso en este PC cada vez que un teléfono vinculado quiere entrar.", s => s.RequireApprovalPerSession, (s, v) => s.RequireApprovalPerSession = v)));

        Add(Section("Qué se puede controlar",
            Toggle("Ver la pantalla", PermissionText.Hint("remote_desktop"), s => s.AllowScreen, (s, v) => s.AllowScreen = v),
            Toggle("Ratón", PermissionText.Hint("mouse"), s => s.AllowMouse, (s, v) => s.AllowMouse = v),
            Toggle("Teclado", PermissionText.Hint("keyboard"), s => s.AllowKeyboard, (s, v) => s.AllowKeyboard = v),
            Toggle("Portapapeles", PermissionText.Hint("clipboard"), s => s.AllowClipboard, (s, v) => s.AllowClipboard = v),
            Toggle("Enviar el portapapeles al teléfono automáticamente", "Desactivado: el teléfono debe pedirlo. Contraseñas copiadas desde gestores se excluyen.", s => s.ClipboardAutoPushToController, (s, v) => s.ClipboardAutoPushToController = v),
            Toggle("Multimedia y volumen", PermissionText.Hint("media"), s => s.AllowMedia, (s, v) => s.AllowMedia = v),
            Toggle("Archivos", PermissionText.Hint("files"), s => s.AllowFiles, (s, v) => s.AllowFiles = v)));

        _power.Items.AddRange(new object[] { "Desactivada", "Solo bloquear (recomendado)", "Bloquear, suspender, reiniciar y apagar" });
        _power.AccessibleName = "Control de energía desde el teléfono";
        _power.SelectedIndexChanged += (_, _) =>
        {
            if (_loading) return;
            var chosen = (PowerPolicy)_power.SelectedIndex;
            if (chosen == PowerPolicy.Full &&
                !_shell.Confirm("Permitir apagar el PC", "Un teléfono autorizado podrá suspender, reiniciar o apagar este PC, siempre tras una confirmación en el propio teléfono. Se puede perder el trabajo sin guardar. ¿Continuar?", "Permitir", danger: true))
            {
                _loading = true; _power.SelectedIndex = (int)_rt.Settings.Current.Power; _loading = false;
                return;
            }
            _rt.Settings.Update(s => s.Power = chosen);
        };
        var powerLabel = new ThemedLabel("Energía", ThemedLabel.Kind.Heading) { Dock = DockStyle.Top };
        var powerHint = new ThemedLabel("El teléfono nunca puede ejecutar otros comandos: solo estas acciones fijas.", ThemedLabel.Kind.Dim) { Dock = DockStyle.Top, Height = 44, TextAlign = ContentAlignment.TopLeft };
        Add(Card(150, powerLabel, powerHint, _power));

        var limits = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.LeftToRight, WrapContents = true, BackColor = Color.Transparent };
        limits.Controls.Add(Limit("w", "Ancho máx.", 160, 7680, (s, v) => s.MaxStreamWidth = v, s => s.MaxStreamWidth));
        limits.Controls.Add(Limit("h", "Alto máx.", 120, 4320, (s, v) => s.MaxStreamHeight = v, s => s.MaxStreamHeight));
        limits.Controls.Add(Limit("fps", "Fotogramas/s", 1, 60, (s, v) => s.MaxStreamFps = v, s => s.MaxStreamFps));
        limits.Controls.Add(Limit("kbps", "Kbps máx.", 100, 50000, (s, v) => s.MaxStreamBitrateKbps = v, s => s.MaxStreamBitrateKbps));
        Add(Card(180, new ThemedLabel("Calidad de pantalla", ThemedLabel.Kind.Heading) { Dock = DockStyle.Top },
            new ThemedLabel("Tope que el teléfono no puede superar. La app reduce la calidad sola si la red va justa.", ThemedLabel.Kind.Dim) { Dock = DockStyle.Top, Height = 44, TextAlign = ContentAlignment.TopLeft }, limits));

        var openIn = new GlassButton { Text = "Abrir carpeta de recibidos", Dock = DockStyle.Left, Width = 230 };
        openIn.Click += (_, _) => OpenFolder(_rt.Settings.Current.DefaultInbox());
        var changeIn = new GlassButton { Text = "Cambiar…", Dock = DockStyle.Left, Width = 130 };
        changeIn.Click += (_, _) => ChooseFolder(true);
        var folderButtons = new Panel { Dock = DockStyle.Bottom, Height = 44, BackColor = Color.Transparent };
        folderButtons.Controls.Add(changeIn);
        folderButtons.Controls.Add(openIn);
        var outButtons = new Panel { Dock = DockStyle.Bottom, Height = 44, BackColor = Color.Transparent };
        var openOut = new GlassButton { Text = "Abrir carpeta para compartir", Dock = DockStyle.Left, Width = 230 };
        openOut.Click += (_, _) => OpenFolder(_rt.Settings.Current.DefaultOutbox());
        var changeOut = new GlassButton { Text = "Cambiar…", Dock = DockStyle.Left, Width = 130 };
        changeOut.Click += (_, _) => ChooseFolder(false);
        outButtons.Controls.Add(changeOut);
        outButtons.Controls.Add(openOut);
        Add(Card(260, new ThemedLabel("Carpetas de archivos", ThemedLabel.Kind.Heading) { Dock = DockStyle.Top }, _inbox, _outbox,
            new ThemedLabel("El teléfono solo puede escribir en «recibidos» y leer de «compartir»; nada más del PC es accesible.", ThemedLabel.Kind.Dim) { Dock = DockStyle.Top, Height = 44, TextAlign = ContentAlignment.TopLeft },
            outButtons, folderButtons));
    }

    private GlassPanel Section(string heading, params ToggleSwitch[] toggles)
    {
        var card = new GlassPanel { Height = 54 + toggles.Length * 56 };
        for (var i = toggles.Length - 1; i >= 0; i--) card.Controls.Add(toggles[i]);
        card.Controls.Add(new ThemedLabel(heading, ThemedLabel.Kind.Heading) { Dock = DockStyle.Top });
        return card;
    }

    private ToggleSwitch Toggle(string text, string hint, Func<HostSettings, bool> read, Action<HostSettings, bool> write)
    {
        var t = new ToggleSwitch { Text = text, Hint = hint, Dock = DockStyle.Top, Height = 56, AccessibleName = text, AccessibleDescription = hint };
        _bound[t] = read;
        t.CheckedChanged += (_, _) =>
        {
            if (_loading) return;
            _rt.Settings.Update(s => write(s, t.Checked));
        };
        return t;
    }

    private Control Limit(string key, string label, int min, int max, Action<HostSettings, int> write, Func<HostSettings, int> read)
    {
        var box = new NumericUpDown { Minimum = min, Maximum = max, Width = 110, Font = Theme.Body(10.5f), BackColor = Theme.FieldBack, ForeColor = Theme.FieldText, Tag = "field", BorderStyle = BorderStyle.FixedSingle, AccessibleName = label };
        box.ValueChanged += (_, _) =>
        {
            if (_loading) return;
            _rt.Settings.Update(s => write(s, (int)box.Value));
        };
        _limits[key] = box;
        _limitReaders[key] = read;
        var holder = new Panel { Width = 130, Height = 70, BackColor = Color.Transparent };
        var caption = new ThemedLabel(label, ThemedLabel.Kind.Dim) { Dock = DockStyle.Top, Height = 24 };
        box.Location = new Point(0, 28);
        holder.Controls.Add(box);
        holder.Controls.Add(caption);
        return holder;
    }

    private readonly Dictionary<string, Func<HostSettings, int>> _limitReaders = new();

    private void ChooseFolder(bool inbox)
    {
        using var dialog = new FolderBrowserDialog { Description = inbox ? "Dónde se guardan los archivos que envía el teléfono" : "Carpeta cuyos archivos puede pedir el teléfono", UseDescriptionForTitle = true };
        if (dialog.ShowDialog(FindForm()) != DialogResult.OK) return;
        _rt.Settings.Update(s => { if (inbox) s.InboxDirectory = dialog.SelectedPath; else s.OutboxDirectory = dialog.SelectedPath; });
        RefreshView();
    }

    private static void OpenFolder(string path)
    {
        try
        {
            Directory.CreateDirectory(path);
            Process.Start(new ProcessStartInfo { FileName = path, UseShellExecute = true });
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or System.ComponentModel.Win32Exception)
        {
            MessageBox.Show("No se pudo abrir la carpeta: " + ex.Message, "ConnectQuick", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }

    public override void RefreshView()
    {
        var s = _rt.Settings.Current;
        _loading = true;
        foreach (var (toggle, read) in _bound) toggle.Checked = read(s);
        _power.SelectedIndex = (int)s.Power;
        foreach (var (key, box) in _limits) box.Value = Math.Clamp(_limitReaders[key](s), (int)box.Minimum, (int)box.Maximum);
        _loading = false;
        _inbox.Text = "Recibidos: " + s.DefaultInbox();
        _outbox.Text = "Para compartir: " + s.DefaultOutbox();
    }
}

/// <summary>Send a file to the connected phone and watch transfers in both directions.</summary>
internal sealed class FilesPage : Page
{
    private readonly HostRuntime _rt;
    private readonly GlassButton _send = new() { Text = "Enviar un archivo al teléfono…", Style = GlassButtonStyle.Primary, Dock = DockStyle.Bottom, Height = 44 };
    private readonly ThemedLabel _hint = new("", ThemedLabel.Kind.Dim) { Dock = DockStyle.Top, Height = 44, TextAlign = ContentAlignment.TopLeft };
    private readonly ListView _list = new()
    {
        View = View.Details, FullRowSelect = true, HeaderStyle = ColumnHeaderStyle.Nonclickable, Dock = DockStyle.Fill, BorderStyle = BorderStyle.None,
        BackColor = Theme.FieldBack, ForeColor = Theme.FieldText, Tag = "field", Font = Theme.Body(10f), AccessibleName = "Transferencias de archivos",
    };
    private readonly Dictionary<string, ListViewItem> _rows = new();

    public FilesPage(HostRuntime rt)
    {
        _rt = rt;
        Add(new ThemedLabel("Archivos", ThemedLabel.Kind.Title) { Height = 44 });
        _send.AccessibleName = "Enviar un archivo al teléfono conectado";
        _send.Click += async (_, _) => await SendAsync();
        Add(Card(120, _hint, _send));

        _list.Columns.Add("Archivo", 260);
        _list.Columns.Add("Sentido", 110);
        _list.Columns.Add("Progreso", 120);
        _list.Columns.Add("Estado", 160);
        _list.Height = 260;
        var listCard = Card(300, new ThemedLabel("Transferencias", ThemedLabel.Kind.Heading) { Dock = DockStyle.Top }, _list);
        listCard.Controls.SetChildIndex(_list, 0);
        Add(listCard);
        _rt.Files.Progress += p => { if (IsHandleCreated) BeginInvoke(new Action(() => OnProgress(p))); };
    }

    private void OnProgress(TransferProgress p)
    {
        if (!_rows.TryGetValue(p.TransferId, out var row))
        {
            row = new ListViewItem(new[] { p.Name, "", "", "" });
            _rows[p.TransferId] = row;
            _list.Items.Insert(0, row);
            while (_list.Items.Count > 50) { var last = _list.Items[^1]; _rows.Remove(_rows.First(kv => kv.Value == last).Key); _list.Items.Remove(last); }
        }
        row.SubItems[0].Text = p.Name;
        row.SubItems[1].Text = p.Direction == TransferDirection.Incoming ? "Teléfono → PC" : "PC → teléfono";
        row.SubItems[2].Text = p.Total > 0 ? $"{Format.Bytes(p.Bytes)} / {Format.Bytes(p.Total)}" : Format.Bytes(p.Bytes);
        row.SubItems[3].Text = p.State switch
        {
            TransferState.Running => "En curso…",
            TransferState.Done => "Completado" + (p.SavedAs is null ? "" : " ✓"),
            TransferState.Cancelled => "Cancelado",
            _ => "Falló" + (p.Reason is null ? "" : $" ({p.Reason})"),
        };
    }

    private async Task SendAsync()
    {
        var session = _rt.Sessions.Active;
        if (session is null || !session.Permissions.Contains("files")) return;
        using var dialog = new OpenFileDialog { Title = "Elige el archivo que quieres enviar al teléfono", CheckFileExists = true, Multiselect = false };
        if (dialog.ShowDialog(FindForm()) != DialogResult.OK) return;
        _send.Enabled = false;
        try
        {
            var ok = await _rt.Files.OfferFileAsync(session, dialog.FileName);
            if (!ok) MessageBox.Show("No se pudo ofrecer el archivo (¿demasiado grande o ya hay una transferencia en curso?).", "ConnectQuick", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
        finally { _send.Enabled = true; }
    }

    public override void RefreshView()
    {
        var s = _rt.Sessions.Active;
        var can = s is not null && s.Permissions.Contains("files") && _rt.Settings.Current.AllowFiles;
        _send.Enabled = can;
        _hint.Text = can
            ? $"Conectado con {s!.DeviceName}. El teléfono te preguntará si acepta el archivo."
            : s is null ? "Conecta un teléfono para enviarle archivos." : "Este teléfono no tiene permiso de archivos, o está desactivado en Permisos y ajustes.";
    }
}

/// <summary>What the app has been doing, for support and for trust. Never contains keys, clipboard text or file contents.</summary>
internal sealed class LogPage : Page
{
    private readonly HostRuntime _rt;
    private readonly TextBox _text = new()
    {
        Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical, Dock = DockStyle.Fill, BorderStyle = BorderStyle.None,
        BackColor = Theme.FieldBack, ForeColor = Theme.FieldText, Tag = "field", Font = new Font(FontFamily.GenericMonospace, 9.5f), AccessibleName = "Registro de actividad",
    };
    private int _shown = -1;

    public LogPage(HostRuntime rt)
    {
        _rt = rt;
        Add(new ThemedLabel("Registro", ThemedLabel.Kind.Title) { Height = 44 });
        var copy = new GlassButton { Text = "Copiar registro", Dock = DockStyle.Bottom, Height = 40, AccessibleName = "Copiar el registro al portapapeles" };
        copy.Click += (_, _) => { try { System.Windows.Forms.Clipboard.SetText(_text.Text); } catch (System.Runtime.InteropServices.ExternalException) { /* clipboard busy: ignore */ } };
        var note = new ThemedLabel("El registro no incluye claves, contraseñas, textos copiados ni contenido de archivos.", ThemedLabel.Kind.Dim) { Dock = DockStyle.Top, Height = 28 };
        var card = Card(480, note, copy, _text);
        card.Controls.SetChildIndex(_text, 0);
        Add(card);
    }

    public override void RefreshView()
    {
        var entries = _rt.Log.Snapshot();
        var signature = entries.Count == 0 ? 0 : entries.Count * 31 + entries[^1].Message.GetHashCode();
        if (signature == _shown) return;
        _shown = signature;
        _text.Text = string.Join(Environment.NewLine, entries.Select(e => $"{e.At:HH:mm:ss} {LevelTag(e.Level)} {e.Category}: {e.Message}"));
        _text.SelectionStart = _text.TextLength;
        _text.ScrollToCaret();
    }

    private static string LevelTag(LogLevel l) => l switch { LogLevel.Error => "[ERROR]", LogLevel.Warn => "[AVISO]", _ => "[info] " };
}
