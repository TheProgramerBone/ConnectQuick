import type { Envelope } from "../../schemaValidator.ts";
import type { Connection } from "../connection.ts";
import type { AppContext } from "../context.ts";
import { newUuid, randomBytesB64Url, computeSas, verifyEd25519 } from "../../crypto.ts";
import { RateLimitedError, NotFoundError, ForbiddenError } from "../../errors.ts";
import { requireRole, deriveAndCheck, pairingDecisionMessage, DEFAULT_PAIRING_PERMISSIONS } from "./util.ts";
import { endSession } from "./session.ts";

/** Host asks for a new pairing session — the "Vincular nuevo dispositivo" button (§6-7). */
export function handlePairingCreate(ctx: AppContext, conn: Connection, envelope: Envelope): void {
  requireRole(conn, "host");
  const p = envelope.payload as { hostName: string; hostEphPub: string };
  const hostId = conn.deviceId!;

  const recent = ctx.pairings.countCreatedSince(hostId, Date.now() - 60_000);
  if (recent >= ctx.config.rateLimit.pairingCreatePerMinutePerHost) {
    throw new RateLimitedError("too many pairing requests, slow down");
  }

  const current = ctx.hosts.find(hostId);
  if (current && p.hostName.trim() && p.hostName !== current.hostName) {
    ctx.hosts.rename(hostId, p.hostName.trim());
  }

  const pairingId = newUuid();
  const nonce = randomBytesB64Url(16);
  const record = ctx.pairings.create(pairingId, hostId, nonce, p.hostEphPub, ctx.config.pairingTtlSeconds);

  conn.send("pairing.created", {
    pairingId,
    nonce,
    expiresAt: record.expiresAt,
    ttlSeconds: ctx.config.pairingTtlSeconds,
  });
}

/** Controller, right after scanning the QR, starts the handshake (§7 step 4). */
export function handlePairingInit(ctx: AppContext, conn: Connection, envelope: Envelope): void {
  requireRole(conn, "controller");
  const p = envelope.payload as {
    pairingId: string;
    deviceId: string;
    deviceName: string;
    identityPub: string;
    ephemeralPub: string;
  };

  if (!ctx.pairingInitLimiter.tryConsume(conn.remoteAddress)) {
    throw new RateLimitedError("too many pairing attempts from this network, slow down");
  }
  deriveAndCheck(p.identityPub, p.deviceId);

  const now = Date.now();
  const request = ctx.pairings.find(p.pairingId);
  if (!request || request.expiresAt <= now) {
    conn.send("pairing.result", { pairingId: p.pairingId, success: false, reason: "expired_or_unknown" });
    return;
  }
  // Atomic claim: two concurrent pairing.init for the same QR must not both proceed (replay).
  if (!ctx.pairings.tryConsume(p.pairingId, now)) {
    conn.send("pairing.result", { pairingId: p.pairingId, success: false, reason: "already_used" });
    return;
  }

  const host = ctx.hosts.find(request.hostId);
  const hostConn = ctx.connections.getHost(request.hostId);
  if (!host || !hostConn) {
    conn.send("pairing.result", { pairingId: p.pairingId, success: false, reason: "host_offline" });
    return;
  }

  const sasCode = computeSas(host.hostPub, p.ephemeralPub, request.nonce);

  ctx.pendingPairings.set({
    pairingId: p.pairingId,
    hostId: request.hostId,
    deviceId: p.deviceId,
    deviceName: p.deviceName,
    identityPub: p.identityPub,
    ephemeralPub: p.ephemeralPub,
    createdAt: now,
  });

  hostConn.send("pairing.incoming", {
    pairingId: p.pairingId,
    deviceId: p.deviceId,
    deviceName: p.deviceName,
    identityPub: p.identityPub,
    ephemeralPub: p.ephemeralPub,
    sasCode,
  });
}

/** Host's answer to the "¿Quieres permitir el acceso?" dialog (§7 step 5-6). */
export function handlePairingDecision(ctx: AppContext, conn: Connection, envelope: Envelope): void {
  requireRole(conn, "host");
  const p = envelope.payload as {
    pairingId: string;
    approved: boolean;
    permissions?: string[];
    hostSignature: string;
  };

  const pending = ctx.pendingPairings.take(p.pairingId);
  if (!pending || pending.hostId !== conn.deviceId) {
    throw new NotFoundError("no pending pairing with that id for this host");
  }

  const host = ctx.hosts.find(conn.deviceId!)!;
  // Permissions are only meaningful for an approval; a rejection always signs an empty list so
  // a Host rejecting a request never needs to think about what permissions "would have" applied.
  const permissions = p.approved
    ? p.permissions && p.permissions.length > 0
      ? p.permissions
      : DEFAULT_PAIRING_PERMISSIONS
    : [];
  const message = pairingDecisionMessage(p.pairingId, pending.deviceId, p.approved, permissions);
  if (!verifyEd25519(host.hostPub, message, p.hostSignature)) {
    throw new ForbiddenError("invalid host signature over pairing decision");
  }

  const targetConn = ctx.connections.getController(pending.deviceId);

  if (!p.approved) {
    targetConn?.send("pairing.result", { pairingId: p.pairingId, success: false, reason: "rejected_by_user" });
    return;
  }

  ctx.devices.authorize(pending.deviceId, conn.deviceId!, pending.identityPub, pending.deviceName, permissions);

  targetConn?.send("pairing.result", {
    pairingId: p.pairingId,
    success: true,
    hostId: conn.deviceId!,
    hostName: host.hostName,
    hostPub: host.hostPub,
    hostSignature: p.hostSignature,
    permissions,
  });
}

/**
 * Host revokes a previously-authorized device (device management screen, section 8). Revocation
 * is immediate and complete: the authorization row is flagged, any live session of that device
 * against this host is torn down on both ends, and the device is told. The device's WebSocket is
 * NOT closed: it may be paired with other hosts and must keep working for them.
 */
export function handlePairingRevoke(ctx: AppContext, conn: Connection, envelope: Envelope): void {
  requireRole(conn, "host");
  const { deviceId } = envelope.payload as { deviceId: string };
  const hostId = conn.deviceId!;
  const record = ctx.devices.find(deviceId, hostId);
  if (!record) {
    throw new NotFoundError("no such authorized device for this host");
  }
  ctx.devices.revoke(deviceId, hostId);
  for (const session of ctx.activeSessions.forDevice(deviceId, hostId)) {
    endSession(ctx, session, "revoked");
  }
  ctx.log.info("device revoked", { hostId, deviceId });
  ctx.connections.getController(deviceId)?.send("pairing.revoked", { deviceId, hostId, reason: "revoked_by_host" });
}

/**
 * The Host declares the complete set of phones it trusts, so a second relay (the PC's own LAN relay,
 * or a replacement cloud server) can serve them without a new QR pairing. Safe because the Host is
 * the policy point: it independently re-verifies every phone's signature and pairing on each
 * `session.connect` (spec section 9), and a Host can only ever authorize phones for itself
 * (`hostId` is the authenticated connection, never a payload field). The list is authoritative:
 * phones this backend knows for the Host but that are absent from it are revoked, so a revocation
 * made while this relay was offline still takes effect. Each entry must prove its deviceId
 * is derived from its public key, same as at pairing.
 */
export function handleRegistrySync(ctx: AppContext, conn: Connection, envelope: Envelope): void {
  requireRole(conn, "host");
  const hostId = conn.deviceId!;
  const { devices } = envelope.payload as {
    devices: { deviceId: string; devicePub: string; deviceName: string; permissions: string[] }[];
  };
  const keep = new Set<string>();
  let authorized = 0;
  for (const d of devices) {
    deriveAndCheck(d.devicePub, d.deviceId);
    keep.add(d.deviceId);
    const existing = ctx.devices.find(d.deviceId, hostId);
    const same =
      existing &&
      !existing.revoked &&
      existing.devicePub === d.devicePub &&
      existing.deviceName === d.deviceName &&
      existing.permissions.join(",") === d.permissions.join(",");
    if (!same) ctx.devices.authorize(d.deviceId, hostId, d.devicePub, d.deviceName, d.permissions);
    authorized++;
  }
  let revoked = 0;
  for (const rec of ctx.devices.listByHost(hostId)) {
    if (rec.revoked || keep.has(rec.deviceId)) continue;
    ctx.devices.revoke(rec.deviceId, hostId);
    for (const session of ctx.activeSessions.forDevice(rec.deviceId, hostId)) endSession(ctx, session, "revoked");
    ctx.connections.getController(rec.deviceId)?.send("pairing.revoked", {
      deviceId: rec.deviceId,
      hostId,
      reason: "revoked_by_host",
    });
    revoked++;
  }
  ctx.log.info("registry synced", { hostId, authorized, revoked });
  conn.send("registry.synced", { authorized, revoked });
}
