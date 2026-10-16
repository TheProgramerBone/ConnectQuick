using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;

namespace ConnectQuick.WindowsHost.Power;

[SupportedOSPlatform("windows")]
public sealed class Win32PowerExecutor : IPowerExecutor
{
    [DllImport("user32.dll", SetLastError = true)] private static extern bool LockWorkStation();
    [DllImport("powrprof.dll", SetLastError = true)] private static extern bool SetSuspendState(bool hibernate, bool forceCritical, bool disableWakeEvent);

    private const int NoShutdownInProgress = 1116; // ERROR_NO_SHUTDOWN_IN_PROGRESS

    public PowerOutcome Lock() => LockWorkStation() ? new(true) : new(false, "os_refused");

    public PowerOutcome Sleep() => SetSuspendState(false, false, false) ? new(true) : new(false, "os_refused");

    public PowerOutcome Restart(int delaySeconds) => Shutdown("/r", delaySeconds);

    public PowerOutcome Shutdown(int delaySeconds) => Shutdown("/s", delaySeconds);

    public PowerOutcome CancelPending()
    {
        var code = Run("/a");
        return code switch
        {
            0 => new(true),
            NoShutdownInProgress => new(false, "nothing_to_cancel"),
            _ => new(false, "os_refused"),
        };
    }

    private PowerOutcome Shutdown(string mode, int delaySeconds)
    {
        // Fixed program, fixed switches; the only variable is a clamped integer.
        var code = Run(mode, "/t", Math.Clamp(delaySeconds, 0, 600).ToString(), "/c", "ConnectQuick remote request");
        return code == 0 ? new(true) : new(false, "os_refused");
    }

    private static int Run(params string[] args)
    {
        var psi = new ProcessStartInfo(Path.Combine(Environment.SystemDirectory, "shutdown.exe"))
        {
            UseShellExecute = false,
            CreateNoWindow = true,
        };
        foreach (var a in args) psi.ArgumentList.Add(a);
        using var p = Process.Start(psi);
        if (p is null) return -1;
        if (!p.WaitForExit(5000)) { try { p.Kill(); } catch { /* best effort */ } return -2; }
        return p.ExitCode;
    }
}
