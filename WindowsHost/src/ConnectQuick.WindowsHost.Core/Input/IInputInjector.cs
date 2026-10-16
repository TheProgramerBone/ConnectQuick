namespace ConnectQuick.WindowsHost.Input;

public enum MouseButton
{
    Left,
    Right,
    Middle,
    X1,
    X2,
}

/// <summary>
/// The only seam between validated input intent and the operating system. The production
/// implementation is <see cref="Win32InputInjector"/>; tests and the headless harness substitute a
/// recorder, which is how the full phone-to-injection path is exercised without a desktop.
/// Every method returns false when the OS refused (e.g. the foreground window is elevated).
/// </summary>
public interface IInputInjector
{
    bool MouseMoveRelative(int dx, int dy);
    /// <summary>x,y in 0..65535 across the primary monitor.</summary>
    bool MouseMoveAbsolute(int x, int y);
    bool MouseButton(MouseButton button, bool down);
    /// <summary>Wheel deltas in 1/120 notch units (positive: up / right).</summary>
    bool MouseWheel(int dx, int dy);
    bool Key(KeyInfo key, bool down);
    bool Text(string text);
}
