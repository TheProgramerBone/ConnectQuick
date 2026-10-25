import { test } from "node:test";
import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { BackendProcess, eventually, hostState, pair, startPair, startPhone, stopAll } from "../lib/stack.ts";

const seed = (): string => randomBytes(32).toString("base64url");

// The PC serves two relays (the "cloud" A where pairing happens and its own "LAN" relay B). The phone is
// paired through A only; when A is gone it must reach the PC through B without a new QR.
test("a phone paired through one relay keeps working through the PC's other relay when the first is gone", async () => {
  const cloud = await BackendProcess.start();
  const lan = await BackendProcess.start();
  const hostSeed = seed();
  const phoneSeed = seed();
  const dataDir = mkdtempSync(join(tmpdir(), "cq-relays-"));
  const p = await startPair(cloud, { seed: hostSeed, dataDir, phoneSeed, env: { CQ_EXTRA_RELAYS: lan.url } });
  let link;
  try {
    await p.host.waitEvent((e) => e["evt"] === "signaling" && e["state"] === "Ready", 15_000);
    await pair(p);
    const host = await p.phone.call({ cmd: "exportHost" });
    assert.deepEqual(host["relays"], [lan.url, cloud.url], "the QR lists the LAN relay first, then the main one");

    // The PC copied the pairing to the LAN relay on its own.
    await eventually(async () => (await fetch(`${lan.url.replace("ws", "http")}/healthz`)).ok, "LAN relay up");
    await p.phone.stop();
    await cloud.stop(); // the "cloud" is gone

    link = startPhone("linkphone");
    await link.waitEvent((e) => e["evt"] === "ready", 30_000);
    await link.call({ cmd: "linkInit", relay: cloud.url, seed: phoneSeed, host });
    await link.call({ cmd: "linkStart" });
    await eventually(async () => (await link.call({ cmd: "linkState" }))["phase"] === "ONLINE", "phone ONLINE through the other relay", 40_000, 200);

    await link.call({ cmd: "move", dx: 5, dy: 5 });
    await eventually(async () => ((await hostState(p))["injected"] as string[]).includes("move 5,5"), "control works through the second relay");
  } finally {
    await stopAll(link, p.phone, p.host, lan, cloud);
  }
});
