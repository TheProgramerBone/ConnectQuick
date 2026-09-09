import { test } from "node:test";
import assert from "node:assert/strict";
import { TokenBucketRateLimiter } from "../../src/rateLimiter.ts";

test("allows up to capacity requests immediately, then blocks", () => {
  const limiter = new TokenBucketRateLimiter(3, 1);
  const now = 1_000_000;
  assert.equal(limiter.tryConsume("k", 1, now), true);
  assert.equal(limiter.tryConsume("k", 1, now), true);
  assert.equal(limiter.tryConsume("k", 1, now), true);
  assert.equal(limiter.tryConsume("k", 1, now), false);
});

test("refills over time at the configured rate", () => {
  const limiter = new TokenBucketRateLimiter(1, 1); // 1 token/sec
  const t0 = 1_000_000;
  assert.equal(limiter.tryConsume("k", 1, t0), true);
  assert.equal(limiter.tryConsume("k", 1, t0 + 100), false); // 100ms later, not enough refill
  assert.equal(limiter.tryConsume("k", 1, t0 + 1000), true); // 1s later, refilled
});

test("buckets are independent per key", () => {
  const limiter = new TokenBucketRateLimiter(1, 1);
  const now = 1_000_000;
  assert.equal(limiter.tryConsume("a", 1, now), true);
  assert.equal(limiter.tryConsume("b", 1, now), true);
  assert.equal(limiter.tryConsume("a", 1, now), false);
});

test("evictIdleSince removes only long-idle, fully-refilled buckets", () => {
  const limiter = new TokenBucketRateLimiter(2, 1);
  const now = 1_000_000;
  limiter.tryConsume("stale", 1, now); // not full, won't be evicted yet
  limiter.tryConsume("stale", 1, now + 2000); // refills to full by now+2000
  limiter.tryConsume("fresh", 0, now + 100_000); // touch to reset lastRefillMs, still full
  const evicted = limiter.evictIdleSince(10_000, now + 200_000);
  assert.equal(evicted, 1);
  assert.equal(limiter.size(), 1);
});
