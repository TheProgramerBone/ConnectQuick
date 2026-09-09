import { test } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { startTestServer } from "../../test-support/testServer.ts";
import { TestClient, generateIdentity, signBytes } from "../../test-support/testClient.ts";
import { authenticate } from "../../test-support/flows.ts";

test("transport handshake: challenge -> signed hello -> ack with capabilities", async () => {
  const { server, url } = await startTestServer();
  try {
    const client = new TestClient(url);
    const identity = generateIdentity();
    await authenticate(client, "host", identity, { hostName: "PC Gaming" });
    client.close();
  } finally {
    await server.close();
  }
});

test("rejects a hello with an invalid challenge signature", async () => {
  const { server, url } = await startTestServer();
  try {
    const client = new TestClient(url);
    await client.ready();
    await client.waitFor("hello.challenge");
    const identity = generateIdentity();
    client.send("hello", {
      role: "host",
      deviceId: identity.deviceId,
      identityPub: identity.publicKeyB64Url,
      challengeSig: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
      clientVersion: "0.1.0",
    });
    const err = await client.waitFor("error");
    assert.equal(err.payload["code"], "auth_failed");
    client.close();
  } finally {
    await server.close();
  }
});

test("rejects a hello whose deviceId doesn't match identityPub", async () => {
  const { server, url } = await startTestServer();
  try {
    const client = new TestClient(url);
    await client.ready();
    const challenge = await client.waitFor("hello.challenge");
    const identity = generateIdentity();
    const other = generateIdentity();
    const sig = signBytes(identity.privateKey, Buffer.from(challenge.payload["serverChallenge"] as string, "base64url"));
    client.send("hello", {
      role: "host",
      deviceId: other.deviceId, // mismatched
      identityPub: identity.publicKeyB64Url,
      challengeSig: sig,
      clientVersion: "0.1.0",
    });
    const err = await client.waitFor("error");
    assert.equal(err.payload["code"], "auth_failed");
    client.close();
  } finally {
    await server.close();
  }
});

test("a second identity cannot claim an already-registered hostId", async () => {
  const { server, url } = await startTestServer();
  try {
    const identity = generateIdentity();
    const first = new TestClient(url);
    await authenticate(first, "host", identity, { hostName: "PC Gaming" });

    // A different keypair claiming the exact same deviceId is cryptographically impossible
    // (deviceId is derived from the pubkey), so simulate the only real attack: the backend
    // must not let hello succeed for `identity.deviceId` paired with a *different* identityPub.
    const impostor = generateIdentity();
    const second = new TestClient(url);
    await second.ready();
    const challenge = await second.waitFor("hello.challenge");
    const sig = signBytes(impostor.privateKey, Buffer.from(challenge.payload["serverChallenge"] as string, "base64url"));
    second.send("hello", {
      role: "host",
      deviceId: identity.deviceId, // claims the first host's id
      identityPub: impostor.publicKeyB64Url, // but with a different key
      challengeSig: sig, // this makes deriveDeviceId(impostor pub) != identity.deviceId
      clientVersion: "0.1.0",
    });
    const err = await second.waitFor("error");
    assert.equal(err.payload["code"], "auth_failed"); // deviceId/identityPub mismatch caught first

    first.close();
    second.close();
  } finally {
    await server.close();
  }
});

test("rejects an unknown message type", async () => {
  const { server, url } = await startTestServer();
  try {
    const client = new TestClient(url);
    const identity = generateIdentity();
    await authenticate(client, "host", identity);
    client.sendRaw(JSON.stringify({ v: 1, id: randomUUID(), ts: Date.now(), type: "not.a.type", payload: {} }));
    const err = await client.waitFor("error");
    assert.equal(err.payload["code"], "invalid_message");
    client.close();
  } finally {
    await server.close();
  }
});

test("rejects a message sent before authentication (other than hello)", async () => {
  const { server, url } = await startTestServer();
  try {
    const client = new TestClient(url);
    await client.ready();
    await client.waitFor("hello.challenge");
    client.send("heartbeat.ping", { seq: 1 });
    const err = await client.waitFor("error");
    assert.equal(err.payload["code"], "auth_failed");
    client.close();
  } finally {
    await server.close();
  }
});

test("rejects a server-only message type sent by a client", async () => {
  const { server, url } = await startTestServer();
  try {
    const client = new TestClient(url);
    const identity = generateIdentity();
    await authenticate(client, "host", identity);
    client.send("presence.update", { hostId: "x", online: true });
    const err = await client.waitFor("error");
    assert.equal(err.payload["code"], "invalid_message");
    client.close();
  } finally {
    await server.close();
  }
});

test("heartbeat ping/pong round-trip", async () => {
  const { server, url } = await startTestServer();
  try {
    const client = new TestClient(url);
    const identity = generateIdentity();
    await authenticate(client, "host", identity);
    client.send("heartbeat.ping", { seq: 42 });
    const pong = await client.waitFor("heartbeat.pong");
    assert.equal(pong.payload["seq"], 42);
    client.close();
  } finally {
    await server.close();
  }
});
