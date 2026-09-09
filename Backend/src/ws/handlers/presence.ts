import type { AppContext } from "../context.ts";

/** Notifies every currently-connected, non-revoked authorized controller of a host's presence. */
export function broadcastPresence(ctx: AppContext, hostId: string, online: boolean): void {
  const host = ctx.hosts.find(hostId);
  const devices = ctx.devices.listByHost(hostId).filter((d) => !d.revoked);
  for (const d of devices) {
    const conn = ctx.connections.getController(d.deviceId);
    if (!conn) continue;
    conn.send("presence.update", {
      hostId,
      online,
      ...(online ? {} : { lastSeenAt: host?.lastSeenAt ?? Date.now() }),
    });
  }
}
