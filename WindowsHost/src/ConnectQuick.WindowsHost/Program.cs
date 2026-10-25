using ConnectQuick.WindowsHost.Clipboard;
using ConnectQuick.WindowsHost.Config;
using ConnectQuick.WindowsHost.Identity;
using ConnectQuick.WindowsHost.Input;
using ConnectQuick.WindowsHost.Media;
using ConnectQuick.WindowsHost.Power;
using ConnectQuick.WindowsHost.Runtime;
using ConnectQuick.WindowsHost.Streaming;
using ConnectQuick.WindowsHost.Ui;

namespace ConnectQuick.WindowsHost;

static class Program
{
    [STAThread]
    static void Main()
    {
        // One instance per user: two copies would fight over the same identity on the relay.
        using var singleInstance = new Mutex(initiallyOwned: true, @"Local\ConnectQuick.WindowsHost", out var isFirst);
        if (!isFirst)
        {
            MessageBox.Show("ConnectQuick ya se está ejecutando. Búscalo en la bandeja del sistema (junto al reloj).", "ConnectQuick", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        ApplicationConfiguration.Initialize();
        Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);

        var config = HostConfig.LoadDefault();
        var appData = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "ConnectQuick");
        var approver = new UiConnectionApprover();

        // The same HostRuntime the integration tests drive, wired to the real Windows adapters.
        var runtime = new HostRuntime(new HostRuntimeOptions
        {
            RelayUri = config.RelayUri,
            ExtraRelays = config.ExtraRelays,
            HostName = config.HostName,
            SeedStore = new SecureKeyStore(),
            Adapters = new HostAdapters(new Win32InputInjector(), new Win32Clipboard(), new Win32MediaKeys(), new Win32PowerExecutor()),
            LogPath = Path.Combine(appData, "logs", "connectquick.log"),
            Approver = approver,
            VideoPeers = SipSorceryVideoPeerFactory.ForWindows(),
        });

        Application.ThreadException += (_, e) => runtime.Log.Error("ui", $"unhandled UI error: {e.Exception.GetType().Name}");

        using var tray = new TrayApplicationContext(runtime, approver);
        runtime.Start();
        Application.Run(tray);

        // Releasing held keys/buttons and closing the relay happens in DisposeAsync; do not skip it on exit.
        runtime.DisposeAsync().AsTask().GetAwaiter().GetResult();
    }
}
