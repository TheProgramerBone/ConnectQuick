import type { Connection, Role } from "../connection.ts";
import { AuthError, ForbiddenError } from "../../errors.ts";
import { deriveDeviceId } from "../../crypto.ts";

export function requireRole(conn: Connection, role: Role): void {
  if (conn.role !== role) {
    throw new ForbiddenError(`this message requires role=${role}`);
  }
}

/** Confirms a claimed deviceId is the one that self-certifying derivation produces. */
export function deriveAndCheck(identityPub: string, claimedDeviceId: string): string {
  const expected = deriveDeviceId(identityPub);
  if (expected !== claimedDeviceId) {
    throw new AuthError("deviceId does not match identityPub");
  }
  return expected;
}

export const DEFAULT_PAIRING_PERMISSIONS = [
  "remote_desktop",
  "mouse",
  "keyboard",
  "clipboard",
  "files",
  "media",
  "apps",
  "power",
];

/**
 * Canonical bytes the Host signs (with its long-term Ed25519 identity key) to authorize a
 * pairing. Documented in Protocol/protocol-spec.md — any implementation producing
 * `pairing.decision` must sign exactly this, in this field order, joined with "|".
 * When `permissions` is omitted by the caller, DEFAULT_PAIRING_PERMISSIONS is what gets
 * signed and verified — a Host omitting the field must have signed that same default list.
 */
export function pairingDecisionMessage(
  pairingId: string,
  deviceId: string,
  approved: boolean,
  permissions: string[],
): Buffer {
  return Buffer.from(`${pairingId}|${deviceId}|${approved ? 1 : 0}|${permissions.join(",")}`, "utf8");
}

/**
 * Bytes the Controller signs (Ed25519, long-term identity key) to open a session. Binds the
 * session to one Host, one Controller device, one ephemeral key and a timestamp, so a relay
 * cannot splice a captured request onto another host/device or replay it later.
 * Spec: Protocol/protocol-spec.md section 9.
 */
export function sessionConnectMessage(
  sessionId: string,
  hostId: string,
  deviceId: string,
  controllerEphPub: string,
  issuedAt: number,
): Buffer {
  return Buffer.from(
    `connectquick:session.connect:v1|${sessionId}|${hostId}|${deviceId}|${controllerEphPub}|${issuedAt}`,
    "utf8",
  );
}

/** Bytes the Host signs to accept a session; covers BOTH ephemeral keys and the granted permissions. */
export function sessionAcceptMessage(
  sessionId: string,
  hostId: string,
  deviceId: string,
  controllerEphPub: string,
  hostEphPub: string,
  permissions: string[],
): Buffer {
  return Buffer.from(
    `connectquick:session.accept:v1|${sessionId}|${hostId}|${deviceId}|${controllerEphPub}|${hostEphPub}|${permissions.join(",")}`,
    "utf8",
  );
}
