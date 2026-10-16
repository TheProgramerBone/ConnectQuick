using System.Globalization;
using System.Text;

namespace ConnectQuick.WindowsHost.Files;

/// <summary>
/// Everything that decides where an incoming/outgoing file may live. The phone never supplies a
/// path: it supplies a display NAME, which is reduced to a single safe path segment here, joined to
/// a root the user configured, re-validated after canonicalisation, and created exclusively
/// (never overwriting, never following a pre-planted link).
/// </summary>
public static class FileSafety
{
    public const int MaxNameLength = 200;

    private static readonly HashSet<string> ReservedBaseNames = new(StringComparer.OrdinalIgnoreCase)
    {
        "CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "COM¹", "COM²", "COM³",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9", "LPT¹", "LPT²", "LPT³",
    };

    private static bool IsForbidden(char c) =>
        c < 0x20 || c == 0x7F || c is '<' or '>' or ':' or '"' or '/' or '\\' or '|' or '?' or '*'
        // Bidi controls and zero-width characters are how "photo<RLO>gpj.exe" masquerades as a JPEG.
        || (c >= '​' && c <= '‏') || (c >= '‪' && c <= '‮') || (c >= '⁦' && c <= '⁩') || c == '﻿';

    /// <summary>
    /// Reduces any string to ONE safe Windows file-name segment. Never throws, never returns a path,
    /// "." / ".." or a reserved device name, and is idempotent.
    /// </summary>
    public static string SanitizeFileName(string? raw)
    {
        var s = (raw ?? "").Normalize(NormalizationForm.FormC);
        var sb = new StringBuilder(s.Length);
        foreach (var c in s) sb.Append(IsForbidden(c) ? '_' : c);
        var name = sb.ToString().Trim(' ').TrimEnd('.', ' ');

        // Reserved device names are reserved regardless of extension: "aux.txt" still opens AUX.
        var dot = name.IndexOf('.');
        var stem = dot < 0 ? name : name[..dot];
        if (ReservedBaseNames.Contains(stem.TrimEnd(' '))) name = "_" + name;

        if (name.Length > MaxNameLength)
        {
            var ext = Path.GetExtension(name);
            if (ext.Length > 20) ext = ext[..20];
            name = name[..(MaxNameLength - ext.Length)] + ext;
            // Do not cut a surrogate pair in half.
            if (char.IsHighSurrogate(name[MaxNameLength - ext.Length - 1])) name = name.Remove(MaxNameLength - ext.Length - 1, 1);
        }
        name = name.TrimEnd('.', ' ');
        return name.Length == 0 ? "file" : name;
    }

    /// <summary>True when <paramref name="path"/> is a symlink or junction (or cannot be inspected).</summary>
    public static bool IsReparsePoint(string path)
    {
        try
        {
            var attrs = File.GetAttributes(path);
            return attrs.HasFlag(FileAttributes.ReparsePoint);
        }
        catch (FileNotFoundException) { return false; }
        catch (DirectoryNotFoundException) { return false; }
        catch (Exception) { return true; } // cannot tell => do not trust
    }

    /// <summary>
    /// Joins a single sanitized segment onto the root and proves the result is a direct child of the
    /// root after canonicalisation. Returns null otherwise.
    /// </summary>
    public static string? ResolveChild(string root, string safeName)
    {
        if (safeName != SanitizeFileName(safeName)) return null; // callers must sanitize first
        var rootFull = Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        var full = Path.GetFullPath(Path.Combine(rootFull, safeName));
        var parent = Path.GetDirectoryName(full)?.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        return string.Equals(parent, rootFull, StringComparison.OrdinalIgnoreCase) ? full : null;
    }

    /// <summary>"report.pdf" -> "report (1).pdf" -> "report (2).pdf" ... first name that does not exist in <paramref name="dir"/>.</summary>
    public static string UniqueName(string dir, string safeName, int attempt = 0)
    {
        var stem = Path.GetFileNameWithoutExtension(safeName);
        var ext = Path.GetExtension(safeName);
        var candidate = attempt == 0 ? safeName : SanitizeFileName($"{stem} ({attempt.ToString(CultureInfo.InvariantCulture)}){ext}");
        return File.Exists(Path.Combine(dir, candidate)) || Directory.Exists(Path.Combine(dir, candidate))
            ? UniqueName(dir, safeName, attempt + 1)
            : candidate;
    }
}
