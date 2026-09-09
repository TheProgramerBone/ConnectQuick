import { generateKeyPairSync, sign, randomUUID, type KeyObject } from "node:crypto";
import { WebSocket } from "ws";
import { deriveDeviceId } from "../src/crypto.ts";

export interface TestIdentity {
  publicKeyB64Url: string;
  privateKey: KeyObject;
  deviceId: string;
}

/**
 * Mirrors what the real Windows Host / Android Controller will do at first run: generate an
 * Ed25519 identity keypair and derive the self-certifying deviceId from its public key. Exposed
 * here (rather than importing anything app-specific) so tests exercise exactly the wire format
 * real clients must produce — the raw 32-byte public key, base64url of the JWK `x` field, no
 * DER/PEM wrapping, matching Backend/src/crypto.ts's `rawEd25519PublicKey`.
 */
export function generateIdentity(): TestIdentity {
  const { publicKey, privateKey } = generateKeyPairSync("ed25519");
  const jwk = publicKey.export({ format: "jwk" }) as { x: string };
  const publicKeyB64Url = jwk.x;
  return { publicKeyB64Url, privateKey, deviceId: deriveDeviceId(publicKeyB64Url) };
}

/** A fresh X25519 keypair for one pairing attempt (never reused — see Protocol §5-6). */
export function generateEphemeral(): { publicKeyB64Url: string } {
  const { publicKey } = generateKeyPairSync("x25519");
  const jwk = publicKey.export({ format: "jwk" }) as { x: string };
  return { publicKeyB64Url: jwk.x };
}

export function signBytes(privateKey: KeyObject, message: Buffer): string {
  return sign(null, message, privateKey).toString("base64url");
}

export interface Envelope {
  v: number;
  id: string;
  ts: number;
  type: string;
  payload: Record<string, unknown>;
}

/** Minimal promise-based WS test client: connect, send typed envelopes, await typed replies. */
export class TestClient {
  readonly socket: WebSocket;
  private readonly received: Envelope[] = [];
  private readonly waiters: { type: string; resolve: (e: Envelope) => void }[] = [];
  private readonly anyWaiters: ((e: Envelope) => void)[] = [];
  private readonly opened: Promise<void>;

  constructor(url: string) {
    this.socket = new WebSocket(url);
    this.opened = new Promise((resolve, reject) => {
      this.socket.once("open", () => resolve());
      this.socket.once("error", reject);
    });
    this.socket.on("message", (data) => {
      const env = JSON.parse(data.toString("utf8")) as Envelope;
      const anyWaiter = this.anyWaiters.shift();
      if (anyWaiter) {
        anyWaiter(env);
        return;
      }
      const idx = this.waiters.findIndex((w) => w.type === env.type);
      if (idx >= 0) {
        const [waiter] = this.waiters.splice(idx, 1);
        waiter!.resolve(env);
      } else {
        this.received.push(env);
      }
    });
  }

  /** Waits for the next inbound message regardless of type, consumed strictly in order. */
  next(timeoutMs = 2000): Promise<Envelope> {
    const buffered = this.received.shift();
    if (buffered) return Promise.resolve(buffered);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("timed out waiting for next message")), timeoutMs);
      this.anyWaiters.push((e) => {
        clearTimeout(timer);
        resolve(e);
      });
    });
  }

  async ready(): Promise<void> {
    await this.opened;
  }

  send(type: string, payload: Record<string, unknown>): void {
    const env: Envelope = { v: 1, id: randomUUID(), ts: Date.now(), type, payload };
    this.socket.send(JSON.stringify(env));
  }

  sendRaw(raw: string): void {
    this.socket.send(raw);
  }

  waitFor(type: string, timeoutMs = 2000): Promise<Envelope> {
    const buffered = this.received.findIndex((e) => e.type === type);
    if (buffered >= 0) {
      const [env] = this.received.splice(buffered, 1);
      return Promise.resolve(env!);
    }
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`timed out waiting for ${type}`)), timeoutMs);
      this.waiters.push({
        type,
        resolve: (e) => {
          clearTimeout(timer);
          resolve(e);
        },
      });
    });
  }

  close(): void {
    this.socket.close();
  }
}
