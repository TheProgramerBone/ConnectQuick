using ConnectQuick.WindowsHost.Settings;
using Xunit;

namespace ConnectQuick.WindowsHost.Tests;

public class SettingsThemeTests
{
    [Fact]
    public void Default_theme_is_dark()
    {
        Assert.Equal("dark", new HostSettings().Theme);
    }

    [Theory]
    [InlineData("light", "light")]
    [InlineData("LIGHT", "light")]
    [InlineData("dark", "dark")]
    [InlineData("neon", "dark")]
    [InlineData("", "dark")]
    public void Normalize_keeps_only_known_themes(string input, string expected)
    {
        var s = new HostSettings { Theme = input };
        s.Normalize();
        Assert.Equal(expected, s.Theme);
    }
}
