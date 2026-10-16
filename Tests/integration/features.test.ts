import { test } from "node:test";
import assert from "node:assert/strict";
import { createHash, randomBytes } from "node:crypto";
import { mkdtempSync, readdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { BackendProcess, connect, eventually, hostState, pair, phoneState, startPair, stopAll } from "../lib/stack.ts";

const sha = (b: Buffer) => createHash("sha256").update(b).digest("hex");

async function withStack<T>(fn: (p: Awaited<ReturnType<typeof startPair>>, backend: BackendProcess, dataDir: string) => Promise<T>, opts: { permissions?: string[] } = {}): Promise<T> {
  const backend = await BackendProcess.start();
  const dataDir = mkdtempSync(join(tmpdir(), "cq-hostdata-"));
  const p = await startPair(backend, { dataDir });
  try {
    await pair(p, opts.permissions);
    await connect(p);
    return await fn(p, backend, dataDir);
  } finally {
    await stopAll(p.phone, p.host, backend);
  }
}

test("clipboard: push to the PC, pull from it, dedupe, and auto-push without echo", async () => {
  await withStack(async (p) => {
    assert.equal((await p.phone.call({ cmd: "clipboardPush", text: "hola ñandú 🙂" }))["outcome"], "sent");
    assert.equal((await hostState(p))["clipboard"], "hola ñandú 🙂");
    assert.equal((await p.phone.call({ cmd: "clipboardPush", text: "hola ñandú 🙂" }))["outcome"], "duplicate");
    assert.equal((await hostState(p))["clipboardWrites"], 1, "the clipboard is written once");

    await p.host.call({ cmd: "copy", text: "copiado en el PC" });
    const pulled = await p.phone.call({ cmd: "clipboardPull" });
    assert.deepEqual([pulled["outcome"], pulled["text"]], ["text", "copiado en el PC"]);

    const tooBig = await p.phone.call({ cmd: "clipboardPush", text: "€".repeat(6000) });
    assert.deepEqual([tooBig["outcome"], tooBig["reason"]], ["failed", "too_large"]);

    // Auto push is OFF by default: a local copy on the PC stays private.
    await p.host.call({ cmd: "copy", text: "privado del usuario" });
    await new Promise((r) => setTimeout(r, 1200));
    assert.deepEqual((await phoneState(p))["remoteClipboard"], []);

    // Turn it on and reconnect (the watcher starts with the session).
    await p.host.call({ cmd: "settings", clipboardAutoPush: true });
    await connect(p);
    await p.host.call({ cmd: "copy", text: "enviado solo" });
    await eventually(async () => ((await phoneState(p))["remoteClipboard"] as string[]).includes("enviado solo"), "auto-pushed text on the phone", 8000);

    await p.phone.call({ cmd: "clipboardPush", text: "vuelta del movil" });
    await new Promise((r) => setTimeout(r, 1500));
    assert.deepEqual(((await phoneState(p))["remoteClipboard"] as string[]).filter((t) => t === "vuelta del movil"), [], "no echo of what the phone just sent");
  });
});

test("media keys reach the PC and the phone is told they are unconfirmed", async () => {
  await withStack(async (p) => {
    for (const action of ["play_pause", "next", "prev", "stop", "vol_up", "vol_down", "mute"]) {
      const r = await p.phone.call({ cmd: "media", action });
      assert.equal(r["delivered"], true, action);
      assert.equal(r["confirmed"], false, "Windows cannot confirm that a player acted");
    }
    await p.phone.call({ cmd: "media", action: "vol_up", steps: 3 });
    const pressed = (await hostState(p))["media"] as string[];
    assert.deepEqual(pressed.slice(0, 7), ["PlayPause", "Next", "Previous", "Stop", "VolumeUp", "VolumeDown", "Mute"]);
    assert.equal(pressed.filter((k) => k === "VolumeUp").length, 4);
  });
});

test("power: lock is one step; shutdown needs the confirmation round trip; policy and permission are enforced", async () => {
  await withStack(async (p) => {
    const lock = await p.phone.call({ cmd: "power", action: "lock" });
    assert.equal(lock["ok"], true);
    assert.deepEqual((await hostState(p))["power"], ["lock"]);

    await new Promise((r) => setTimeout(r, 1100)); // per-second request limit
    const ask = await p.phone.call({ cmd: "power", action: "shutdown" });
    assert.equal(ask["needsConfirmation"], true);
    assert.deepEqual((await hostState(p))["power"], ["lock"], "asking must not shut the PC down");
    const done = await p.phone.call({ cmd: "powerConfirm" });
    assert.equal(done["ok"], true);
    assert.equal(done["delaySeconds"], 15);
    assert.deepEqual((await hostState(p))["power"], ["lock", "shutdown/15"]);

    await p.host.call({ cmd: "settings", power: "LockOnly" });
    await new Promise((r) => setTimeout(r, 1100));
    const refused = await p.phone.call({ cmd: "power", action: "restart" });
    assert.deepEqual([refused["ok"], refused["reason"]], [false, "policy_lock_only"]);

    await p.host.call({ cmd: "settings", power: "Disabled" });
    await new Promise((r) => setTimeout(r, 1100));
    const started = Date.now();
    const off = await p.phone.call({ cmd: "power", action: "lock" });
    assert.deepEqual([off["ok"], off["reason"]], [false, "disabled_by_host"]);
    assert.ok(Date.now() - started < 3000, "a refusal must come back at once, not after a timeout");
    assert.deepEqual((await hostState(p))["power"], ["lock", "shutdown/15"]);
  }, { permissions: ["remote_desktop", "mouse", "keyboard", "clipboard", "files", "media", "power"] });
});

test("a device paired without 'power' or 'keyboard' cannot use them, and is told immediately", async () => {
  await withStack(async (p) => {
    const started = Date.now();
    const r = await p.phone.call({ cmd: "power", action: "lock" });
    assert.deepEqual([r["ok"], r["reason"]], [false, "forbidden"]);
    await p.phone.call({ cmd: "key", code: "KeyA", down: true });
    await p.phone.call({ cmd: "move", dx: 5, dy: 5 });
    await eventually(async () => ((await hostState(p))["injected"] as string[]).length >= 1, "mouse allowed");
    assert.ok(Date.now() - started < 6000);
    const injected = (await hostState(p))["injected"] as string[];
    assert.deepEqual(injected, ["move 5,5"], "only the permitted mouse event reached the OS");
    await eventually(async () => ((await phoneState(p))["hostErrors"] as string[]).includes("forbidden"), "forbidden reported to the phone");
  }, { permissions: ["mouse", "clipboard", "files", "media"] });
});

test("the PC can switch a capability off at runtime without re-pairing", async () => {
  await withStack(async (p) => {
    await p.host.call({ cmd: "settings", allowMouse: false });
    await p.phone.call({ cmd: "move", dx: 1, dy: 1 });
    await eventually(async () => ((await phoneState(p))["hostErrors"] as string[]).includes("disabled_by_host"), "disabled_by_host reported");
    assert.deepEqual((await hostState(p))["injected"], []);
    await p.host.call({ cmd: "settings", allowMouse: true });
    await p.phone.call({ cmd: "move", dx: 2, dy: 2 });
    await eventually(async () => ((await hostState(p))["injected"] as string[]).length === 1, "mouse works again");
  });
});

test("a hostile controller cannot push invalid, unknown or out-of-range messages through to the OS, and abuse ends the session", async () => {
  await withStack(async (p) => {
    await p.phone.call({ cmd: "raw", type: "input.mouse_move", fields: { dx: 999999, dy: 0 } });
    await p.phone.call({ cmd: "raw", type: "input.key", fields: { code: "../../etc/passwd", down: true } });
    await p.phone.call({ cmd: "raw", type: "exec.shell", fields: { cmd: "calc.exe" } });
    await p.phone.call({ cmd: "raw", type: "input.text", fields: { text: "ok\u0007" } });
    await eventually(async () => ((await phoneState(p))["hostErrors"] as string[]).filter((c) => c === "invalid_message").length >= 4, "all four rejected");
    assert.deepEqual((await hostState(p))["injected"], []);

    const ended = p.phone.waitEvent((e) => e["evt"] === "sessionEnded", 15_000, p.phone.mark());
    for (let i = 0; i < 40; i++) await p.phone.call({ cmd: "raw", type: "exec.shell", fields: { cmd: "calc.exe" } });
    assert.equal((await ended)["reason"], "protocol_violations");
    assert.equal((await hostState(p))["session"], null, "the host dropped the abusive session");
    assert.deepEqual((await hostState(p))["injected"], []);
  });
});

test("files: upload arrives intact; hostile names stay inside the inbox; duplicates, limits and missing permission are handled", async () => {
  await withStack(async (p, _b, dataDir) => {
    const data = randomBytes(300_000);
    const up = await p.phone.call({ cmd: "upload", name: "informe ñ.bin", dataB64: data.toString("base64") }, 60_000);
    assert.deepEqual([up["ok"], up["savedAs"]], [true, "informe ñ.bin"]);
    let inbox = (await hostState(p))["inbox"] as { name: string; size: number; sha256: string }[];
    assert.deepEqual(inbox.map((f) => [f.name, f.size, f.sha256]), [["informe ñ.bin", data.length, sha(data)]]);

    // Hostile names are reduced to a single safe segment inside the inbox.
    const evil = randomBytes(1000);
    const hostile = await p.phone.call({ cmd: "upload", name: "../../escape.txt", dataB64: evil.toString("base64") });
    assert.equal(hostile["ok"], true);
    const savedAs = hostile["savedAs"] as string;
    assert.ok(!/[\\/:]/.test(savedAs) && savedAs !== ".." && savedAs !== ".", `a single safe segment, got ${savedAs}`);
    inbox = (await hostState(p))["inbox"] as typeof inbox;
    assert.equal(inbox.length, 2);
    assert.deepEqual(readdirSync(dataDir).sort(), ["devices.json", "inbox", "settings.json"], "nothing was written outside the inbox");

    // The same bytes again, under another name: recognised as a duplicate.
    const dup = await p.phone.call({ cmd: "upload", name: "otro nombre.bin", dataB64: data.toString("base64") });
    assert.deepEqual([dup["ok"], dup["reason"]], [false, "already_exists"]);

    // Over the PC's configured limit.
    await p.host.call({ cmd: "settings", maxFileBytes: 2048 });
    const big = await p.phone.call({ cmd: "upload", name: "grande.bin", dataB64: randomBytes(5000).toString("base64") });
    assert.deepEqual([big["ok"], big["reason"]], [false, "too_large"]);
  });
});

test("files: the phone lists and downloads from the PC outbox, and the PC can push a file it picked", async () => {
  await withStack(async (p) => {
    const outFile = randomBytes(120_000);
    await p.host.call({ cmd: "seedOutbox", name: "para el movil.bin", dataB64: outFile.toString("base64") });
    const listing = await p.phone.call({ cmd: "list" });
    assert.deepEqual((listing["files"] as any[]).map((f) => f.name), ["para el movil.bin"]);

    await p.phone.call({ cmd: "download", name: "para el movil.bin" });
    await eventually(async () => (await phoneState(p) as any)["downloads"]["para el movil.bin"] === sha(outFile), "downloaded file matches", 20_000);

    // Names that are not in the outbox listing are refused, whatever they look like.
    for (const name of ["../secret.txt", "..\\secret.txt", "/etc/passwd", "C:\\Windows\\win.ini"]) {
      await p.phone.call({ cmd: "download", name });
    }
    await new Promise((r) => setTimeout(r, 600));
    assert.equal(Object.keys((await phoneState(p) as any)["downloads"]).length, 1);

    // A file the person at the PC picked.
    const picked = randomBytes(70_000);
    const offered = await p.host.call({ cmd: "offerFile", dataB64: picked.toString("base64") }, 30_000);
    assert.equal(offered["offered"], true);
    await eventually(async () => Object.values((await phoneState(p) as any)["downloads"]).includes(sha(picked)), "PC-picked file arrived", 20_000);
  });
});

test("files: no 'files' permission means an immediate, explained refusal", async () => {
  await withStack(async (p) => {
    const started = Date.now();
    const r = await p.phone.call({ cmd: "upload", name: "x.bin", dataB64: randomBytes(100).toString("base64") });
    assert.deepEqual([r["ok"], r["reason"]], [false, "forbidden"]);
    assert.ok(Date.now() - started < 5000);
    assert.deepEqual((await hostState(p))["inbox"], []);
  }, { permissions: ["mouse"] });
});
