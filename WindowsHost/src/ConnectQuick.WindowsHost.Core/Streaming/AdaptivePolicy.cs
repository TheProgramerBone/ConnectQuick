namespace ConnectQuick.WindowsHost.Streaming;

/// <summary>
/// Chooses the next stream quality from the loss the receiver reports. Pure and deterministic, so
/// it is unit-tested instead of discovered in the field. Shape: back off fast when packets are being
/// lost (multiplicative decrease), recover slowly after several clean reports (small increase), and
/// trade resolution and frame rate for bitrate when the pipe is very small — a sharp low-fps
/// picture beats a smeared one for reading a desktop.
/// </summary>
public static class AdaptivePolicy
{
    public const int MinBitrateKbps = 150;
    private static readonly double[] ScaleLadder = { 1.0, 0.75, 0.5 };
    private const int CleanReportsToIncrease = 3;

    public readonly record struct State(int ScaleIndex, int CleanStreak);

    /// <param name="max">The ceiling the user/host allowed; adaptation never exceeds it.</param>
    public static (StreamProfile Profile, State State) Next(StreamProfile current, StreamProfile max, State state, double fractionLost)
    {
        var bitrate = current.BitrateKbps;
        var scaleIndex = state.ScaleIndex;
        var clean = state.CleanStreak;

        if (fractionLost >= 0.10)
        {
            bitrate = (int)(bitrate * 0.6);
            clean = 0;
        }
        else if (fractionLost >= 0.03)
        {
            bitrate = (int)(bitrate * 0.85);
            clean = 0;
        }
        else if (fractionLost < 0.01)
        {
            clean++;
            if (clean >= CleanReportsToIncrease)
            {
                bitrate = (int)Math.Ceiling(bitrate * 1.15);
                clean = 0;
            }
        }
        else
        {
            clean = 0; // 1-3 % loss: hold steady
        }

        bitrate = Math.Clamp(bitrate, MinBitrateKbps, max.BitrateKbps);

        // Resolution follows the bitrate band it can afford.
        var fraction = (double)bitrate / max.BitrateKbps;
        var wantedScale = fraction < 0.35 ? 2 : fraction < 0.70 ? 1 : 0;
        if (wantedScale > scaleIndex) scaleIndex = wantedScale;            // drop quality immediately
        else if (wantedScale < scaleIndex && clean == 0 && fractionLost < 0.01) scaleIndex--; // climb back one step at a time

        var scale = ScaleLadder[Math.Clamp(scaleIndex, 0, ScaleLadder.Length - 1)];
        var fps = bitrate < 300 ? Math.Min(max.Fps, 15) : max.Fps;
        var profile = new StreamProfile(Even((int)(max.Width * scale)), Even((int)(max.Height * scale)), fps, bitrate);
        return (profile, new State(scaleIndex, clean));
    }

    private static int Even(int v) => Math.Max(2, v & ~1);
}
