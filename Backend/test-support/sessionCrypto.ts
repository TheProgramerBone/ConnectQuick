import {
  createCipheriv,
  createDecipheriv,
  createHash,
  createPrivateKey,
  createPublicKey,
  diffieHellman,
  generateKeyPairSync,
  hkdfSync,
  type KeyObject,
} from "node:crypto";

/**
 * Reference implementation of the end-to-end session layer (Protocol/protocol-spec.md section 9)
 * in TypeScript. The backend itself never runs this code: it only routes ciphertext. It exists
 * so that tests can stand in for a Controller or a Host, and so the shared test vectors
 * (Protocol/test-vectors/session-vectors.json) are generated from, and checked against, one
 * implementation that .NET and Kotlin must then reproduce bit for bit.
 *
 *   shared     = X25519(ephemeral private, peer ephemeral public)
 *   transcript = SHA-256("connectquick:session.transcript:v1|" + sessionId|hostId|deviceId|
 *                        controllerEphPub|hostEphPub|permissions)
 *   prk        = HKDF-Extract(salt = transcript, ikm = shared)
 *   key(c2h)   = HKDF-Expand(prk, "connectquick:session:v1:c2h", 32)
 *   key(h2c)   = HKDF-Expand(prk, "connectquick:session:v1:h2c", 32)
 *   nonce(seq) = 0x00000000 || uint64_be(seq)            (96 bit; one key per direction)
 *   aad        = "cq1|" + sessionId + "|" + direction + "|" + seq
 *   frame      = base64url(AES-256-GCM(key, nonce, plaintext, aad) || 16-byte tag)
 */

export type Direction = "c2h" | "h2c";

export interface Ephemeral {
  privateKey: KeyObject;
  publicB64Url: string;
}

export function generateEphemeral(): Ephemeral {
  const { publicKey, privateKey } = generateKeyPairSync("x25519");
  return { privateKey, publicB64Url: (publicKey.export({ format: "jwk" }) as { x: string }).x };
}

export function ephemeralFromRaw(privateB64Url: string, publicB64Url: string): Ephemeral {
  const privateKey = createPrivateKey({
    key: { kty: "OKP", crv: "X25519", d: privateB64Url, x: publicB64Url },
    format: "jwk",
  });
  return { privateKey, publicB64Url };
}

export function sharedSecret(own: Ephemeral, peerPublicB64Url: string): Buffer {
  const peer = createPublicKey({ key: { kty: "OKP", crv: "X25519", x: peerPublicB64Url }, format: "jwk" });
  const secret = diffieHellman({ privateKey: own.privateKey, publicKey: peer });
  if (secret.every((b) => b === 0)) throw new Error("degenerate X25519 shared secret");
  return secret;
}

export function transcriptHash(
  sessionId: string,
  hostId: string,
  deviceId: string,
  controllerEphPub: string,
  hostEphPub: string,
  permissions: string[],
): Buffer {
  return createHash("sha256")
    .update(
      `connectquick:session.transcript:v1|${sessionId}|${hostId}|${deviceId}|${controllerEphPub}|${hostEphPub}|${permissions.join(",")}`,
      "utf8",
    )
    .digest();
}

export function deriveSessionKeys(shared: Buffer, transcript: Buffer): { c2h: Buffer; h2c: Buffer } {
  const expand = (info: string): Buffer =>
    Buffer.from(hkdfSync("sha256", shared, transcript, Buffer.from(info, "utf8"), 32));
  return { c2h: expand("connectquick:session:v1:c2h"), h2c: expand("connectquick:session:v1:h2c") };
}

export const MAX_SEQ = 0xffffffff;

function nonceFor(seq: number): Buffer {
  const nonce = Buffer.alloc(12);
  nonce.writeBigUInt64BE(BigInt(seq), 4);
  return nonce;
}

function aadFor(sessionId: string, direction: Direction, seq: number): Buffer {
  return Buffer.from(`cq1|${sessionId}|${direction}|${seq}`, "utf8");
}

export interface Frame {
  seq: number;
  ct: string;
}

/** One side of an established session. Strictly increasing sequence numbers; replays are rejected. */
export class SessionChannel {
  readonly sessionId: string;
  private readonly sendKey: Buffer;
  private readonly recvKey: Buffer;
  private readonly sendDir: Direction;
  private readonly recvDir: Direction;
  private sendSeq = 0;
  private lastRecvSeq = -1;

  constructor(sessionId: string, sendKey: Buffer, recvKey: Buffer, sendDir: Direction, recvDir: Direction) {
    this.sessionId = sessionId;
    this.sendKey = sendKey;
    this.recvKey = recvKey;
    this.sendDir = sendDir;
    this.recvDir = recvDir;
  }

  static forController(sessionId: string, keys: { c2h: Buffer; h2c: Buffer }): SessionChannel {
    return new SessionChannel(sessionId, keys.c2h, keys.h2c, "c2h", "h2c");
  }

  static forHost(sessionId: string, keys: { c2h: Buffer; h2c: Buffer }): SessionChannel {
    return new SessionChannel(sessionId, keys.h2c, keys.c2h, "h2c", "c2h");
  }

  seal(plaintext: Buffer | string, seq = this.sendSeq): Frame {
    if (seq > MAX_SEQ) throw new Error("sequence space exhausted: start a new session");
    const cipher = createCipheriv("aes-256-gcm", this.sendKey, nonceFor(seq));
    cipher.setAAD(aadFor(this.sessionId, this.sendDir, seq));
    const body = Buffer.concat([cipher.update(plaintext as Buffer), cipher.final()]);
    const ct = Buffer.concat([body, cipher.getAuthTag()]).toString("base64url");
    if (seq === this.sendSeq) this.sendSeq++;
    return { seq, ct };
  }

  open(seq: number, ct: string): Buffer {
    if (!Number.isInteger(seq) || seq < 0 || seq > MAX_SEQ) throw new Error("bad sequence number");
    if (seq <= this.lastRecvSeq) throw new Error("replayed or reordered frame");
    const raw = Buffer.from(ct, "base64url");
    if (raw.length < 16) throw new Error("frame too short");
    const decipher = createDecipheriv("aes-256-gcm", this.recvKey, nonceFor(seq));
    decipher.setAAD(aadFor(this.sessionId, this.recvDir, seq));
    decipher.setAuthTag(raw.subarray(raw.length - 16));
    const plain = Buffer.concat([decipher.update(raw.subarray(0, raw.length - 16)), decipher.final()]);
    this.lastRecvSeq = seq; // only advance after the tag verified
    return plain;
  }
}
