import { TokenBucketRateLimiter } from "../rateLimiter.ts";

export type SessionPhase = "PENDING" | "ACTIVE";

export interface ActiveSession {
  sessionId: string;
  phase: SessionPhase;
  controllerConnId: string;
  hostConnId: string;
  controllerDeviceId: string;
  hostId: string;
  /** Controller's ephemeral X25519 public key, kept to verify the Host's signed accept. */
  controllerEphPub: string;
  createdAt: number;
  acceptedAt: number | null;
  lastDataAt: number;
  /** Consecutive rate-limit violations; the session is cut when this gets out of hand. */
  violations: number;
}

/**
 * Tracks every control session the relay is currently routing. A session is a pure signaling/
 * routing record — the backend never holds a session key and never sees plaintext (see
 * Docs/SECURITY.md, "end-to-end session encryption").
 *
 * Invariants (enforced by create/remove):
 *  - at most one session per Controller connection,
 *  - at most one session per Host connection (two phones driving one mouse is never intended).
 */
export class ActiveSessionStore {
  private readonly byId = new Map<string, ActiveSession>();
  private readonly byController = new Map<string, string>();
  private readonly byHost = new Map<string, string>();
  readonly frameLimiter: TokenBucketRateLimiter;
  readonly byteLimiter: TokenBucketRateLimiter;

  constructor(framesPerSecond = 400, bytesPerSecond = 8 * 1024 * 1024) {
    this.frameLimiter = new TokenBucketRateLimiter(framesPerSecond * 2, framesPerSecond);
    this.byteLimiter = new TokenBucketRateLimiter(bytesPerSecond * 2, bytesPerSecond);
  }

  create(s: Omit<ActiveSession, "phase" | "acceptedAt" | "lastDataAt" | "violations">): ActiveSession {
    if (this.byId.has(s.sessionId)) throw new Error("duplicate sessionId");
    if (this.byController.has(s.controllerConnId) || this.byHost.has(s.hostConnId)) {
      throw new Error("connection already has a session; end it first");
    }
    const session: ActiveSession = {
      ...s,
      phase: "PENDING",
      acceptedAt: null,
      lastDataAt: s.createdAt,
      violations: 0,
    };
    this.byId.set(s.sessionId, session);
    this.byController.set(s.controllerConnId, s.sessionId);
    this.byHost.set(s.hostConnId, s.sessionId);
    return session;
  }

  get(sessionId: string): ActiveSession | undefined {
    return this.byId.get(sessionId);
  }

  forController(controllerConnId: string): ActiveSession | undefined {
    const id = this.byController.get(controllerConnId);
    return id ? this.byId.get(id) : undefined;
  }

  forHost(hostConnId: string): ActiveSession | undefined {
    const id = this.byHost.get(hostConnId);
    return id ? this.byId.get(id) : undefined;
  }

  /** Every session (any phase) of one device against one host — used by revocation. */
  forDevice(controllerDeviceId: string, hostId: string): ActiveSession[] {
    return this.all().filter((s) => s.controllerDeviceId === controllerDeviceId && s.hostId === hostId);
  }

  all(): ActiveSession[] {
    return [...this.byId.values()];
  }

  size(): number {
    return this.byId.size;
  }

  remove(sessionId: string): ActiveSession | undefined {
    const s = this.byId.get(sessionId);
    if (!s) return undefined;
    this.byId.delete(sessionId);
    if (this.byController.get(s.controllerConnId) === sessionId) this.byController.delete(s.controllerConnId);
    if (this.byHost.get(s.hostConnId) === sessionId) this.byHost.delete(s.hostConnId);
    this.frameLimiter.delete(sessionId);
    this.byteLimiter.delete(sessionId);
    return s;
  }
}
