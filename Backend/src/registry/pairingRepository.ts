import type { DatabaseSync } from "node:sqlite";

export interface PairingRequestRecord {
  pairingId: string;
  hostId: string;
  nonce: string;
  hostEphPub: string;
  createdAt: number;
  expiresAt: number;
  used: boolean;
}

export class PairingRepository {
  private readonly db: DatabaseSync;

  constructor(db: DatabaseSync) {
    this.db = db;
  }

  create(pairingId: string, hostId: string, nonce: string, hostEphPub: string, ttlSeconds: number): PairingRequestRecord {
    const now = Date.now();
    const expiresAt = now + ttlSeconds * 1000;
    this.db
      .prepare(
        `INSERT INTO pairing_requests (pairing_id, host_id, nonce, host_eph_pub, created_at, expires_at, used)
         VALUES (?, ?, ?, ?, ?, ?, 0)`,
      )
      .run(pairingId, hostId, nonce, hostEphPub, now, expiresAt);
    return { pairingId, hostId, nonce, hostEphPub, createdAt: now, expiresAt, used: false };
  }

  find(pairingId: string): PairingRequestRecord | undefined {
    const row = this.db.prepare(`SELECT * FROM pairing_requests WHERE pairing_id = ?`).get(pairingId) as
      | Record<string, unknown>
      | undefined;
    if (!row) return undefined;
    return {
      pairingId: row["pairing_id"] as string,
      hostId: row["host_id"] as string,
      nonce: row["nonce"] as string,
      hostEphPub: row["host_eph_pub"] as string,
      createdAt: row["created_at"] as number,
      expiresAt: row["expires_at"] as number,
      used: Boolean(row["used"]),
    };
  }

  /**
   * Atomically marks a pairing request used, but only if it is still unused and unexpired.
   * Returns true if this call is the one that claimed it (prevents replay / double-consumption
   * races between two concurrent `pairing.init` attempts for the same pairingId).
   */
  tryConsume(pairingId: string, now: number): boolean {
    const result = this.db
      .prepare(`UPDATE pairing_requests SET used = 1 WHERE pairing_id = ? AND used = 0 AND expires_at > ?`)
      .run(pairingId, now);
    return result.changes === 1;
  }

  purgeExpired(now: number): number {
    const result = this.db.prepare(`DELETE FROM pairing_requests WHERE expires_at <= ?`).run(now);
    return Number(result.changes);
  }

  countCreatedSince(hostId: string, sinceMs: number): number {
    const row = this.db
      .prepare(`SELECT COUNT(*) as c FROM pairing_requests WHERE host_id = ? AND created_at >= ?`)
      .get(hostId, sinceMs) as { c: number };
    return row.c;
  }
}
