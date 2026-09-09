import { createHash, createPublicKey, randomBytes, randomUUID, sign, verify, type KeyObject } from "node:crypto";

/**
 * Wire format for public keys/nonces/signatures throughout the protocol: base64url of the raw
 * bytes (32 bytes for Ed25519/X25519 keys, no DER/PEM wrapping) — this matches what libsodium
 * produces natively on the Windows Host (NSec) and Android Controller (lazysodium), so the same
 * base64url string is valid on all three platforms.
 */

export function newUuid(): string {
  return randomUUID();
}

export function randomBytesB64Url(length: number): string {
  return randomBytes(length).toString("base64url");
}

export function sha256(data: Buffer | string): Buffer {
  return createHash("sha256").update(data).digest();
}

/** deviceId = base64url(SHA-256("connectquick:device:" || identityPub))[0:22] */
export function deriveDeviceId(identityPubB64Url: string): string {
  const raw = Buffer.from(identityPubB64Url, "base64url");
  const digest = sha256(Buffer.concat([Buffer.from("connectquick:device:", "utf8"), raw]));
  return digest.toString("base64url").slice(0, 22);
}

function rawEd25519PublicKey(pubB64Url: string): KeyObject {
  const x = pubB64Url;
  return createPublicKey({ key: { kty: "OKP", crv: "Ed25519", x }, format: "jwk" });
}

export function verifyEd25519(pubB64Url: string, message: Buffer, signatureB64Url: string): boolean {
  try {
    const raw = Buffer.from(pubB64Url, "base64url");
    if (raw.length !== 32) return false;
    const key = rawEd25519PublicKey(pubB64Url);
    const sig = Buffer.from(signatureB64Url, "base64url");
    return verify(null, message, key, sig);
  } catch {
    return false;
  }
}

/**
 * The backend never holds a device's private key, but it does hold its own short-lived signing
 * key used only to sign `pairing.result` transcripts it relays — kept here for symmetry/tests;
 * not used to authenticate devices to each other (that's done with their own identity keys).
 */
export function signWithEphemeral(privateKey: KeyObject, message: Buffer): string {
  return sign(null, message, privateKey).toString("base64url");
}

/** 6-digit SAS derived from both public keys, shown on the Host during authorization. */
export function computeSas(hostPubB64Url: string, deviceEphemeralPubB64Url: string, nonceB64Url: string): string {
  const digest = sha256(
    Buffer.concat([
      Buffer.from(hostPubB64Url, "base64url"),
      Buffer.from(deviceEphemeralPubB64Url, "base64url"),
      Buffer.from(nonceB64Url, "base64url"),
    ]),
  );
  const num = digest.readUInt32BE(0) % 1_000_000;
  return num.toString().padStart(6, "0");
}
