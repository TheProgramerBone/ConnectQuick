import { test } from "node:test";
import assert from "node:assert/strict";
import { startTestServer } from "../../test-support/testServer.ts";
import { TestClient, generateIdentity } from "../../test-support/testClient.ts";
import { authenticate, pairHostAndController } from "../../test-support/flows.ts";

test("session.connect relays to the host and session.state relays back", async () => {
  const { server, url } = await startTestServer();
  try {
    const { hostClient, controllerClient, hostId, controllerIdentity } = await pairHostAndController(url);

    controllerClient.send("session.connect", { hostId });
    const connecting = await controllerClient.waitFor("session.state");
    assert.equal(connecting.payload["state"], "CONNECTING");

    const forwarded = await hostClient.waitFor("session.connect");
    assert.equal(forwarded.payload["hostId"], hostId);
    assert.equal(forwarded.payload["deviceId"], controllerIdentity.deviceId);
    assert.equal(forwarded.payload["deviceName"], "Android de Juan");

    hostClient.send("session.state", { state: "CONNECTED", rttMs: 34 });
    const connected = await controllerClient.waitFor("session.state");
    assert.equal(connected.payload["state"], "CONNECTED");
    assert.equal(connected.payload["rttMs"], 34);

    hostClient.close();
    controllerClient.close();
  } finally {
    await server.close();
  }
});

test("session.connect from an unauthorized controller is rejected", async () => {
  const { server, url } = await startTestServer();
  try {
    const { hostClient, hostId } = await pairHostAndController(url);

    const strangerIdentity = generateIdentity();
    const stranger = new TestClient(url);
    await authenticate(stranger, "controller", strangerIdentity);
    stranger.send("session.connect", { hostId });
    const err = await stranger.waitFor("error");
    assert.equal(err.payload["code"], "forbidden");

    hostClient.close();
    stranger.close();
  } finally {
    await server.close();
  }
});

test("session.connect against an offline host reports DISCONNECTED without contacting anyone", async () => {
  const { server, url } = await startTestServer();
  try {
    const { hostClient, controllerClient, hostId } = await pairHostAndController(url);
    hostClient.close();
    await new Promise((r) => setTimeout(r, 50));

    controllerClient.send("session.connect", { hostId });
    const state = await controllerClient.waitFor("session.state");
    assert.equal(state.payload["state"], "DISCONNECTED");
    assert.equal(state.payload["reason"], "host_offline");

    controllerClient.close();
  } finally {
    await server.close();
  }
});

test("host disconnecting mid-session notifies the linked controller", async () => {
  const { server, url } = await startTestServer();
  try {
    const { hostClient, controllerClient, hostId } = await pairHostAndController(url);
    controllerClient.send("session.connect", { hostId });
    await controllerClient.waitFor("session.state"); // CONNECTING
    await hostClient.waitFor("session.connect");

    hostClient.close();
    const disconnected = await controllerClient.waitFor("session.state");
    assert.equal(disconnected.payload["state"], "DISCONNECTED");
    assert.equal(disconnected.payload["reason"], "host_disconnected");

    controllerClient.close();
  } finally {
    await server.close();
  }
});
