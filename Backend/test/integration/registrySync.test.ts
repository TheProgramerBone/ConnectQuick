import { test } from "node:test";
import assert from "node:assert/strict";
import { startTestServer } from "../../test-support/testServer.ts";
import { TestClient, generateIdentity } from "../../test-support/testClient.ts";
import { authenticate } from "../../test-support/flows.ts";

const entry = (id: { deviceId: string; publicKeyB64Url: string }, name = "Android de Juan") => ({
  deviceId: id.deviceId,
  devicePub: id.publicKeyB64Url,
  deviceName: name,
  permissions: ["remote_desktop", "mouse", "keyboard"],
});

test("registry.sync lets a fresh relay serve phones paired elsewhere, and is authoritative", async () => {
  const { server, url } = await startTestServer();
  try {
    const host = generateIdentity();
    const phoneA = generateIdentity();
    const phoneB = generateIdentity();
    const hostClient = new TestClient(url);
    await authenticate(hostClient, "host", host, { hostName: "PC" });

    hostClient.send("registry.sync", { devices: [entry(phoneA), entry(phoneB, "Tablet")] });
    const first = await hostClient.waitFor("registry.synced");
    assert.deepEqual(first.payload, { authorized: 2, revoked: 0 });
    assert.equal(server.ctx.devices.isAuthorized(phoneA.deviceId, host.deviceId), true);
    assert.deepEqual(server.ctx.devices.find(phoneB.deviceId, host.deviceId)?.permissions, ["remote_desktop", "mouse", "keyboard"]);

    // A phone left out of the list is revoked (a revocation made while this relay was offline).
    hostClient.send("registry.sync", { devices: [entry(phoneA)] });
    const second = await hostClient.waitFor("registry.synced");
    assert.deepEqual(second.payload, { authorized: 1, revoked: 1 });
    assert.equal(server.ctx.devices.isAuthorized(phoneA.deviceId, host.deviceId), true);
    assert.equal(server.ctx.devices.isAuthorized(phoneB.deviceId, host.deviceId), false);

    // Syncing never touches another host's phones.
    const other = generateIdentity();
    const otherClient = new TestClient(url);
    await authenticate(otherClient, "host", other, { hostName: "Otro" });
    otherClient.send("registry.sync", { devices: [] });
    await otherClient.waitFor("registry.synced");
    assert.equal(server.ctx.devices.isAuthorized(phoneA.deviceId, host.deviceId), true);

    hostClient.close();
    otherClient.close();
  } finally {
    await server.close();
  }
});

test("registry.sync rejects a device id that is not derived from its key, and controllers cannot use it", async () => {
  const { server, url } = await startTestServer();
  try {
    const host = generateIdentity();
    const phone = generateIdentity();
    const impostor = generateIdentity();
    const hostClient = new TestClient(url);
    await authenticate(hostClient, "host", host, { hostName: "PC" });
    hostClient.send("registry.sync", { devices: [{ ...entry(phone), devicePub: impostor.publicKeyB64Url }] });
    const err = await hostClient.waitFor("error");
    assert.equal(err.payload["code"], "auth_failed");
    assert.equal(server.ctx.devices.isAuthorized(phone.deviceId, host.deviceId), false);

    const ctl = new TestClient(url);
    await authenticate(ctl, "controller", phone);
    ctl.send("registry.sync", { devices: [entry(phone)] });
    const err2 = await ctl.waitFor("error");
    assert.equal(err2.payload["code"], "forbidden");
    assert.equal(server.ctx.devices.isAuthorized(phone.deviceId, phone.deviceId), false);

    hostClient.close();
    ctl.close();
  } finally {
    await server.close();
  }
});
