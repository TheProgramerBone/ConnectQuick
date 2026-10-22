using System.Drawing.Drawing2D;

namespace ConnectQuick.WindowsHost.Ui;

/// <summary>A translucent rounded surface. Children keep a transparent background so the glass shows through.</summary>
internal class GlassPanel : Panel
{
    public GlassPanel()
    {
        SetStyle(ControlStyles.SupportsTransparentBackColor | ControlStyles.OptimizedDoubleBuffer |
                 ControlStyles.AllPaintingInWmPaint | ControlStyles.UserPaint | ControlStyles.ResizeRedraw, true);
        BackColor = Color.Transparent;
        Padding = new Padding(16);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        base.OnPaint(e);
        var g = e.Graphics;
        Theme.Quality(g);
        var r = new Rectangle(0, 0, Width - 1, Height - 1);
        if (r.Width < 4 || r.Height < 4) return;
        using var path = Theme.RoundedRect(r, Theme.Radius);
        using (var fill = new SolidBrush(Theme.GlassFill)) g.FillPath(fill, path);
        if (!Theme.HighContrast)
        {
            var state = g.Save();
            g.SetClip(path);
            // Depth: a faint shade pooling at the bottom, and a bright sheen across the top, so the pane reads as thick crystal.
            var shade = new Rectangle(r.X, r.Y + r.Height / 2, r.Width, r.Height - r.Height / 2);
            using (var shadeBrush = new LinearGradientBrush(shade, Color.FromArgb(0, Theme.GlassShade), Theme.GlassShade, LinearGradientMode.Vertical))
                g.FillRectangle(shadeBrush, shade);
            var sheen = new Rectangle(r.X + 1, r.Y + 1, r.Width - 2, Math.Max(10, r.Height / 2));
            using (var sheenBrush = new LinearGradientBrush(sheen, Theme.GlassHighlight, Color.FromArgb(0, 255, 255, 255), LinearGradientMode.Vertical))
                g.FillRectangle(sheenBrush, sheen);
            g.Restore(state);
            // Inner rim: a second hairline just inside the border gives the bevelled-glass edge.
            using var rimPath = Theme.RoundedRect(Rectangle.Inflate(r, -2, -2), Theme.Radius - 2);
            using var rim = new Pen(Color.FromArgb(Theme.IsLight ? 120 : 26, 255, 255, 255), 1f);
            g.DrawPath(rim, rimPath);
        }
        using var border = new Pen(Theme.GlassBorder, 1f);
        g.DrawPath(border, path);
    }
}

internal enum GlassButtonStyle
{
    Secondary,
    Primary,
    Danger,
}

/// <summary>Rounded button on the native Button base: keyboard, focus, access keys, AcceptButton and screen readers all keep working.</summary>
internal sealed class GlassButton : Button
{
    private bool _hover;
    private bool _down;

    public GlassButtonStyle Style { get; set; } = GlassButtonStyle.Secondary;

    public GlassButton()
    {
        SetStyle(ControlStyles.SupportsTransparentBackColor | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint, true);
        FlatStyle = FlatStyle.Flat;
        FlatAppearance.BorderSize = 0;
        BackColor = Color.Transparent;
        Font = Theme.Strong(10f);
        Height = 40;
        MinimumSize = new Size(96, 40);
        Cursor = Cursors.Hand;
        UseVisualStyleBackColor = false;
    }

    protected override void OnMouseEnter(EventArgs e) { _hover = true; Invalidate(); base.OnMouseEnter(e); }
    protected override void OnMouseLeave(EventArgs e) { _hover = false; _down = false; Invalidate(); base.OnMouseLeave(e); }
    protected override void OnMouseDown(MouseEventArgs mevent) { _down = true; Invalidate(); base.OnMouseDown(mevent); }
    protected override void OnMouseUp(MouseEventArgs mevent) { _down = false; Invalidate(); base.OnMouseUp(mevent); }
    protected override void OnEnabledChanged(EventArgs e) { Invalidate(); base.OnEnabledChanged(e); }
    protected override void OnGotFocus(EventArgs e) { Invalidate(); base.OnGotFocus(e); }
    protected override void OnLostFocus(EventArgs e) { Invalidate(); base.OnLostFocus(e); }

    protected override void OnPaint(PaintEventArgs pevent)
    {
        var g = pevent.Graphics;
        // Paint what is behind us first so the rounded corners are transparent over the glass/backdrop.
        base.OnPaintBackground(pevent);
        Theme.Quality(g);
        var r = new Rectangle(1, 1, Width - 3, Height - 3);
        if (r.Width < 4 || r.Height < 4) return;

        Color fill;
        Color text;
        switch (Style)
        {
            case GlassButtonStyle.Primary:
                fill = Theme.AccentFill; text = Theme.OnAccent; break;
            case GlassButtonStyle.Danger:
                fill = Theme.DangerFill; text = Color.White; break;
            default:
                fill = _hover ? Theme.GlassFillHover : Theme.GlassFill; text = Theme.Text; break;
        }
        if (Theme.HighContrast) { fill = _hover ? SystemColors.Highlight : SystemColors.Control; text = _hover ? SystemColors.HighlightText : SystemColors.ControlText; }
        if (_down) fill = ControlPaint.Dark(fill, 0.1f);
        if (!Enabled) { fill = Color.FromArgb(Math.Min((int)fill.A, 40), fill); text = Theme.TextDim; }

        using var path = Theme.RoundedRect(r, 12);
        if (Style == GlassButtonStyle.Primary && Enabled && !Theme.HighContrast)
        {
            // Lit from above: lighter at the top, the accent at the bottom.
            var top = _down ? fill : Theme.AccentFillTop;
            using var brush = new LinearGradientBrush(r, top, fill, LinearGradientMode.Vertical);
            g.FillPath(brush, path);
        }
        else
        {
            using var brush = new SolidBrush(fill);
            g.FillPath(brush, path);
        }
        if (!Theme.HighContrast && Enabled)
        {
            var state = g.Save();
            g.SetClip(path);
            var gloss = new Rectangle(r.X, r.Y, r.Width, r.Height / 2);
            using var glossBrush = new LinearGradientBrush(gloss, Color.FromArgb(Style == GlassButtonStyle.Secondary ? (Theme.IsLight ? 110 : 34) : 52, 255, 255, 255), Color.FromArgb(0, 255, 255, 255), LinearGradientMode.Vertical);
            g.FillRectangle(glossBrush, gloss);
            g.Restore(state);
        }
        using (var pen = new Pen(Theme.GlassBorder, 1f)) g.DrawPath(pen, path);
        TextRenderer.DrawText(g, Text, Font, r, text, TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis | TextFormatFlags.NoPrefix);

        if (Focused && ShowFocusCues)
        {
            using var focusPath = Theme.RoundedRect(Rectangle.Inflate(r, -3, -3), 9);
            using var focusPen = new Pen(Theme.Focus, 2f);
            g.DrawPath(focusPen, focusPath);
        }
    }
}

/// <summary>
/// An on/off switch. Derives from CheckBox so assistive technology announces it as a check box with
/// its label and state, Space toggles it, and it takes part in the normal tab order.
/// </summary>
internal sealed class ToggleSwitch : CheckBox
{
    public string? Hint { get; set; }

    public ToggleSwitch()
    {
        SetStyle(ControlStyles.SupportsTransparentBackColor | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint, true);
        AutoSize = false;
        BackColor = Color.Transparent;
        Font = Theme.Body(10f);
        Height = 52;
        Cursor = Cursors.Hand;
        UseVisualStyleBackColor = false;
    }

    protected override void OnCheckedChanged(EventArgs e) { Invalidate(); base.OnCheckedChanged(e); }
    protected override void OnEnabledChanged(EventArgs e) { Invalidate(); base.OnEnabledChanged(e); }
    protected override void OnGotFocus(EventArgs e) { Invalidate(); base.OnGotFocus(e); }
    protected override void OnLostFocus(EventArgs e) { Invalidate(); base.OnLostFocus(e); }

    protected override void OnPaint(PaintEventArgs pevent)
    {
        var g = pevent.Graphics;
        base.OnPaintBackground(pevent);
        Theme.Quality(g);

        const int trackW = 48, trackH = 26;
        var track = new Rectangle(Width - trackW - 2, (Height - trackH) / 2, trackW, trackH);
        var textRect = new Rectangle(0, 0, Math.Max(10, track.Left - 12), Height);

        var textColor = Enabled ? Theme.Text : Theme.TextDim;
        var hasHint = !string.IsNullOrEmpty(Hint);
        if (hasHint)
        {
            var titleRect = new Rectangle(textRect.X, 4, textRect.Width, Height / 2 - 2);
            var hintRect = new Rectangle(textRect.X, Height / 2 - 2, textRect.Width, Height / 2 - 2);
            TextRenderer.DrawText(g, Text, Theme.Strong(10f), titleRect, textColor, TextFormatFlags.Left | TextFormatFlags.Bottom | TextFormatFlags.EndEllipsis | TextFormatFlags.NoPrefix);
            TextRenderer.DrawText(g, Hint, Theme.Body(9f), hintRect, Theme.TextDim, TextFormatFlags.Left | TextFormatFlags.Top | TextFormatFlags.EndEllipsis | TextFormatFlags.NoPrefix);
        }
        else
        {
            TextRenderer.DrawText(g, Text, Font, textRect, textColor, TextFormatFlags.Left | TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis | TextFormatFlags.NoPrefix);
        }

        var on = Checked;
        var trackColor = on ? (Enabled ? Theme.AccentFill : Color.FromArgb(90, Theme.AccentFill)) : Color.FromArgb(Enabled ? Theme.SwitchOff.A : 40, Theme.SwitchOff);
        if (Theme.HighContrast) trackColor = on ? SystemColors.Highlight : SystemColors.Control;
        using (var path = Theme.RoundedRect(track, trackH / 2))
        {
            using var brush = new SolidBrush(trackColor);
            g.FillPath(brush, path);
            using var pen = new Pen(Theme.GlassBorder, 1f);
            g.DrawPath(pen, path);
        }

        var d = trackH - 8;
        var thumb = new Rectangle(on ? track.Right - d - 4 : track.Left + 4, track.Top + 4, d, d);
        using (var brush = new SolidBrush(Theme.HighContrast ? SystemColors.ControlText : Color.White)) g.FillEllipse(brush, thumb);
        // The state is also written in the track ("I"/"O" like a hardware switch) so it never relies on colour alone.
        var mark = on ? "I" : "O";
        var markRect = on ? new Rectangle(track.Left + 4, track.Top, d + 6, trackH) : new Rectangle(track.Right - d - 10, track.Top, d + 6, trackH);
        TextRenderer.DrawText(g, mark, Theme.Strong(7.5f), markRect, on ? Theme.OnAccent : Theme.TextDim, TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPrefix);

        if (Focused && ShowFocusCues)
        {
            using var pen = new Pen(Theme.Focus, 2f);
            using var focus = Theme.RoundedRect(Rectangle.Inflate(track, 3, 3), trackH / 2 + 3);
            g.DrawPath(pen, focus);
        }
    }
}

/// <summary>Small label that shows a state as colour + shape + word.</summary>
internal sealed class StatusPill : Control
{
    private LinkState _state = LinkState.Offline;
    private string _label = LinkStateInfo.Label(LinkState.Offline);

    public StatusPill()
    {
        SetStyle(ControlStyles.SupportsTransparentBackColor | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint, true);
        BackColor = Color.Transparent;
        Font = Theme.Strong(11f);
        Height = 36;
        TabStop = false;
        AccessibleRole = AccessibleRole.StaticText;
    }

    public void Set(LinkState state, string? label = null)
    {
        _state = state;
        _label = label ?? LinkStateInfo.Label(state);
        AccessibleName = "Estado: " + _label;
        Invalidate();
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        base.OnPaint(e);
        var glyph = new Rectangle(0, (Height - 16) / 2, 16, 16);
        LinkStateInfo.DrawGlyph(e.Graphics, _state, glyph);
        TextRenderer.DrawText(e.Graphics, _label, Font, new Rectangle(26, 0, Math.Max(10, Width - 26), Height), Theme.Text,
            TextFormatFlags.Left | TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis | TextFormatFlags.NoPrefix);
    }
}

/// <summary>Flat text label that follows the theme; the single place that decides label fonts and colours.</summary>
internal sealed class ThemedLabel : Label
{
    public enum Kind { Title, Heading, Body, Dim }

    private readonly Kind _kind;
    private Color? _override;

    /// <summary>Re-reads the colour for the active theme (called when the theme changes). A colour set explicitly stays.</summary>
    public void ApplyTheme()
    {
        if (_override is null) ForeColor = _kind == Kind.Dim ? Theme.TextDim : Theme.Text;
    }

    /// <summary>Use for a deliberate colour (accent code, warning): it survives theme switches only if the caller re-sets it.</summary>
    public void SetColor(Color color) { _override = color; ForeColor = color; }

    public ThemedLabel(string text, Kind kind = Kind.Body)
    {
        _kind = kind;
        SetStyle(ControlStyles.SupportsTransparentBackColor, true);
        BackColor = Color.Transparent;
        AutoSize = false;
        UseMnemonic = false;
        Text = text;
        switch (kind)
        {
            case Kind.Title: Font = Theme.Title(20f); ForeColor = Theme.Text; Height = 44; break;
            case Kind.Heading: Font = Theme.Strong(12f); ForeColor = Theme.Text; Height = 30; break;
            case Kind.Dim: Font = Theme.Body(9.5f); ForeColor = Theme.TextDim; Height = 24; break;
            default: Font = Theme.Body(10f); ForeColor = Theme.Text; Height = 28; break;
        }
        TextAlign = ContentAlignment.MiddleLeft;
    }
}
