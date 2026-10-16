import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { DatabaseSync } from "node:sqlite";
import { openDatabase } from "../../src/db.ts";
import { DeviceRepository } from "../../src/registry/deviceRepository.ts";

test("a v1 database (device_id primary key) is migrated in place and keeps every authorization", () => {
  const dir = mkdtempSync(join(tmpdir(), "cq-mig-"));
  const path = join(dir, "old.sqlite");
  try {
    const old = new DatabaseSync(path);
    old.exec(`
      CREATE TABLE hosts (host_id TEXT PRIMARY KEY, host_pub TEXT NOT NULL, host_name TEXT NOT NULL, host_version TEXT, created_at INTEGER NOT NULL, last_seen_at INTEGER);
      CREATE TABLE authorized_devices (device_id TEXT PRIMARY KEY, host_id TEXT NOT NULL REFERENCES hosts(host_id), device_pub TEXT NOT NULL, device_name TEXT NOT NULL, permissions TEXT NOT NULL, authorized_at INTEGER NOT NULL, last_connected_at INTEGER, revoked INTEGER NOT NULL DEFAULT 0);
      INSERT INTO hosts VALUES ('host1','p1','PC 1','0.1',1,1);
      INSERT INTO hosts VALUES ('host2','p2','PC 2','0.1',1,1);
      INSERT INTO authorized_devices VALUES ('phone','host1','phonePub','Pixel','["mouse"]',5,NULL,0);
    `);
    old.close();

    const db = openDatabase(path);
    const repo = new DeviceRepository(db);
    assert.equal(repo.isAuthorized("phone", "host1"), true, "pre-existing pairing survives the migration");
    assert.deepEqual(repo.find("phone", "host1")?.permissions, ["mouse"]);

    repo.authorize("phone", "host2", "phonePub", "Pixel", ["keyboard"]);
    assert.equal(repo.isAuthorized("phone", "host2"), true, "the same phone can now pair with a second PC");
    assert.equal(repo.isAuthorized("phone", "host1"), true, "...without disturbing the first");
    assert.equal(repo.listByDevice("phone").length, 2);

    repo.revoke("phone", "host1");
    assert.equal(repo.isAuthorized("phone", "host1"), false);
    assert.equal(repo.isAuthorized("phone", "host2"), true, "revoking one host leaves the other pairing intact");
    db.close();

    const again = openDatabase(path); // running the migration twice is a no-op
    assert.equal(new DeviceRepository(again).listByDevice("phone").length, 2);
    again.close();
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
