using System.Runtime.InteropServices;
using System.Runtime.Versioning;

namespace ConnectQuick.WindowsHost.Streaming;

/// <summary>
/// One captured desktop frame, top-down BGRA. <see cref="Bgra"/> is a reused buffer: valid only
/// until the next call to <see cref="IScreenCapturer.Capture"/>. When the desktop cannot be read
/// (UAC prompt, lock screen, Ctrl+Alt+Del) <see cref="BlockedReason"/> says so instead of
/// returning a misleading black picture as if it were the user's screen.
/// </summary>
public readonly record struct CaptureResult(int Width, int Height, byte[]? Bgra, string? BlockedReason)
{
    public static CaptureResult Blocked(int width, int height, string reason) => new(width, height, null, reason);
}

public interface IScreenCapturer : IDisposable
{
    CaptureResult Capture();
}

/// <summary>
/// Desktop capture with plain GDI (BitBlt of the screen DC into a DIB section) plus the mouse
/// cursor. It works on every supported Windows version and needs no extra dependency, at the price
/// of CPU: roughly one full-frame copy per capture. The streaming pipeline downscales and skips
/// unchanged frames so the cost stays modest; Windows Graphics Capture / DXGI Desktop Duplication
/// (GPU copies) are the upgrade path behind this same interface when profiling on real hardware
/// shows it is needed. Cannot capture the secure desktop or DRM-protected video — by design.
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class GdiScreenCapturer : IScreenCapturer
{
    private const int SmCxScreen = 0, SmCyScreen = 1;
    private const uint SrcCopy = 0x00CC0020, CaptureBlt = 0x40000000;
    private const uint DesktopSwitchDesktop = 0x0100;
    private const int CursorShowing = 0x1;
    private const uint DiNormal = 0x3;

    [StructLayout(LayoutKind.Sequential)] private struct BITMAPINFOHEADER { public uint biSize; public int biWidth, biHeight; public ushort biPlanes, biBitCount; public uint biCompression, biSizeImage; public int biXPelsPerMeter, biYPelsPerMeter; public uint biClrUsed, biClrImportant; }
    [StructLayout(LayoutKind.Sequential)] private struct BITMAPINFO { public BITMAPINFOHEADER bmiHeader; public uint bmiColors; }
    [StructLayout(LayoutKind.Sequential)] private struct POINT { public int x, y; }
    [StructLayout(LayoutKind.Sequential)] private struct CURSORINFO { public int cbSize, flags; public IntPtr hCursor; public POINT ptScreenPos; }
    [StructLayout(LayoutKind.Sequential)] private struct ICONINFO { public bool fIcon; public int xHotspot, yHotspot; public IntPtr hbmMask, hbmColor; }

    [DllImport("user32.dll")] private static extern int GetSystemMetrics(int nIndex);
    [DllImport("user32.dll")] private static extern IntPtr GetDC(IntPtr hWnd);
    [DllImport("user32.dll")] private static extern int ReleaseDC(IntPtr hWnd, IntPtr hDC);
    [DllImport("user32.dll")] private static extern IntPtr OpenInputDesktop(uint dwFlags, bool fInherit, uint dwDesiredAccess);
    [DllImport("user32.dll")] private static extern bool CloseDesktop(IntPtr hDesktop);
    [DllImport("user32.dll")] private static extern bool GetCursorInfo(ref CURSORINFO pci);
    [DllImport("user32.dll")] private static extern bool GetIconInfo(IntPtr hIcon, out ICONINFO piconinfo);
    [DllImport("user32.dll")] private static extern bool DrawIconEx(IntPtr hdc, int xLeft, int yTop, IntPtr hIcon, int cxWidth, int cyHeight, uint istepIfAniCur, IntPtr hbrFlickerFreeDraw, uint diFlags);
    [DllImport("gdi32.dll")] private static extern IntPtr CreateCompatibleDC(IntPtr hdc);
    [DllImport("gdi32.dll")] private static extern IntPtr CreateDIBSection(IntPtr hdc, ref BITMAPINFO pbmi, uint usage, out IntPtr ppvBits, IntPtr hSection, uint offset);
    [DllImport("gdi32.dll")] private static extern IntPtr SelectObject(IntPtr hdc, IntPtr h);
    [DllImport("gdi32.dll")] private static extern bool BitBlt(IntPtr hdcDest, int x, int y, int cx, int cy, IntPtr hdcSrc, int x1, int y1, uint rop);
    [DllImport("gdi32.dll")] private static extern bool DeleteObject(IntPtr ho);
    [DllImport("gdi32.dll")] private static extern bool DeleteDC(IntPtr hdc);

    private int _width, _height;
    private IntPtr _memDc, _dib, _bits, _oldBitmap;
    private byte[] _buffer = Array.Empty<byte>();

    private bool EnsureSurface(int w, int h)
    {
        if (_dib != IntPtr.Zero && w == _width && h == _height) return true;
        ReleaseSurface();
        var screen = GetDC(IntPtr.Zero);
        if (screen == IntPtr.Zero) return false;
        try
        {
            _memDc = CreateCompatibleDC(screen);
            var info = new BITMAPINFO { bmiHeader = new BITMAPINFOHEADER { biSize = (uint)Marshal.SizeOf<BITMAPINFOHEADER>(), biWidth = w, biHeight = -h /* top-down */, biPlanes = 1, biBitCount = 32 } };
            _dib = CreateDIBSection(screen, ref info, 0, out _bits, IntPtr.Zero, 0);
            if (_dib == IntPtr.Zero || _bits == IntPtr.Zero) { ReleaseSurface(); return false; }
            _oldBitmap = SelectObject(_memDc, _dib);
            _width = w; _height = h;
            _buffer = new byte[w * h * 4];
            return true;
        }
        finally { ReleaseDC(IntPtr.Zero, screen); }
    }

    private void ReleaseSurface()
    {
        if (_memDc != IntPtr.Zero && _oldBitmap != IntPtr.Zero) SelectObject(_memDc, _oldBitmap);
        if (_dib != IntPtr.Zero) DeleteObject(_dib);
        if (_memDc != IntPtr.Zero) DeleteDC(_memDc);
        _memDc = _dib = _bits = _oldBitmap = IntPtr.Zero;
    }

    public CaptureResult Capture()
    {
        var w = GetSystemMetrics(SmCxScreen);
        var h = GetSystemMetrics(SmCyScreen);
        if (w <= 0 || h <= 0) return CaptureResult.Blocked(Math.Max(w, 2), Math.Max(h, 2), "no_display");

        // OpenInputDesktop fails when the desktop receiving input is not ours to read: UAC consent,
        // the lock screen, Ctrl+Alt+Del. Say so rather than streaming black as if it were the screen.
        var inputDesktop = OpenInputDesktop(0, false, DesktopSwitchDesktop);
        if (inputDesktop == IntPtr.Zero) return CaptureResult.Blocked(w, h, "secure_desktop");
        CloseDesktop(inputDesktop);

        if (!EnsureSurface(w, h)) return CaptureResult.Blocked(w, h, "capture_unavailable");
        var screen = GetDC(IntPtr.Zero);
        if (screen == IntPtr.Zero) return CaptureResult.Blocked(w, h, "capture_unavailable");
        try
        {
            if (!BitBlt(_memDc, 0, 0, w, h, screen, 0, 0, SrcCopy | CaptureBlt)) return CaptureResult.Blocked(w, h, "capture_failed");
        }
        finally { ReleaseDC(IntPtr.Zero, screen); }

        DrawCursor();
        Marshal.Copy(_bits, _buffer, 0, _buffer.Length);
        return new CaptureResult(w, h, _buffer, null);
    }

    private void DrawCursor()
    {
        var ci = new CURSORINFO { cbSize = Marshal.SizeOf<CURSORINFO>() };
        if (!GetCursorInfo(ref ci) || (ci.flags & CursorShowing) == 0 || ci.hCursor == IntPtr.Zero) return;
        if (!GetIconInfo(ci.hCursor, out var icon)) return;
        try { DrawIconEx(_memDc, ci.ptScreenPos.x - icon.xHotspot, ci.ptScreenPos.y - icon.yHotspot, ci.hCursor, 0, 0, 0, IntPtr.Zero, DiNormal); }
        finally
        {
            if (icon.hbmMask != IntPtr.Zero) DeleteObject(icon.hbmMask);
            if (icon.hbmColor != IntPtr.Zero) DeleteObject(icon.hbmColor);
        }
    }

    public void Dispose() => ReleaseSurface();
}
