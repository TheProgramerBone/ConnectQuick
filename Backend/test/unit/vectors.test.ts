import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { buildInnerVectors, buildSessionVectors } from "../../scripts/generate-vectors.ts";
import { SessionChannel, deriveSessionKeys, ephemeralFromRaw, sharedSecret, transcriptHash } from "../../test-support/sessionCrypto.ts";
import { validateInner } from "../../test-support/innerValidator.ts";
import { verifyEd25519 } from "../../src/crypto.ts";

const dir = fileURLToPath(new URL("../../../Protocol/test-vectors/", import.meta.url));
const read = (name: string): any => JSON.parse(readFileSync(`${dir}${name}`, "utf8"));

test("committed session vectors are exactly what the reference implementation generates", () => {
  assert.deepEqual(read("session-vectors.json"), JSON.parse(JSON.stringify(buildSessionVectors())));
});

test("committed inner-message vectors are exactly what the generator produces", () => {
  assert.deepEqual(read("inner-message-vectors.json"), JSON.parse(JSON.stringify(buildInnerVectors())));
});

test("session vectors: signatures verify, both sides derive the same keys, every frame opens", () => {
  const v = read("session-vectors.json");
  const { host, controller } = v.identities;
  assert.equal(
    verifyEd25519(controller.publicKeyB64Url, Buffer.from(v.expected.connectMessage), v.expected.connectSigB64Url),
    true,
  );
  assert.equal(verifyEd25519(host.publicKeyB64Url, Buffer.from(v.expected.acceptMessage), v.expected.acceptSigB64Url), true);

  const c = ephemeralFromRaw(v.ephemeral.controller.privateB64Url, v.ephemeral.controller.publicB64Url);
  const h = ephemeralFromRaw(v.ephemeral.host.privateB64Url, v.ephemeral.host.publicB64Url);
  const transcript = transcriptHash(v.session.sessionId, host.expectedDeviceId, controller.expectedDeviceId, c.publicB64Url, h.publicB64Url, v.session.permissions);
  assert.equal(transcript.toString("base64url"), v.expected.transcriptSha256B64Url);
  const sharedC = sharedSecret(c, h.publicB64Url);
  assert.equal(sharedC.toString("base64url"), v.expected.sharedSecretB64Url);
  assert.deepEqual(sharedSecret(h, c.publicB64Url), sharedC, "ECDH is symmetric");
  const keys = deriveSessionKeys(sharedC, transcript);
  assert.equal(keys.c2h.toString("base64url"), v.expected.keyC2HB64Url);
  assert.equal(keys.h2c.toString("base64url"), v.expected.keyH2CB64Url);
  assert.notEqual(keys.c2h.toString("hex"), keys.h2c.toString("hex"), "directions use different keys");

  for (const f of v.frames) {
    const receiver = f.direction === "c2h" ? SessionChannel.forHost(v.session.sessionId, keys) : SessionChannel.forController(v.session.sessionId, keys);
    assert.equal(receiver.open(f.seq, f.ct).toString("utf8"), f.plaintext);
  }
});

test("session vectors: tampered, reflected and re-sequenced frames are rejected", () => {
  const v = read("session-vectors.json");
  const c = ephemeralFromRaw(v.ephemeral.controller.privateB64Url, v.ephemeral.controller.publicB64Url);
  const h = ephemeralFromRaw(v.ephemeral.host.privateB64Url, v.ephemeral.host.publicB64Url);
  const { host, controller } = v.identities;
  const keys = deriveSessionKeys(
    sharedSecret(c, h.publicB64Url),
    transcriptHash(v.session.sessionId, host.expectedDeviceId, controller.expectedDeviceId, c.publicB64Url, h.publicB64Url, v.session.permissions),
  );
  const fresh = (): SessionChannel => SessionChannel.forHost(v.session.sessionId, keys);
  assert.throws(() => fresh().open(v.negative.tamperedFrame.seq, v.negative.tamperedFrame.ctWithFlippedBit));
  assert.throws(() => SessionChannel.forController(v.session.sessionId, keys).open(v.negative.wrongDirection.seq, v.negative.wrongDirection.ct));
  assert.throws(() => fresh().open(v.negative.wrongSeq.presentedSeq, v.negative.wrongSeq.ct));
  // A different transcript (e.g. a MITM that altered a granted permission) yields different keys.
  const other = deriveSessionKeys(
    sharedSecret(c, h.publicB64Url),
    transcriptHash(v.session.sessionId, host.expectedDeviceId, controller.expectedDeviceId, c.publicB64Url, h.publicB64Url, [...v.session.permissions, "power"]),
  );
  assert.notEqual(other.c2h.toString("hex"), keys.c2h.toString("hex"));
});

test("sequence numbers must strictly increase and cannot exceed the 32-bit space", () => {
  const v = read("session-vectors.json");
  const key = Buffer.alloc(32, 7);
  const tx = new SessionChannel(v.session.sessionId, key, key, "c2h", "c2h");
  const a = tx.seal("{}");
  const b = tx.seal("{}");
  assert.equal(b.seq, a.seq + 1);
  const rx = new SessionChannel(v.session.sessionId, key, key, "c2h", "c2h");
  rx.open(b.seq, b.ct);
  assert.throws(() => rx.open(a.seq, a.ct), /replayed/, "an older frame after a newer one is refused");
  assert.throws(() => tx.seal("{}", 4294967296), /exhausted/);
});

test("inner-message vectors: every valid message is accepted and every invalid one rejected", () => {
  const v = read("inner-message-vectors.json");
  assert.ok(v.valid.length >= 40 && v.invalid.length >= 40);
  for (const m of v.valid) assert.equal(validateInner(m.json), null, m.name);
  for (const m of v.invalid) assert.equal(validateInner(m.json), m.reason, m.name);
});

test("every inner message type in the schema has at least one valid vector", () => {
  const v = read("inner-message-vectors.json");
  const covered = new Set(v.valid.map((m: { json: string }) => JSON.parse(m.json).t));
  const schema = JSON.parse(readFileSync(`${dir}../schemas/session-messages.schema.json`, "utf8"));
  for (const key of Object.keys(schema.$defs)) {
    assert.ok(covered.has(key.replace("_", ".")), `no valid vector for ${key}`);
  }
});
