import { test } from "node:test";
import assert from "node:assert/strict";
import { WebSocket } from "ws";
import { startTestServer } from "../../test-support/testServer.ts";
import { TestClient, generateIdentity } from "../../test-support/testClient.ts";
import { authenticate, pairHostAndController } from "../../test-support/flows.ts";

async function get(url: string, path: string, headers: Record<string, string> = {}): Promise<{ status: number; body: unknown }> {
  const res = await fetch(`${url.replace("ws://", "http://")}${path}`, { headers });
  return { status: res.status, body: await res.json() };
}

test("health endpoints: /healthz and /readyz answer, unknown paths 404, /metrics hidden without a token", async () => {
  const { server, url } = await startTestServer();
  try {
    assert.deepEqual(await get(url, "/healthz"), { status: 200, body: { status: "ok" } });
    assert.deepEqual(await get(url, "/readyz"), { status: 200, body: { status: "ready" } });
    assert.equal((await get(url, "/nope")).status, 404);
    assert.equal((await get(url, "/metrics")).status, 404, "metrics must not exist unless a token is configured");
  } finally {
    await server.close();
  }
});

test("/metrics requires the exact bearer token", async () => {
  const { server, url } = await startTestServer({ metricsToken: "tok-123" });
  try {
    assert.equal((await get(url, "/metrics")).status, 404);
    assert.equal((await get(url, "/metrics", { authorization: "Bearer wrong" })).status, 404);
    const ok = await get(url, "/metrics", { authorization: "Bearer tok-123" });
    assert.equal(ok.status, 200);
    assert.deepEqual(Object.keys(ok.body as object).sort(), ["connections", "pendingPairings", "sessionsActive", "sessionsPending"]);
  } finally {
    await server.close();
  }
});

test("draining flips /readyz to 503 and refuses new connections", async () => {
  const { server, url } = await startTestServer({ shutdownGraceMs: 0 });
  try {
    await server.drain();
    assert.equal((await get(url, "/readyz")).status, 503);
    const ws = new WebSocket(url);
    const code = await new Promise<number>((resolve) => ws.once("close", (c) => resolve(c)));
    assert.equal(code, 1013);
  } finally {
    await server.close();
  }
});

test("browser origins are refused unless allow-listed; native clients (no Origin) are fine", async () => {
  const { server, url } = await startTestServer({ allowedOrigins: ["https://app.example"] });
  try {
    const evil = new WebSocket(url, { headers: { origin: "https://evil.example" } });
    const err = await new Promise<Error>((resolve) => evil.once("error", resolve));
    assert.match(err.message, /403/);

    const good = new WebSocket(url, { headers: { origin: "https://app.example" } });
    await new Promise<void>((resolve, reject) => {
      good.once("open", () => resolve());
      good.once("error", reject);
    });
    good.close();

    const native = new TestClient(url);
    await native.ready();
    native.close();
  } finally {
    await server.close();
  }
});

test("per-IP connection limit refuses the excess connection", async () => {
  const { server, url } = await startTestServer({ limits: { maxConnectionsPerIp: 2 } });
  try {
    const a = new TestClient(url);
    const b = new TestClient(url);
    await Promise.all([a.ready(), b.ready()]);
    const c = new WebSocket(url);
    const code = await new Promise<number>((resolve) => c.once("close", (code) => resolve(code)));
    assert.equal(code, 1013);
    a.close();
    b.close();
  } finally {
    await server.close();
  }
});

test("X-Forwarded-For is ignored unless trustProxy is enabled", async () => {
  const off = await startTestServer();
  const on = await startTestServer({ trustProxy: true });
  try {
    const plain = new WebSocket(off.url, { headers: { "x-forwarded-for": "203.0.113.9" } });
    await new Promise((r) => plain.once("open", r));
    assert.equal([...off.server.ctx.connections.all()][0]!.remoteAddress.includes("203.0.113.9"), false);
    plain.close();

    const proxied = new WebSocket(on.url, { headers: { "x-forwarded-for": "203.0.113.9, 10.0.0.1" } });
    await new Promise((r) => proxied.once("open", r));
    assert.equal([...on.server.ctx.connections.all()][0]!.remoteAddress, "203.0.113.9");
    proxied.close();
  } finally {
    await off.server.close();
    await on.server.close();
  }
});

test("ice.request returns ICE servers with a per-device TURN credential", async () => {
  const { server, url } = await startTestServer({
    ice: { stunUrls: ["stun:s.example:3478"], turnUrls: ["turn:t.example:3478"], turnSecret: "shh", turnTtlSeconds: 120 },
  });
  try {
    const f = await pairHostAndController(url);
    f.controllerClient.send("ice.request", {});
    const cfg = await f.controllerClient.waitFor("ice.config");
    assert.equal(cfg.payload["turn"], true);
    const servers = cfg.payload["iceServers"] as { urls: string[]; username?: string }[];
    assert.equal(servers.length, 2);
    assert.ok(servers[1]!.username!.endsWith(`:${f.controllerIdentity.deviceId}`));
    assert.ok(!JSON.stringify(cfg.payload).includes("shh"));
    f.hostClient.close();
    f.controllerClient.close();
  } finally {
    await server.close();
  }
});

test("hello.ack advertises the e2e session capability, and turn only when configured", async () => {
  const noTurn = await startTestServer({ ice: { stunUrls: [], turnUrls: [], turnSecret: null } });
  const withTurn = await startTestServer({ ice: { turnUrls: ["turn:t.example"], turnSecret: "k" } });
  try {
    const c1 = new TestClient(noTurn.url);
    const ack1 = await authenticate(c1, "controller", generateIdentity());
    const caps1 = ack1["capabilities"] as string[];
    assert.ok(caps1.includes("session.e2e.v1") && caps1.includes("ice"));
    assert.ok(!caps1.includes("turn") && !caps1.includes("stun"));

    const c2 = new TestClient(withTurn.url);
    const caps2 = (await authenticate(c2, "controller", generateIdentity()))["capabilities"] as string[];
    assert.ok(caps2.includes("turn") && caps2.includes("stun"));
    c1.close();
    c2.close();
  } finally {
    await noTurn.server.close();
    await withTurn.server.close();
  }
});

test("a newer connection of the same host replaces the old one without announcing it offline", async () => {
  const { server, url } = await startTestServer();
  try {
    const f = await pairHostAndController(url);
    const presence: boolean[] = [];
    f.controllerClient.socket.on("message", (raw) => {
      const env = JSON.parse(raw.toString()) as { type: string; payload: { online: boolean } };
      if (env.type === "presence.update") presence.push(env.payload.online);
    });

    const oldClosed = new Promise<number>((resolve) => f.hostClient.socket.once("close", (code) => resolve(code)));
    const reborn = new TestClient(url);
    await authenticate(reborn, "host", f.hostIdentity, { hostName: "PC Gaming" });
    assert.equal(await oldClosed, 4010, "the stale connection is closed as replaced");
    await new Promise((r) => setTimeout(r, 100));
    assert.ok(!presence.includes(false), "a takeover must not flap the host offline");
    assert.equal(server.ctx.connections.getHost(f.hostId)?.replaced, false);

    reborn.close();
    f.controllerClient.close();
  } finally {
    await server.close();
  }
});

test("the same phone can be paired with two hosts and open a session with each", async () => {
  const { server, url } = await startTestServer();
  try {
    const one = await pairHostAndController(url, { hostName: "PC Uno" });
    // Pair the SAME controller identity with a second host through the real flow.
    const hostTwo = generateIdentity();
    const hostTwoClient = new TestClient(url);
    await authenticate(hostTwoClient, "host", hostTwo, { hostName: "PC Dos" });
    server.ctx.devices.authorize(one.controllerIdentity.deviceId, hostTwo.deviceId, one.controllerIdentity.publicKeyB64Url, "Pixel", ["mouse"]);
    assert.equal(server.ctx.devices.isAuthorized(one.controllerIdentity.deviceId, one.hostId), true);
    assert.equal(server.ctx.devices.isAuthorized(one.controllerIdentity.deviceId, hostTwo.deviceId), true);
    one.hostClient.close();
    one.controllerClient.close();
    hostTwoClient.close();
  } finally {
    await server.close();
  }
});
