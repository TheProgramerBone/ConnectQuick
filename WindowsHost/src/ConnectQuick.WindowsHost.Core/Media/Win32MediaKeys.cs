using System.Runtime.InteropServices;
using System.Runtime.Versioning;

namespace ConnectQuick.WindowsHost.Media;

[SupportedOSPlatform("windows")]
public sealed class Win32MediaKeys : IMediaKeys
{
    [StructLayout(LayoutKind.Sequential)]
    private struct KEYBDINPUT { public ushort wVk, wScan; public uint dwFlags, time; public UIntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT { public int dx, dy; public uint mouseData, dwFlags, time; public UIntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion { [FieldOffset(0)] public MOUSEINPUT mi; [FieldOffset(0)] public KEYBDINPUT ki; }
    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT { public uint type; public InputUnion u; }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

    private static ushort Vk(MediaKey key) => key switch
    {
        MediaKey.PlayPause => 0xB3,
        MediaKey.Next => 0xB0,
        MediaKey.Previous => 0xB1,
        MediaKey.Stop => 0xB2,
        MediaKey.VolumeUp => 0xAF,
        MediaKey.VolumeDown => 0xAE,
        _ => 0xAD,
    };

    public bool Press(MediaKey key)
    {
        const uint extended = 0x0001, keyUp = 0x0002;
        INPUT Make(uint flags) => new() { type = 1, u = new InputUnion { ki = new KEYBDINPUT { wVk = Vk(key), dwFlags = flags } } };
        var inputs = new[] { Make(extended), Make(extended | keyUp) };
        return SendInput(2, inputs, Marshal.SizeOf<INPUT>()) == 2;
    }
}
