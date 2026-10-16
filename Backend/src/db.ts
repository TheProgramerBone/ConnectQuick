import { DatabaseSync } from "node:sqlite";
import { mkdirSync } from "node:fs";
import { dirname } from "node:path";

const SCHEMA = `
CREATE TABLE IF NOT EXISTS hosts (
  host_id TEXT PRIMARY KEY,
  host_pub TEXT NOT NULL,
  host_name TEXT NOT NULL,
  host_version TEXT,
  created_at INTEGER NOT NULL,
  last_seen_at INTEGER
);

CREATE TABLE IF NOT EXISTS pairing_requests (
  pairing_id TEXT PRIMARY KEY,
  host_id TEXT NOT NULL REFERENCES hosts(host_id),
  nonce TEXT NOT NULL,
  host_eph_pub TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  used INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_pairing_requests_host ON pairing_requests(host_id);

-- One row per (device, host) pair: the same phone may be paired with several PCs.
CREATE TABLE IF NOT EXISTS authorized_devices (
  device_id TEXT NOT NULL,
  host_id TEXT NOT NULL REFERENCES hosts(host_id),
  device_pub TEXT NOT NULL,
  device_name TEXT NOT NULL,
  permissions TEXT NOT NULL,
  authorized_at INTEGER NOT NULL,
  last_connected_at INTEGER,
  revoked INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (device_id, host_id)
);

CREATE INDEX IF NOT EXISTS idx_authorized_devices_host ON authorized_devices(host_id);
`;

/**
 * Schema v1 keyed authorized_devices by device_id alone, so a phone paired with a second PC
 * silently kept pointing at the first one (ON CONFLICT updated everything except host_id) and
 * could never open a session with the second. Detect that shape and rebuild the table with the
 * (device_id, host_id) key, preserving every row.
 */
function migrateAuthorizedDevices(db: DatabaseSync): void {
  const cols = db.prepare(`PRAGMA table_info(authorized_devices)`).all() as { name: string; pk: number }[];
  if (cols.length === 0) return; // fresh database, nothing to migrate
  const pkCols = cols.filter((c) => c.pk > 0).map((c) => c.name);
  if (pkCols.length === 2) return; // already composite
  db.exec("BEGIN");
  try {
    db.exec(`ALTER TABLE authorized_devices RENAME TO authorized_devices_v1`);
    db.exec(`DROP INDEX IF EXISTS idx_authorized_devices_host`);
    db.exec(SCHEMA);
    db.exec(
      `INSERT INTO authorized_devices
         (device_id, host_id, device_pub, device_name, permissions, authorized_at, last_connected_at, revoked)
       SELECT device_id, host_id, device_pub, device_name, permissions, authorized_at, last_connected_at, revoked
       FROM authorized_devices_v1`,
    );
    db.exec(`DROP TABLE authorized_devices_v1`);
    db.exec("COMMIT");
  } catch (err) {
    db.exec("ROLLBACK");
    throw err;
  }
}

export function openDatabase(path: string): DatabaseSync {
  if (path !== ":memory:") {
    mkdirSync(dirname(path), { recursive: true });
  }
  const db = new DatabaseSync(path);
  db.exec("PRAGMA journal_mode = WAL;");
  db.exec("PRAGMA foreign_keys = ON;");
  migrateAuthorizedDevices(db);
  db.exec(SCHEMA);
  return db;
}
