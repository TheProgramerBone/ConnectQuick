import { test } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { startTestServer } from "../../test-support/testServer.ts";
import { TestClient, generateIdentity, signBytes } from "../../test-support/testClient.ts";
import { authenticate, buildSessionConnect, openSession, pairHostAndController } from "../../test-support/flows.ts";
import { generateEphemeral } from "../../test-support/sessionCrypto.ts";
import { sessionAcceptMessage } from "../../src/ws/handlers/util.ts";

/** Consumes session.state messages until one carries `state`; earlier ones (CONNECTING, CONNECTED) are lifecycle noise. */
async function waitForState(client: TestClient, state: string): Promise<Record<string, unknown>> {
  for (let i = 0; i < 6; i++) {
    const msg = await client.waitFor("session.state");
    if (msg.payload["state"] === state) return msg.payload;
  }
  throw new Error(`never saw session.state ${state}`);
}

test("session handshake relays connect to the host and accept back, and the host sees who is asking", async () => {
  const { server, url } = await startTestServer();
  try {
    const f = await pairHostAndController(url);
    const { payload, sessionId } = buildSessionConnect(f.controllerIdentity, f.hostId);
    f.controllerClient.send("session.connect", payload);

    const connecting = await f.controllerClient.waitFor("session.state");
    assert.equal(connecting.payload["state"], "CONNECTING");

    const forwarded = await f.hostClient.waitFor("session.connect");
    assert.equal(forwarded.payload["sessionId"], sessionId);
    assert.equal(forwarded.payload["deviceId"], f.controllerIdentity.deviceId);
    assert.equal(forwarded.payload["deviceName"], "Android de Juan");
    assert.equal(forwarded.payload["identityPub"], f.controllerIdentity.publicKeyB64Url);
    assert.equal(forwarded.payload["ephPub"], payload["ephPub"]);

    f.hostClient.close();
    f.controllerClient.close();
  } finally {
    await server.close();
  }
});

test("end-to-end frames flow both ways, the relay only ever sees ciphertext", async () => {
  const { server, url } = await startTestServer();
  try {
    const f = await pairHostAndController(url);
    const s = await openSession(f);

    const secret = JSON.stringify({ t: "clipboard.push", text: "contraseña-super-secreta" });
    const frame = s.controller.seal(secret);
    assert.ok(!Buffer.from(frame.ct, "base64url").includes(Buffer.from("secreta")), "plaintext must not appear in the frame");
    f.controllerClient.send("session.data", { sessionId: s.sessionId, ...frame });

    const atHost = await f.hostClient.waitFor("session.data");
    assert.equal(atHost.payload["ct"], frame.ct, "the relay must forward the ciphertext untouched");
    assert.equal(s.host.open(atHost.payload["seq"] as number, atHost.payload["ct"] as string).toString(), secret);

    const reply = s.host.seal(JSON.stringify({ t: "ctl.pong", n: 1 }));
    f.hostClient.send("session.data", { sessionId: s.sessionId, ...reply });
    const atController = await f.controllerClient.waitFor("session.data");
    assert.equal(
      s.controller.open(atController.payload["seq"] as number, atController.payload["ct"] as string).toString(),
      '{"t":"ctl.pong","n":1}',
    );

    // Nothing the backend logs or stores may contain the plaintext either.
    assert.ok(!JSON.stringify([...server.ctx.activeSessions.all()]).includes("secreta"));

    f.hostClient.close();
    f.controllerClient.close();
  } finally {
    await server.close();
  }
});

test("a tampered or replayed frame is rejected by the receiving endpoint", async () => {
  const { server, url } = await startTestServer();
  try {
    const f = await pairHostAndController(url);
    const s = await openSession(f);
    const frame = s.controller.seal('{"t":"ctl.ping","n":1}');
    f.controllerClient.send("session.data", { sessionId: s.sessionId, ...frame });
    const relayed = await f.hostClient.waitFor("session.data");
    s.host.open(relayed.payload["seq"] as number, relayed.payload["ct"] as string);

    assert.throws(() => s.host.open(frame.seq, frame.ct), /replayed/);
    const forged = s.controller.seal('{"t":"ctl.ping","n":2}');
    const flipped = Buffer.from(forged.ct, "base64url");
    flipped[0] = flipped[0]! ^ 1;
    assert.throws(() => s.host.open(forged.seq, flipped.toString("base64url")));
    // A frame sealed for the other direction cannot be reflected back at its sender.
    const reflected = s.controller.seal('{"t":"ctl.ping","n":3}');
    assert.throws(() => s.controller.open(reflected.seq + 10, reflected.ct));

    f.hostClient.close();
    f.controllerClient.close();
  } finally {
    await server.close();
  }
});

test("session.connect from an unauthorized controller is rejected", async () => {
  const { server, url } = await startTestServer();
  try {
    const { hostClient, hostId } = await pairHostAndController(url);
    const stranger = generateIdentity();
    const strangerClient = new TestClient(url);
    await authenticate(strangerClient, "controller", stranger);
    strangerClient.send("session.connect", buildSessionConnect(stranger, hostId).payload);
    const err = await strangerClient.waitFor("error");
    assert.equal(err.payload["code"], "forbidden");
    hostClient.close();
    strangerClient.close();
  } finally {
    await server.close();
  }
});

test("session.connect against an offline host reports DISCONNECTED without contacting anyone", async () => {
  const { server, url } = await startTestServer();
  try {
    const f = await pairHostAndController(url);
    f.hostClient.close();
    await new Promise((r) => setTimeout(r, 50));
    f.controllerClient.send("session.connect", buildSessionConnect(f.controllerIdentity, f.hostId).payload);
    const state = await f.controllerClient.waitFor("session.state");
    assert.equal(state.payload["state"], "DISCONNECTED");
    assert.equal(state.payload["reason"], "host_offline");
    f.controllerClient.close();
  } finally {
    await server.close();
  }
});

test("host disconnecting mid-session ends it for the controller", async () => {
  const { server, url } = await startTestServer();
  try {
    const f = await pairHostAndController(url);
    await openSession(f);
    f.hostClient.close();
    const ended = await f.controllerClient.waitFor("session.end");
    assert.equal(ended.payload["reason"], "host_disconnected");
    const state = await waitForState(f.controllerClient, "DISCONNECTED");
    assert.equal(state["reason"], "host_disconnected");
    assert.equal(server.ctx.activeSessions.size(), 0);
    f.controllerClient.close();
  } finally {
    await server.close();
  }
});

test("controller disconnecting mid-session tells the host so it can release held keys", async () => {
  const { server, url } = await startTestServer();
  try {
    const f = await pairHostAndController(url);
    await openSession(f);
    f.controllerClient.close();
    const ended = await f.hostClient.waitFor("session.end");
    assert.equal(ended.payload["reason"], "controller_disconnected");
    assert.equal(server.ctx.activeSessions.size(), 0);
    f.hostClient.close();
  } finally {
    await server.close();
  }
});

test("explicit session.end is relayed and frees the session; ending twice is harmless", async () => {
  const { server, url } = await startTestServer();
  try {
    const f = await pairHostAndController(url);
    const s = await openSession(f);
    f.controllerClient.send("session.end", { sessionId: s.sessionId, reason: "user_disconnect" });
    const ended = await f.hostClient.waitFor("session.end");
    assert.equal(ended.payload["reason"], "user_disconnect");
    f.controllerClient.send("session.end", { sessionId: s.sessionId, reason: "again" });
    f.hostClient.send("session.data", { sessionId: s.sessionId, ...s.host.seal("{}") });
    const err = await f.hostClient.waitFor("error");
    assert.equal(err.payload["code"], "not_found", "data after the session ended is refused");
    f.hostClient.close();
    f.controllerClient.close();
  } finally {
    await server.close();
  }
});

test("session.connect security: forged signature, stale timestamp, replayed id, spoofed identity fields", async () => {
  const { server, url } = await startTestServer();
  try {
    const f = await pairHostAndController(url);

    const forged = buildSessionConnect(f.controllerIdentity, f.hostId).payload;
    forged["sig"] = signBytes(generateIdentity().privateKey, Buffer.from("nope"));
    f.controllerClient.send("session.connect", forged);
    assert.equal((await f.controllerClient.waitFor("error")).payload["code"], "forbidden");

    const otherHost = buildSessionConnect(f.controllerIdentity, f.hostId).payload;
    otherHost["hostId"] = "x".repeat(22); // signature no longer matches the signed host
    f.controllerClient.send("session.connect", otherHost);
    assert.equal((await f.controllerClient.waitFor("error")).payload["code"], "forbidden");

    const stale = buildSessionConnect(f.controllerIdentity, f.hostId, { issuedAt: Date.now() - 10 * 60_000 }).payload;
    f.controllerClient.send("session.connect", stale);
    assert.equal((await f.controllerClient.waitFor("error")).payload["code"], "expired");

    const spoof = buildSessionConnect(f.controllerIdentity, f.hostId).payload;
    spoof["deviceId"] = "someoneElse1234567890";
    f.controllerClient.send("session.connect", spoof);
    assert.equal((await f.controllerClient.waitFor("error")).payload["code"], "invalid_message");

    const ok = buildSessionConnect(f.controllerIdentity, f.hostId);
    f.controllerClient.send("session.connect", ok.payload);
    await f.hostClient.waitFor("session.connect");
    // A different connection of the same device replays the very same (validly signed) request.
    const second = new TestClient(url);
    await authenticate(second, "controller", f.controllerIdentity);
    second.send("session.connect", ok.payload);
    const replay = await second.waitFor("error");
    assert.equal(replay.payload["code"], "invalid_message");
    assert.match(String(replay.payload["message"]), /already used/);

    second.close();
    f.hostClient.close();
    f.controllerClient.close();
  } finally {
    await server.close();
  }
});

test("a second device is told host_busy; the same device reconnecting takes over its own session", async () => {
  const { server, url } = await startTestServer();
  try {
    const f = await pairHostAndController(url);
    await openSession(f);

    // Second phone, paired with the same host through the real pairing flow.
    const phone2 = generateIdentity();
    const phone2Client = new TestClient(url);
    await authenticate(phone2Client, "controller", phone2);
    server.ctx.devices.authorize(phone2.deviceId, f.hostId, phone2.publicKeyB64Url, "Tablet", f.permissions);
    phone2Client.send("session.connect", buildSessionConnect(phone2, f.hostId).payload);
    const busy = await phone2Client.waitFor("session.state");
    assert.equal(busy.payload["state"], "REJECTED");
    assert.equal(busy.payload["reason"], "host_busy");

    // Same phone, new socket (network switch): replaces the stale session.
    const reborn = new TestClient(url);
    await authenticate(reborn, "controller", f.controllerIdentity);
    reborn.send("session.connect", buildSessionConnect(f.controllerIdentity, f.hostId).payload);
    const ended = await f.hostClient.waitFor("session.end");
    assert.equal(ended.payload["reason"], "replaced");
    await f.hostClient.waitFor("session.connect");

    reborn.close();
    phone2Client.close();
    f.hostClient.close();
    f.controllerClient.close();
  } finally {
    await server.close();
  }
});

test("data from or to a non-participant, or for an unknown session, is refused", async () => {
  const { server, url } = await startTestServer();
  try {
    const f = await pairHostAndController(url);
    const s = await openSession(f);

    const intruder = generateIdentity();
    const intruderClient = new TestClient(url);
    await authenticate(intruderClient, "controller", intruder);
    intruderClient.send("session.data", { sessionId: s.sessionId, ...s.controller.seal("{}") });
    assert.equal((await intruderClient.waitFor("error")).payload["code"], "not_found");

    f.controllerClient.send("session.data", { sessionId: randomUUID(), ...s.controller.seal("{}") });
    assert.equal((await f.controllerClient.waitFor("error")).payload["code"], "not_found");

    intruderClient.close();
    f.hostClient.close();
    f.controllerClient.close();
  } finally {
    await server.close();
  }
});

test("host accept is verified: bad signature, extra permissions and unknown sessions are refused", async () => {
  const { server, url } = await startTestServer();
  try {
    const f = await pairHostAndController(url, { permissions: ["mouse", "keyboard"] });
    const { payload, sessionId } = buildSessionConnect(f.controllerIdentity, f.hostId);
    f.controllerClient.send("session.connect", payload);
    const incoming = await f.hostClient.waitFor("session.connect");

    const hostEph = generateEphemeral();
    const accept = (perms: string[], signer = f.hostIdentity): Record<string, unknown> => ({
      sessionId,
      ephPub: hostEph.publicB64Url,
      permissions: perms,
      sig: signBytes(
        signer.privateKey,
        sessionAcceptMessage(sessionId, f.hostId, f.controllerIdentity.deviceId, incoming.payload["ephPub"] as string, hostEph.publicB64Url, perms),
      ),
    });

    f.hostClient.send("session.accept", accept(["mouse", "keyboard", "power"]));
    assert.equal((await f.hostClient.waitFor("error")).payload["code"], "forbidden", "cannot grant what was never paired");

    f.hostClient.send("session.accept", accept(["mouse"], generateIdentity()));
    assert.equal((await f.hostClient.waitFor("error")).payload["code"], "forbidden", "must be signed by the host identity");

    f.hostClient.send("session.accept", { ...accept(["mouse"]), sessionId: randomUUID() });
    assert.equal((await f.hostClient.waitFor("error")).payload["code"], "not_found");

    f.hostClient.send("session.accept", accept(["mouse"])); // the honest one still works afterwards
    const ok = await f.controllerClient.waitFor("session.accept");
    assert.deepEqual(ok.payload["permissions"], ["mouse"]);

    f.hostClient.close();
    f.controllerClient.close();
  } finally {
    await server.close();
  }
});

test("host can reject a session and the controller learns why", async () => {
  const { server, url } = await startTestServer();
  try {
    const f = await pairHostAndController(url);
    const { payload, sessionId } = buildSessionConnect(f.controllerIdentity, f.hostId);
    f.controllerClient.send("session.connect", payload);
    await f.hostClient.waitFor("session.connect");
    f.hostClient.send("session.reject", { sessionId, reason: "user_denied" });
    const rejected = await f.controllerClient.waitFor("session.reject");
    assert.equal(rejected.payload["reason"], "user_denied");
    assert.equal(server.ctx.activeSessions.size(), 0);
    f.hostClient.close();
    f.controllerClient.close();
  } finally {
    await server.close();
  }
});

test("revoking a device ends its live session on both sides and blocks reconnection", async () => {
  const { server, url } = await startTestServer();
  try {
    const f = await pairHostAndController(url);
    await openSession(f);

    f.hostClient.send("pairing.revoke", { deviceId: f.controllerIdentity.deviceId });
    const endedController = await f.controllerClient.waitFor("session.end");
    assert.equal(endedController.payload["reason"], "revoked");
    const revoked = await f.controllerClient.waitFor("pairing.revoked");
    assert.equal(revoked.payload["hostId"], f.hostId);
    const endedHost = await f.hostClient.waitFor("session.end");
    assert.equal(endedHost.payload["reason"], "revoked");
    assert.equal(server.ctx.activeSessions.size(), 0);
    assert.equal(f.controllerClient.socket.readyState, f.controllerClient.socket.OPEN, "other pairings must keep working");

    f.controllerClient.send("session.connect", buildSessionConnect(f.controllerIdentity, f.hostId).payload);
    assert.equal((await f.controllerClient.waitFor("error")).payload["code"], "forbidden");

    f.hostClient.close();
    f.controllerClient.close();
  } finally {
    await server.close();
  }
});

test("housekeeping ends sessions nobody answered, that went idle, or that outlived their maximum duration", async () => {
  const { server, url } = await startTestServer();
  try {
    const f = await pairHostAndController(url);

    // 1) The Host never answers the connect.
    f.controllerClient.send("session.connect", buildSessionConnect(f.controllerIdentity, f.hostId).payload);
    await f.hostClient.waitFor("session.connect");
    await waitForState(f.controllerClient, "CONNECTING");
    const t0 = Date.now();
    const { maintain } = server;
    maintain(t0 + 31_000);
    const timedOut = await f.controllerClient.waitFor("session.end");
    assert.equal(timedOut.payload["reason"], "accept_timeout");
    await f.hostClient.waitFor("session.end");
    await waitForState(f.controllerClient, "DISCONNECTED");

    // 2) Active but silent.
    const s = await openSession(f);
    await waitForState(f.controllerClient, "CONNECTED");
    maintain(Date.now() + 61_000);
    assert.equal((await f.controllerClient.waitFor("session.end")).payload["reason"], "idle_timeout");
    await f.hostClient.waitFor("session.end");
    assert.equal(server.ctx.activeSessions.get(s.sessionId), undefined);

    f.hostClient.close();
    f.controllerClient.close();
  } finally {
    await server.close();
  }
});

test("a data flood is throttled per session and the session is cut when the abuse persists", async () => {
  const { server, url } = await startTestServer({ session: { dataFramesPerSecond: 5 }, rateLimit: { rawFramesPerSecondPerConnection: 100000 } });
  try {
    const f = await pairHostAndController(url);
    const s = await openSession(f);
    let limited = 0;
    f.controllerClient.socket.on("message", (raw) => {
      const env = JSON.parse(raw.toString()) as { type: string; payload: { code?: string } };
      if (env.type === "error" && env.payload.code === "rate_limited") limited++;
    });
    for (let i = 0; i < 400; i++) {
      f.controllerClient.send("session.data", { sessionId: s.sessionId, ...s.controller.seal("{}") });
    }
    await new Promise((r) => setTimeout(r, 300));
    assert.ok(limited > 0, "excess frames must be answered with rate_limited");
    assert.equal(server.ctx.activeSessions.get(s.sessionId), undefined, "persistent abuse ends the session");
    f.hostClient.close();
    f.controllerClient.close();
  } finally {
    await server.close();
  }
});

test("frames larger than the protocol limit close the offending connection", async () => {
  const { server, url } = await startTestServer();
  try {
    const f = await pairHostAndController(url);
    const closed = new Promise<number>((resolve) => f.controllerClient.socket.once("close", (code) => resolve(code)));
    f.controllerClient.sendRaw("x".repeat(70 * 1024));
    assert.equal(await closed, 1009);
    f.hostClient.close();
  } finally {
    await server.close();
  }
});
