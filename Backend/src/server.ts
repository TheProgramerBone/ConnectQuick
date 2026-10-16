import { randomUUID, timingSafeEqual } from "node:crypto";
import {
  createServer as createHttpServer,
  type IncomingMessage,
  type Server as HttpServer,
  type ServerResponse,
} from "node:http";
import { createServer as createHttpsServer, type Server as HttpsServer } from "node:https";
import { readFileSync } from "node:fs";
import { pathToFileURL } from "node:url";
import { WebSocketServer, type WebSocket } from "ws";
import { loadConfig, type Config } from "./config.ts";
import { openDatabase } from "./db.ts";
import { HostRepository } from "./registry/hostRepository.ts";
import { DeviceRepository } from "./registry/deviceRepository.ts";
import { PairingRepository } from "./registry/pairingRepository.ts";
import { Logger } from "./log.ts";
import { TokenBucketRateLimiter } from "./rateLimiter.ts";
import { parseEnvelope } from "./schemaValidator.ts";
import { randomBytesB64Url } from "./crypto.ts";
import { Connection } from "./ws/connection.ts";
import { ConnectionRegistry } from "./ws/connectionRegistry.ts";
import { PendingPairingStore } from "./ws/pendingPairings.ts";
import { ActiveSessionStore } from "./ws/activeSessions.ts";
import { route } from "./ws/router.ts";
import { broadcastPresence } from "./ws/handlers/presence.ts";
import { endSession } from "./ws/handlers/session.ts";
import { runMaintenance } from "./maintenance.ts";
import { ProtocolError, AuthError } from "./errors.ts";
import type { AppContext } from "./ws/context.ts";

export interface App {
  ctx: AppContext;
  wss: WebSocketServer;
  httpServer: HttpServer | HttpsServer;
  /** Runs one housekeeping pass now (tests use this with a fake clock). */
  maintain(now?: number): void;
}

export interface StartedServer {
  ctx: AppContext;
  address(): { port: number };
  /** Runs one housekeeping pass now (tests drive this with a fake clock). */
  maintain(now?: number): void;
  /** Stops accepting connections, tells clients to reconnect elsewhere, then closes. */
  drain(): Promise<void>;
  close(): Promise<void>;
}

function sendError(conn: Connection, code: string, message: string, refId?: string): void {
  conn.send("error", { code, message, ...(refId ? { refId } : {}) });
}

/** Client address as seen by the backend. X-Forwarded-For is honoured only when explicitly trusted. */
export function clientAddress(req: IncomingMessage, trustProxy: boolean): string {
  if (trustProxy) {
    const xff = req.headers["x-forwarded-for"];
    const first = (Array.isArray(xff) ? xff[0] : xff)?.split(",")[0]?.trim();
    if (first) return first;
  }
  return req.socket.remoteAddress ?? "unknown";
}

function bearerMatches(header: string | undefined, token: string): boolean {
  if (!header?.startsWith("Bearer ")) return false;
  const given = Buffer.from(header.slice(7));
  const expected = Buffer.from(token);
  return given.length === expected.length && timingSafeEqual(given, expected);
}

function handleHttp(ctx: AppContext, checkDb: () => void, req: IncomingMessage, res: ServerResponse): void {
  const path = (req.url ?? "/").split("?")[0];
  const send = (status: number, body: unknown): void => {
    res.writeHead(status, {
      "content-type": "application/json",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
    });
    res.end(JSON.stringify(body));
  };
  if (req.method !== "GET") return send(405, { error: "method_not_allowed" });
  if (path === "/healthz") return send(200, { status: "ok" });
  if (path === "/readyz") {
    if (ctx.draining) return send(503, { status: "draining" });
    try {
      checkDb();
      return send(200, { status: "ready" });
    } catch {
      return send(503, { status: "db_unavailable" });
    }
  }
  if (path === "/metrics" && ctx.config.metricsToken && bearerMatches(req.headers["authorization"], ctx.config.metricsToken)) {
    const sessions = ctx.activeSessions.all();
    return send(200, {
      connections: ctx.connections.all().length,
      sessionsPending: sessions.filter((s) => s.phase === "PENDING").length,
      sessionsActive: sessions.filter((s) => s.phase === "ACTIVE").length,
      pendingPairings: ctx.pendingPairings.size(),
    });
  }
  return send(404, { error: "not_found" });
}

/** Builds the app (db, registries, WS server) without binding a port — used directly by tests. */
export function createApp(config: Config = loadConfig()): App {
  const db = openDatabase(config.dbPath);
  const log = new Logger("backend");

  const ctx: AppContext = {
    config,
    hosts: new HostRepository(db),
    devices: new DeviceRepository(db),
    pairings: new PairingRepository(db),
    connections: new ConnectionRegistry(),
    pendingPairings: new PendingPairingStore(),
    activeSessions: new ActiveSessionStore(config.session.dataFramesPerSecond, config.session.dataBytesPerSecond),
    pairingInitLimiter: new TokenBucketRateLimiter(
      config.rateLimit.pairingInitPerMinutePerIp,
      config.rateLimit.pairingInitPerMinutePerIp / 60,
    ),
    messageLimiter: new TokenBucketRateLimiter(
      config.rateLimit.messagesPerSecondPerConnection,
      config.rateLimit.messagesPerSecondPerConnection,
    ),
    rawLimiter: new TokenBucketRateLimiter(
      config.rateLimit.rawFramesPerSecondPerConnection * 2,
      config.rateLimit.rawFramesPerSecondPerConnection,
    ),
    seenSessionIds: new Map(),
    draining: false,
    log,
  };

  const checkDb = (): void => {
    db.prepare("SELECT 1").get();
  };
  const onRequest = (req: IncomingMessage, res: ServerResponse): void => handleHttp(ctx, checkDb, req, res);

  const certPath = process.env["CERT_PATH"];
  const keyPath = process.env["KEY_PATH"];
  let httpServer: HttpServer | HttpsServer;
  if (certPath && keyPath) {
    httpServer = createHttpsServer({ cert: readFileSync(certPath), key: readFileSync(keyPath) }, onRequest);
    log.info("TLS enabled");
  } else {
    httpServer = createHttpServer(onRequest);
    log.warn(
      "no CERT_PATH/KEY_PATH set - serving plaintext ws://. Only for local development; in " +
        "production terminate TLS here or at a reverse proxy placed in front of this process.",
    );
  }

  const connectionsPerIp = new Map<string, number>();

  const wss = new WebSocketServer({
    server: httpServer,
    maxPayload: config.maxMessageBytes,
    // Native clients send no Origin header. A browser always does, so any Origin that is not
    // explicitly allow-listed is a web page trying to talk to us (cross-site WebSocket hijacking).
    verifyClient: (info, cb) => {
      const origin = info.origin;
      if (origin && !config.allowedOrigins.includes(origin)) {
        cb(false, 403, "origin not allowed");
        return;
      }
      cb(true);
    },
  });

  wss.on("connection", (socket: WebSocket, req) => {
    const id = randomUUID();
    const remoteAddress = clientAddress(req, config.trustProxy);

    const ipCount = connectionsPerIp.get(remoteAddress) ?? 0;
    if (
      ctx.draining ||
      ctx.connections.all().length >= config.limits.maxConnections ||
      ipCount >= config.limits.maxConnectionsPerIp
    ) {
      log.warn("connection refused (limit or draining)", { remoteAddress, draining: ctx.draining });
      socket.close(1013, "try again later");
      return;
    }
    connectionsPerIp.set(remoteAddress, ipCount + 1);

    const challenge = randomBytesB64Url(32);
    const conn = new Connection(id, socket, remoteAddress, challenge);
    conn.state = "AUTHENTICATING";
    ctx.connections.add(conn);
    conn.send("hello.challenge", { serverChallenge: challenge, protocolVersion: config.protocolVersion });

    const authTimeout = setTimeout(() => {
      if (conn.state === "AUTHENTICATING") {
        log.warn("auth timeout, closing", { connId: id, remoteAddress });
        socket.close(4008, "auth timeout");
      }
    }, 10_000);
    authTimeout.unref?.();

    socket.on("message", (data, isBinary) => {
      conn.touch();
      if (isBinary) {
        sendError(conn, "invalid_message", "binary frames are not accepted");
        return;
      }
      if (!ctx.rawLimiter.tryConsume(conn.id)) {
        sendError(conn, "rate_limited", "too many frames, slow down");
        return;
      }

      let envelope;
      try {
        envelope = parseEnvelope(Buffer.isBuffer(data) ? data : Buffer.from(data as ArrayBuffer), config.maxMessageBytes);
      } catch (err) {
        sendError(conn, err instanceof ProtocolError ? err.code : "invalid_message", (err as Error).message);
        return;
      }

      // session.data is budgeted per session by its handler; everything else shares the control budget.
      if (envelope.type !== "session.data" && !ctx.messageLimiter.tryConsume(conn.id)) {
        sendError(conn, "rate_limited", "too many messages, slow down", envelope.id);
        return;
      }

      try {
        route(ctx, conn, envelope);
      } catch (err) {
        if (err instanceof ProtocolError) {
          sendError(conn, err.code, err.message, envelope.id);
          if (err instanceof AuthError) {
            socket.close(4003, "auth failed");
          }
        } else {
          log.error("unhandled handler error", { message: (err as Error).message, type: envelope.type });
          sendError(conn, "internal_error", "internal error", envelope.id);
        }
      }
    });

    socket.on("close", () => {
      clearTimeout(authTimeout);
      const remaining = (connectionsPerIp.get(remoteAddress) ?? 1) - 1;
      if (remaining <= 0) connectionsPerIp.delete(remoteAddress);
      else connectionsPerIp.set(remoteAddress, remaining);

      ctx.connections.remove(conn);
      ctx.rawLimiter.delete(conn.id);
      ctx.messageLimiter.delete(conn.id);

      // Whatever session this connection was in ends now, so the other side releases its keys/
      // buttons/stream instead of waiting for a timeout.
      const session = ctx.activeSessions.forHost(conn.id) ?? ctx.activeSessions.forController(conn.id);
      if (session) {
        endSession(ctx, session, conn.role === "host" ? "host_disconnected" : "controller_disconnected", conn.id);
      }

      if (conn.role === "host" && conn.deviceId) {
        // A replaced connection must not announce the host offline: its successor is already online.
        const wasCurrent = ctx.connections.getHost(conn.deviceId) === conn;
        ctx.connections.removeHost(conn.deviceId, conn);
        if (wasCurrent) {
          ctx.hosts.touchLastSeen(conn.deviceId);
          broadcastPresence(ctx, conn.deviceId, false);
        }
        log.info("host disconnected", { hostId: conn.deviceId, replaced: conn.replaced });
      } else if (conn.role === "controller" && conn.deviceId) {
        ctx.connections.removeController(conn.deviceId, conn);
        ctx.pendingPairings.discardByDeviceId(conn.deviceId);
        log.info("controller disconnected", { deviceId: conn.deviceId, replaced: conn.replaced });
      }
    });

    socket.on("error", (err) => {
      log.warn("socket error", { connId: id, message: err.message });
    });
  });

  const maintain = (now = Date.now()): void => runMaintenance(ctx, now);
  const sweepInterval = setInterval(() => maintain(), 5_000);
  sweepInterval.unref();
  httpServer.on("close", () => clearInterval(sweepInterval));

  return { ctx, wss, httpServer, maintain };
}

/** Builds the app and binds it to config.port (0 = ephemeral, used by tests). */
export function startServer(config: Config = loadConfig()): Promise<StartedServer> {
  const app = createApp(config);
  const { ctx, httpServer } = app;
  return new Promise((resolve, reject) => {
    httpServer.once("error", reject);
    httpServer.listen(config.port, () => {
      const addr = httpServer.address();
      const port = typeof addr === "object" && addr ? addr.port : config.port;
      ctx.log.info("listening", { port });
      const started: StartedServer = {
        ctx,
        address: () => ({ port }),
        maintain: app.maintain,
        drain: async () => {
          ctx.draining = true;
          ctx.log.info("draining", { connections: ctx.connections.all().length });
          for (const conn of ctx.connections.all()) conn.socket.close(1001, "server restarting");
          await new Promise((r) => setTimeout(r, Math.min(config.shutdownGraceMs, 5000)));
        },
        close: () =>
          new Promise<void>((res) => {
            for (const conn of ctx.connections.all()) conn.socket.terminate();
            httpServer.close(() => res());
          }),
      };
      resolve(started);
    });
  });
}

// Only start listening when this file is run directly (e.g. `node src/server.ts`) — importing
// it from tests must never bind a real port as a side effect. Comparing via pathToFileURL
// (rather than a manual `file://${argv[1]}` string) matters on Windows: raw argv[1] uses
// backslashes and an unencoded drive letter, which never equals import.meta.url's forward
// slashes and percent-encoding — that naive comparison silently never matches on Windows.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  startServer()
    .then((server) => {
      let stopping = false;
      const stop = (signal: string): void => {
        if (stopping) return;
        stopping = true;
        server.ctx.log.info("shutdown requested", { signal });
        server
          .drain()
          .then(() => server.close())
          .then(() => process.exit(0))
          .catch(() => process.exit(1));
      };
      process.on("SIGTERM", () => stop("SIGTERM"));
      process.on("SIGINT", () => stop("SIGINT"));
    })
    .catch((err) => {
      console.error("fatal startup error", err);
      process.exit(1);
    });
}
