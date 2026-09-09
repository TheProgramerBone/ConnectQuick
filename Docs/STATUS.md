# Status

Honest, phase-by-phase state of the project against the plan in the original brief (§45). Updated
whenever a phase's status changes — this is the file to read to know what's real versus planned.

Nothing described as "done" below is a stub, a mock, or a UI element with no backing
implementation — see each phase's "Verified by" for how to check that yourself.

## Phase 1 — Architecture ✅ done

Repo structure, protocol design, device identity scheme, minimal backend, secure transport.
**Verified by**: `Backend/npm test` (57 tests), `Backend/npm run typecheck`.

## Phase 2 — Pairing ✅ done (backend + WindowsHost) / ⛔ not started (Android UI)

QR *content* format, handshake, Host-authorization signing, device storage, revocation are fully
implemented and tested on the backend. The **WindowsHost** tray app (`/WindowsHost`) now
implements its side for real: generates/persists its Ed25519 identity (DPAPI-encrypted), displays
the QR for "Vincular nuevo dispositivo", shows the "¿Autorizar?" dialog with the SAS code, and
signs `pairing.decision`. What's **not** built: the actual Android app that scans the QR — that
still lives in the not-yet-created `/Android` project.
**Verified by**: `Backend/test/integration/pairing.test.ts`,
`WindowsHost/src/ConnectQuick.WindowsHost.Tests` (crypto vectors bit-for-bit, plus a real
end-to-end pairing test that spawns the actual backend and drives the real `PairingCoordinator`
against it, with the not-yet-built Android side simulated using the same vector-verified crypto).

## Phase 3 — Communication ✅ done (backend side) / 🟡 partial (WindowsHost) / ⛔ not started (Android)

Session state machine, heartbeat, presence broadcast, and the `session.connect`/`session.state`
relay primitive are implemented on the backend. The WindowsHost now proves a real client-side
reconnect loop too: `BackendClient` (`WindowsHost/src/ConnectQuick.WindowsHost/Net/BackendClient.cs`)
does `hello`/`hello.ack`, a 15s `heartbeat.ping`, and exponential-backoff-with-jitter reconnection
(capped 30s, per §6) — but this is only exercised against a real backend in the pairing E2E test
above, not (yet) a dedicated disconnect/backoff/reconnect test. `session.connect`/`session.state`
themselves are not used by WindowsHost yet — there's no session to negotiate until Phase 4/5 exist.
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

## Phase 12 — Testing 🟡 ongoing, backend scope only

57 backend unit + integration tests, 100%-ish coverage on the files that matter (`npm test` prints
a per-file coverage table). No Android/WindowsHost tests exist because no Android/WindowsHost code
exists. No test yet exercises a real cross-machine Internet path (NAT/CGNAT/latency) — everything
today runs loopback-only, which proves the protocol logic but not real-world NAT traversal
(that claim can only really be proven once WebRTC/ICE is in the picture — see Phase 5).

## What exists on disk right now

```
Backend/     real, tested Node/TypeScript signaling+relay+registry server
Protocol/    protocol-spec.md, JSON Schemas, test-vectors — the shared contract
Docs/        this file, ARCHITECTURE.md, SECURITY.md (+ empty diagrams/ placeholder)
WindowsHost/ real, tested .NET tray app — identity, pairing (QR + authorize), reconnect/heartbeat.
             No desktop capture/input/clipboard/files/media/power yet (Phases 4-9).
Android/     empty — reserved
Tests/       empty — reserved (Backend's own tests live inside Backend/test, WindowsHost's inside
             WindowsHost/src/ConnectQuick.WindowsHost.Tests; this top-level dir is for
             integration tests that need *both* real clients at once, once Android exists too)
```

## Suggested next milestone

A minimal **Android** Controller that can scan the WindowsHost's QR and complete a real pairing
against the backend — the WindowsHost side of Phase 2/3 is now solid (see above), so this is the
remaining piece to make pairing usable end-to-end with two real devices instead of one real device
plus a simulated Controller. After that, Phase 4 (mouse + keyboard) is the smallest next slice that
makes the system actually *do* something, since Phase 5 (desktop streaming) is the larger,
WebRTC-shaped effort behind it.
