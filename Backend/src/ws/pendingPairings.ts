export interface PendingPairing {
  pairingId: string;
  hostId: string;
  deviceId: string;
  deviceName: string;
  identityPub: string;
  ephemeralPub: string;
  createdAt: number;
}

/**
 * Pairing requests that a Host has been asked to approve/reject (between `pairing.incoming`
 * and `pairing.decision`). Looked up by pairingId when the decision arrives, and by deviceId
 * when a controller disconnects before the host ever answers (§7 step 5-6 in flight).
 */
export class PendingPairingStore {
  private readonly byId = new Map<string, PendingPairing>();

  set(p: PendingPairing): void {
    this.byId.set(p.pairingId, p);
  }

  /** Removes and returns the entry — a decision can only ever be consumed once. */
  take(pairingId: string): PendingPairing | undefined {
    const p = this.byId.get(pairingId);
    if (p) this.byId.delete(pairingId);
    return p;
  }

  discardByDeviceId(deviceId: string): void {
    for (const [id, p] of this.byId) {
      if (p.deviceId === deviceId) this.byId.delete(id);
    }
  }
}
