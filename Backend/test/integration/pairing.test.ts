import { test } from "node:test";
import assert from "node:assert/strict";
import { startTestServer } from "../../test-support/testServer.ts";
import { TestClient, generateIdentity, generateEphemeral, signBytes } from "../../test-support/testClient.ts";
import { authenticate, pairHostAndController } from "../../test-support/flows.ts";

test("full pairing flow: create -> init -> incoming -> decision -> result", async () => {
  const { server, url } = await startTestServer();
  try {
    const { hostClient, controllerClient, controllerIdentity, hostId, permissions } = await pairHostAndController(url);

    assert.ok(permissions.length > 0);
    assert.equal(server.ctx.devices.isAuthorized(controllerIdentity.deviceId, hostId), true);
    const record = server.ctx.devices.find(controllerIdentity.deviceId);
    assert.equal(record?.deviceName, "Android de Juan");
    assert.deepEqual(record?.permissions, permissions);

    hostClient.close();
    controllerClient.close();
  } finally {
    await server.close();
  }
});

test("host can reject a pairing request", async () => {
  const { server, url } = await startTestServer();
  try {
    const hostIdentity = generateIdentity();
    const controllerIdentity = generateIdentity();
    const hostClient = new TestClient(url);
    await authenticate(hostClient, "host", hostIdentity, { hostName: "PC Gaming" });

    const hostEph = generateEphemeral();
    hostClient.send("pairing.create", { hostName: "PC Gaming", hostEphPub: hostEph.publicKeyB64Url });
    const created = await hostClient.waitFor("pairing.created");
    const pairingId = created.payload["pairingId"] as string;

    const controllerClient = new TestClient(url);
    await authenticate(controllerClient, "controller", controllerIdentity);
    const controllerEph = generateEphemeral();
    controllerClient.send("pairing.init", {
      pairingId,
      deviceId: controllerIdentity.deviceId,
      deviceName: "Android de Juan",
      identityPub: controllerIdentity.publicKeyB64Url,
      ephemeralPub: controllerEph.publicKeyB64Url,
    });
    await hostClient.waitFor("pairing.incoming");

    const message = Buffer.from(`${pairingId}|${controllerIdentity.deviceId}|0|`, "utf8");
    const hostSignature = signBytes(hostIdentity.privateKey, message);
    hostClient.send("pairing.decision", { pairingId, approved: false, hostSignature });

    const result = await controllerClient.waitFor("pairing.result");
    assert.equal(result.payload["success"], false);
    assert.equal(result.payload["reason"], "rejected_by_user");
    assert.equal(server.ctx.devices.isAuthorized(controllerIdentity.deviceId, hostIdentity.deviceId), false);

    hostClient.close();
    controllerClient.close();
  } finally {
    await server.close();
  }
});

test("an expired pairing request is rejected", async () => {
  const { server, url } = await startTestServer({ pairingTtlSeconds: 60 });
  try {
    const hostIdentity = generateIdentity();
    const hostClient = new TestClient(url);
    await authenticate(hostClient, "host", hostIdentity);
    hostClient.send("pairing.create", { hostName: "PC Gaming", hostEphPub: generateEphemeral().publicKeyB64Url });
    const created = await hostClient.waitFor("pairing.created");
    const pairingId = created.payload["pairingId"] as string;

    // Force-expire it directly rather than sleeping 60s in a test.
    server.ctx.pairings.purgeExpired(Date.now() + 61_000);

    const controllerIdentity = generateIdentity();
    const controllerClient = new TestClient(url);
    await authenticate(controllerClient, "controller", controllerIdentity);
    controllerClient.send("pairing.init", {
      pairingId,
      deviceId: controllerIdentity.deviceId,
      deviceName: "Android de Juan",
      identityPub: controllerIdentity.publicKeyB64Url,
      ephemeralPub: generateEphemeral().publicKeyB64Url,
    });
    const result = await controllerClient.waitFor("pairing.result");
    assert.equal(result.payload["success"], false);
    assert.equal(result.payload["reason"], "expired_or_unknown");

    hostClient.close();
    controllerClient.close();
  } finally {
    await server.close();
  }
});

test("a pairingId cannot be consumed twice (replay protection)", async () => {
  const { server, url } = await startTestServer();
  try {
    const hostIdentity = generateIdentity();
    const hostClient = new TestClient(url);
    await authenticate(hostClient, "host", hostIdentity);
    hostClient.send("pairing.create", { hostName: "PC Gaming", hostEphPub: generateEphemeral().publicKeyB64Url });
    const created = await hostClient.waitFor("pairing.created");
    const pairingId = created.payload["pairingId"] as string;

    const initPayload = (deviceId: string, pub: string) => ({
      pairingId,
      deviceId,
      deviceName: "Attacker replay",
      identityPub: pub,
      ephemeralPub: generateEphemeral().publicKeyB64Url,
    });

    const controllerA = generateIdentity();
    const clientA = new TestClient(url);
    await authenticate(clientA, "controller", controllerA);
    clientA.send("pairing.init", initPayload(controllerA.deviceId, controllerA.publicKeyB64Url));
    await hostClient.waitFor("pairing.incoming"); // first claim succeeds and reaches the host

    const controllerB = generateIdentity();
    const clientB = new TestClient(url);
    await authenticate(clientB, "controller", controllerB);
    clientB.send("pairing.init", initPayload(controllerB.deviceId, controllerB.publicKeyB64Url));
    const resultB = await clientB.waitFor("pairing.result");
    assert.equal(resultB.payload["success"], false);
    assert.equal(resultB.payload["reason"], "already_used");

    hostClient.close();
    clientA.close();
    clientB.close();
  } finally {
    await server.close();
  }
});

test("pairing.init against an offline host reports host_offline", async () => {
  const { server, url } = await startTestServer();
  try {
    // Register a host, get a pairingId, then disconnect the host before the controller inits.
    const hostIdentity = generateIdentity();
    const hostClient = new TestClient(url);
    await authenticate(hostClient, "host", hostIdentity);
    hostClient.send("pairing.create", { hostName: "PC Gaming", hostEphPub: generateEphemeral().publicKeyB64Url });
    const created = await hostClient.waitFor("pairing.created");
    const pairingId = created.payload["pairingId"] as string;
    hostClient.close();
    await new Promise((r) => setTimeout(r, 50)); // let the close propagate server-side

    const controllerIdentity = generateIdentity();
    const controllerClient = new TestClient(url);
    await authenticate(controllerClient, "controller", controllerIdentity);
    controllerClient.send("pairing.init", {
      pairingId,
      deviceId: controllerIdentity.deviceId,
      deviceName: "Android de Juan",
      identityPub: controllerIdentity.publicKeyB64Url,
      ephemeralPub: generateEphemeral().publicKeyB64Url,
    });
    const result = await controllerClient.waitFor("pairing.result");
    assert.equal(result.payload["success"], false);
    assert.equal(result.payload["reason"], "host_offline");
    controllerClient.close();
  } finally {
    await server.close();
  }
});

test("revoking a device disconnects it and blocks future authorization checks", async () => {
  const { server, url } = await startTestServer();
  try {
    const { hostClient, controllerClient, controllerIdentity, hostId } = await pairHostAndController(url);
    assert.equal(server.ctx.devices.isAuthorized(controllerIdentity.deviceId, hostId), true);

    hostClient.send("pairing.revoke", { deviceId: controllerIdentity.deviceId });
    const revoked = await controllerClient.waitFor("pairing.revoked");
    assert.equal(revoked.payload["deviceId"], controllerIdentity.deviceId);
    assert.equal(server.ctx.devices.isAuthorized(controllerIdentity.deviceId, hostId), false);

    hostClient.close();
  } finally {
    await server.close();
  }
});

test("only the owning host can revoke a device it authorized", async () => {
  const { server, url } = await startTestServer();
  try {
    const fixture = await pairHostAndController(url);
    const otherHostIdentity = generateIdentity();
    const otherHostClient = new TestClient(url);
    await authenticate(otherHostClient, "host", otherHostIdentity, { hostName: "Other PC" });

    otherHostClient.send("pairing.revoke", { deviceId: fixture.controllerIdentity.deviceId });
    const err = await otherHostClient.waitFor("error");
    assert.equal(err.payload["code"], "not_found");
    assert.equal(server.ctx.devices.isAuthorized(fixture.controllerIdentity.deviceId, fixture.hostId), true);

    fixture.hostClient.close();
    fixture.controllerClient.close();
    otherHostClient.close();
  } finally {
    await server.close();
  }
});
