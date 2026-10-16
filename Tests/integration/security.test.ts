import { test } from "node:test";
import assert from "node:assert/strict";
import { randomBytes, randomUUID } from "node:crypto";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { FakeBackend, type FakeClient, type Json } from "../lib/fakeBackend.ts";
import { eventually, hostState, startHost, startPhone, stopAll } from "../lib/stack.ts";
import { generateIdentity, signBytes, type TestIdentity } from "../../Backend/test-support/testClient.ts";
import { deriveDeviceId } from "../../Backend/src/crypto.ts";
import { sessionAcceptMessage, sessionConnectMessage } from "../../Backend/src/ws/handlers/util.ts";
import {
  SessionChannel,
  deriveSessionKeys,
  generateEphemeral,
  sharedSecret,
  transcriptHash,
  type Ephemeral,
} from "../../Backend/test-support/sessionCrypto.ts";

const PERMS = ["remote_desktop", "mouse", "keyboard", "clipboard", "files", "media"];

// ---------------------------------------------------------------------------------------------
// Part 1 — a malicious relay (or impostor PC) against the Android controller
// ---------------------------------------------------------------------------------------------

async function phoneAgainstFakeRelay(
  respond: (fake: FakeBackend, phone: FakeClient, connect: Json, hostIdentity: TestIdentity) => void,
  pairedPermissions = ["mouse"],
  timeoutMs = 20_000,
): Promise<Json> {
  const fake = await FakeBackend.start();
  const hostIdentity = generateIdentity();
  const phone = startPhone("phone");
  try {
    await phone.waitEvent((e) => e["evt"] === "ready", 30_000);
    await phone.call({ cmd: "init", relay: fake.url });
    await phone.waitEvent((e) => e["evt"] === "signaling" && e["state"] === "READY", 15_000);
    await phone.call({
      cmd: "importHost",
      host: { hostId: hostIdentity.deviceId, hostName: "Real PC", hostPub: hostIdentity.publicKeyB64Url, relay: fake.url, permissions: pairedPermissions, pairedAtMs: Date.now() },
    });
    fake.onMessage = (client, type, payload) => {
      if (type === "session.connect") respond(fake, client, payload, hostIdentity);
    };
    return await phone.call({ cmd: "connect", timeoutMs }, timeoutMs + 15_000);
  } finally {
    await stopAll(phone, fake);
  }
}

function signedAccept(host: TestIdentity, connect: Json, phoneDeviceId: string, hostEph: Ephemeral, permissions: string[], signer: TestIdentity = host): Json {
  return {
    sessionId: connect["sessionId"],
    ephPub: hostEph.publicB64Url,
    permissions,
    sig: signBytes(signer.privateKey, sessionAcceptMessage(connect["sessionId"], host.deviceId, phoneDeviceId, connect["ephPub"], hostEph.publicB64Url, permissions)),
  };
}

test("controller refuses a session accepted with a signature from anybody but the PC it paired with", async () => {
  const attacker = generateIdentity();
  const r = await phoneAgainstFakeRelay((fake, phoneClient, connect, host) => {
    const eph = generateEphemeral();
    fake.send(phoneClient, "session.accept", signedAccept(host, connect, phoneClient.deviceId!, eph, ["mouse"], attacker));
  });
  assert.equal(r["error"], "host_signature_invalid");
});

test("controller refuses an accept whose ephemeral key was swapped after the PC signed it (man in the middle)", async () => {
  const r = await phoneAgainstFakeRelay((fake, phoneClient, connect, host) => {
    const honest = generateEphemeral();
    const accept = signedAccept(host, connect, phoneClient.deviceId!, honest, ["mouse"]);
    accept["ephPub"] = generateEphemeral().publicB64Url; // the relay substitutes its own key, keeping the PC's signature
    fake.send(phoneClient, "session.accept", accept);
  });
  assert.equal(r["error"], "host_signature_invalid");
});

test("controller refuses an accept that grants more than the user approved at pairing, even when correctly signed", async () => {
  const r = await phoneAgainstFakeRelay((fake, phoneClient, connect, host) => {
    const eph = generateEphemeral();
    fake.send(phoneClient, "session.accept", signedAccept(host, connect, phoneClient.deviceId!, eph, ["mouse", "power"]));
  }, ["mouse"]);
  assert.equal(r["error"], "host_granted_unpaired_permissions");
});

test("controller does not trust a session until the PC's first frame decrypts (key confirmation)", async () => {
  const r = await phoneAgainstFakeRelay((fake, phoneClient, connect, host) => {
    const eph = generateEphemeral();
    fake.send(phoneClient, "session.accept", signedAccept(host, connect, phoneClient.deviceId!, eph, ["mouse"]));
    // The "PC" cannot actually derive the keys: it sends a first frame encrypted with unrelated keys.
    const wrong = SessionChannel.forHost(connect["sessionId"], { c2h: randomBytes(32), h2c: randomBytes(32) });
    const frame = wrong.seal(JSON.stringify({ t: "ctl.caps", hostVersion: "x", permissions: ["mouse"], features: [] }));
    fake.send(phoneClient, "session.data", { sessionId: connect["sessionId"], seq: frame.seq, ct: frame.ct });
  }, ["mouse"], 20_000);
  assert.equal(r["error"], "key_confirmation_failed");
});

test("controller times out cleanly when the relay never answers, and reports why", async () => {
  const r = await phoneAgainstFakeRelay(() => undefined, ["mouse"], 2_000);
  assert.equal(r["error"], "connect_timeout");
});

test("controller reports a rejection from the PC with its reason", async () => {
  const r = await phoneAgainstFakeRelay((fake, phoneClient, connect) => {
    fake.send(phoneClient, "session.reject", { sessionId: connect["sessionId"], reason: "user_denied" });
  });
  assert.equal(r["error"], "user_denied");
});

// ---------------------------------------------------------------------------------------------
// Part 2 — forged and replayed session requests against the Windows host
// ---------------------------------------------------------------------------------------------

interface HostRig {
  fake: FakeBackend;
  host: ReturnType<typeof startHost>;
  hostClient: FakeClient;
  hostId: string;
  hostPub: string;
  phone: TestIdentity;
  dataDir: string;
}

async function hostRig(opts: { pairedPhone?: boolean } = {}): Promise<HostRig> {
  const fake = await FakeBackend.start();
  const phone = generateIdentity();
  const dataDir = mkdtempSync(join(tmpdir(), "cq-rig-"));
  mkdirSync(dataDir, { recursive: true });
  if (opts.pairedPhone !== false) {
    // The user approved this phone on THIS PC earlier (the host's own record is what it trusts).
    writeFileSync(join(dataDir, "devices.json"), JSON.stringify([{ DeviceId: phone.deviceId, DeviceName: "Test Phone", Permissions: PERMS, AuthorizedAtMs: Date.now() }]));
  }
  const host = startHost(fake.url, { dataDir });
  const ready = await host.waitEvent((e) => e["evt"] === "ready", 30_000);
  const hostClient = await fake.waitForClient("host");
  await host.waitEvent((e) => e["evt"] === "signaling" && e["state"] === "Ready", 15_000);
  return { fake, host, hostClient, hostId: ready["hostId"], hostPub: ready["hostPub"], phone, dataDir };
}

function connectPayload(rig: HostRig, over: Partial<{ hostId: string; sessionId: string; issuedAt: number; identity: TestIdentity; deviceIdClaim: string; signer: TestIdentity; ephPub: string }> = {}): { payload: Json; eph: Ephemeral } {
  const identity = over.identity ?? rig.phone;
  const eph = generateEphemeral();
  const sessionId = over.sessionId ?? randomUUID();
  const issuedAt = over.issuedAt ?? Date.now();
  const hostId = over.hostId ?? rig.hostId;
  const ephPub = over.ephPub ?? eph.publicB64Url;
  const sig = signBytes((over.signer ?? identity).privateKey, sessionConnectMessage(sessionId, hostId, identity.deviceId, ephPub, issuedAt));
  return {
    eph,
    payload: { hostId, sessionId, ephPub, issuedAt, sig, deviceId: over.deviceIdClaim ?? identity.deviceId, deviceName: "Test Phone", identityPub: identity.publicKeyB64Url },
  };
}

/** Sends a session.connect straight to the host and returns the reason of the session.reject it answers with. */
async function rejectionReason(rig: HostRig, payload: Json): Promise<string> {
  const from = rig.fake.received.length;
  rig.fake.send(rig.hostClient, "session.connect", payload);
  const m = await rig.fake.waitForMessage((x) => x.role === "host" && x.type === "session.reject" && x.payload["sessionId"] === payload["sessionId"], 10_000, from);
  return m.payload["reason"];
}

async function expectReject(rig: HostRig, payload: Json, reason: string): Promise<void> {
  assert.equal(await rejectionReason(rig, payload), reason);
  assert.equal((await hostState(rig))["session"], null, "no session may exist after a rejection");
}

test("the host independently rejects forged, stale, replayed, unpaired and misdirected session requests", async () => {
  const rig = await hostRig();
  try {
    await expectReject(rig, connectPayload(rig, { signer: generateIdentity() }).payload, "bad_signature");
    await expectReject(rig, connectPayload(rig, { issuedAt: Date.now() - 10 * 60_000 }).payload, "stale_request");
    await expectReject(rig, connectPayload(rig, { hostId: "someOtherHost123456789012" }).payload, "wrong_host");
    await expectReject(rig, connectPayload(rig, { deviceIdClaim: "claimedSomeoneElse12345" }).payload, "bad_identity");
    await expectReject(rig, connectPayload(rig, { identity: generateIdentity() }).payload, "not_paired");

    // A request that is valid is accepted once; the very same one replayed is refused, and the live session is undisturbed.
    const ok = connectPayload(rig);
    const from = rig.fake.received.length;
    rig.fake.send(rig.hostClient, "session.connect", ok.payload);
    await rig.fake.waitForMessage((x) => x.role === "host" && x.type === "session.accept", 10_000, from);
    assert.equal(await rejectionReason(rig, { ...ok.payload }), "replayed_session");
    assert.notEqual((await hostState(rig))["session"], null, "the legitimate session survives a replayed request");
  } finally {
    await stopAll(rig.host, rig.fake);
  }
});

test("the host trusts its own record of approved phones, not the relay's claim that a phone is authorized", async () => {
  const rig = await hostRig({ pairedPhone: false });
  try {
    await expectReject(rig, connectPayload(rig).payload, "not_paired");
  } finally {
    await stopAll(rig.host, rig.fake);
  }
});

test("a reference (TypeScript) controller completes the handshake with the .NET host: keys agree and input is injected; tampered and replayed frames are dropped", async () => {
  const rig = await hostRig();
  try {
    const { payload, eph } = connectPayload(rig);
    const from = rig.fake.received.length;
    rig.fake.send(rig.hostClient, "session.connect", payload);
    const accept = await rig.fake.waitForMessage((x) => x.role === "host" && x.type === "session.accept", 10_000, from);

    // The host's signature over the transcript verifies against the key it advertised.
    const { verifyEd25519 } = await import("../../Backend/src/crypto.ts");
    const signed = sessionAcceptMessage(payload["sessionId"], rig.hostId, rig.phone.deviceId, payload["ephPub"], accept.payload["ephPub"], accept.payload["permissions"]);
    assert.equal(verifyEd25519(rig.hostPub, signed, accept.payload["sig"]), true);

    const keys = deriveSessionKeys(
      sharedSecret(eph, accept.payload["ephPub"]),
      transcriptHash(payload["sessionId"], rig.hostId, rig.phone.deviceId, payload["ephPub"], accept.payload["ephPub"], accept.payload["permissions"]),
    );
    const channel = SessionChannel.forController(payload["sessionId"], keys);

    // The first frame the host sends is ctl.caps, and our independently derived keys decrypt it.
    const first = await rig.fake.waitForMessage((x) => x.role === "host" && x.type === "session.data", 10_000, from);
    const caps = JSON.parse(channel.open(first.payload["seq"], first.payload["ct"]).toString());
    assert.equal(caps.t, "ctl.caps");
    assert.deepEqual(caps.permissions, PERMS);

    const send = (obj: object): void => {
      const f = channel.seal(JSON.stringify(obj));
      rig.fake.send(rig.hostClient, "session.data", { sessionId: payload["sessionId"], seq: f.seq, ct: f.ct });
    };
    send({ t: "input.mouse_move", dx: 11, dy: 12 });
    await eventually(async () => ((await hostState(rig))["injected"] as string[]).includes("move 11,12"), "reference controller input injected");

    // A frame whose ciphertext was altered by the relay, and a replay of an old one, change nothing.
    const good = channel.seal(JSON.stringify({ t: "input.mouse_move", dx: 99, dy: 99 }));
    const flipped = Buffer.from(good.ct, "base64url");
    flipped[0] = flipped[0]! ^ 1;
    rig.fake.send(rig.hostClient, "session.data", { sessionId: payload["sessionId"], seq: good.seq, ct: flipped.toString("base64url") });
    const replayOf = channel.seal(JSON.stringify({ t: "input.mouse_move", dx: 1, dy: 1 }));
    rig.fake.send(rig.hostClient, "session.data", { sessionId: payload["sessionId"], seq: replayOf.seq, ct: replayOf.ct });
    rig.fake.send(rig.hostClient, "session.data", { sessionId: payload["sessionId"], seq: replayOf.seq, ct: replayOf.ct });
    await new Promise((r) => setTimeout(r, 500));
    const injected = (await hostState(rig))["injected"] as string[];
    assert.ok(!injected.includes("move 99,99"), "altered ciphertext must not act");
    assert.equal(injected.filter((e) => e === "move 1,1").length, 1, "a replayed frame must not act twice");
  } finally {
    await stopAll(rig.host, rig.fake);
  }
});
