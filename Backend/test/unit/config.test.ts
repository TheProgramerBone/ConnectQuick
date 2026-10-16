import { test } from "node:test";
import assert from "node:assert/strict";
import { loadConfig } from "../../src/config.ts";

test("pairing rate limits have safe defaults and can be raised for networks with many users behind one address", () => {
  const defaults = loadConfig({});
  assert.equal(defaults.rateLimit.pairingInitPerMinutePerIp, 5);
  assert.equal(defaults.rateLimit.pairingCreatePerMinutePerHost, 10);
  const raised = loadConfig({ PAIRING_INIT_PER_MINUTE_PER_IP: "60", PAIRING_CREATE_PER_MINUTE_PER_HOST: "20" });
  assert.equal(raised.rateLimit.pairingInitPerMinutePerIp, 60);
  assert.equal(raised.rateLimit.pairingCreatePerMinutePerHost, 20);
});

test("nonsensical limits are refused at startup instead of silently disabling protection", () => {
  assert.throws(() => loadConfig({ PAIRING_INIT_PER_MINUTE_PER_IP: "0" }), /PAIRING_INIT_PER_MINUTE_PER_IP/);
  assert.throws(() => loadConfig({ PAIRING_INIT_PER_MINUTE_PER_IP: "many" }), /PAIRING_INIT_PER_MINUTE_PER_IP/);
});

test("TURN URLs without a secret are refused: no unauthenticated relay is ever advertised", () => {
  assert.throws(() => loadConfig({ TURN_URLS: "turn:t.example:3478" }), /TURN_SECRET/);
});
