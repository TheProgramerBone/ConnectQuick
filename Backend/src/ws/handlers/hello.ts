import type { Envelope } from "../../schemaValidator.ts";
import type { Connection } from "../connection.ts";
import type { AppContext } from "../context.ts";
import { verifyEd25519 } from "../../crypto.ts";
import { AuthError } from "../../errors.ts";
import { deriveAndCheck } from "./util.ts";
import { broadcastPresence } from "./presence.ts";

/**
 * Transport-level authentication (Protocol §"Autenticación de la conexión"): the client proves
 * possession of the private key behind its self-certifying deviceId by signing the random
 * challenge this connection was greeted with. This is independent of *authorization* — whether
 * a given controller is allowed to act on a given host is checked per-action against
 * `authorized_devices`, not here.
 */
export function handleHello(ctx: AppContext, conn: Connection, envelope: Envelope): void {
  if (conn.state !== "AUTHENTICATING") {
    throw new AuthError("hello received outside AUTHENTICATING state");
  }
  const p = envelope.payload as {
    role: "host" | "controller";
    deviceId: string;
    identityPub: string;
    challengeSig: string;
    clientVersion: string;
    hostName?: string;
  };

  deriveAndCheck(p.identityPub, p.deviceId);

  const challengeBytes = Buffer.from(conn.serverChallenge, "base64url");
  if (!verifyEd25519(p.identityPub, challengeBytes, p.challengeSig)) {
    throw new AuthError("invalid challenge signature");
  }

  if (p.role === "host") {
    const existing = ctx.hosts.find(p.deviceId);
    if (existing && existing.hostPub !== p.identityPub) {
      // Someone without the original private key is trying to claim an already-registered
      // hostId — reject rather than silently re-pinning the key (TOFU, but enforced).
      throw new AuthError("hostId already registered to a different identity key");
    }
    const hostName = p.hostName?.trim() || existing?.hostName || "Windows PC";
    ctx.hosts.findOrRegister(p.deviceId, p.identityPub, hostName, p.clientVersion);
    if (existing && p.hostName && p.hostName.trim() && p.hostName !== existing.hostName) {
      ctx.hosts.rename(p.deviceId, p.hostName.trim());
    }
    ctx.hosts.touchLastSeen(p.deviceId);
    // A reconnect can arrive before the old socket's timeout fires (Wi-Fi <-> mobile data). The
    // newest authenticated connection wins; the stale one is closed so it can never act again.
    const previous = ctx.connections.getHost(p.deviceId);
    if (previous && previous !== conn) {
      previous.replaced = true;
      previous.socket.close(4010, "replaced by a newer connection");
    }
    ctx.connections.addHost(p.deviceId, conn);
  } else {
    // Controllers don't need a separate identity registry: their deviceId already is the
    // cryptographic commitment to identityPub. Whether they may control a given host is
    // decided per-action against authorized_devices (set only through §7 pairing).
    const previous = ctx.connections.getController(p.deviceId);
    if (previous && previous !== conn) {
      previous.replaced = true;
      previous.socket.close(4010, "replaced by a newer connection");
    }
    ctx.connections.addController(p.deviceId, conn);
  }

  conn.role = p.role;
  conn.deviceId = p.deviceId;
  conn.identityPub = p.identityPub;
  conn.clientVersion = p.clientVersion;
  conn.state = "CONNECTED";
  conn.touch();

  conn.send("hello.ack", {
    accepted: true,
    serverChallenge: conn.serverChallenge,
    protocolVersion: ctx.config.protocolVersion,
    capabilities: [
      "pairing",
      "presence",
      "session.e2e.v1",
      "ice",
      ...(ctx.config.ice.stunUrls.length > 0 ? ["stun"] : []),
      ...(ctx.config.ice.turnUrls.length > 0 && ctx.config.ice.turnSecret ? ["turn"] : []),
    ],
  });

  ctx.log.info("device authenticated", { role: p.role, deviceId: p.deviceId });

  if (p.role === "host") {
    broadcastPresence(ctx, p.deviceId, true);
  }
}
