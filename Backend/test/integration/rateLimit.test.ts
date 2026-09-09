import { test } from "node:test";
import assert from "node:assert/strict";
import { startTestServer } from "../../test-support/testServer.ts";
import { TestClient, generateIdentity, generateEphemeral } from "../../test-support/testClient.ts";
import { authenticate } from "../../test-support/flows.ts";

test("pairing.create is rate-limited per host", async () => {
  const { server, url } = await startTestServer({ rateLimit: { pairingCreatePerMinutePerHost: 2 } });
  try {
    const identity = generateIdentity();
    const client = new TestClient(url);
    await authenticate(client, "host", identity);

    for (let i = 0; i < 2; i++) {
      client.send("pairing.create", { hostName: "PC", hostEphPub: generateEphemeral().publicKeyB64Url });
      await client.waitFor("pairing.created");
    }
    client.send("pairing.create", { hostName: "PC", hostEphPub: generateEphemeral().publicKeyB64Url });
    const err = await client.waitFor("error");
    assert.equal(err.payload["code"], "rate_limited");

    client.close();
  } finally {
    await server.close();
  }
});

test("generic per-connection message flood is rate-limited", async () => {
  const { server, url } = await startTestServer({ rateLimit: { messagesPerSecondPerConnection: 3 } });
  try {
    const identity = generateIdentity();
    const client = new TestClient(url);
    await authenticate(client, "host", identity);

    for (let i = 0; i < 20; i++) {
      client.send("heartbeat.ping", { seq: i });
    }
    // Drain replies in order until we see a rate_limited error.
    let sawRateLimit = false;
    for (let i = 0; i < 20; i++) {
      const env = await client.next(500).catch(() => null);
      if (!env) break;
      if (env.type === "error" && env.payload["code"] === "rate_limited") {
        sawRateLimit = true;
        break;
      }
    }
    assert.equal(sawRateLimit, true);
    client.close();
  } finally {
    await server.close();
  }
});
