namespace ConnectQuick.WindowsHost.Input;

/// <summary>A physical key as Windows wants to receive it: set-1 scan code (+E0 prefix) or, for the few keys without one, a virtual-key.</summary>
public readonly record struct KeyInfo(ushort Scan, bool Extended, ushort Vk = 0);

/// <summary>
/// Maps DOM <c>KeyboardEvent.code</c> names (what the phone sends in <c>input.key</c>) to Windows
/// scan codes. Scan codes are layout-independent ("the key in the A position"), which is what a
/// remote keyboard wants; text that must come out as specific characters uses <c>input.text</c>
/// instead. Anything not in this table is rejected, never guessed.
/// </summary>
public static class KeyMap
{
    private static readonly Dictionary<string, KeyInfo> Map = Build();

    public static bool TryGet(string code, out KeyInfo key) => Map.TryGetValue(code, out key);
    public static IReadOnlyCollection<string> Codes => Map.Keys;

    private static Dictionary<string, KeyInfo> Build()
    {
        var m = new Dictionary<string, KeyInfo>(StringComparer.Ordinal);
        void P(string code, ushort scan) => m[code] = new KeyInfo(scan, false);
        void E(string code, ushort scan) => m[code] = new KeyInfo(scan, true);
        void V(string code, ushort vk) => m[code] = new KeyInfo(0, false, vk);

        const string row1 = "1234567890";
        for (var i = 0; i < row1.Length; i++) P("Digit" + row1[i], (ushort)(0x02 + i));
        const string rowQ = "QWERTYUIOP";
        for (var i = 0; i < rowQ.Length; i++) P("Key" + rowQ[i], (ushort)(0x10 + i));
        const string rowA = "ASDFGHJKL";
        for (var i = 0; i < rowA.Length; i++) P("Key" + rowA[i], (ushort)(0x1E + i));
        const string rowZ = "ZXCVBNM";
        for (var i = 0; i < rowZ.Length; i++) P("Key" + rowZ[i], (ushort)(0x2C + i));

        P("Escape", 0x01); P("Minus", 0x0C); P("Equal", 0x0D); P("Backspace", 0x0E); P("Tab", 0x0F);
        P("BracketLeft", 0x1A); P("BracketRight", 0x1B); P("Enter", 0x1C); P("ControlLeft", 0x1D);
        P("Semicolon", 0x27); P("Quote", 0x28); P("Backquote", 0x29); P("ShiftLeft", 0x2A); P("Backslash", 0x2B);
        P("Comma", 0x33); P("Period", 0x34); P("Slash", 0x35); P("ShiftRight", 0x36);
        P("AltLeft", 0x38); P("Space", 0x39); P("CapsLock", 0x3A);
        for (var i = 0; i < 10; i++) P("F" + (i + 1), (ushort)(0x3B + i));
        P("F11", 0x57); P("F12", 0x58);
        for (var i = 13; i <= 24; i++) V("F" + i, (ushort)(0x7C + (i - 13)));

        P("NumLock", 0x45); P("ScrollLock", 0x46);
        P("Numpad7", 0x47); P("Numpad8", 0x48); P("Numpad9", 0x49); P("NumpadSubtract", 0x4A);
        P("Numpad4", 0x4B); P("Numpad5", 0x4C); P("Numpad6", 0x4D); P("NumpadAdd", 0x4E);
        P("Numpad1", 0x4F); P("Numpad2", 0x50); P("Numpad3", 0x51); P("Numpad0", 0x52);
        P("NumpadDecimal", 0x53); P("NumpadMultiply", 0x37);
        E("NumpadEnter", 0x1C); E("NumpadDivide", 0x35);

        E("ControlRight", 0x1D); E("AltRight", 0x38);
        E("MetaLeft", 0x5B); E("MetaRight", 0x5C); E("ContextMenu", 0x5D);
        E("PrintScreen", 0x37); V("Pause", 0x13);
        E("Insert", 0x52); E("Delete", 0x53); E("Home", 0x47); E("End", 0x4F); E("PageUp", 0x49); E("PageDown", 0x51);
        E("ArrowUp", 0x48); E("ArrowDown", 0x50); E("ArrowLeft", 0x4B); E("ArrowRight", 0x4D);
        return m;
    }
}
