import type { Envelope } from "../../schemaValidator.ts";
import type { Connection } from "../connection.ts";
import type { AppContext } from "../context.ts";
import type { ActiveSession } from "../activeSessions.ts";
import { verifyEd25519 } from "../../crypto.ts";
import { ExpiredError, ForbiddenError, NotFoundError, RateLimitedError, ValidationError } from "../../errors.ts";
import { requireRole, sessionAcceptMessage, sessionConnectMessage } from "./util.ts";

/** A Controller dropped for abuse after this many consecutive rate-limit hits on one session. */
const MAX_CONSECUTIVE_VIOLATIONS = 200;

/**
 * Tears a session down and tells both ends why. `skipConnId` is the connection that initiated
 * the end (it already knows), or a connection that is already gone.
 *
 * Both peers must treat `session.end` as "release everything now": the Host releases every held
 * key/button and stops streaming; the Controller returns to its disconnected state.
 */
export function endSession(ctx: AppContext, session: ActiveSession, reason: string, skipConnId?: string): void {
  if (!ctx.activeSessions.remove(session.sessionId)) return;
  const hostConn = ctx.connections.getById(session.hostConnId);
  const controllerConn = ctx.connections.getById(session.controllerConnId);
  if (hostConn && hostConn.id !== skipConnId) {
    hostConn.send("session.end", { sessionId: session.sessionId, reason });
  }
  if (controllerConn && controllerConn.id !== skipConnId) {
    controllerConn.send("session.end", { sessionId: session.sessionId, reason });
    controllerConn.send("session.state", { state: "DISCONNECTED", reason });
  }
  ctx.log.info("session ended", { sessionId: session.sessionId, hostId: session.hostId, reason });
}

/**
 * Controller asks to open an end-to-end encrypted control session with an already-paired host.
 * The backend authenticates and authorizes the request, verifies the Controller's signature
 * (so garbage never reaches the Host), then relays it. It never learns the session key: the
 * X25519 shared secret exists only on the two endpoints.
 */
export function handleSessionConnect(ctx: AppContext, conn: Connection, envelope: Envelope): void {
  requireRole(conn, "controller");
  const p = envelope.payload as {
    hostId: string;
    sessionId: string;
    ephPub: string;
    issuedAt: number;
    sig: string;
    deviceId?: string;
    deviceName?: string;
    identityPub?: string;
  };
  // deviceId/deviceName/identityPub are set BY the backend on the way to the Host; a client that
  // supplies them is lying or confused, and we must not forward its version.
  if (p.deviceId !== undefined || p.deviceName !== undefined || p.identityPub !== undefined) {
    throw new ValidationError("deviceId/deviceName/identityPub are set by the backend and must not be sent");
  }

  const now = Date.now();
  if (Math.abs(now - p.issuedAt) > ctx.config.session.connectSkewMs) {
    throw new ExpiredError("session.connect issuedAt is outside the accepted window");
  }
  if (!ctx.devices.isAuthorized(conn.deviceId!, p.hostId)) {
    throw new ForbiddenError("device is not authorized for this host");
  }
  const message = sessionConnectMessage(p.sessionId, p.hostId, conn.deviceId!, p.ephPub, p.issuedAt);
  if (!verifyEd25519(conn.identityPub!, message, p.sig)) {
    throw new ForbiddenError("invalid signature over session.connect");
  }
  if (ctx.seenSessionIds.has(p.sessionId)) {
    throw new ValidationError("sessionId already used");
  }
  ctx.seenSessionIds.set(p.sessionId, now + 2 * ctx.config.session.connectSkewMs);

  const hostConn = ctx.connections.getHost(p.hostId);
  if (!hostConn) {
    conn.send("session.state", { state: "DISCONNECTED", reason: "host_offline" });
    return;
  }

  // One session per connection: a new request supersedes this controller's previous one.
  const previous = ctx.activeSessions.forController(conn.id);
  if (previous) endSession(ctx, previous, "replaced", conn.id);

  const hostBusy = ctx.activeSessions.forHost(hostConn.id);
  if (hostBusy) {
    if (hostBusy.controllerDeviceId === conn.deviceId) {
      endSession(ctx, hostBusy, "replaced", conn.id); // same phone reconnecting: take over
    } else {
      conn.send("session.state", { state: "REJECTED", reason: "host_busy" });
      return;
    }
  }

  ctx.activeSessions.create({
    sessionId: p.sessionId,
    controllerConnId: conn.id,
    hostConnId: hostConn.id,
    controllerDeviceId: conn.deviceId!,
    hostId: p.hostId,
    controllerEphPub: p.ephPub,
    createdAt: now,
  });
  conn.send("session.state", { state: "CONNECTING" });

  const authRecord = ctx.devices.find(conn.deviceId!, p.hostId);
  hostConn.send("session.connect", {
    hostId: p.hostId,
    sessionId: p.sessionId,
    ephPub: p.ephPub,
    issuedAt: p.issuedAt,
    sig: p.sig,
    deviceId: conn.deviceId!,
    deviceName: authRecord?.deviceName ?? "Unknown device",
    identityPub: conn.identityPub!,
  });
}

/** Host accepts: its signature must cover both ephemeral keys and the permissions it grants. */
export function handleSessionAccept(ctx: AppContext, conn: Connection, envelope: Envelope): void {
  requireRole(conn, "host");
  const p = envelope.payload as { sessionId: string; ephPub: string; permissions: string[]; sig: string };
  const session = ctx.activeSessions.get(p.sessionId);
  if (!session || session.hostConnId !== conn.id || session.phase !== "PENDING") {
    throw new NotFoundError("no pending session with that id for this host");
  }
  const record = ctx.devices.find(session.controllerDeviceId, session.hostId);
  if (!record || record.revoked) {
    endSession(ctx, session, "revoked");
    return;
  }
  // The Host may grant less than was paired, never more.
  const allowed = new Set(record.permissions);
  if (!p.permissions.every((perm) => allowed.has(perm))) {
    throw new ForbiddenError("accept grants permissions that were never paired");
  }
  const host = ctx.hosts.find(session.hostId)!;
  const message = sessionAcceptMessage(
    session.sessionId,
    session.hostId,
    session.controllerDeviceId,
    session.controllerEphPub,
    p.ephPub,
    p.permissions,
  );
  if (!verifyEd25519(host.hostPub, message, p.sig)) {
    throw new ForbiddenError("invalid host signature over session.accept");
  }

  session.phase = "ACTIVE";
  session.acceptedAt = Date.now();
  session.lastDataAt = session.acceptedAt;
  ctx.devices.touchLastConnected(session.controllerDeviceId, session.hostId);

  const controllerConn = ctx.connections.getById(session.controllerConnId);
  controllerConn?.send("session.accept", { sessionId: p.sessionId, ephPub: p.ephPub, permissions: p.permissions, sig: p.sig });
  controllerConn?.send("session.state", { state: "CONNECTED" });
  ctx.log.info("session active", { sessionId: session.sessionId, hostId: session.hostId });
}

export function handleSessionReject(ctx: AppContext, conn: Connection, envelope: Envelope): void {
  requireRole(conn, "host");
  const p = envelope.payload as { sessionId: string; reason: string };
  const session = ctx.activeSessions.get(p.sessionId);
  if (!session || session.hostConnId !== conn.id || session.phase !== "PENDING") {
    throw new NotFoundError("no pending session with that id for this host");
  }
  ctx.activeSessions.remove(session.sessionId);
  const controllerConn = ctx.connections.getById(session.controllerConnId);
  controllerConn?.send("session.reject", { sessionId: p.sessionId, reason: p.reason });
  controllerConn?.send("session.state", { state: "REJECTED", reason: p.reason });
  ctx.log.info("session rejected by host", { sessionId: session.sessionId, reason: p.reason });
}

/**
 * Opaque end-to-end encrypted frame. Routed purely by sessionId and by which side of that
 * session the sender is; the payload is never parsed. Per-session frame and byte budgets keep
 * a misbehaving (or compromised) endpoint from turning the relay into a bandwidth cannon.
 */
export function handleSessionData(ctx: AppContext, conn: Connection, envelope: Envelope): void {
  const p = envelope.payload as { sessionId: string; seq: number; ct: string };
  const session = ctx.activeSessions.get(p.sessionId);
  if (!session || session.phase !== "ACTIVE") throw new NotFoundError("unknown session");
  const fromController = conn.role === "controller" && session.controllerConnId === conn.id;
  const fromHost = conn.role === "host" && session.hostConnId === conn.id;
  if (!fromController && !fromHost) throw new NotFoundError("unknown session");

  const frameOk = ctx.activeSessions.frameLimiter.tryConsume(session.sessionId);
  const byteOk = ctx.activeSessions.byteLimiter.tryConsume(session.sessionId, p.ct.length);
  if (!frameOk || !byteOk) {
    session.violations++;
    if (session.violations >= MAX_CONSECUTIVE_VIOLATIONS) {
      endSession(ctx, session, "rate_limit_abuse");
      return;
    }
    throw new RateLimitedError("session data rate exceeded");
  }
  session.violations = 0;

  const peer = ctx.connections.getById(fromController ? session.hostConnId : session.controllerConnId);
  if (!peer) {
    endSession(ctx, session, "peer_gone", conn.id);
    return;
  }
  if (peer.socket.bufferedAmount > ctx.config.session.maxBufferedBytes) {
    endSession(ctx, session, "peer_too_slow");
    return;
  }
  session.lastDataAt = Date.now();
  peer.send("session.data", { sessionId: p.sessionId, seq: p.seq, ct: p.ct });
}

export function handleSessionEnd(ctx: AppContext, conn: Connection, envelope: Envelope): void {
  const p = envelope.payload as { sessionId: string; reason: string };
  const session = ctx.activeSessions.get(p.sessionId);
  if (!session) return; // already gone: ending twice is not an error
  const participant =
    (conn.role === "controller" && session.controllerConnId === conn.id) ||
    (conn.role === "host" && session.hostConnId === conn.id);
  if (!participant) throw new NotFoundError("unknown session");
  endSession(ctx, session, p.reason, conn.id);
}

/** Host reports a coarse state change; relayed to the Controller of its current session. */
export function handleSessionState(ctx: AppContext, conn: Connection, envelope: Envelope): void {
  requireRole(conn, "host");
  const { state, reason, rttMs } = envelope.payload as { state: string; reason?: string; rttMs?: number };
  const session = ctx.activeSessions.forHost(conn.id);
  if (!session) return;
  const controllerConn = ctx.connections.getById(session.controllerConnId);
  controllerConn?.send("session.state", {
    state,
    ...(reason !== undefined ? { reason } : {}),
    ...(rttMs !== undefined ? { rttMs } : {}),
  });
  if (state === "DISCONNECTED" || state === "REJECTED") {
    endSession(ctx, session, reason ?? state.toLowerCase(), conn.id);
  }
}
