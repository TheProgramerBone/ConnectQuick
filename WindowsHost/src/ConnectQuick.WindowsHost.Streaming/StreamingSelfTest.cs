using SIPSorceryMedia.Abstractions;
using SIPSorceryMedia.Encoders;

namespace ConnectQuick.WindowsHost.Streaming;

/// <summary>
/// Run once before the first stream. It proves the video encoder is genuinely usable on this
/// machine (the native libvpx loads and produces a VP8 key frame) and that the codec numbering
/// assumption documented on <see cref="Vp8LibvpxEncoder"/> still holds, so a broken install is
/// reported to the user as "video unavailable" instead of failing in the middle of a call.
/// </summary>
public static class StreamingSelfTest
{
    public static bool Run(out string detail)
    {
        try
        {
            if ((int)VideoCodecsEnum.VP8 != 8) { detail = "SIPSorcery codec numbering changed (VP8 != 8)"; return false; }
            using var probe = new VpxVideoEncoder();
            var supported = probe.SupportedFormats;
            if (supported.Count != 1) { detail = $"expected one encoder format, found {supported.Count}"; return false; }

            using var enc = new Vp8LibvpxEncoder(500);
            var frame = new byte[64 * 64 * 4];
            for (var i = 0; i < frame.Length; i += 4) { frame[i] = 200; frame[i + 1] = 100; frame[i + 2] = 50; frame[i + 3] = 255; }
            var encoded = enc.Encode(frame, 64, 64, forceKeyFrame: true);
            if (encoded is null || !Vp8LibvpxEncoder.IsVp8KeyFrame(encoded.Value.Data)) { detail = "the encoder did not produce a VP8 key frame"; return false; }
            detail = "ok";
            return true;
        }
        catch (Exception ex) when (ex is DllNotFoundException or BadImageFormatException or EntryPointNotFoundException)
        {
            detail = "libvpx could not be loaded: " + ex.GetType().Name;
            return false;
        }
        catch (Exception ex)
        {
            detail = ex.GetType().Name + ": " + ex.Message;
            return false;
        }
    }
}
