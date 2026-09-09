import { test } from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync, sign } from "node:crypto";
import { deriveDeviceId, verifyEd25519, computeSas, sha256, randomBytesB64Url } from "../../src/crypto.ts";

function ed25519PubB64Url(): string {
  const { publicKey } = generateKeyPairSync("ed25519");
  return (publicKey.export({ format: "jwk" }) as { x: string }).x;
}

test("deriveDeviceId is deterministic for the same public key", () => {
  const pub = ed25519PubB64Url();
  assert.equal(deriveDeviceId(pub), deriveDeviceId(pub));
});

test("deriveDeviceId differs for different public keys", () => {
  assert.notEqual(deriveDeviceId(ed25519PubB64Url()), deriveDeviceId(ed25519PubB64Url()));
});

test("deriveDeviceId output is 22 base64url characters", () => {
  const id = deriveDeviceId(ed25519PubB64Url());
  assert.equal(id.length, 22);
  assert.match(id, /^[A-Za-z0-9_-]{22}$/);
});

test("verifyEd25519 accepts a genuine signature and rejects tampering", () => {
  const { publicKey, privateKey } = generateKeyPairSync("ed25519");
  const pub = (publicKey.export({ format: "jwk" }) as { x: string }).x;
  const message = Buffer.from("connectquick test message");
  const sig = sign(null, message, privateKey).toString("base64url");

  assert.equal(verifyEd25519(pub, message, sig), true);
  assert.equal(verifyEd25519(pub, Buffer.from("different message"), sig), false);
  assert.equal(verifyEd25519(pub, message, sig.slice(0, -2) + "AA"), false);
});

test("verifyEd25519 fails closed on malformed input instead of throwing", () => {
  assert.equal(verifyEd25519("not-valid-base64url-key", Buffer.from("x"), "also-not-valid"), false);
});

test("computeSas is deterministic and 6 digits", () => {
  const a = computeSas("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "cccccccccccccccc");
  const b = computeSas("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "cccccccccccccccc");
  assert.equal(a, b);
  assert.match(a, /^[0-9]{6}$/);
});

test("computeSas changes if any input changes (binding property)", () => {
  const base = computeSas("hostpub-aaaaaaaaaaaaaaaaaaaaaaaa", "ephpub-bbbbbbbbbbbbbbbbbbbbbbbb", "nonce-cccccc");
  const diffHost = computeSas("hostpub-zzzzzzzzzzzzzzzzzzzzzzzz", "ephpub-bbbbbbbbbbbbbbbbbbbbbbbb", "nonce-cccccc");
  const diffEph = computeSas("hostpub-aaaaaaaaaaaaaaaaaaaaaaaa", "ephpub-zzzzzzzzzzzzzzzzzzzzzzzz", "nonce-cccccc");
  const diffNonce = computeSas("hostpub-aaaaaaaaaaaaaaaaaaaaaaaa", "ephpub-bbbbbbbbbbbbbbbbbbbbbbbb", "nonce-zzzzzz");
  assert.notEqual(base, diffHost);
  assert.notEqual(base, diffEph);
  assert.notEqual(base, diffNonce);
});

test("sha256 matches a known vector", () => {
  assert.equal(sha256("").toString("hex"), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
  assert.equal(
    sha256("abc").toString("hex"),
    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
  );
});

test("randomBytesB64Url produces the right decoded length and no padding", () => {
  const b64 = randomBytesB64Url(32);
  assert.doesNotMatch(b64, /[=+/]/);
  assert.equal(Buffer.from(b64, "base64url").length, 32);
});
