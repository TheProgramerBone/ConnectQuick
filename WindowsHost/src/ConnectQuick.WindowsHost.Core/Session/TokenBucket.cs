using System.Diagnostics;

namespace ConnectQuick.WindowsHost.Session;

/// <summary>Thread-safe token bucket; the clock is injectable so tests do not sleep.</summary>
public sealed class TokenBucket
{
    private readonly double _capacity;
    private readonly double _refillPerSecond;
    private readonly Func<long> _nowTicks;
    private readonly object _lock = new();
    private double _tokens;
    private long _last;

    public TokenBucket(double capacity, double refillPerSecond, Func<long>? nowTicks = null)
    {
        _capacity = capacity;
        _refillPerSecond = refillPerSecond;
        _nowTicks = nowTicks ?? Stopwatch.GetTimestamp;
        _tokens = capacity;
        _last = _nowTicks();
    }

    public bool TryConsume(double cost = 1)
    {
        lock (_lock)
        {
            var now = _nowTicks();
            var elapsedSeconds = (now - _last) / (double)Stopwatch.Frequency;
            if (elapsedSeconds > 0)
            {
                _tokens = Math.Min(_capacity, _tokens + elapsedSeconds * _refillPerSecond);
                _last = now;
            }
            if (_tokens < cost) return false;
            _tokens -= cost;
            return true;
        }
    }
}
