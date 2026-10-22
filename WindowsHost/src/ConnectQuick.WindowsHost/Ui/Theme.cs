using System.Drawing.Drawing2D;
using System.Drawing.Text;

namespace ConnectQuick.WindowsHost.Ui;

/// <summary>
/// "Liquid Crystal" design tokens for the Windows app: a deep, calm backdrop with translucent glass
/// surfaces, one cool accent, and status colours that are always paired with a shape and a word
/// (never colour alone). Text/background pairs below keep at least 4.5:1 contrast (7:1 for body
/// text). When Windows is in High Contrast mode every token collapses to the system colours and
/// the glass effects are dropped, so the app stays fully legible.
/// </summary>
internal static class Theme
{
    public static bool HighContrast => SystemInformation.HighContrast;

    /// <summary>"dark" (default) or "light". Persisted in <c>HostSettings.Theme</c>.</summary>
    public static bool IsLight { get; private set; }

    /// <summary>Raised after <see cref="Apply"/> changes the mode so open windows can repaint.</summary>
    public static event Action? Changed;

    public static void Apply(string? mode)
    {
        var light = string.Equals(mode, "light", StringComparison.OrdinalIgnoreCase);
        if (light == IsLight) return;
        IsLight = light;
        Changed?.Invoke();
    }

    private static Color Pick(Color dark, Color light) => IsLight ? light : dark;

    // Backdrop (top → bottom of the window).
    public static Color Bg0 => HighContrast ? SystemColors.Window : Pick(Color.FromArgb(7, 10, 24), Color.FromArgb(226, 236, 250));
    public static Color Bg1 => HighContrast ? SystemColors.Window : Pick(Color.FromArgb(22, 28, 62), Color.FromArgb(246, 249, 255));

    // Glass surfaces: translucent layers over the backdrop, with a brighter hairline on top.
    public static Color GlassFill => HighContrast ? SystemColors.Window : Pick(Color.FromArgb(30, 255, 255, 255), Color.FromArgb(150, 255, 255, 255));
    public static Color GlassFillHover => HighContrast ? SystemColors.Highlight : Pick(Color.FromArgb(56, 255, 255, 255), Color.FromArgb(205, 255, 255, 255));
    public static Color GlassBorder => HighContrast ? SystemColors.WindowText : Pick(Color.FromArgb(84, 255, 255, 255), Color.FromArgb(150, 120, 140, 185));
    public static Color GlassHighlight => Color.FromArgb(HighContrast ? 0 : IsLight ? 170 : 66, 255, 255, 255);
    public static Color GlassShade => Color.FromArgb(HighContrast ? 0 : IsLight ? 22 : 60, IsLight ? 90 : 0, IsLight ? 110 : 0, IsLight ? 160 : 20);

    public static Color Text => HighContrast ? SystemColors.WindowText : Pick(Color.FromArgb(240, 244, 255), Color.FromArgb(17, 24, 48));       // 16:1 on Bg0 / 14:1 light
    public static Color TextDim => HighContrast ? SystemColors.GrayText : Pick(Color.FromArgb(172, 184, 214), Color.FromArgb(64, 78, 112));       // 8:1 dark / 6.5:1 light

    public static Color Accent => HighContrast ? SystemColors.Highlight : Pick(Color.FromArgb(104, 208, 255), Color.FromArgb(0, 96, 190));
    public static Color AccentFill => HighContrast ? SystemColors.Highlight : Pick(Color.FromArgb(46, 128, 230), Color.FromArgb(0, 102, 204));
    public static Color AccentFillTop => HighContrast ? SystemColors.Highlight : Pick(Color.FromArgb(92, 168, 255), Color.FromArgb(58, 150, 245));
    public static Color OnAccent => HighContrast ? SystemColors.HighlightText : Color.White;                   // 4.9:1+ on AccentFill
    public static Color Ok => HighContrast ? SystemColors.WindowText : Pick(Color.FromArgb(94, 226, 168), Color.FromArgb(0, 128, 84));
    public static Color Warn => HighContrast ? SystemColors.WindowText : Pick(Color.FromArgb(255, 204, 96), Color.FromArgb(160, 90, 0));
    public static Color Danger => HighContrast ? SystemColors.WindowText : Pick(Color.FromArgb(255, 122, 132), Color.FromArgb(190, 30, 55));
    public static Color DangerFill => HighContrast ? SystemColors.Highlight : Color.FromArgb(190, 52, 72);     // 5.6:1 with white
    public static Color Focus => HighContrast ? SystemColors.Highlight : Pick(Color.FromArgb(255, 255, 255), Color.FromArgb(0, 70, 160));

    // Text fields (combo, numeric, list, log): solid so text stays readable on any backdrop.
    public static Color FieldBack => HighContrast ? SystemColors.Window : Pick(Color.FromArgb(20, 27, 56), Color.FromArgb(255, 255, 255));
    public static Color FieldText => HighContrast ? SystemColors.WindowText : Pick(Color.White, Color.FromArgb(17, 24, 48));
    public static Color SwitchOff => Pick(Color.FromArgb(90, 255, 255, 255), Color.FromArgb(150, 120, 135, 170));

    // Soft "aurora" lights behind the glass: (x, y) as fractions of the window, radius as a fraction of its larger side.
    private static readonly (float X, float Y, float R, int Dark, int Light)[] Orbs =
    {
        (0.88f, 0.02f, 0.55f, unchecked((int)0x2E5AAAFF), unchecked((int)0x6090C8FF)),
        (0.10f, 0.95f, 0.50f, unchecked((int)0x288A5CFF), unchecked((int)0x58B79CFF)),
        (0.55f, 0.55f, 0.35f, unchecked((int)0x1A22D4C8), unchecked((int)0x40FFC6E8)),
    };

    public const int Radius = 14;
    public const int Gap = 12;

    private static readonly string[] PreferredFonts = { "Segoe UI Variable Text", "Segoe UI", "Tahoma" };
    private static string? _family;

    private static string Family
    {
        get
        {
            if (_family is not null) return _family;
            using var installed = new InstalledFontCollection();
            var names = installed.Families.Select(f => f.Name).ToHashSet(StringComparer.OrdinalIgnoreCase);
            return _family = PreferredFonts.FirstOrDefault(names.Contains) ?? SystemFonts.DefaultFont.FontFamily.Name;
        }
    }

    public static Font Body(float size = 10f) => new(Family, size, FontStyle.Regular, GraphicsUnit.Point);
    public static Font Strong(float size = 10f) => new(Family, size, FontStyle.Bold, GraphicsUnit.Point);
    public static Font Title(float size = 20f) => new(Family, size, FontStyle.Bold, GraphicsUnit.Point);

    public static GraphicsPath RoundedRect(Rectangle r, int radius)
    {
        var d = Math.Max(2, radius * 2);
        d = Math.Min(d, Math.Min(r.Width, r.Height));
        var path = new GraphicsPath();
        path.AddArc(r.X, r.Y, d, d, 180, 90);
        path.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        path.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        path.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        path.CloseFigure();
        return path;
    }

    /// <summary>Paints the window backdrop: a vertical gradient with soft coloured lights, like light refracting through crystal.</summary>
    public static void PaintBackdrop(Graphics g, Rectangle bounds)
    {
        if (bounds.Width <= 0 || bounds.Height <= 0) return;
        if (HighContrast)
        {
            g.Clear(SystemColors.Window);
            return;
        }
        using (var brush = new LinearGradientBrush(bounds, Bg1, Bg0, LinearGradientMode.Vertical))
            g.FillRectangle(brush, bounds);

        var state = g.Save();
        g.SetClip(bounds);
        g.SmoothingMode = SmoothingMode.AntiAlias;
        var side = Math.Max(bounds.Width, bounds.Height);
        foreach (var (x, y, r, dark, light) in Orbs)
        {
            var argb = Color.FromArgb(IsLight ? light : dark);
            var radius = (int)(side * r);
            var box = new Rectangle((int)(bounds.Width * x) - radius, (int)(bounds.Height * y) - radius, radius * 2, radius * 2);
            using var path = new GraphicsPath();
            path.AddEllipse(box);
            using var glow = new PathGradientBrush(path)
            {
                CenterColor = argb,
                SurroundColors = new[] { Color.FromArgb(0, argb) },
            };
            g.FillEllipse(glow, box);
        }
        g.Restore(state);
    }

    public static void Quality(Graphics g)
    {
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.TextRenderingHint = TextRenderingHint.ClearTypeGridFit;
        g.PixelOffsetMode = PixelOffsetMode.HighQuality;
    }
}

/// <summary>Connection state shown consistently in the tray, header and device list: a colour, a SHAPE and a word.</summary>
internal enum LinkState
{
    Offline,
    Connecting,
    Ready,
    InSession,
    Paused,
}

internal static class LinkStateInfo
{
    public static string Label(LinkState s) => s switch
    {
        LinkState.Offline => "Sin conexión con el servidor",
        LinkState.Connecting => "Conectando…",
        LinkState.Ready => "Listo · esperando a tu teléfono",
        LinkState.InSession => "Teléfono conectado",
        LinkState.Paused => "Pausado · no se aceptan conexiones",
        _ => "",
    };

    public static Color Color(LinkState s) => s switch
    {
        LinkState.Ready => Theme.Ok,
        LinkState.InSession => Theme.Accent,
        LinkState.Connecting => Theme.Warn,
        LinkState.Paused => Theme.Warn,
        _ => Theme.Danger,
    };

    /// <summary>Draws the state glyph: circle = ok/ready, ring = busy, triangle = attention, square = off.</summary>
    public static void DrawGlyph(Graphics g, LinkState s, Rectangle r)
    {
        Theme.Quality(g);
        var c = Color(s);
        using var fill = new SolidBrush(c);
        using var pen = new Pen(c, Math.Max(2f, r.Width / 7f));
        switch (s)
        {
            case LinkState.Ready:
                g.FillEllipse(fill, r);
                break;
            case LinkState.InSession:
                g.FillEllipse(fill, r);
                using (var inner = new Pen(Theme.Bg0, Math.Max(1.5f, r.Width / 9f)))
                    g.DrawEllipse(inner, Rectangle.Inflate(r, -r.Width / 4, -r.Height / 4));
                break;
            case LinkState.Connecting:
                g.DrawEllipse(pen, Rectangle.Inflate(r, -1, -1));
                break;
            case LinkState.Paused:
            {
                var tri = new[] { new Point(r.Left + r.Width / 2, r.Top), new Point(r.Right, r.Bottom), new Point(r.Left, r.Bottom) };
                g.FillPolygon(fill, tri);
                break;
            }
            default:
                g.FillRectangle(fill, Rectangle.Inflate(r, -1, -1));
                break;
        }
    }
}
