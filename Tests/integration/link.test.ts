import { test } from "node:test";
import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { BackendProcess, eventually, hostState, pair, startHost, startPair, startPhone, stopAll } from "../lib/stack.ts";
import type { JsonLineProcess } from "../lib/jsonLineProcess.ts";

const seed = (): string => randomBytes(32).toString("base64url");

/** Pairs a phone, then returns a SECOND phone process that uses RemoteLink (the app's connection manager) with the same identity. */
async function linkedPhone(backend: BackendProcess, hostSeed: string, dataDir: string) {
  const phoneSeed = seed();
  const p = await startPair(backend, { seed: hostSeed, dataDir, phoneSeed });
  await pair(p);
  const host = await p.phone.call({ cmd: "exportHost" });
  await p.phone.stop();
  const link = startPhone("linkphone");
  await link.waitEvent((e) => e["evt"] === "ready", 30_000);
  await link.call({ cmd: "linkInit", relay: backend.url, seed: phoneSeed, host });
  return { p, link, host, phoneSeed };
}

const phase = (l: JsonLineProcess) => async (): Promise<string> => (await l.call({ cmd: "linkState" }))["phase"];
const waitPhase = (l: JsonLineProcess, want: string, ms = 20_000) =>
  eventually(async () => (await phase(l)()) === want, `link phase ${want}`, ms, 100);

test("the link reaches ONLINE on its own and survives a PC crash and restart without any user action", async () => {
  const backend = await BackendProcess.start();
  const hostSeed = seed();
  const dataDir = mkdtempSync(join(tmpdir(), "cq-link-"));
  const { p, link } = await linkedPhone(backend, hostSeed, dataDir);
  let host2;
  try {
    await link.call({ cmd: "linkStart" });
    await waitPhase(link, "ONLINE");
    await link.call({ cmd: "move", dx: 2, dy: 2 });
    await eventually(async () => ((await hostState(p))["injected"] as string[]).includes("move 2,2"), "control works over the link");

    await p.host.kill();
    await eventually(async () => ["HOST_OFFLINE", "RECONNECTING"].includes(await phase(link)()), "the link notices the PC is gone", 20_000, 100);

    host2 = startHost(backend.url, { seed: hostSeed, dataDir });
    await host2.waitEvent((e) => e["evt"] === "signaling" && e["state"] === "Ready", 30_000);
    p.host = host2;
    await waitPhase(link, "ONLINE", 30_000);
    await link.call({ cmd: "move", dx: 3, dy: 3 });
    await eventually(async () => ((await hostState(p))["injected"] as string[]).includes("move 3,3"), "control works again after the PC came back");
  } finally {
    await stopAll(link, host2, p.host, backend);
  }
});

test("the link recovers from a backend restart by itself", async () => {
  const backend = await BackendProcess.start();
  const hostSeed = seed();
  const { p, link } = await linkedPhone(backend, hostSeed, mkdtempSync(join(tmpdir(), "cq-link-")));
  try {
    await link.call({ cmd: "linkStart" });
    await waitPhase(link, "ONLINE");
    await backend.restart(500);
    await eventually(async () => (await phase(link)()) !== "ONLINE", "link notices the outage", 20_000, 100);
    await waitPhase(link, "ONLINE", 40_000);
    await link.call({ cmd: "move", dx: 5, dy: 6 });
    await eventually(async () => ((await hostState(p))["injected"] as string[]).includes("move 5,6"), "control works after the outage");
  } finally {
    await stopAll(link, p.host, backend);
  }
});

test("starting before the PC is on waits patiently and connects the moment it appears", async () => {
  const backend = await BackendProcess.start();
  const hostSeed = seed();
  const dataDir = mkdtempSync(join(tmpdir(), "cq-link-"));
  const { p, link } = await linkedPhone(backend, hostSeed, dataDir);
  let host2;
  try {
    await p.host.kill();
    await link.call({ cmd: "linkStart" });
    await waitPhase(link, "HOST_OFFLINE");
    assert.equal((await link.call({ cmd: "linkState" }))["reason"], "host_offline");
    host2 = startHost(backend.url, { seed: hostSeed, dataDir });
    await waitPhase(link, "ONLINE", 30_000);
    p.host = host2;
  } finally {
    await stopAll(link, host2, p.host, backend);
  }
});

test("a revoked phone stops trying instead of hammering the PC", async () => {
  const backend = await BackendProcess.start();
  const { p, link } = await linkedPhone(backend, seed(), mkdtempSync(join(tmpdir(), "cq-link-")));
  try {
    await link.call({ cmd: "linkStart" });
    await waitPhase(link, "ONLINE");
    const phoneId = ((await hostState(p))["devices"] as any[])[0].deviceId as string;
    await p.host.call({ cmd: "revoke", deviceId: phoneId });
    await waitPhase(link, "REVOKED", 20_000);
    const sessionsBefore = p.host.events.filter((e) => e["evt"] === "session" && e["state"] === "started").length;
    await new Promise((r) => setTimeout(r, 4000));
    assert.equal(await phase(link)(), "REVOKED", "it stays revoked");
    assert.equal(p.host.events.filter((e) => e["evt"] === "session" && e["state"] === "started").length, sessionsBefore, "and does not keep knocking");
  } finally {
    await stopAll(link, p.host, backend);
  }
});

test("a PC that refuses connections is reported once with its reason, then the user can retry", async () => {
  const backend = await BackendProcess.start();
  const { p, link } = await linkedPhone(backend, seed(), mkdtempSync(join(tmpdir(), "cq-link-")));
  try {
    await p.host.call({ cmd: "settings", acceptConnections: false });
    await link.call({ cmd: "linkStart" });
    await waitPhase(link, "FAILED");
    assert.equal((await link.call({ cmd: "linkState" }))["reason"], "host_not_accepting");

    await p.host.call({ cmd: "settings", acceptConnections: true });
    await link.call({ cmd: "linkRetry" });
    await waitPhase(link, "ONLINE");
  } finally {
    await stopAll(link, p.host, backend);
  }
});

test("stopping the link ends the session and nothing reconnects afterwards", async () => {
  const backend = await BackendProcess.start();
  const { p, link } = await linkedPhone(backend, seed(), mkdtempSync(join(tmpdir(), "cq-link-")));
  try {
    await link.call({ cmd: "linkStart" });
    await waitPhase(link, "ONLINE");
    await link.call({ cmd: "linkStop" });
    await waitPhase(link, "STOPPED");
    await eventually(async () => (await hostState(p))["session"] === null, "host session ended");
    await new Promise((r) => setTimeout(r, 2500));
    assert.equal(await phase(link)(), "STOPPED");
    assert.equal((await hostState(p))["session"], null);
  } finally {
    await stopAll(link, p.host, backend);
  }
});
