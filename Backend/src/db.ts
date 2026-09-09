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

CREATE TABLE IF NOT EXISTS authorized_devices (
  device_id TEXT PRIMARY KEY,
  host_id TEXT NOT NULL REFERENCES hosts(host_id),
  device_pub TEXT NOT NULL,
  device_name TEXT NOT NULL,
  permissions TEXT NOT NULL,
  authorized_at INTEGER NOT NULL,
  last_connected_at INTEGER,
  revoked INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_authorized_devices_host ON authorized_devices(host_id);
`;

export function openDatabase(path: string): DatabaseSync {
  if (path !== ":memory:") {
    mkdirSync(dirname(path), { recursive: true });
  }
  const db = new DatabaseSync(path);
  db.exec("PRAGMA journal_mode = WAL;");
  db.exec("PRAGMA foreign_keys = ON;");
  db.exec(SCHEMA);
  return db;
}
