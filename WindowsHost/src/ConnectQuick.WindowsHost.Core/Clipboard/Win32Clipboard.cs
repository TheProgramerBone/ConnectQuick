using System.Runtime.InteropServices;
using System.Runtime.Versioning;

namespace ConnectQuick.WindowsHost.Clipboard;

[SupportedOSPlatform("windows")]
public sealed class Win32Clipboard : IClipboard
{
    private const uint CfUnicodeText = 13;
    private const uint GmemMoveable = 0x0002;

    [DllImport("user32.dll")] private static extern uint GetClipboardSequenceNumber();
    [DllImport("user32.dll", SetLastError = true)] private static extern bool OpenClipboard(IntPtr hWndNewOwner);
    [DllImport("user32.dll", SetLastError = true)] private static extern bool CloseClipboard();
    [DllImport("user32.dll")] private static extern bool EmptyClipboard();
    [DllImport("user32.dll")] private static extern IntPtr GetClipboardData(uint uFormat);
    [DllImport("user32.dll")] private static extern IntPtr SetClipboardData(uint uFormat, IntPtr hMem);
    [DllImport("user32.dll")] private static extern bool IsClipboardFormatAvailable(uint format);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern uint RegisterClipboardFormat(string lpszFormat);
    [DllImport("kernel32.dll")] private static extern IntPtr GlobalAlloc(uint uFlags, UIntPtr dwBytes);
    [DllImport("kernel32.dll")] private static extern IntPtr GlobalLock(IntPtr hMem);
    [DllImport("kernel32.dll")] private static extern bool GlobalUnlock(IntPtr hMem);
    [DllImport("kernel32.dll")] private static extern IntPtr GlobalFree(IntPtr hMem);

    private static readonly Lazy<uint> ExcludeFormat = new(() => RegisterClipboardFormat("ExcludeClipboardContentFromMonitorProcessing"));

    public long SequenceNumber => GetClipboardSequenceNumber();

    public bool IsExcludedFromMonitoring => Open() && Using(() => IsClipboardFormatAvailable(ExcludeFormat.Value));

    private static bool Open()
    {
        // Another process may hold the clipboard for a few ms; retry briefly instead of failing.
        for (var i = 0; i < 10; i++)
        {
            if (OpenClipboard(IntPtr.Zero)) return true;
            Thread.Sleep(10);
        }
        return false;
    }

    private static T Using<T>(Func<T> body)
    {
        try { return body(); }
        finally { CloseClipboard(); }
    }

    public string? GetText()
    {
        if (!Open()) return null;
        return Using(() =>
        {
            if (!IsClipboardFormatAvailable(CfUnicodeText)) return null;
            var handle = GetClipboardData(CfUnicodeText);
            if (handle == IntPtr.Zero) return null;
            var ptr = GlobalLock(handle);
            if (ptr == IntPtr.Zero) return null;
            try { return Marshal.PtrToStringUni(ptr); }
            finally { GlobalUnlock(handle); }
        });
    }

    public bool SetText(string text)
    {
        if (!Open()) return false;
        return Using(() =>
        {
            EmptyClipboard();
            var bytes = (text.Length + 1) * 2;
            var hMem = GlobalAlloc(GmemMoveable, (UIntPtr)bytes);
            if (hMem == IntPtr.Zero) return false;
            var ptr = GlobalLock(hMem);
            if (ptr == IntPtr.Zero) { GlobalFree(hMem); return false; }
            try
            {
                Marshal.Copy(text.ToCharArray(), 0, ptr, text.Length);
                Marshal.WriteInt16(ptr, text.Length * 2, 0);
            }
            finally { GlobalUnlock(hMem); }
            if (SetClipboardData(CfUnicodeText, hMem) == IntPtr.Zero) { GlobalFree(hMem); return false; }
            return true; // ownership of hMem passed to the system
        });
    }
}
