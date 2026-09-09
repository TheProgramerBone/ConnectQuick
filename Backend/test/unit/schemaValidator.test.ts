import { test } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { parseEnvelope, makeEnvelope } from "../../src/schemaValidator.ts";
import { ValidationError } from "../../src/errors.ts";

test("round-trips a well-formed envelope", () => {
  const env = makeEnvelope("heartbeat.ping", { seq: 1 });
  const parsed = parseEnvelope(JSON.stringify(env));
  assert.equal(parsed.type, "heartbeat.ping");
  assert.deepEqual(parsed.payload, { seq: 1 });
});

test("rejects malformed JSON", () => {
  assert.throws(() => parseEnvelope("{not json"), ValidationError);
});

test("rejects a payload that fails its schema", () => {
  const env = makeEnvelope("heartbeat.ping", { seq: "not-a-number" as unknown as number });
  assert.throws(() => parseEnvelope(JSON.stringify(env)), ValidationError);
});

test("rejects an unknown message type", () => {
  assert.throws(
    () => parseEnvelope(JSON.stringify({ v: 1, id: randomUUID(), ts: Date.now(), type: "bogus.type", payload: {} })),
    ValidationError,
  );
});

test("rejects unknown top-level fields (additionalProperties: false)", () => {
  const env = { ...makeEnvelope("heartbeat.ping", { seq: 1 }), extra: "nope" };
  assert.throws(() => parseEnvelope(JSON.stringify(env)), ValidationError);
});

test("rejects unknown payload fields for a known type", () => {
  const env = makeEnvelope("heartbeat.ping", { seq: 1, extra: "nope" } as unknown as { seq: number });
  assert.throws(() => parseEnvelope(JSON.stringify(env)), ValidationError);
});

test("rejects messages over the byte limit", () => {
  const env = makeEnvelope("pairing.revoke", { deviceId: "x".repeat(100) });
  assert.throws(() => parseEnvelope(JSON.stringify(env), 10), ValidationError);
});

test("rejects a protocol version other than the supported const", () => {
  const bad = { v: 2, id: randomUUID(), ts: Date.now(), type: "heartbeat.ping", payload: { seq: 1 } };
  assert.throws(() => parseEnvelope(JSON.stringify(bad)), ValidationError);
});

test("rejects a malformed message id (not a UUID)", () => {
  const bad = { v: 1, id: "not-a-uuid", ts: Date.now(), type: "heartbeat.ping", payload: { seq: 1 } };
  assert.throws(() => parseEnvelope(JSON.stringify(bad)), ValidationError);
});

