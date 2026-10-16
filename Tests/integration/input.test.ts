import { test } from "node:test";
import assert from "node:assert/strict";
import { BackendProcess, connect, eventually, hostState, pair, phoneState, startPair, stopAll } from "../lib/stack.ts";

test("pairing by QR, an end-to-end encrypted session, and mouse + keyboard from the Android core to the Windows host runtime", async () => {
  const backend = await BackendProcess.start();
  const p = await startPair(backend);
  try {
    const paired = await pair(p);
    assert.deepEqual(paired["permissions"], ["remote_desktop", "mouse", "keyboard", "clipboard", "files", "media"], "secure default grant: no remote power");
    // The person compares two screens: the code the phone derived on its own must be the one the PC shows.
    const hostSide = await p.host.waitEvent((e) => e["evt"] === "paired", 10_000);
    assert.match(String(paired["sas"]), /^\d{6}$/);
    assert.equal(paired["sas"], hostSide["sas"], "phone and PC show the same comparison code");

    const session = await connect(p);
    assert.deepEqual(session["permissions"], ["clipboard", "files", "keyboard", "media", "mouse", "remote_desktop"]);
    assert.ok((session["features"] as string[]).includes("input"));

    await p.phone.call({ cmd: "move", dx: -12, dy: 7 });
    await p.phone.call({ cmd: "abs", x: 65535, y: 0 });
    await p.phone.call({ cmd: "button", button: "right", down: true });
    await p.phone.call({ cmd: "button", button: "right", down: false });
    await p.phone.call({ cmd: "wheel", dx: 0, dy: -120 });
    await p.phone.call({ cmd: "key", code: "KeyA", down: true });
    await p.phone.call({ cmd: "key", code: "KeyA", down: false });
    await p.phone.call({ cmd: "key", code: "ArrowLeft", down: true });
    await p.phone.call({ cmd: "key", code: "ArrowLeft", down: false });
    await p.phone.call({ cmd: "text", text: "ñandú ✓ 🙂" });

    const expected = ["move -12,7", "abs 65535,0", "btn Right down", "btn Right up", "wheel 0,-120", "key 1E down", "key 1E up", "key 4BE down", "key 4BE up", "text ñandú ✓ 🙂"];
    const injected = await eventually(async () => {
      const s = (await hostState(p))["injected"] as string[];
      return s.length >= expected.length ? s : null;
    }, "all input events to reach the injector");
    assert.deepEqual(injected, expected);
  } finally {
    await stopAll(p.phone, p.host, backend);
  }
});

test("keys and buttons held by the phone are released when it disconnects cleanly", async () => {
  const backend = await BackendProcess.start();
  const p = await startPair(backend);
  try {
    await pair(p);

    await connect(p);
    await p.phone.call({ cmd: "key", code: "ShiftLeft", down: true });
    await p.phone.call({ cmd: "button", button: "left", down: true });
    await eventually(async () => ((await hostState(p))["held"] as any).keys === 1, "shift held on the PC");
    await p.phone.call({ cmd: "disconnect" });
    await eventually(async () => {
      const s = await hostState(p);
      return (s["held"] as any).keys === 0 && (s["held"] as any).buttons === 0;
    }, "held input released after a clean disconnect");
    const injected = (await hostState(p))["injected"] as string[];
    assert.ok(injected.includes("key 2A up") && injected.includes("btn Left up"));
  } finally {
    await stopAll(p.phone, p.host, backend);
  }
});

test("a phone that dies while holding a key does not leave it stuck on the PC", async () => {
  const backend = await BackendProcess.start();
  const p = await startPair(backend);
  try {
    await pair(p);
    await connect(p);
    await p.phone.call({ cmd: "key", code: "ControlLeft", down: true });
    await eventually(async () => ((await hostState(p))["held"] as any).keys === 1, "ctrl held");
    await p.phone.kill(); // power loss: the relay sees the socket drop
    await eventually(async () => ((await hostState(p))["held"] as any).keys === 0, "ctrl released after the phone vanished", 15_000);
    assert.ok(((await hostState(p))["injected"] as string[]).includes("key 1D up"));
    assert.equal((await hostState(p))["session"], null);
  } finally {
    await stopAll(p.host, backend);
  }
});

test("when the relay dies the host releases input immediately instead of waiting for a timeout", async () => {
  const backend = await BackendProcess.start();
  const p = await startPair(backend);
  try {
    await pair(p);
    await connect(p);
    await p.phone.call({ cmd: "key", code: "AltLeft", down: true });
    await eventually(async () => ((await hostState(p))["held"] as any).keys === 1, "alt held");
    await backend.kill();
    await eventually(async () => ((await hostState(p))["held"] as any).keys === 0, "alt released after the relay disappeared", 15_000);
    assert.equal((await phoneState(p))["sessionOpen"], false);
  } finally {
    await stopAll(p.phone, p.host, backend);
  }
});
