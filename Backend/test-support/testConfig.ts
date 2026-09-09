import type { Config } from "../src/config.ts";

const DEFAULT_RATE_LIMIT = {
  pairingCreatePerMinutePerHost: 10,
  pairingInitPerMinutePerIp: 5,
  messagesPerSecondPerConnection: 30,
};

export type ConfigOverrides = Omit<Partial<Config>, "rateLimit"> & {
  rateLimit?: Partial<Config["rateLimit"]>;
};

/** A full Config for tests — deliberately not derived from env, so tests are hermetic. */
export function testConfig(overrides: ConfigOverrides = {}): Config {
  const { rateLimit: rateLimitOverrides, ...rest } = overrides;
  return {
    port: 0, // ephemeral — OS picks a free port
    dbPath: ":memory:",
    protocolVersion: 1,
    pairingTtlSeconds: 60,
    heartbeatIntervalMs: 15_000,
    heartbeatTimeoutMs: 40_000,
    maxMessageBytes: 64 * 1024,
    rateLimit: { ...DEFAULT_RATE_LIMIT, ...rateLimitOverrides },
    ...rest,
  };
}
