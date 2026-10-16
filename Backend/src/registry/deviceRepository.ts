import type { DatabaseSync } from "node:sqlite";

export interface AuthorizedDeviceRecord {
  deviceId: string;
  hostId: string;
  devicePub: string;
  deviceName: string;
  permissions: string[];
  authorizedAt: number;
  lastConnectedAt: number | null;
  revoked: boolean;
}

function rowToRecord(row: Record<string, unknown>): AuthorizedDeviceRecord {
  return {
    deviceId: row["device_id"] as string,
    hostId: row["host_id"] as string,
    devicePub: row["device_pub"] as string,
    deviceName: row["device_name"] as string,
    permissions: JSON.parse(row["permissions"] as string) as string[],
    authorizedAt: row["authorized_at"] as number,
    lastConnectedAt: (row["last_connected_at"] as number | null) ?? null,
    revoked: Boolean(row["revoked"]),
  };
}

export class DeviceRepository {
  private readonly db: DatabaseSync;

  constructor(db: DatabaseSync) {
    this.db = db;
  }

  authorize(
    deviceId: string,
    hostId: string,
    devicePub: string,
    deviceName: string,
    permissions: string[],
  ): AuthorizedDeviceRecord {
    const now = Date.now();
    this.db
      .prepare(
        `INSERT INTO authorized_devices
           (device_id, host_id, device_pub, device_name, permissions, authorized_at, last_connected_at, revoked)
         VALUES (?, ?, ?, ?, ?, ?, ?, 0)
         ON CONFLICT(device_id, host_id) DO UPDATE SET
           device_pub = excluded.device_pub,
           device_name = excluded.device_name,
           permissions = excluded.permissions,
           authorized_at = excluded.authorized_at,
           revoked = 0`,
      )
      .run(deviceId, hostId, devicePub, deviceName, JSON.stringify(permissions), now, now);
    return this.find(deviceId, hostId)!;
  }

  find(deviceId: string, hostId: string): AuthorizedDeviceRecord | undefined {
    const row = this.db
      .prepare(`SELECT * FROM authorized_devices WHERE device_id = ? AND host_id = ?`)
      .get(deviceId, hostId) as Record<string, unknown> | undefined;
    return row ? rowToRecord(row) : undefined;
  }

  listByHost(hostId: string): AuthorizedDeviceRecord[] {
    const rows = this.db.prepare(`SELECT * FROM authorized_devices WHERE host_id = ?`).all(hostId) as Record<
      string,
      unknown
    >[];
    return rows.map(rowToRecord);
  }

  listByDevice(deviceId: string): AuthorizedDeviceRecord[] {
    const rows = this.db.prepare(`SELECT * FROM authorized_devices WHERE device_id = ?`).all(deviceId) as Record<
      string,
      unknown
    >[];
    return rows.map(rowToRecord);
  }

  isAuthorized(deviceId: string, hostId: string): boolean {
    const rec = this.find(deviceId, hostId);
    return !!rec && !rec.revoked;
  }

  revoke(deviceId: string, hostId: string): void {
    this.db.prepare(`UPDATE authorized_devices SET revoked = 1 WHERE device_id = ? AND host_id = ?`).run(deviceId, hostId);
  }

  touchLastConnected(deviceId: string, hostId: string): void {
    this.db
      .prepare(`UPDATE authorized_devices SET last_connected_at = ? WHERE device_id = ? AND host_id = ?`)
      .run(Date.now(), deviceId, hostId);
  }
}
