import type { DatabaseSync } from "node:sqlite";

export interface HostRecord {
  hostId: string;
  hostPub: string;
  hostName: string;
  hostVersion: string | null;
  createdAt: number;
  lastSeenAt: number | null;
}

export class HostRepository {
  private readonly db: DatabaseSync;

  constructor(db: DatabaseSync) {
    this.db = db;
  }

  /** Returns the existing record for hostId, or registers it the first time it's ever seen. */
  findOrRegister(hostId: string, hostPub: string, hostName: string, hostVersion: string | null): HostRecord {
    const existing = this.find(hostId);
    if (existing) return existing;
    const now = Date.now();
    this.db
      .prepare(
        `INSERT INTO hosts (host_id, host_pub, host_name, host_version, created_at, last_seen_at)
         VALUES (?, ?, ?, ?, ?, ?)`,
      )
      .run(hostId, hostPub, hostName, hostVersion, now, now);
    return { hostId, hostPub, hostName, hostVersion, createdAt: now, lastSeenAt: now };
  }

  find(hostId: string): HostRecord | undefined {
    const row = this.db.prepare(`SELECT * FROM hosts WHERE host_id = ?`).get(hostId) as
      | Record<string, unknown>
      | undefined;
    if (!row) return undefined;
    return {
      hostId: row["host_id"] as string,
      hostPub: row["host_pub"] as string,
      hostName: row["host_name"] as string,
      hostVersion: (row["host_version"] as string | null) ?? null,
      createdAt: row["created_at"] as number,
      lastSeenAt: (row["last_seen_at"] as number | null) ?? null,
    };
  }

  touchLastSeen(hostId: string): void {
    this.db.prepare(`UPDATE hosts SET last_seen_at = ? WHERE host_id = ?`).run(Date.now(), hostId);
  }

  rename(hostId: string, newName: string): void {
    this.db.prepare(`UPDATE hosts SET host_name = ? WHERE host_id = ?`).run(newName, hostId);
  }
}
