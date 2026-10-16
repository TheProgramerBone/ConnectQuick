namespace ConnectQuick.WindowsHost.Diagnostics;

public enum LogLevel
{
    Info,
    Warn,
    Error,
}

public sealed record LogEntry(DateTimeOffset At, LogLevel Level, string Category, string Message);

/// <summary>
/// Diagnostic log shown in the settings window and optionally mirrored to a file. Callers pass
/// identifiers and counts only: never key material, signatures, clipboard text, file contents or
/// SDP. <see cref="Redact"/> is a last line of defence for accidental long blobs, not a licence to
/// log secrets.
/// </summary>
public sealed class HostEventLog
{
    private readonly object _lock = new();
    private readonly LinkedList<LogEntry> _entries = new();
    private readonly string? _filePath;
    private const int Capacity = 500;

    public event Action<LogEntry>? Added;

    public HostEventLog(string? filePath = null)
    {
        _filePath = filePath;
    }

    public IReadOnlyList<LogEntry> Snapshot()
    {
        lock (_lock) return _entries.ToList();
    }

    public void Info(string category, string message) => Add(LogLevel.Info, category, message);
    public void Warn(string category, string message) => Add(LogLevel.Warn, category, message);
    public void Error(string category, string message) => Add(LogLevel.Error, category, message);

    public void Add(LogLevel level, string category, string message)
    {
        var entry = new LogEntry(DateTimeOffset.Now, level, category, Redact(message));
        lock (_lock)
        {
            _entries.AddLast(entry);
            while (_entries.Count > Capacity) _entries.RemoveFirst();
            if (_filePath is not null)
            {
                try
                {
                    Directory.CreateDirectory(Path.GetDirectoryName(_filePath)!);
                    if (File.Exists(_filePath) && new FileInfo(_filePath).Length > 1_000_000) File.Move(_filePath, _filePath + ".1", true);
                    File.AppendAllText(_filePath, $"{entry.At:O} {entry.Level,-5} {entry.Category}: {entry.Message}{Environment.NewLine}");
                }
                catch (IOException) { /* diagnostics must never break the app */ }
                catch (UnauthorizedAccessException) { }
            }
        }
        Added?.Invoke(entry);
    }

    internal static string Redact(string message)
    {
        var oneLine = message.Replace('\r', ' ').Replace('\n', ' ');
        return oneLine.Length > 300 ? oneLine[..300] + "..." : oneLine;
    }
}
