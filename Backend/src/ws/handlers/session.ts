import type { Envelope } from "../../schemaValidator.ts";
import type { Connection } from "../connection.ts";
import type { AppContext } from "../context.ts";
import { ForbiddenError } from "../../errors.ts";
import { requireRole } from "./util.ts";

/**
 * Controller asks to start a session with an already-paired host (§28 UX de conexión). The
 * backend only relays this negotiation — it never terminates or inspects what rides on top of
 * it once CONNECTED (future phases: WebRTC offer/answer for desktop streaming, input, etc.).
 */
export function handleSessionConnect(ctx: AppContext, conn: Connection, envelope: Envelope): void {
  requireRole(conn, "controller");
  const { hostId } = envelope.payload as { hostId: string };

  if (!ctx.devices.isAuthorized(conn.deviceId!, hostId)) {
    throw new ForbiddenError("device is not authorized for this host");
  }
  const hostConn = ctx.connections.getHost(hostId);
  if (!hostConn) {
    conn.send("session.state", { state: "DISCONNECTED", reason: "host_offline" });
    return;
  }

  ctx.activeSessions.link(conn.id, hostConn.id);
  conn.send("session.state", { state: "CONNECTING" });

  const authRecord = ctx.devices.find(conn.deviceId!);
  hostConn.send("session.connect", {
    hostId,
    deviceId: conn.deviceId!,
    deviceName: authRecord?.deviceName ?? "Unknown device",
  });
}

/** Host reports a session state change; relayed to every controller currently linked to it. */
export function handleSessionState(ctx: AppContext, conn: Connection, envelope: Envelope): void {
  requireRole(conn, "host");
  const { state, reason, rttMs } = envelope.payload as { state: string; reason?: string; rttMs?: number };
  for (const controllerConnId of ctx.activeSessions.controllersFor(conn.id)) {
    const controllerConn = ctx.connections.getById(controllerConnId);
    controllerConn?.send("session.state", {
      state,
      ...(reason !== undefined ? { reason } : {}),
      ...(rttMs !== undefined ? { rttMs } : {}),
    });
    if (state === "DISCONNECTED" || state === "REJECTED") {
      ctx.activeSessions.unlinkController(controllerConnId);
    }
  }
}
