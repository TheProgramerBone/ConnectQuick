namespace ConnectQuick.WindowsHost.Streaming;

/// <summary>Resolution helpers for the encoder: pick an aspect-correct, even output size and downscale BGRA into a reused buffer.</summary>
public static class FrameScaler
{
    /// <summary>Largest even size that fits <c>maxW x maxH</c> while keeping the source aspect ratio. Never upscales.</summary>
    public static (int Width, int Height) FitWithin(int srcW, int srcH, int maxW, int maxH)
    {
        var scale = Math.Min(1.0, Math.Min((double)maxW / srcW, (double)maxH / srcH));
        var w = Math.Max(2, (int)(srcW * scale) & ~1);
        var h = Math.Max(2, (int)(srcH * scale) & ~1);
        return (w, h);
    }

    /// <summary>
    /// Bilinear downscale (nearest when the sizes match). Rows are independent, so they are split
    /// across cores; the destination is filled in place and must be at least dstW*dstH*4 bytes.
    /// </summary>
    public static void ScaleBgra(byte[] src, int srcW, int srcH, byte[] dst, int dstW, int dstH)
    {
        if (src.Length < srcW * srcH * 4) throw new ArgumentException("source buffer too small", nameof(src));
        if (dst.Length < dstW * dstH * 4) throw new ArgumentException("destination buffer too small", nameof(dst));
        if (srcW == dstW && srcH == dstH)
        {
            Buffer.BlockCopy(src, 0, dst, 0, dstW * dstH * 4);
            return;
        }
        var xRatio = (srcW - 1) / (double)Math.Max(1, dstW - 1);
        var yRatio = (srcH - 1) / (double)Math.Max(1, dstH - 1);
        Parallel.For(0, dstH, y =>
        {
            var sy = y * yRatio;
            var y0 = (int)sy;
            var y1 = Math.Min(y0 + 1, srcH - 1);
            var fy = (int)((sy - y0) * 256);
            for (var x = 0; x < dstW; x++)
            {
                var sx = x * xRatio;
                var x0 = (int)sx;
                var x1 = Math.Min(x0 + 1, srcW - 1);
                var fx = (int)((sx - x0) * 256);
                var i00 = (y0 * srcW + x0) * 4;
                var i10 = (y0 * srcW + x1) * 4;
                var i01 = (y1 * srcW + x0) * 4;
                var i11 = (y1 * srcW + x1) * 4;
                var o = (y * dstW + x) * 4;
                for (var c = 0; c < 4; c++)
                {
                    var top = src[i00 + c] * (256 - fx) + src[i10 + c] * fx;
                    var bottom = src[i01 + c] * (256 - fx) + src[i11 + c] * fx;
                    dst[o + c] = (byte)((top * (256 - fy) + bottom * fy) >> 16);
                }
            }
        });
    }
}
