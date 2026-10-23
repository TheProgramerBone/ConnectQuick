import { randomBytes } from "node:crypto";
export interface IceConfig {
  /** STUN server URLs handed to clients (stun:host:port). Empty list = no STUN. */
  stunUrls: string[];
  /** TURN server URLs (turn:/turns:host:port?transport=...). Empty list = no TURN. */
  turnUrls: string[];
  /** coturn `static-auth-secret`. Never sent to clients; only used to mint short-lived credentials. */
  turnSecret: string | null;
  turnTtlSeconds: number;
  /**
   * TURN server embedded in this process (TCP; for a PC that is its own relay behind Tailscale Funnel
   * or any TLS-terminating TCP proxy). null = disabled. When set, `turnUrls` are what controllers are
   * told to dial (the public, usually `turns:` address) and hosts get no TURN: the relayed traffic
   * ends on this very machine, which the host reaches directly.
   */
  embeddedTurn: { port: number; relayIp: string | null } | null;
}

export interface SessionLimits {
  /** A session.connect the Host never answers is dropped after this long. */
  acceptTimeoutMs: number;
  /** No session.data in either direction for this long => the session is considered dead. */
  idleTimeoutMs: number;
  /** Hard cap on one session's lifetime; clients re-handshake (new ephemeral keys) after it. */
  maxDurationMs: number;
  /** Max |now - issuedAt| accepted on session.connect (replay window). */
  connectSkewMs: number;
  /** If the peer's socket has more than this queued, the session is cut instead of buffering forever. */
  maxBufferedBytes: number;
  dataFramesPerSecond: number;
  dataBytesPerSecond: number;
}

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
    /** Pre-parse flood cap, applies to every frame including session.data. */
    rawFramesPerSecondPerConnection: number;
  };
  session: SessionLimits;
  ice: IceConfig;
  limits: {
    maxConnections: number;
    maxConnectionsPerIp: number;
  };
  /** Trust X-Forwarded-For for the client address. Only enable behind a reverse proxy you control. */
  trustProxy: boolean;
  /** Browser origins allowed to open the WebSocket. Native clients send no Origin header at all. */
  allowedOrigins: string[];
  shutdownGraceMs: number;
  /** When set, GET /metrics (Authorization: Bearer <token>) exposes counters. Unset => 404. */
  metricsToken: string | null;
}

function intEnv(env: NodeJS.ProcessEnv, name: string, fallback: number, min = 0): number {
  const raw = env[name];
  if (raw === undefined || raw === "") return fallback;
  const n = Number(raw);
  if (!Number.isInteger(n) || n < min) {
    throw new Error(`invalid ${name}: expected an integer >= ${min}, got "${raw}"`);
  }
  return n;
}

function listEnv(env: NodeJS.ProcessEnv, name: string, fallback: string[]): string[] {
  const raw = env[name];
  if (raw === undefined) return fallback;
  return raw
    .split(",")
    .map((s) => s.trim())
    .filter((s) => s.length > 0);
}

/** Default public STUN only helps clients discover their reflexive address; it never relays media. */
const DEFAULT_STUN = ["stun:stun.l.google.com:19302"];

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const embeddedPortRaw = env["TURN_EMBEDDED_PORT"];
  const embeddedTurn = embeddedPortRaw !== undefined && embeddedPortRaw !== ""
    ? { port: intEnv(env, "TURN_EMBEDDED_PORT", 3478, 0), relayIp: env["TURN_RELAY_IP"] ? env["TURN_RELAY_IP"] : null }
    : null;
  const turnUrls = listEnv(env, "TURN_URLS", []);
  // The embedded server only needs a secret shared with this same process: a random one per run is fine.
  const turnSecret = env["TURN_SECRET"] ? env["TURN_SECRET"] : embeddedTurn ? randomBytes(32).toString("base64") : null;
  if (turnUrls.length > 0 && !turnSecret) {
    throw new Error("TURN_URLS is set but TURN_SECRET is not: refusing to hand out unauthenticated TURN URLs");
  }
  return {
    port: intEnv(env, "PORT", 8443, 0),
    dbPath: env["DB_PATH"] ?? "./data/connectquick.sqlite",
    protocolVersion: 1,
    pairingTtlSeconds: 60,
    heartbeatIntervalMs: 15_000,
    heartbeatTimeoutMs: 40_000,
    maxMessageBytes: 64 * 1024,
    rateLimit: {
      pairingCreatePerMinutePerHost: intEnv(env, "PAIRING_CREATE_PER_MINUTE_PER_HOST", 10, 1),
      // Many people behind one NAT (an office, a campus) share an address: operators can raise this.
      pairingInitPerMinutePerIp: intEnv(env, "PAIRING_INIT_PER_MINUTE_PER_IP", 5, 1),
      messagesPerSecondPerConnection: 30,
      rawFramesPerSecondPerConnection: intEnv(env, "RAW_FRAMES_PER_SECOND", 600, 1),
    },
    session: {
      acceptTimeoutMs: intEnv(env, "SESSION_ACCEPT_TIMEOUT_MS", 30_000, 1000),
      idleTimeoutMs: intEnv(env, "SESSION_IDLE_TIMEOUT_MS", 60_000, 1000),
      maxDurationMs: intEnv(env, "SESSION_MAX_DURATION_MS", 12 * 3600_000, 60_000),
      connectSkewMs: 60_000,
      maxBufferedBytes: intEnv(env, "SESSION_MAX_BUFFERED_BYTES", 4 * 1024 * 1024, 1024),
      dataFramesPerSecond: intEnv(env, "SESSION_FRAMES_PER_SECOND", 400, 1),
      dataBytesPerSecond: intEnv(env, "SESSION_BYTES_PER_SECOND", 8 * 1024 * 1024, 1024),
    },
    ice: {
      stunUrls: listEnv(env, "STUN_URLS", DEFAULT_STUN),
      turnUrls,
      turnSecret,
      turnTtlSeconds: intEnv(env, "TURN_TTL_SECONDS", 3600, 60),
      embeddedTurn,
    },
    limits: {
      maxConnections: intEnv(env, "MAX_CONNECTIONS", 5000, 1),
      maxConnectionsPerIp: intEnv(env, "MAX_CONNECTIONS_PER_IP", 50, 1),
    },
    trustProxy: env["TRUST_PROXY"] === "1",
    allowedOrigins: listEnv(env, "ALLOWED_ORIGINS", []),
    shutdownGraceMs: intEnv(env, "SHUTDOWN_GRACE_MS", 5000, 0),
    metricsToken: env["METRICS_TOKEN"] ? env["METRICS_TOKEN"] : null,
  };
}
