import { test } from "node:test";
import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { BackendProcess, NetworkProxy, connect, eventually, hostState, pair, phoneState, startHost, startPair, startPhone, stopAll } from "../lib/stack.ts";

const seed = (): string => randomBytes(32).toString("base64url");

test("revoking a device on the PC cuts its live session at once, releases its input, and keeps it out afterwards", async () => {
  const backend = await BackendProcess.start();
  const p = await startPair(backend);
  try {
    await pair(p);
    await connect(p);
    await p.phone.call({ cmd: "key", code: "ControlLeft", down: true });
    await eventually(async () => ((await hostState(p))["held"] as any).keys === 1, "ctrl held");

    const ended = p.phone.waitEvent((e) => e["evt"] === "sessionEnded", 10_000, p.phone.mark());
    const revoked = p.phone.waitEvent((e) => e["evt"] === "revoked", 10_000, p.phone.mark());
    await p.host.call({ cmd: "revoke", deviceId: p.phoneId });
    assert.equal((await ended)["reason"], "revoked");
    assert.equal((await revoked)["hostId"], p.hostId);

    const state = await hostState(p);
    assert.equal((state["held"] as any).keys, 0, "the revoked phone left nothing pressed");
    assert.equal(state["session"], null);
    assert.deepEqual(state["devices"], []);

    const retry = await p.phone.call({ cmd: "connect" }, 30_000);
    assert.ok(retry["error"], `a revoked phone must not get back in: ${JSON.stringify(retry)}`);
    assert.equal((await hostState(p))["session"], null);

    // Pairing again (a fresh, explicit approval) restores access.
    await pair(p);
    await connect(p);
  } finally {
    await stopAll(p.phone, p.host, backend);
  }
});

test("a PC crash ends the session cleanly; after it restarts the same phone reconnects without re-pairing", async () => {
  const backend = await BackendProcess.start();
  const hostSeed = seed();
  const dataDir = mkdtempSync(join(tmpdir(), "cq-crash-"));
  const p = await startPair(backend, { seed: hostSeed, dataDir });
  let host2;
  try {
    await pair(p);
    await connect(p);
    const ended = p.phone.waitEvent((e) => e["evt"] === "sessionEnded", 15_000, p.phone.mark());
    await p.host.kill(); // power loss: no goodbye
    assert.equal((await ended)["reason"], "host_disconnected");

    const offline = await p.phone.call({ cmd: "connect" }, 30_000);
    assert.equal(offline["error"], "host_offline", "an offline PC is reported as such, not as a hang");

    host2 = startHost(backend.url, { seed: hostSeed, dataDir });
    await host2.waitEvent((e) => e["evt"] === "signaling" && e["state"] === "Ready", 30_000);
    p.host = host2;
    const again = await p.phone.call({ cmd: "connect" }, 30_000);
    assert.ok(!again["error"], JSON.stringify(again));
    await p.phone.call({ cmd: "move", dx: 3, dy: 4 });
    await eventually(async () => ((await hostState(p))["injected"] as string[]).includes("move 3,4"), "input flows after the PC came back");
  } finally {
    await stopAll(p.phone, host2, p.host, backend);
  }
});

test("a backend restart is survived: both ends reconnect on their own and a new session works, pairing intact", async () => {
  const backend = await BackendProcess.start();
  const p = await startPair(backend);
  try {
    await pair(p);
    await connect(p);
    const sessionEnded = p.phone.waitEvent((e) => e["evt"] === "sessionEnded", 15_000, p.phone.mark());
    const hostMark = p.host.mark();
    const phoneMark = p.phone.mark();
    await backend.restart(500);
    assert.ok(["signaling_lost", "host_disconnected"].includes((await sessionEnded)["reason"]));

    await p.host.waitEvent((e) => e["evt"] === "signaling" && e["state"] === "Ready", 30_000, hostMark);
    await p.phone.waitEvent((e) => e["evt"] === "signaling" && e["state"] === "READY", 30_000, phoneMark);
    await connect(p);
    await p.phone.call({ cmd: "move", dx: 9, dy: 9 });
    await eventually(async () => ((await hostState(p))["injected"] as string[]).includes("move 9,9"), "input flows on the new session");
  } finally {
    await stopAll(p.phone, p.host, backend);
  }
});

test("a network change on the phone drops the session, reconnects quickly, and a new session works", async () => {
  const backend = await BackendProcess.start();
  const p = await startPair(backend);
  try {
    await pair(p);
    await connect(p);
    const ended = p.phone.waitEvent((e) => e["evt"] === "sessionEnded", 10_000, p.phone.mark());
    const back = p.phone.waitEvent((e) => e["evt"] === "signaling" && e["state"] === "READY", 10_000, p.phone.mark());
    const t0 = Date.now();
    await p.phone.call({ cmd: "networkChanged" });
    assert.equal((await ended)["reason"], "signaling_lost");
    await back;
    assert.ok(Date.now() - t0 < 8000, `reconnected in ${Date.now() - t0} ms`);
    await connect(p);
    assert.equal((await phoneState(p))["sessionOpen"], true);
  } finally {
    await stopAll(p.phone, p.host, backend);
  }
});

test("a silent network partition (no FIN, no error) is detected, input is released, and the link recovers after it heals", async () => {
  const backend = await BackendProcess.start();
  const proxy = await NetworkProxy.start(backend.port);
  const fast = { CQ_HEARTBEAT_MS: "1500", CQ_DEAD_AFTER_MS: "5000" };
  const p = await startPair(proxy, { env: fast });
  try {
    await pair(p);
    await connect(p);
    await p.phone.call({ cmd: "key", code: "ShiftLeft", down: true });
    await eventually(async () => ((await hostState(p))["held"] as any).keys === 1, "shift held");

    const hostDown = p.host.waitEvent((e) => e["evt"] === "signaling" && e["state"] === "Disconnected", 20_000, p.host.mark());
    const phoneDown = p.phone.waitEvent((e) => e["evt"] === "signaling" && e["state"] === "DISCONNECTED", 20_000, p.phone.mark());
    const hostMark = p.host.mark();
    const phoneMark = p.phone.mark();
    proxy.blackhole();
    await Promise.all([hostDown, phoneDown]);
    assert.equal(((await hostState(p))["held"] as any).keys, 0, "held input released although the network never said anything");
    assert.equal((await phoneState(p))["sessionOpen"], false, "the phone must not keep showing a connection that is gone");

    proxy.heal();
    await p.host.waitEvent((e) => e["evt"] === "signaling" && e["state"] === "Ready", 30_000, hostMark);
    await p.phone.waitEvent((e) => e["evt"] === "signaling" && e["state"] === "READY", 30_000, phoneMark);
    await connect(p);
    await p.phone.call({ cmd: "move", dx: 1, dy: 2 });
    await eventually(async () => ((await hostState(p))["injected"] as string[]).includes("move 1,2"), "input flows after recovery");
  } finally {
    await stopAll(p.phone, p.host, proxy, backend);
  }
});

test("a device that was never paired cannot open a session, even with the PC's public details", async () => {
  const backend = await BackendProcess.start();
  const p = await startPair(backend);
  const stranger = startPhone("stranger");
  try {
    await pair(p);
    const host = await p.phone.call({ cmd: "exportHost" });
    await stranger.waitEvent((e) => e["evt"] === "ready", 30_000);
    await stranger.call({ cmd: "init", relay: backend.url });
    await stranger.waitEvent((e) => e["evt"] === "signaling" && e["state"] === "READY", 15_000);
    await stranger.call({ cmd: "importHost", host });
    const r = await stranger.call({ cmd: "connect" }, 30_000);
    assert.equal(r["error"], "forbidden");
    assert.equal((await hostState(p))["session"], null);
    assert.deepEqual(((await hostState(p))["injected"]), []);
  } finally {
    await stopAll(stranger, p.phone, p.host, backend);
  }
});

test("two instances with one identity do not fight: the older one stops reconnecting", async () => {
  const backend = await BackendProcess.start();
  const phoneSeed = seed();
  const a = startPhone("A");
  const b = startPhone("B");
  try {
    await a.waitEvent((e) => e["evt"] === "ready", 30_000);
    await a.call({ cmd: "init", relay: backend.url, seed: phoneSeed });
    await a.waitEvent((e) => e["evt"] === "signaling" && e["state"] === "READY", 15_000);
    const aDown = a.waitEvent((e) => e["evt"] === "signaling" && e["state"] === "DISCONNECTED", 15_000, a.mark());
    await b.waitEvent((e) => e["evt"] === "ready", 30_000);
    await b.call({ cmd: "init", relay: backend.url, seed: phoneSeed });
    await b.waitEvent((e) => e["evt"] === "signaling" && e["state"] === "READY", 15_000);
    await aDown;
    await new Promise((r) => setTimeout(r, 5000)); // long enough for several reconnect attempts if it were fighting
    assert.equal((await a.call({ cmd: "state" }))["signaling"], "DISCONNECTED", "the replaced instance stays down");
    assert.equal((await b.call({ cmd: "state" }))["signaling"], "READY", "the newer instance is undisturbed");
    assert.equal(b.events.filter((e) => e["evt"] === "signaling" && e["state"] === "DISCONNECTED").length, 0);
  } finally {
    await stopAll(a, b, backend);
  }
});

test("the PC can stop accepting connections without revoking anyone", async () => {
  const backend = await BackendProcess.start();
  const p = await startPair(backend);
  try {
    await pair(p);
    await p.host.call({ cmd: "settings", acceptConnections: false });
    const refused = await p.phone.call({ cmd: "connect" }, 30_000);
    assert.equal(refused["error"], "host_not_accepting");
    await p.host.call({ cmd: "settings", acceptConnections: true });
    await connect(p);
  } finally {
    await stopAll(p.phone, p.host, backend);
  }
});
