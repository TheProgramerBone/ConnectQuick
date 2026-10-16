interface Bucket {
  tokens: number;
  lastRefillMs: number;
}

/** Simple in-memory token-bucket rate limiter, keyed by an arbitrary string (IP, hostId, deviceId). */
export class TokenBucketRateLimiter {
  private readonly capacity: number;
  private readonly refillPerMs: number;
  private readonly buckets = new Map<string, Bucket>();

  constructor(capacity: number, refillPerSecond: number) {
    this.capacity = capacity;
    this.refillPerMs = refillPerSecond / 1000;
  }

  /** Returns true if `cost` tokens were available and consumed; false if rate-limited. */
  tryConsume(key: string, cost = 1, now = Date.now()): boolean {
    let bucket = this.buckets.get(key);
    if (!bucket) {
      bucket = { tokens: this.capacity, lastRefillMs: now };
      this.buckets.set(key, bucket);
    }
    const elapsed = now - bucket.lastRefillMs;
    if (elapsed > 0) {
      bucket.tokens = Math.min(this.capacity, bucket.tokens + elapsed * this.refillPerMs);
      bucket.lastRefillMs = now;
    }
    if (bucket.tokens < cost) return false;
    bucket.tokens -= cost;
    return true;
  }

  /** Periodic cleanup so long-lived processes don't accumulate one bucket per ever-seen key forever. */
  evictIdleSince(cutoffMs: number, now = Date.now()): number {
    let evicted = 0;
    for (const [key, bucket] of this.buckets) {
      if (now - bucket.lastRefillMs > cutoffMs && bucket.tokens >= this.capacity) {
        this.buckets.delete(key);
        evicted++;
      }
    }
    return evicted;
  }

  /** Forget one key (e.g. when the thing it throttles, such as a session, ends). */
  delete(key: string): void {
    this.buckets.delete(key);
  }

  size(): number {
    return this.buckets.size;
  }
}
