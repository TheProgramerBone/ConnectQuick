import { test } from "node:test";
import assert from "node:assert/strict";
import { openDatabase } from "../../src/db.ts";
import { HostRepository } from "../../src/registry/hostRepository.ts";
import { DeviceRepository } from "../../src/registry/deviceRepository.ts";
import { PairingRepository } from "../../src/registry/pairingRepository.ts";

function freshDb() {
  return openDatabase(":memory:");
}

/** authorized_devices/pairing_requests both FK-reference hosts(host_id) — register first. */
function withHosts(...hostIds: string[]) {
  const db = freshDb();
  const hosts = new HostRepository(db);
  for (const id of hostIds) hosts.findOrRegister(id, `pub-${id}`, `Host ${id}`, null);
  return { db, hosts };
}

test("HostRepository.findOrRegister registers once and is idempotent thereafter", () => {
  const repo = new HostRepository(freshDb());
  const a = repo.findOrRegister("host1", "pubA", "PC Gaming", "0.1.0");
  const b = repo.findOrRegister("host1", "pubA", "Different name ignored", "0.2.0");
  assert.equal(a.hostName, "PC Gaming");
  assert.equal(b.hostName, "PC Gaming"); // second call is a no-op find, not an overwrite
  assert.equal(b.createdAt, a.createdAt);
});

test("HostRepository.rename and touchLastSeen update in place", () => {
  const repo = new HostRepository(freshDb());
  repo.findOrRegister("host1", "pubA", "Old name", null);
  repo.rename("host1", "New name");
  const rec = repo.find("host1");
  assert.equal(rec?.hostName, "New name");

  const before = repo.find("host1")!.lastSeenAt;
  repo.touchLastSeen("host1");
  assert.ok((repo.find("host1")!.lastSeenAt ?? 0) >= (before ?? 0));
});

test("DeviceRepository.authorize + isAuthorized + revoke lifecycle", () => {
  const { db } = withHosts("host1", "host2");
  const repo = new DeviceRepository(db);
  repo.authorize("dev1", "host1", "devPub", "Android de Juan", ["mouse", "keyboard"]);

  assert.equal(repo.isAuthorized("dev1", "host1"), true);
  assert.equal(repo.isAuthorized("dev1", "host2"), false, "authorization is scoped to one host");
  assert.equal(repo.isAuthorized("unknown", "host1"), false);

  const rec = repo.find("dev1");
  assert.deepEqual(rec?.permissions, ["mouse", "keyboard"]);

  repo.revoke("dev1");
  assert.equal(repo.isAuthorized("dev1", "host1"), false);
  assert.equal(repo.find("dev1")?.revoked, true);
});

test("DeviceRepository.listByHost only returns that host's devices", () => {
  const { db } = withHosts("host1", "host2");
  const repo = new DeviceRepository(db);
  repo.authorize("dev1", "host1", "pub1", "Phone A", []);
  repo.authorize("dev2", "host1", "pub2", "Phone B", []);
  repo.authorize("dev3", "host2", "pub3", "Phone C", []);
  const forHost1 = repo.listByHost("host1").map((d) => d.deviceId).sort();
  assert.deepEqual(forHost1, ["dev1", "dev2"]);
});

test("PairingRepository.tryConsume claims a request exactly once (replay protection)", () => {
  const { db } = withHosts("host1");
  const repo = new PairingRepository(db);
  const now = Date.now();
  repo.create("pairing1", "host1", "nonce", "ephPub", 60);

  assert.equal(repo.tryConsume("pairing1", now), true, "first claim succeeds");
  assert.equal(repo.tryConsume("pairing1", now), false, "second claim (replay) is rejected");
});

test("PairingRepository.tryConsume rejects an expired request", () => {
  const { db } = withHosts("host1");
  const repo = new PairingRepository(db);
  const created = repo.create("pairing1", "host1", "nonce", "ephPub", 60);
  assert.equal(repo.tryConsume("pairing1", created.expiresAt + 1), false);
});

test("PairingRepository.countCreatedSince and purgeExpired", () => {
  const { db } = withHosts("host1", "host2");
  const repo = new PairingRepository(db);
  const now = Date.now();
  repo.create("p1", "host1", "n1", "e1", 60);
  repo.create("p2", "host1", "n2", "e2", 60);
  repo.create("p3", "host2", "n3", "e3", 60);
  assert.equal(repo.countCreatedSince("host1", now - 1000), 2);
  assert.equal(repo.countCreatedSince("host2", now - 1000), 1);

  const purged = repo.purgeExpired(now + 61_000);
  assert.equal(purged, 3);
  assert.equal(repo.find("p1"), undefined);
});
