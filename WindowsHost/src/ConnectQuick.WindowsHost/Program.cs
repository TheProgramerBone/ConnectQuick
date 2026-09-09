using ConnectQuick.WindowsHost.Config;
using ConnectQuick.WindowsHost.Identity;
using ConnectQuick.WindowsHost.Net;
using ConnectQuick.WindowsHost.Pairing;
using ConnectQuick.WindowsHost.Ui;

namespace ConnectQuick.WindowsHost;

static class Program
{
    [STAThread]
    static void Main()
    {
        ApplicationConfiguration.Initialize();

        var config = HostConfig.LoadDefault();
        var identity = DeviceIdentity.LoadOrCreate(new SecureKeyStore());
        var deviceStore = new AuthorizedDeviceStore();
        var client = new BackendClient(config.RelayUri);
        var coordinator = new PairingCoordinator(client, identity, config, deviceStore);

        var lifetimeCts = new CancellationTokenSource();
        _ = client.RunForeverAsync(lifetimeCts.Token); // background connect/reconnect loop for the whole app lifetime

        using var trayContext = new TrayApplicationContext(coordinator, deviceStore, config, identity);
        Application.ApplicationExit += (_, _) => lifetimeCts.Cancel();
        Application.Run(trayContext);
    }
}
