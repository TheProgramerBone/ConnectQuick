using SIPSorceryMedia.Abstractions;
using SIPSorceryMedia.Encoders;

namespace ConnectQuick.WindowsHost.Streaming;

public readonly record struct EncodedFrame(byte[] Data, bool IsKeyFrame);

/// <summary>Turns raw BGRA frames into compressed video. Swappable: today VP8 via libvpx; a hardware H.264 encoder would slot in here.</summary>
public interface IFrameEncoder : IDisposable
{
    /// <summary>Encodes one frame; null when the encoder produced nothing for it (rate control may skip frames).</summary>
    EncodedFrame? Encode(byte[] bgra, int width, int height, bool forceKeyFrame);
    /// <summary>Applies a new target bitrate. May restart the encoder and so emit a key frame.</summary>
    void SetBitrate(int kbps);
}

/// <summary>
/// VP8 through the libvpx wrapper shipped with SIPSorceryMedia.Encoders (<c>vpxmd.dll</c>, Windows x86/x64).
///
/// The Encoders package (built for Abstractions 8.x) and SIPSorcery 10.x number <c>VideoCodecsEnum</c>
/// differently (VP8 is 7 versus 8), so the codec value handed to the encoder is taken from the format
/// the encoder itself advertises rather than from our own compiled constant. <c>VideoPixelFormatsEnum</c>
/// is identical in both. <see cref="StreamingSelfTest"/> pins these assumptions.
/// </summary>
public sealed class Vp8LibvpxEncoder : IFrameEncoder
{
    private readonly object _lock = new();
    private VpxVideoEncoder _encoder;
    private readonly VideoCodecsEnum _encoderCodec;
    private bool _needKeyFrame = true;

    public Vp8LibvpxEncoder(int initialKbps)
    {
        _encoder = new VpxVideoEncoder { TargetKbps = (uint)Math.Max(100, initialKbps) };
        _encoderCodec = _encoder.SupportedFormats.Single().Codec; // the one codec it implements: VP8, in ITS numbering
    }

    public EncodedFrame? Encode(byte[] bgra, int width, int height, bool forceKeyFrame)
    {
        lock (_lock)
        {
            if (forceKeyFrame || _needKeyFrame)
            {
                _encoder.ForceKeyFrame();
                _needKeyFrame = false;
            }
            var data = _encoder.EncodeVideo(width, height, bgra, VideoPixelFormatsEnum.Bgra, _encoderCodec);
            if (data is null || data.Length == 0) return null;
            return new EncodedFrame(data, IsVp8KeyFrame(data));
        }
    }

    public void SetBitrate(int kbps)
    {
        lock (_lock)
        {
            if (_encoder.TargetKbps == (uint)kbps) return;
            // The wrapper fixes the rate at initialisation, so a new rate means a fresh encoder (and a key frame).
            _encoder.Dispose();
            _encoder = new VpxVideoEncoder { TargetKbps = (uint)Math.Max(100, kbps) };
            _needKeyFrame = true;
        }
    }

    /// <summary>RFC 6386 section 9.1: bit 0 of the first byte is 0 for key frames, followed by the start code 9D 01 2A.</summary>
    public static bool IsVp8KeyFrame(byte[] frame) =>
        frame.Length >= 6 && (frame[0] & 0x01) == 0 && frame[3] == 0x9D && frame[4] == 0x01 && frame[5] == 0x2A;

    public void Dispose()
    {
        lock (_lock) _encoder.Dispose();
    }
}
