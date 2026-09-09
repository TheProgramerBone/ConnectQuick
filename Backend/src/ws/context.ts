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
  /** Keyed by connection id — generic flood protection independent of message type. */
  messageLimiter: TokenBucketRateLimiter;
  log: Logger;
}
