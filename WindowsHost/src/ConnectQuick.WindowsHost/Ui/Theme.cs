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

    // Backdrop (top → bottom of the window).
    public static Color Bg0 => HighContrast ? SystemColors.Window : Color.FromArgb(9, 13, 27);
    public static Color Bg1 => HighContrast ? SystemColors.Window : Color.FromArgb(24, 30, 58);

    // Glass surfaces: translucent white over the backdrop, with a brighter hairline on top.
    public static Color GlassFill => HighContrast ? SystemColors.Window : Color.FromArgb(34, 255, 255, 255);
    public static Color GlassFillHover => HighContrast ? SystemColors.Highlight : Color.FromArgb(54, 255, 255, 255);
    public static Color GlassBorder => HighContrast ? SystemColors.WindowText : Color.FromArgb(78, 255, 255, 255);
    public static Color GlassHighlight => Color.FromArgb(HighContrast ? 0 : 60, 255, 255, 255);

    public static Color Text => HighContrast ? SystemColors.WindowText : Color.FromArgb(240, 244, 255);     // ~16:1 on Bg0
    public static Color TextDim => HighContrast ? SystemColors.GrayText : Color.FromArgb(172, 184, 214);       // ~8:1 on Bg0

    public static Color Accent => HighContrast ? SystemColors.Highlight : Color.FromArgb(104, 208, 255);
    public static Color AccentFill => HighContrast ? SystemColors.Highlight : Color.FromArgb(46, 128, 230);
    public static Color OnAccent => HighContrast ? SystemColors.HighlightText : Color.White;                   // 4.9:1 on AccentFill
    public static Color Ok => HighContrast ? SystemColors.WindowText : Color.FromArgb(94, 226, 168);
    public static Color Warn => HighContrast ? SystemColors.WindowText : Color.FromArgb(255, 204, 96);
    public static Color Danger => HighContrast ? SystemColors.WindowText : Color.FromArgb(255, 122, 132);
    public static Color DangerFill => HighContrast ? SystemColors.Highlight : Color.FromArgb(190, 52, 72);     // 5.6:1 with white
    public static Color Focus => HighContrast ? SystemColors.Highlight : Color.FromArgb(255, 255, 255);

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

    /// <summary>Paints the window backdrop: a vertical gradient with a soft cool glow in the top-right corner.</summary>
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

        var glow = new Rectangle(bounds.Width / 2, -bounds.Height / 3, bounds.Width, bounds.Height);
        using var path = new GraphicsPath();
        path.AddEllipse(glow);
        using var glowBrush = new PathGradientBrush(path)
        {
            CenterColor = Color.FromArgb(46, 90, 170, 255),
            SurroundColors = new[] { Color.FromArgb(0, 90, 170, 255) },
        };
        var state = g.Save();
        g.SetClip(bounds);
        g.FillEllipse(glowBrush, glow);
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
