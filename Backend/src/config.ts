export interface Config {
  port: number;
  dbPath: string;
  protocolVersion: number;
  pairingTtlSeconds: number;
  heartbeatIntervalMs: number;
  heartbeatTimeoutMs: number;
  maxMessageBytes: number;
  rateLimit: {
    pairingCreatePerMinutePerHost: number;
    pairingInitPerMinutePerIp: number;
    messagesPerSecondPerConnection: number;
  };
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  return {
    port: Number(env["PORT"] ?? 8443),
    dbPath: env["DB_PATH"] ?? "./data/connectquick.sqlite",
    protocolVersion: 1,
    pairingTtlSeconds: 60,
    heartbeatIntervalMs: 15_000,
    heartbeatTimeoutMs: 40_000,
    maxMessageBytes: 64 * 1024,
    rateLimit: {
      pairingCreatePerMinutePerHost: 10,
      pairingInitPerMinutePerIp: 5,
      messagesPerSecondPerConnection: 30,
    },
  };
}
