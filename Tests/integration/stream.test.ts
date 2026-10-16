import { test } from "node:test";
import assert from "node:assert/strict";
import { BackendProcess, connect, eventually, hostState, pair, startPair, stopAll } from "../lib/stack.ts";

async function streamState(p: Awaited<ReturnType<typeof startPair>>): Promise<Record<string, any>> {
  return p.phone.call({ cmd: "streamState" });
}
async function hostStream(p: Awaited<ReturnType<typeof startPair>>): Promise<Record<string, any>> {
  return p.host.call({ cmd: "streamInfo" });
}

test("screen streaming signalling: offer, answer and ICE candidates cross the relay inside the encrypted channel; the PC's limits apply; adaptation and teardown work", async () => {
  const backend = await BackendProcess.start();
  const p = await startPair(backend);
  try {
    await pair(p);
    await connect(p);
    await p.host.call({ cmd: "settings", maxFileBytes: 1_000_000_000 }); // untouched; makes sure unrelated settings do not interfere

    await p.phone.call({ cmd: "streamStart", maxWidth: 3840, maxHeight: 2160, maxFps: 60, maxBitrateKbps: 30000 });
    await eventually(async () => (await streamState(p))["phase"] === "NEGOTIATING", "phone negotiating");
    // The phase flips to NEGOTIATING a moment before the engine is handed the offer: wait for it, don't race it.
    await eventually(async () => (await streamState(p))["offer"] === "v=0\r\nfake-offer", "the PC's offer reached the phone");
    await eventually(async () => (await hostStream(p))["answer"] === "v=0\r\nharness-answer", "the phone's answer reached the PC");
    const info = await hostStream(p);
    assert.deepEqual(info["profile"], { w: 1920, h: 1080, fps: 30, kbps: 8000 }, "the PC caps the request to its own limits");

    // ICE candidates in both directions.
    await p.host.call({ cmd: "peerState", state: "Connecting", candidate: "candidate:5 1 udp 2130706431 10.1.1.1 51000 typ host" });
    await eventually(async () => (await streamState(p))["remoteCandidates"] === 1, "PC candidate reached the phone");
    await eventually(async () => (await hostStream(p))["remoteCandidates"] === 1, "phone candidate reached the PC");

    // Media connected: the phone is told, with the real size.
    await p.host.call({ cmd: "peerState", state: "Connected", stats: { fps: 24, kbps: 1500, w: 1920, h: 1080, loss: 0.2 } });
    await eventually(async () => (await streamState(p))["phase"] === "STREAMING", "phone sees streaming");
    assert.equal((await streamState(p))["width"], 1920);
    await eventually(async () => (await streamState(p))["fps"] === 24, "live stats reach the phone", 10_000);
    await eventually(async () => (await hostStream(p))["adaptations"] > 0, "heavy loss makes the PC lower the quality", 10_000);

    // The PC cannot read the desktop (UAC / lock screen): the phone hears about it.
    await p.host.call({ cmd: "peerState", state: "Connected", blocked: "secure_desktop" });
    await eventually(async () => (await streamState(p))["blocked"] === "secure_desktop", "blocked capture is surfaced");

    // Leaving the stream does not end the session: input still works.
    await p.phone.call({ cmd: "streamStop" });
    await eventually(async () => (await hostStream(p))["disposed"] === true, "PC released capture and encoder");
    assert.equal((await streamState(p))["rtcClosed"], true);
    await p.phone.call({ cmd: "move", dx: 4, dy: 4 });
    await eventually(async () => ((await hostState(p))["injected"] as string[]).includes("move 4,4"), "control still works after the stream stopped");
  } finally {
    await stopAll(p.phone, p.host, backend);
  }
});

test("ending the session releases the media connection on both ends", async () => {
  const backend = await BackendProcess.start();
  const p = await startPair(backend);
  try {
    await pair(p);
    await connect(p);
    await p.phone.call({ cmd: "streamStart" });
    await eventually(async () => (await hostStream(p))["answer"] !== null, "negotiated");
    await p.phone.call({ cmd: "disconnect" });
    await eventually(async () => (await hostStream(p))["disposed"] === true, "PC released the stream when the session ended");
  } finally {
    await stopAll(p.phone, p.host, backend);
  }
});

test("a phone paired without screen access, or a PC with the screen switched off, is refused at once with the reason", async () => {
  const backend = await BackendProcess.start();
  const p = await startPair(backend);
  try {
    await pair(p, ["mouse", "keyboard"]);
    await connect(p);
    await p.phone.call({ cmd: "streamStart" });
    await eventually(async () => (await streamState(p))["phase"] === "ERROR", "refused");
    assert.equal((await streamState(p))["reason"], "forbidden");
    assert.equal((await hostStream(p))["peers"], 0, "no capture or encoder was even created");
  } finally {
    await stopAll(p.phone, p.host, backend);
  }

  const b2 = await BackendProcess.start();
  const q = await startPair(b2);
  try {
    await pair(q);
    await connect(q);
    await q.host.call({ cmd: "settings", allowScreen: false });
    await q.phone.call({ cmd: "streamStart" });
    await eventually(async () => (await streamState(q))["phase"] === "ERROR", "disabled by the PC");
    assert.equal((await streamState(q))["reason"], "disabled_by_host");
  } finally {
    await stopAll(q.phone, q.host, b2);
  }
});

test("without a TURN relay a failed connection is explained as such; with one, ICE servers carry short-lived per-device credentials", async () => {
  const noTurn = await BackendProcess.start({ STUN_URLS: "stun:stun.example.org:3478" });
  const a = await startPair(noTurn);
  try {
    await pair(a);
    await connect(a);
    await a.phone.call({ cmd: "streamStart" });
    await eventually(async () => (await streamState(a))["rtcSessions"] === 1, "media session created (after the ICE lookup)");
    const info = await streamState(a);
    assert.equal(info["hasTurn"], false);
    assert.equal(info["hasStun"], true);
    await a.phone.call({ cmd: "rtcState", state: "FAILED" });
    await eventually(async () => (await streamState(a))["phase"] === "ERROR", "failure reported");
    assert.equal((await streamState(a))["reason"], "ice_failed_no_relay");
  } finally {
    await stopAll(a.phone, a.host, noTurn);
  }

  const withTurn = await BackendProcess.start({ STUN_URLS: "stun:stun.example.org:3478", TURN_URLS: "turn:turn.example.org:3478", TURN_SECRET: "integration-test-secret", TURN_TTL_SECONDS: "600" });
  const b = await startPair(withTurn);
  try {
    await pair(b);
    await connect(b);
    await b.phone.call({ cmd: "streamStart" });
    await eventually(async () => (await streamState(b))["rtcSessions"] === 1, "media session created (after the ICE lookup)");
    const info = await streamState(b);
    assert.equal(info["hasTurn"], true);
    assert.match(info["turnUsername"], new RegExp(`^\\d{10}:${b.phoneId}$`), "username = expiry:deviceId");
    await b.phone.call({ cmd: "rtcState", state: "FAILED" });
    await eventually(async () => (await streamState(b))["reason"] === "ice_failed", "with a relay the message says so");
  } finally {
    await stopAll(b.phone, b.host, withTurn);
  }
});
