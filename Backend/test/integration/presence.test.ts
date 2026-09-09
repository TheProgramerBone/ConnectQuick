import { test } from "node:test";
import assert from "node:assert/strict";
import { startTestServer } from "../../test-support/testServer.ts";
import { TestClient, generateIdentity } from "../../test-support/testClient.ts";
import { authenticate, pairHostAndController } from "../../test-support/flows.ts";

test("an authorized controller is notified when its host goes offline and back online", async () => {
  const { server, url } = await startTestServer();
  try {
    const { hostClient, hostIdentity, controllerClient, hostId } = await pairHostAndController(url);

    hostClient.close();
    const offline = await controllerClient.waitFor("presence.update");
    assert.equal(offline.payload["hostId"], hostId);
    assert.equal(offline.payload["online"], false);
    assert.ok(typeof offline.payload["lastSeenAt"] === "number");

    const hostClient2 = new TestClient(url);
    await authenticate(hostClient2, "host", hostIdentity, { hostName: "PC Gaming" });
    const online = await controllerClient.waitFor("presence.update");
    assert.equal(online.payload["hostId"], hostId);
    assert.equal(online.payload["online"], true);

    hostClient2.close();
    controllerClient.close();
  } finally {
    await server.close();
  }
});

test("a revoked (unauthorized) controller does not receive presence updates", async () => {
  const { server, url } = await startTestServer();
  try {
    const fixture = await pairHostAndController(url);
    fixture.hostClient.send("pairing.revoke", { deviceId: fixture.controllerIdentity.deviceId });
    await fixture.controllerClient.waitFor("pairing.revoked");

    // Reconnect a *different* controller (unrelated) to prove presence broadcast only reaches
    // currently-authorized devices, not everyone who ever connected.
    const bystander = new TestClient(url);
    const bystanderIdentity = generateIdentity();
    await authenticate(bystander, "controller", bystanderIdentity);

    fixture.hostClient.close();
    await assert.rejects(() => bystander.waitFor("presence.update", 300));

    bystander.close();
  } finally {
    await server.close();
  }
});
