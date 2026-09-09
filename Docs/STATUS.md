# Status

Honest, phase-by-phase state of the project against the plan in the original brief (§45). Updated
whenever a phase's status changes — this is the file to read to know what's real versus planned.

Nothing described as "done" below is a stub, a mock, or a UI element with no backing
implementation — see each phase's "Verified by" for how to check that yourself.

## Phase 1 — Architecture ✅ done

Repo structure, protocol design, device identity scheme, minimal backend, secure transport.
**Verified by**: `Backend/npm test` (57 tests), `Backend/npm run typecheck`.

## Phase 2 — Pairing ✅ done (backend side) / ⛔ not started (client UIs)

QR *content* format, handshake, Host-authorization signing, device storage, revocation are fully
implemented and tested on the backend. What's **not** built: the actual Windows tray app that
generates/displays the QR and shows the "¿Autorizar?" dialog, and the actual Android app that
scans it — those live in Phase-2-shaped work inside the not-yet-created `/WindowsHost` and
`/Android` projects.
**Verified by**: `Backend/test/integration/pairing.test.ts`.

## Phase 3 — Communication ✅ done (backend side) / ⛔ not started (client UIs)

Session state machine, heartbeat, presence broadcast, and the `session.connect`/`session.state`
relay primitive are implemented. Reconnection is only proven from the *server's* perspective
(idle timeout, clean re-auth on a fresh socket); no client exists yet to prove a real
disconnect → backoff → reconnect loop end-to-end.
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
Android/     empty — reserved
WindowsHost/ empty — reserved
Tests/       empty — reserved (Backend's own tests live inside Backend/test today;
             this top-level dir is for cross-platform/integration tests once there's
             more than one platform to integrate)
```

## Suggested next milestone

Phase 2/3 client UIs: a minimal WindowsHost (.NET) tray app that can actually generate a QR and
complete a real pairing against this backend, and a minimal Android app that can scan it — that's
the smallest slice that makes the system usable (if not yet featureful) end-to-end, and it's the
natural next step given the backend side of Phases 1-3 is already solid.
