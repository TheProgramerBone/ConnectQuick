import { test } from "node:test";
import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import { buildIceConfig } from "../../src/ice.ts";
import { loadConfig } from "../../src/config.ts";
import { testConfig } from "../../test-support/testConfig.ts";

test("without TURN configured only STUN is advertised and clients are told TURN is unavailable", () => {
  const cfg = testConfig({ ice: { stunUrls: ["stun:stun.example.org:3478"], turnUrls: [], turnSecret: null } });
  const ice = buildIceConfig(cfg, "dev1");
  assert.equal(ice.turn, false);
  assert.equal(ice.stun, true);
  assert.equal(ice.ttlSeconds, 0);
  assert.deepEqual(ice.iceServers, [{ urls: ["stun:stun.example.org:3478"] }]);
});

test("TURN credentials are coturn REST-style: expiring username and HMAC-SHA1 credential, never the secret", () => {
  const cfg = testConfig({
    ice: { stunUrls: [], turnUrls: ["turns:turn.example.org:5349?transport=tcp"], turnSecret: "s3cret", turnTtlSeconds: 600 },
  });
  const now = 1_700_000_000_000;
  const ice = buildIceConfig(cfg, "devABC", now);
  assert.equal(ice.turn, true);
  assert.equal(ice.ttlSeconds, 600);
  const server = ice.iceServers[0]!;
  assert.equal(server.username, `${1_700_000_000 + 600}:devABC`);
  assert.equal(server.credential, createHmac("sha1", "s3cret").update(server.username!).digest("base64"));
  assert.ok(!JSON.stringify(ice).includes("s3cret"));
});

test("credentials are per device and per request time", () => {
  const cfg = testConfig({ ice: { stunUrls: [], turnUrls: ["turn:t.example.org"], turnSecret: "k", turnTtlSeconds: 60 } });
  const a = buildIceConfig(cfg, "devA", 1_000_000).iceServers[0]!;
  const b = buildIceConfig(cfg, "devB", 1_000_000).iceServers[0]!;
  const later = buildIceConfig(cfg, "devA", 2_000_000).iceServers[0]!;
  assert.notEqual(a.credential, b.credential);
  assert.notEqual(a.credential, later.credential);
});

test("loadConfig refuses TURN URLs without a secret and rejects malformed numbers", () => {
  assert.throws(() => loadConfig({ TURN_URLS: "turn:t.example.org" }), /TURN_SECRET/);
  assert.throws(() => loadConfig({ PORT: "eighty" }), /invalid PORT/);
  assert.throws(() => loadConfig({ SESSION_IDLE_TIMEOUT_MS: "5" }), /SESSION_IDLE_TIMEOUT_MS/);
  const cfg = loadConfig({ STUN_URLS: "", TRUST_PROXY: "1", ALLOWED_ORIGINS: "https://a.example, https://b.example" });
  assert.deepEqual(cfg.ice.stunUrls, [], "an empty STUN_URLS explicitly disables the public default");
  assert.equal(cfg.trustProxy, true);
  assert.deepEqual(cfg.allowedOrigins, ["https://a.example", "https://b.example"]);
  assert.deepEqual(loadConfig({}).ice.stunUrls, ["stun:stun.l.google.com:19302"]);
});
