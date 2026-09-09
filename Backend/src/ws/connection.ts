import type { WebSocket } from "ws";
import { makeEnvelope } from "../schemaValidator.ts";

export type SessionState = "CONNECTING" | "AUTHENTICATING" | "CONNECTED" | "RECONNECTING" | "DISCONNECTED" | "REJECTED";
export type Role = "host" | "controller";

/**
 * Server-side state for one live WebSocket. This is intentionally in-memory-only and
 * ephemeral — see Docs/SECURITY.md: nothing here outlives the socket except what's already
 * durable in the database (host/device registry rows).
 */
export class Connection {
  readonly id: string;
  readonly socket: WebSocket;
  readonly remoteAddress: string;
  readonly serverChallenge: string;

  state: SessionState = "CONNECTING";
  role: Role | null = null;
  /** For hosts this equals hostId; for controllers, the controller's own deviceId. */
  deviceId: string | null = null;
  identityPub: string | null = null;
  clientVersion: string | null = null;
  lastActivityAt: number = Date.now();

  constructor(id: string, socket: WebSocket, remoteAddress: string, serverChallenge: string) {
    this.id = id;
    this.socket = socket;
    this.remoteAddress = remoteAddress;
    this.serverChallenge = serverChallenge;
  }

  send(type: string, payload: Record<string, unknown>): void {
    if (this.socket.readyState !== this.socket.OPEN) return;
    this.socket.send(JSON.stringify(makeEnvelope(type, payload)));
  }

  touch(): void {
    this.lastActivityAt = Date.now();
  }
}
