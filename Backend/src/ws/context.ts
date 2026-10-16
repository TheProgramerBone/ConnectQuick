import type { Config } from "../config.ts";
import type { HostRepository } from "../registry/hostRepository.ts";
import type { DeviceRepository } from "../registry/deviceRepository.ts";
import type { PairingRepository } from "../registry/pairingRepository.ts";
import type { ConnectionRegistry } from "./connectionRegistry.ts";
import type { PendingPairingStore } from "./pendingPairings.ts";
import type { ActiveSessionStore } from "./activeSessions.ts";
import type { TokenBucketRateLimiter } from "../rateLimiter.ts";
import type { Logger } from "../log.ts";

/** Everything a message handler needs, threaded through explicitly (no globals/singletons). */
export interface AppContext {
  config: Config;
  hosts: HostRepository;
  devices: DeviceRepository;
  pairings: PairingRepository;
  connections: ConnectionRegistry;
  pendingPairings: PendingPairingStore;
  activeSessions: ActiveSessionStore;
  /** Keyed by remote IP — protects pairing.init against brute-forcing pairingId/nonce. */
  pairingInitLimiter: TokenBucketRateLimiter;
  /** Keyed by connection id — generic flood protection for control messages. */
  messageLimiter: TokenBucketRateLimiter;
  /** Keyed by connection id — coarse pre-parse flood cap that also covers session.data. */
  rawLimiter: TokenBucketRateLimiter;
  /** sessionId -> expiry (ms). A session.connect whose sessionId was already seen is a replay. */
  seenSessionIds: Map<string, number>;
  /** Set while shutting down: /readyz fails and new connections are refused. */
  draining: boolean;
  log: Logger;
}
