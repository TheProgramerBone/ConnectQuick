using ConnectQuick.WindowsHost.Runtime;

namespace ConnectQuick.WindowsHost.Ui;

/// <summary>
/// The control centre: a nav rail on the left (a real keyboard-navigable list of buttons, Ctrl+1…5) and
/// one page at a time on the right. Closing the window hides it to the tray; the app keeps serving.
/// </summary>
internal sealed class MainWindow : GlassForm
{
    private readonly record struct Tab(string Title, string Hint, Page Page, GlassButton Button);

    private readonly List<Tab> _tabs = new();
    private readonly System.Windows.Forms.Timer _tick = new() { Interval = 1000 };
    private readonly StatusPill _headerPill = new() { Dock = DockStyle.Bottom, Height = 60, Font = Theme.Body(9.5f) };
    private int _current;

    public bool QuitRequested { get; set; }

    public MainWindow(HostRuntime rt, IShellActions shell)
    {
        Text = "ConnectQuick";
        MinimumSize = new Size(900, 640);
        ClientSize = new Size(1040, 720);
        KeyPreview = true;

        var rail = new Panel { Dock = DockStyle.Left, Width = 236, Padding = new Padding(16, 20, 8, 16), BackColor = Color.Transparent };
        var content = new Panel { Dock = DockStyle.Fill, Padding = new Padding(20, 20, 20, 12), BackColor = Color.Transparent };
        Controls.Add(content);
        Controls.Add(rail);

        var brand = new ThemedLabel("ConnectQuick", ThemedLabel.Kind.Heading) { Dock = DockStyle.Top, Height = 48, Font = Theme.Strong(15f) };
        var nav = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, WrapContents = false, BackColor = Color.Transparent, Padding = new Padding(0, 8, 0, 0) };
        rail.Controls.Add(nav);
        rail.Controls.Add(_headerPill);
        rail.Controls.Add(brand);
        nav.BringToFront();

        void AddTab(string title, string hint, Page page)
        {
            var index = _tabs.Count;
            var button = new GlassButton { Text = title, Width = 196, Height = 46, Margin = new Padding(0, 0, 0, 8), AccessibleName = title, AccessibleDescription = hint };
            button.Click += (_, _) => Select(index);
            nav.Controls.Add(button);
            content.Controls.Add(page);
            _tabs.Add(new Tab(title, hint, page, button));
        }

        AddTab("Estado", "Resumen y acciones principales", new StatusPage(rt, shell));
        AddTab("Dispositivos", "Teléfonos vinculados", new DevicesPage(rt, shell));
        AddTab("Permisos y ajustes", "Qué se puede controlar", new SettingsPage(rt, shell));
        AddTab("Archivos", "Enviar y recibir archivos", new FilesPage(rt));
        AddTab("Registro", "Actividad reciente", new LogPage(rt));
        Select(0);

        _tick.Tick += (_, _) =>
        {
            if (!Visible) return;
            _headerPill.Set(shell.CurrentState);
            _tabs[_current].Page.RefreshView();
        };
        _tick.Start();

        FormClosing += (_, e) =>
        {
            if (QuitRequested || e.CloseReason != CloseReason.UserClosing) return;
            e.Cancel = true; // keep running in the tray
            Hide();
        };
        FormClosed += (_, _) => _tick.Dispose();
        VisibleChanged += (_, _) => { if (Visible) { _headerPill.Set(shell.CurrentState); _tabs[_current].Page.RefreshView(); } };
    }

    public void Select(int index)
    {
        _current = Math.Clamp(index, 0, _tabs.Count - 1);
        for (var i = 0; i < _tabs.Count; i++)
        {
            var on = i == _current;
            _tabs[i].Page.Visible = on;
            _tabs[i].Button.Style = on ? GlassButtonStyle.Primary : GlassButtonStyle.Secondary;
            _tabs[i].Button.Invalidate();
            if (on) _tabs[i].Page.RefreshView();
        }
    }

    public void ShowPage(int index)
    {
        Select(index);
        Present();
    }

    /// <summary>Shows the window in front of whatever else is open, restoring it if minimised.</summary>
    public void Present()
    {
        if (!Visible) Show();
        if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
        Activate();
    }

    protected override bool ProcessCmdKey(ref Message msg, Keys keyData)
    {
        if ((keyData & Keys.Control) == Keys.Control)
        {
            var key = keyData & Keys.KeyCode;
            if (key >= Keys.D1 && key < Keys.D1 + _tabs.Count) { Select(key - Keys.D1); return true; }
        }
        return base.ProcessCmdKey(ref msg, keyData);
    }
}

/// <summary>Modal yes/no in the app's own style, with the safe choice focused so Enter never confirms a destructive action by accident.</summary>
internal sealed class ConfirmForm : GlassForm
{
    public ConfirmForm(string title, string body, string confirmText, bool danger)
    {
        Text = title;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        ShowInTaskbar = false;
        ClientSize = new Size(480, 250);

        var card = new GlassPanel { Dock = DockStyle.Fill };
        var host = new Panel { Dock = DockStyle.Fill, Padding = new Padding(20), BackColor = Color.Transparent };
        host.Controls.Add(card);
        Controls.Add(host);

        var buttons = new TableLayoutPanel { Dock = DockStyle.Bottom, Height = 56, ColumnCount = 2, BackColor = Color.Transparent };
        buttons.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        buttons.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        var cancel = new GlassButton { Text = "Cancelar", Dock = DockStyle.Fill, Margin = new Padding(0, 8, 6, 0) };
        var ok = new GlassButton { Text = confirmText, Style = danger ? GlassButtonStyle.Danger : GlassButtonStyle.Primary, Dock = DockStyle.Fill, Margin = new Padding(6, 8, 0, 0) };
        cancel.Click += (_, _) => { DialogResult = DialogResult.Cancel; Close(); };
        ok.Click += (_, _) => { DialogResult = DialogResult.OK; Close(); };
        buttons.Controls.Add(cancel, 0, 0);
        buttons.Controls.Add(ok, 1, 0);
        CancelButton = cancel;

        var heading = new ThemedLabel(title, ThemedLabel.Kind.Heading) { Dock = DockStyle.Top, Height = 36, Font = Theme.Strong(13f) };
        var text = new ThemedLabel(body, ThemedLabel.Kind.Body) { Dock = DockStyle.Fill, TextAlign = ContentAlignment.TopLeft };
        card.Controls.Add(text);
        card.Controls.Add(heading);
        card.Controls.Add(buttons);
        Shown += (_, _) => cancel.Focus();
    }
}
