import { randomUUID } from "node:crypto";
import { createServer as createHttpServer, type Server as HttpServer } from "node:http";
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
import { ProtocolError, AuthError } from "./errors.ts";
import type { AppContext } from "./ws/context.ts";

export interface App {
  ctx: AppContext;
  wss: WebSocketServer;
  httpServer: HttpServer | HttpsServer;
}

export interface StartedServer {
  ctx: AppContext;
  address(): { port: number };
  close(): Promise<void>;
}

function sendError(conn: Connection, code: string, message: string, refId?: string): void {
  conn.send("error", { code, message, ...(refId ? { refId } : {}) });
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
    activeSessions: new ActiveSessionStore(),
    pairingInitLimiter: new TokenBucketRateLimiter(
      config.rateLimit.pairingInitPerMinutePerIp,
      config.rateLimit.pairingInitPerMinutePerIp / 60,
    ),
    messageLimiter: new TokenBucketRateLimiter(
      config.rateLimit.messagesPerSecondPerConnection,
      config.rateLimit.messagesPerSecondPerConnection,
    ),
    log,
  };

  const certPath = process.env["CERT_PATH"];
  const keyPath = process.env["KEY_PATH"];
  let httpServer: HttpServer | HttpsServer;
  if (certPath && keyPath) {
    httpServer = createHttpsServer({ cert: readFileSync(certPath), key: readFileSync(keyPath) });
    log.info("TLS enabled");
  } else {
    httpServer = createHttpServer();
    log.warn(
      "no CERT_PATH/KEY_PATH set - serving plaintext ws://. Only for local development; in " +
        "production terminate TLS here or at a reverse proxy placed in front of this process.",
    );
  }

  const wss = new WebSocketServer({ server: httpServer, maxPayload: config.maxMessageBytes });

  wss.on("connection", (socket: WebSocket, req) => {
    const id = randomUUID();
    const remoteAddress = req.socket.remoteAddress ?? "unknown";
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
      if (!ctx.messageLimiter.tryConsume(conn.id)) {
        sendError(conn, "rate_limited", "too many messages, slow down");
        return;
      }

      let envelope;
      try {
        envelope = parseEnvelope(Buffer.isBuffer(data) ? data : Buffer.from(data as ArrayBuffer), config.maxMessageBytes);
      } catch (err) {
        sendError(conn, err instanceof ProtocolError ? err.code : "invalid_message", (err as Error).message);
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
      ctx.connections.remove(conn);
      if (conn.role === "host" && conn.deviceId) {
        ctx.connections.removeHost(conn.deviceId, conn);
        ctx.hosts.touchLastSeen(conn.deviceId);
        broadcastPresence(ctx, conn.deviceId, false);
        for (const controllerConnId of ctx.activeSessions.controllersFor(conn.id)) {
          ctx.connections.getById(controllerConnId)?.send("session.state", {
            state: "DISCONNECTED",
            reason: "host_disconnected",
          });
        }
        ctx.activeSessions.unlinkHost(conn.id);
        log.info("host disconnected", { hostId: conn.deviceId });
      } else if (conn.role === "controller" && conn.deviceId) {
        ctx.connections.removeController(conn.deviceId, conn);
        ctx.activeSessions.unlinkController(conn.id);
        ctx.pendingPairings.discardByDeviceId(conn.deviceId);
        log.info("controller disconnected", { deviceId: conn.deviceId });
      }
    });

    socket.on("error", (err) => {
      log.warn("socket error", { connId: id, message: err.message });
    });
  });

  // Idle-connection sweep: a client that stops sending anything (heartbeats included) for
  // heartbeatTimeoutMs is assumed dead and closed, freeing server + DB presence state.
  const sweepInterval = setInterval(() => {
    const now = Date.now();
    for (const conn of ctx.connections.all()) {
      if (now - conn.lastActivityAt > config.heartbeatTimeoutMs) {
        log.info("closing idle connection", { connId: conn.id, deviceId: conn.deviceId ?? undefined });
        conn.socket.close(4009, "idle timeout");
      }
    }
  }, config.heartbeatIntervalMs);
  sweepInterval.unref();
  httpServer.on("close", () => clearInterval(sweepInterval));

  return { ctx, wss, httpServer };
}

/** Builds the app and binds it to config.port (0 = ephemeral, used by tests). */
export function startServer(config: Config = loadConfig()): Promise<StartedServer> {
  const { ctx, httpServer } = createApp(config);
  return new Promise((resolve, reject) => {
    httpServer.once("error", reject);
    httpServer.listen(config.port, () => {
      const addr = httpServer.address();
      const port = typeof addr === "object" && addr ? addr.port : config.port;
      ctx.log.info("listening", { port });
      resolve({
        ctx,
        address: () => ({ port }),
        close: () =>
          new Promise<void>((res) => {
            for (const conn of ctx.connections.all()) conn.socket.terminate();
            httpServer.close(() => res());
          }),
      });
    });
  });
}

// Only start listening when this file is run directly (e.g. `node src/server.ts`) — importing
// it from tests must never bind a real port as a side effect. Comparing via pathToFileURL
// (rather than a manual `file://${argv[1]}` string) matters on Windows: raw argv[1] uses
// backslashes and an unencoded drive letter, which never equals import.meta.url's forward
// slashes and percent-encoding — that naive comparison silently never matches on Windows.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  startServer().catch((err) => {
    console.error("fatal startup error", err);
    process.exit(1);
  });
}
