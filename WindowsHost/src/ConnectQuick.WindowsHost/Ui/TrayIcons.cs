using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;

namespace ConnectQuick.WindowsHost.Ui;

/// <summary>
/// Draws the tray/window icon in code (no binary assets in the repo): a glass tile with a link glyph and
/// a state badge whose SHAPE changes with the state, so it is readable on any tray background and
/// without colour vision.
/// </summary>
internal static class TrayIcons
{
    private static readonly Dictionary<LinkState, Icon> Cache = new();

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool DestroyIcon(IntPtr handle);

    public static Icon Render(LinkState state)
    {
        lock (Cache)
        {
            if (Cache.TryGetValue(state, out var cached)) return cached;
            var icon = Draw(state, 32);
            Cache[state] = icon;
            return icon;
        }
    }

    private static Icon Draw(LinkState state, int size)
    {
        using var bmp = new Bitmap(size, size);
        using (var g = Graphics.FromImage(bmp))
        {
            g.Clear(Color.Transparent);
            Theme.Quality(g);

            var tile = new Rectangle(1, 1, size - 3, size - 3);
            using (var path = Theme.RoundedRect(tile, 8))
            {
                using var fill = new LinearGradientBrush(tile, Color.FromArgb(70, 140, 255), Color.FromArgb(24, 40, 110), LinearGradientMode.ForwardDiagonal);
                g.FillPath(fill, path);
                using var rim = new Pen(Color.FromArgb(200, 255, 255, 255), 1.4f);
                g.DrawPath(rim, path);
            }

            // Two interlocked rounded links.
            using var link = new Pen(Color.White, 2.6f) { StartCap = LineCap.Round, EndCap = LineCap.Round };
            g.DrawEllipse(link, 6, 8, 11, 11);
            g.DrawEllipse(link, 13, 13, 11, 11);

            var badge = new Rectangle(size - 13, size - 13, 11, 11);
            using var halo = new SolidBrush(Color.FromArgb(24, 40, 110));
            g.FillEllipse(halo, Rectangle.Inflate(badge, 2, 2));
            LinkStateInfo.DrawGlyph(g, state, badge);
        }

        var handle = bmp.GetHicon();
        try
        {
            using var temp = Icon.FromHandle(handle);
            return (Icon)temp.Clone(); // detach from the native handle so it can be destroyed
        }
        finally
        {
            DestroyIcon(handle);
        }
    }
}
