# Status

Honest, phase-by-phase state of the project against the plan in the original brief. Updated whenever
a phase's status changes — this is the file to read to know what is real versus planned, and **what
has and has not actually been run**.

Last full update: 16-oct-2026.

## How to read the marks

- ✅ **Implemented and verified** by automated tests that run in this repo (the command is given).
- 🟡 **Implemented, but part of it has not been run** — usually because it needs Windows, a physical
  phone, or infrastructure that does not exist in the development sandbox. The gap is stated.
- ⛔ Not implemented.

Nothing marked implemented is a stub, a mock presented as a feature, or a UI element without a working
function behind it. Where the UI cannot do something yet (e.g. "apps"), it does not offer it.

## What was and was not run (summary)

| Piece | Automated tests | Run for real? |
|---|---|---|
| Backend (relay, registry, limits, ICE) | ✅ 95 tests, real WebSocket server | ✅ load-tested as a real process (see *Scaling*) |
| Protocol + E2E crypto, three implementations | ✅ shared vectors reproduced bit-for-bit by TS, .NET and Kotlin | ✅ |
| Windows Host **core** (session, input, clipboard, media, files, power, streaming service) | ✅ 156 tests | ✅ as a headless process in the cross-platform suite |
| Android **core** (session, link, clients, gesture engine, wording) | ✅ 89 tests (JVM) | ✅ as a separate process in the cross-platform suite |
| Cross-platform: real backend + .NET Host process + Kotlin controller process | ✅ 40 scenarios (`Tests/`) | ✅ |
| WebRTC ICE + DTLS-SRTP + RTP delivery | ✅ loopback test (real SIPSorcery on both ends) with a synthetic encoder | 🟡 real GDI capture + libvpx VP8 **not run** (needs Windows); Android libwebrtc **not run** |
| Win32 adapters (`SendInput`, clipboard, media keys, power, GDI capture, DPAPI) | 🟡 struct layouts, key maps and logic tested | ⛔ **not run on Windows** — none available here |
| Windows UI (WinForms, tray, dialogs) | ⛔ no UI tests | 🟡 **compiles** against the WinForms reference assemblies; **never shown on a screen** |
| Android app (Views, Keystore identity, CameraX, libwebrtc view) | ⛔ no instrumented tests | 🟡 **compiles** against the Android 14 framework API and the libwebrtc API (all but the CameraX/ML Kit scan screen, which could not be compiled here); **never installed on a device**; Gradle could not resolve its plugins in the sandbox, so `assembleDebug` has **not** been run in this session |
| TURN relay | ✅ credential generation tested | ⛔ no TURN server exists in this environment |

## Phase 1 — Architecture ✅

Repo structure, protocol design, device identity scheme, backend, secure transport.
**Verified by**: `cd Backend && npm test`.

## Phase 2 — Pairing ✅

QR pairing, Host-signed decisions, device storage, revocation. Windows (DPAPI-protected identity) and
Android (Android-Keystore-wrapped identity) both implement their side; the phone independently verifies
the Host's signature over the result. **New**: the phone derives the 6-digit comparison code (SAS)
itself and shows it, the PC shows it in the authorize dialog, and an automated test proves both
derive the same digits.
**Verified by**: `Backend/test/integration/pairing.test.ts`, `Tests/integration/input.test.ts` (pairs a
real .NET Host process with a real Kotlin controller process through a real backend), `.NET PairingEndToEndTests`.
Manually, on 11-sep-2026, the earlier (pre-session-layer) pairing was run between a real PC and a real
phone over a LAN (`Docs/GUIA_DE_PRUEBAS.md`); that manual run pre-dates this work and has **not** been repeated for
the new screens.

## Phase 3 — Communication and end-to-end sessions ✅

`session.connect/accept/data/end` relay with an end-to-end encrypted layer: ephemeral X25519 per session,
Ed25519-signed handshake bound to both keys and the granted permissions, HKDF-SHA256, AES-256-GCM with
strictly increasing sequence numbers, key confirmation by the first encrypted frame. The backend only
routes opaque frames (`Protocol/protocol-spec.md` §7). Auto-reconnect with backoff, dead-link detection
(a silent network partition is noticed without any FIN), revocation that cuts a live session at once,
backend/PC restarts survived, duplicate-identity handling (close code 4010).
**Verified by**: `Backend/test/integration/{session,hardening}.test.ts`, `Tests/integration/{resilience,link,security}.test.ts`,
shared vectors in `Protocol/test-vectors/`.

## Phase 4 — Mouse + keyboard 🟡 (logic ✅, real injection not run)

Touchpad gestures (move, tap, double-tap-drag, two-finger scroll, two-finger tap = right click) in a
deterministic, unit-tested engine; physical-scancode keyboard, Unicode text, held-key tracking, and
**guaranteed release** of every key/button when the session ends, the phone dies, the network drops or the
relay goes away; per-type validation and rate limits on the PC.
**Verified by**: `.NET InputTests`, `Kotlin TouchpadEngineTest`, `Tests/integration/input.test.ts`.
**Not run**: the real `SendInput` calls on Windows (the call layout is tested, the effect on a real desktop is not); typing into elevated windows is subject to Windows UIPI and will not work — by design, ConnectQuick does not try to bypass UAC.

## Phase 5 — Desktop streaming 🟡 (signalling and transport ✅, real capture/decoding not run)

The relay never carries video. The PC captures (GDI), scales, encodes (VP8 via libvpx) and sends over
WebRTC (SIPSorcery); the phone receives with libwebrtc. SDP and ICE travel inside the encrypted channel;
STUN/TURN credentials are issued by the backend (ephemeral, per device); the PC adapts resolution/fps/bitrate
to loss and RTT; a locked screen/UAC prompt is reported instead of showing black; everything stops with the session.
**Verified by**: `.NET WebRtcLoopbackTests` (real ICE + DTLS-SRTP + RTP fragmentation/reassembly between two real stacks),
`StreamingServiceTests` (limits, adaptation, teardown), `Kotlin StreamControllerTest`, `Tests/integration/stream.test.ts`.
**Not run**: capture and VP8 encoding on a real Windows desktop; libwebrtc decoding/rendering on a phone; real-world NAT
traversal; TURN (no relay exists here). Over a different network than the PC's, streaming needs a TURN server
(`Docs/ARCHITECTURE.md`, *Running TURN*); without one the app says so rather than hanging.

## Phase 6 — Clipboard 🟡 (logic ✅)

Text only, ≤16 KiB, SHA-256-verified, loop-free, auto-push **off by default** and skipped for data Windows marks
as excluded from history (password managers). **Verified by**: `.NET ClipboardTests`, `Kotlin ClipboardClientTest`,
`Tests/integration/features.test.ts`. **Not run**: the real Win32 clipboard.

## Phase 7 — Multimedia 🟡 (logic ✅)

Play/pause, next/previous, stop, volume ±, mute through media keys. Windows cannot confirm that a player
acted, so the app says "sent", never "playing". **Verified by**: `.NET MediaTests`, `Kotlin MediaClientTest`,
`Tests/integration/features.test.ts`. **Not run**: real key delivery on Windows.

## Phase 8 — Files 🟡 (logic ✅)

Both directions, with SHA-256, resume, size and free-space limits, duplicate detection, and a sandboxed folder
on each side. Hostile names (`../`, absolute paths, reserved device names, bidi spoofing, over-long, symlinks/
junctions) cannot place anything outside the inbox; incomplete files are invisible until the hash matches;
Zone.Identifier is set on received files. **Verified by**: `.NET FileTransferTests`, `Kotlin FilesTest`/`FileNamesTest`,
`Tests/integration/features.test.ts`. **Not run**: Windows file-system specifics (junction handling, Zone.Identifier) and the
Android `MediaStore`/picker adapters on a device.

## Phase 9 — Power 🟡 (logic ✅)

Lock is one step. Sleep, restart and shut down need a one-time token that only works after an explicit
confirmation on the phone, then (restart/shutdown) a grace period with cancel. The PC's policy defaults to
**lock only**. No other command can be sent — there is no message for it. **Verified by**: `.NET PowerTests`,
`Kotlin PowerClientTest`, `Tests/integration/features.test.ts`. **Not run**: the real Windows calls (deliberately — the
tests use a recording executor).

## Phase 10 — UX 🟡 (compiled, never shown)

"Liquid Crystal" design system on both platforms — translucent glass surfaces with a hairline and sheen over a
calm gradient, one cool accent, status as colour + shape + words, 48 dp touch targets, scalable text, scrolling
screens, High Contrast / high-text-contrast fallbacks, accessible names and descriptions, safe-by-default focus on
destructive dialogs.
- **Windows**: tray icon whose shape changes with state; control centre with Status, Devices (revoke), Permissions &
  settings (every switch), Files (send to phone, progress) and Log; authorize dialog with per-permission switches
  (remote power never pre-selected, Enter does not approve); optional per-connection approval; "disconnect phone now";
  single instance; the same `HostRuntime` the integration suite drives.
- **Android**: home with the real link state and a tile per capability that says *why* it is unavailable; pairing with
  the comparison code; touchpad + keyboard; live screen with direct touch; clipboard; media; files; power with a
  confirmation dialog; network-change handling; the link runs only while the app is visible (+60 s grace).
- **Themes (Oct 2026)**: dark (default) and light on both platforms, switchable at runtime (Windows: Permissions &
  settings → Apariencia, stored in `settings.json` as `theme`; Android: home → Apariencia, stored in preferences).
  Richer glass: aurora backdrop lights, bevelled panels with sheen/shade, lit primary buttons.
- **Pairing window fix (Oct 2026)**: when a phone scans the QR the PC window now switches to the 6-digit code and says
  to confirm in the authorize dialog (it used to keep showing the stale single-use QR); the dialog opens on top of
  it and is activated; rejection/connection loss end in a clear state with "new code". Written without a compiler
  or a Windows machine: **unverified on real hardware**, and the .NET suite could not be run in that session.
**Verified by**: wording/state logic is unit-tested (`LinkPresentationTest`, `StreamTextsTest`); both UIs compile.
**Not verified**: appearance, layout at different DPIs/font scales, TalkBack/Narrator behaviour, performance, and every
interaction on a real screen. Expect visual polish work once they are run.

## Phase 11 — Security 🟡 (see `Docs/SECURITY.md`)

End-to-end sessions, signed handshakes, replay windows, per-session and per-IP limits, origin allow-list, schema validation
on both ends, secure defaults on the PC, no arbitrary command execution, path-traversal/symlink defences, two-step power.
Dependency audit: `npm audit` clean after `npm audit fix`; the vulnerable transitive .NET packages in the streaming stack were
pinned to patched versions. **Not done**: external security review, fuzzing, penetration test, code signing.

## Phase 12 — Testing ✅ (with the limits above)

```
Backend/   cd Backend && npm test                                  95 tests (+ coverage table)
Host       cd WindowsHost && dotnet test src/ConnectQuick.WindowsHost.Tests   156 tests
Android    controller-core, JVM: ./gradlew -p Android/controller-core test      89 tests *
Cross-platform   cd Tests && npm test                              40 scenarios (real backend + .NET + Kotlin processes)
Load       cd Backend && npm run loadtest
```
\* In the development sandbox Gradle could not download its plugins, so the Kotlin tests were compiled and run directly with
`kotlinc` + JUnit (same sources, same test classes). `./gradlew test` has therefore **not** been run in this session.

## Backend scaling — measured, decision taken

One backend process on one core carries ~18 000 relayed frames/s at 500 live sessions (p99 87 ms, 0.56 core, 143 MB), at which
point the load generator, not the server, was the limit. A control session uses tens of frames/s and **video does not pass
through the backend**, so a single small instance serves thousands of concurrent sessions. Multi-instance (and Redis) is therefore
**not implemented**: it is not justified yet and would add failure modes. The measured numbers, the thresholds that would trigger
the move, and the design (route by `hostId`, shared registry) are in `Docs/ARCHITECTURE.md`, *Scaling*.

## What exists on disk

```
Backend/     Node/TypeScript relay + registry (real, tested, load-tested)
Protocol/    spec, JSON Schemas (envelope + inner messages), shared test vectors
WindowsHost/ Core (platform-neutral), Streaming (WebRTC), Testing (fakes), HostHarness (headless host for the suite),
             Tests, and the WinForms app (the only part that needs Windows to build)
Android/     controller-core (pure JVM: protocol, crypto, session, link, clients, gestures, wording) and app (Views)
Tests/       cross-platform integration suite
Docs/        this file, ARCHITECTURE, SECURITY, GUIA_DE_PRUEBAS
```

## Next steps, in order of value

1. On a Windows PC with a phone: run `Docs/GUIA_DE_PRUEBAS.md` end to end. This is the first time the Win32 adapters, the WinForms UI,
   the Android app, GDI capture, VP8 and libwebrtc meet real hardware — expect fixes, mostly visual and OS-specific.
2. Run `./gradlew assembleDebug` and `./gradlew test` where Gradle can resolve its plugins; fix anything the sandbox compile check could not see.
3. Stand up coturn (`Docs/ARCHITECTURE.md`) and test streaming across two different networks.
4. An accessibility pass with TalkBack and Narrator, and screenshots for the design system.
5. External security review before any public release.
