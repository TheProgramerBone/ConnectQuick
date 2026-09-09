# Status

Honest, phase-by-phase state of the project against the plan in the original brief (§45). Updated
whenever a phase's status changes — this is the file to read to know what's real versus planned.

Nothing described as "done" below is a stub, a mock, or a UI element with no backing
implementation — see each phase's "Verified by" for how to check that yourself.

## Phase 1 — Architecture ✅ done

Repo structure, protocol design, device identity scheme, minimal backend, secure transport.
**Verified by**: `Backend/npm test` (57 tests), `Backend/npm run typecheck`.

## Phase 2 — Pairing ✅ done (backend + WindowsHost + Android)

QR *content* format, handshake, Host-authorization signing, device storage, revocation are fully
implemented and tested on the backend. The **WindowsHost** tray app (`/WindowsHost`) implements
its side for real: generates/persists its Ed25519 identity (DPAPI-encrypted), displays the QR for
"Vincular nuevo dispositivo", shows the "¿Autorizar?" dialog with the SAS code, and signs
`pairing.decision`. The **Android** app (`/Android`) now implements the other side: generates/
persists its Ed25519 identity (Android-Keystore-encrypted), scans the QR (CameraX + on-device ML
Kit), sends `pairing.init`, and — unlike a naive client — independently verifies the Host's
signature over `pairing.result` before reporting success rather than just trusting the backend.
Both apps are real (if minimal): no session/remote-control UI, no desktop capture, no input
injection (Phases 4+, still not started).
**Verified by**: `Backend/test/integration/pairing.test.ts`;
`WindowsHost/src/ConnectQuick.WindowsHost.Tests` and `Android/app/src/test/kotlin/.../PairingEndToEndTest.kt`
each spawn the actual backend as a subprocess and drive their own real production pairing code
against it, with the *other* not-directly-spawnable platform simulated using the same
vector-verified crypto (`Protocol/test-vectors/crypto-vectors.json`) all three platforms share.
The Android APK itself was also built for real (`./gradlew assembleDebug`) and its manifest/
permissions checked with `aapt dump badging`; it was not run on a physical device or emulator (none
available in this environment) — see `Android/README.md` for exactly what that does and doesn't
prove.

## Phase 3 — Communication ✅ done (backend side) / 🟡 partial (WindowsHost + Android)

Session state machine, heartbeat, presence broadcast, and the `session.connect`/`session.state`
relay primitive are implemented on the backend. Both WindowsHost and Android now prove a real
client-side reconnect loop too: `WindowsHost/.../Net/BackendClient.cs` and
`Android/.../net/BackendClient.kt` each do `hello`/`hello.ack`, a 15s `heartbeat.ping`, and
exponential-backoff-with-jitter reconnection (capped 30s, per §6) — but this is only exercised
against a real backend in each platform's pairing E2E test, not (yet) a dedicated
disconnect/backoff/reconnect test. `session.connect`/`session.state` themselves are not used by
either client yet — there's no session to negotiate until Phase 4/5 exist.
**Verified by**: `Backend/test/integration/session.test.ts`, `presence.test.ts`, `auth.test.ts`.

## Phase 4 — Mouse + keyboard ⛔ not started

No `input.*` message types exist in the protocol yet, no WindowsHost input-injection code, no
Android touchpad/keyboard UI.

## Phase 5 — Desktop streaming ⛔ not started

No WebRTC integration anywhere yet. Architecture decision is made and written down
(`ARCHITECTURE.md`, "desktop-stream/input plane") but zero lines of it are implemented: no
Windows Graphics Capture, no hardware encoding, no Android decoder, no WebRTC signaling message
types.

## Phase 6 — Clipboard ⛔ not started

## Phase 7 — Multimedia ⛔ not started

## Phase 8 — Files ⛔ not started

## Phase 9 — Power ⛔ not started

## Phase 10 — UX (Android/Windows UI polish) ⛔ not started

No UI exists at all yet — there is no Android or WindowsHost project on disk, only empty
directories reserved for them.

## Phase 11 — Security & hardening 🟡 ongoing, backend scope only

Everything listed as implemented in `SECURITY.md` is real and tested. The "known gaps" section of
that file lists what's explicitly deferred because the feature it would harden doesn't exist yet.

## Phase 12 — Testing 🟡 ongoing

57 backend unit + integration tests, 100%-ish coverage on the files that matter (`npm test` prints
a per-file coverage table). WindowsHost and Android each have their own crypto-vectors + real
pairing E2E tests (see Phase 2). No test yet exercises a real cross-machine Internet path
(NAT/CGNAT/latency) — everything today runs loopback-only, which proves the protocol logic but not
real-world NAT traversal (that claim can only really be proven once WebRTC/ICE is in the picture —
see Phase 5). No test yet exercises the two real client apps against each other and the real
backend simultaneously (WindowsHost generating an actual QR that the actual Android app scans) —
each platform's E2E test today simulates the *other* side rather than spawning it, since neither a
WinForms tray app nor an Android emulator/device is available to drive headlessly in this
environment; see `Tests/` below.

## What exists on disk right now

```
Backend/     real, tested Node/TypeScript signaling+relay+registry server
Protocol/    protocol-spec.md, JSON Schemas, test-vectors — the shared contract
Docs/        this file, ARCHITECTURE.md, SECURITY.md (+ empty diagrams/ placeholder)
WindowsHost/ real, tested .NET tray app — identity, pairing (QR + authorize), reconnect/heartbeat.
             No desktop capture/input/clipboard/files/media/power yet (Phases 4-9).
Android/     real, tested Kotlin app — identity, pairing (scan + verify), reconnect/heartbeat.
             Built as a real APK (arm64-v8a debug build); not run on a device/emulator (none
             available here). Same missing-phases list as WindowsHost.
Tests/       empty — reserved for the still-missing cross-platform case: WindowsHost and Android
             actually talking to each other (today each platform's E2E test simulates the other
             side instead of spawning it for real — see Phase 12)
```

## Suggested next milestone

Pairing (Phase 2/3) is now real end-to-end on both client platforms individually, each proven
against the real backend — but WindowsHost and Android have never actually been run against *each
other*. The highest-value next step is a genuine two-device pairing: build the WindowsHost
(`dotnet run`), install the Android APK on a real phone (`Android/README.md`), and scan the real
QR — this would retire the "Host simulated" / "Android simulated" caveat in each platform's E2E
test and be the first fully real cross-platform proof. After that, Phase 4 (mouse + keyboard) is
the smallest next slice that makes the system actually *do* something, since Phase 5 (desktop
streaming) is the larger, WebRTC-shaped effort behind it.
