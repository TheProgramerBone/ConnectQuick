import type { AppContext } from "./ws/context.ts";
import { endSession } from "./ws/handlers/session.ts";

/**
 * Periodic housekeeping, run on a timer by the server and called directly (with a fake clock)
 * by tests. Everything here exists to make sure abandoned state cannot accumulate:
 * sessions the Host never answered, sessions whose peer vanished without a clean close,
 * pairing requests nobody approved, replay-protection entries past their window, idle sockets.
 */
export function runMaintenance(ctx: AppContext, now = Date.now()): void {
  const { session } = ctx.config;

  for (const s of ctx.activeSessions.all()) {
    if (s.phase === "PENDING" && now - s.createdAt > session.acceptTimeoutMs) {
      endSession(ctx, s, "accept_timeout");
    } else if (s.phase === "ACTIVE" && now - s.lastDataAt > session.idleTimeoutMs) {
      endSession(ctx, s, "idle_timeout");
    } else if (s.acceptedAt !== null && now - s.acceptedAt > session.maxDurationMs) {
      endSession(ctx, s, "max_duration");
    }
  }

  for (const [id, expiry] of ctx.seenSessionIds) {
    if (expiry <= now) ctx.seenSessionIds.delete(id);
  }

  ctx.pendingPairings.sweep(ctx.config.pairingTtlSeconds * 2000, now);
  ctx.pairingInitLimiter.evictIdleSince(10 * 60_000, now);
  ctx.messageLimiter.evictIdleSince(60_000, now);
  ctx.rawLimiter.evictIdleSince(60_000, now);

  // A client that stops sending anything (heartbeats included) is assumed dead.
  for (const conn of ctx.connections.all()) {
    if (now - conn.lastActivityAt > ctx.config.heartbeatTimeoutMs) {
      ctx.log.info("closing idle connection", { connId: conn.id, deviceId: conn.deviceId ?? undefined });
      conn.socket.close(4009, "idle timeout");
    }
  }
}
