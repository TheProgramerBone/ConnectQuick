using ConnectQuick.WindowsHost.Config;
using Xunit;

namespace ConnectQuick.WindowsHost.Tests;

public class RelayListTests
{
    private static readonly Uri Cloud = new("wss://35-209-191-155.sslip.io");

    [Fact]
    public void Without_extra_relays_the_QR_is_unchanged()
    {
        var c = new HostConfig(Cloud, "PC");
        Assert.Null(c.PhoneRelays);
        Assert.Empty(c.HostExtraRelayUris);
    }

    [Fact]
    public void The_phone_gets_the_local_relay_first_and_the_main_one_last()
    {
        var c = new HostConfig(Cloud, "PC", ["ws://192.168.1.50:8443"]);
        Assert.Equal(new[] { "ws://192.168.1.50:8443", "wss://35-209-191-155.sslip.io" }, c.PhoneRelays);
    }

    [Fact]
    public void The_PC_itself_reaches_its_local_relay_over_loopback()
    {
        var c = new HostConfig(Cloud, "PC", ["ws://{lan}:8443"]);
        Assert.Equal(new[] { "ws://127.0.0.1:8443/" }, c.HostExtraRelayUris.Select(u => u.ToString()));
    }

    [Fact]
    public void A_repeat_of_the_main_relay_or_junk_is_ignored()
    {
        var c = new HostConfig(Cloud, "PC", ["wss://35-209-191-155.sslip.io", "not a url", "http://x.example", "ws://192.168.1.50:8443", "ws://192.168.1.50:8443"]);
        Assert.Equal(new[] { "ws://192.168.1.50:8443", "wss://35-209-191-155.sslip.io" }, c.PhoneRelays);
        Assert.Single(c.HostExtraRelayUris);
    }
}
