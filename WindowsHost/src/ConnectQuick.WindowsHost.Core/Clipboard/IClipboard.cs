namespace ConnectQuick.WindowsHost.Clipboard;

/// <summary>Plain-text clipboard access. The Windows implementation is <see cref="Win32Clipboard"/>.</summary>
public interface IClipboard
{
    /// <summary>Changes whenever any application changes the clipboard (GetClipboardSequenceNumber).</summary>
    long SequenceNumber { get; }
    /// <summary>Current text, or null when empty or not text (images, files, ... are never synchronised).</summary>
    string? GetText();
    bool SetText(string text);
    /// <summary>True when the owner asked clipboard monitors to ignore this content (password managers do).</summary>
    bool IsExcludedFromMonitoring { get; }
}
