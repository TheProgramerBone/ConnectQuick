using System.Runtime.InteropServices;
using System.Runtime.Versioning;

namespace ConnectQuick.WindowsHost.Input;

/// <summary>
/// Injects input with <c>SendInput</c> — the documented, supported API. It cannot (and this code
/// does not try to) drive the secure desktop (UAC prompts, Ctrl+Alt+Del, the lock screen) or
/// windows of higher integrity: Windows blocks those by design (UIPI) and we report it instead of
/// attempting any workaround.
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class Win32InputInjector : IInputInjector
{
    private const uint InputMouse = 0, InputKeyboard = 1;
    private const uint MouseEventMove = 0x0001, MouseEventLeftDown = 0x0002, MouseEventLeftUp = 0x0004,
        MouseEventRightDown = 0x0008, MouseEventRightUp = 0x0010, MouseEventMiddleDown = 0x0020,
        MouseEventMiddleUp = 0x0040, MouseEventXDown = 0x0080, MouseEventXUp = 0x0100,
        MouseEventWheel = 0x0800, MouseEventHWheel = 0x1000, MouseEventAbsolute = 0x8000;
    private const uint KeyEventExtended = 0x0001, KeyEventKeyUp = 0x0002, KeyEventUnicode = 0x0004, KeyEventScanCode = 0x0008;

    [StructLayout(LayoutKind.Sequential)]
    internal struct MOUSEINPUT { public int dx, dy; public uint mouseData, dwFlags, time; public UIntPtr dwExtraInfo; }

    [StructLayout(LayoutKind.Sequential)]
    internal struct KEYBDINPUT { public ushort wVk, wScan; public uint dwFlags, time; public UIntPtr dwExtraInfo; }

    [StructLayout(LayoutKind.Sequential)]
    internal struct HARDWAREINPUT { public uint uMsg; public ushort wParamL, wParamH; }

    [StructLayout(LayoutKind.Explicit)]
    internal struct InputUnion
    {
        [FieldOffset(0)] public MOUSEINPUT mi;
        [FieldOffset(0)] public KEYBDINPUT ki;
        [FieldOffset(0)] public HARDWAREINPUT hi;
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct INPUT { public uint type; public InputUnion u; }

    /// <summary>Exposed so a test can pin the ABI size (40 bytes on x64): a wrong size makes SendInput fail silently.</summary>
    internal static int InputStructSize => Marshal.SizeOf<INPUT>();

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

    private static bool Send(params INPUT[] inputs) => SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<INPUT>()) == inputs.Length;

    private static INPUT Mouse(int dx, int dy, uint data, uint flags) =>
        new() { type = InputMouse, u = new InputUnion { mi = new MOUSEINPUT { dx = dx, dy = dy, mouseData = data, dwFlags = flags } } };

    private static INPUT Keyboard(ushort vk, ushort scan, uint flags) =>
        new() { type = InputKeyboard, u = new InputUnion { ki = new KEYBDINPUT { wVk = vk, wScan = scan, dwFlags = flags } } };

    public bool MouseMoveRelative(int dx, int dy) => Send(Mouse(dx, dy, 0, MouseEventMove));

    public bool MouseMoveAbsolute(int x, int y) => Send(Mouse(x, y, 0, MouseEventMove | MouseEventAbsolute));

    public bool MouseButton(MouseButton button, bool down) => button switch
    {
        Input.MouseButton.Left => Send(Mouse(0, 0, 0, down ? MouseEventLeftDown : MouseEventLeftUp)),
        Input.MouseButton.Right => Send(Mouse(0, 0, 0, down ? MouseEventRightDown : MouseEventRightUp)),
        Input.MouseButton.Middle => Send(Mouse(0, 0, 0, down ? MouseEventMiddleDown : MouseEventMiddleUp)),
        Input.MouseButton.X1 => Send(Mouse(0, 0, 1, down ? MouseEventXDown : MouseEventXUp)),
        _ => Send(Mouse(0, 0, 2, down ? MouseEventXDown : MouseEventXUp)),
    };

    public bool MouseWheel(int dx, int dy)
    {
        var ok = true;
        // mouseData is a DWORD carrying a signed delta.
        if (dy != 0) ok &= Send(Mouse(0, 0, unchecked((uint)dy), MouseEventWheel));
        if (dx != 0) ok &= Send(Mouse(0, 0, unchecked((uint)dx), MouseEventHWheel));
        return ok;
    }

    public bool Key(KeyInfo key, bool down)
    {
        var up = down ? 0u : KeyEventKeyUp;
        return key.Scan != 0
            ? Send(Keyboard(0, key.Scan, KeyEventScanCode | up | (key.Extended ? KeyEventExtended : 0)))
            : Send(Keyboard(key.Vk, 0, up));
    }

    public bool Text(string text)
    {
        var inputs = new List<INPUT>(text.Length * 2);
        foreach (var ch in text)
        {
            inputs.Add(Keyboard(0, ch, KeyEventUnicode));
            inputs.Add(Keyboard(0, ch, KeyEventUnicode | KeyEventKeyUp));
        }
        return Send(inputs.ToArray());
    }
}
