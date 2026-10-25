# ConnectQuick — project memory for Claude

Read this first. It is the hand-off from the session that built the product end to end (Oct 2026). `Docs/STATUS.md` is the authoritative,
phase-by-phase truth about what is verified; this file adds the context needed to continue working without re-deriving it.

## What the product is

Secure remote control of a **Windows PC from an Android phone** over the Internet, no port forwarding: pairing by QR, then mouse/keyboard,
live screen (WebRTC), clipboard, media keys, file transfer, and confirmed power control. Both devices dial out to a **relay backend**; the relay
only routes **opaque end-to-end-encrypted frames**. Owner speaks Spanish (user-facing UI and `Docs/GUIA_DE_PRUEBAS.md`/`COMPILAR_Y_CONFIRMAR.md` are Spanish; code, protocol docs and this file are English/mixed).

## Repository map

```
Backend/      Node 22 + TypeScript (native type stripping, no build step), node:sqlite, ws, ajv. Relay + registry.
Protocol/     protocol-spec.md (Spanish), schemas/{envelope,payloads,session-messages}.schema.json, test-vectors/ (shared by all 3 platforms)
WindowsHost/  .NET 8. src/: Core (platform-neutral logic), Streaming (GDI→scaler→VP8→SIPSorcery), Testing (fakes), HostHarness (headless host),
              Tests (xUnit), ConnectQuick.WindowsHost (WinForms app — the ONLY project needing Windows to build)
Android/      controller-core (pure JVM Kotlin, Gradle composite build) + app (framework Views; CameraX/ML Kit scan; libwebrtc view)
Tests/        Node test runner driving real processes: backend + .NET HostHarness + Kotlin ControllerHarness
Scripts/      build-windows.ps1, build-android.ps1, start-backend.ps1 (PowerShell, for the owner's Windows PC; never executed here)
Docs/         STATUS, ARCHITECTURE, SECURITY, GUIA_DE_PRUEBAS, COMPILAR_Y_CONFIRMAR
```

## Architecture in brief

- **Planes**: signalling/registry (WebSocket to backend) · control session (same socket, `session.data` frames, E2E) · video (WebRTC direct/TURN; backend never carries video).
- **Identity**: Ed25519 per device; `deviceId = base64url(SHA-256("connectquick:device:"‖pub))[0:22]`. Windows seed in DPAPI; Android seed wrapped by Keystore AES-GCM key.
- **E2E session** (spec §7): ephemeral X25519 → HKDF-SHA256 (salt = transcript hash) → per-direction AES-256-GCM keys; nonce = 4 zero bytes ‖ uint64-BE seq; AAD `cq1|sessionId|dir|seq`;
  strictly increasing seq; Controller signs `session.connect`, Host signs `session.accept` over both ephemeral keys + permissions; key confirmation = first encrypted frame (`ctl.caps`).
  Inner messages (≤40 000 B) are validated at both endpoints against the shared schema (a small JSON-Schema-subset validator exists in C# and Kotlin; ajv in TS).
- **The PC is the policy point**: effective permissions = granted at pairing ∩ switches enabled on the PC (`HostSettings`). Defaults: power = lock only, pairing pre-selects everything except power, clipboard auto-push off.
  `HostSession` pipeline: decrypt → validate → permission → rate limit → dispatch; 25 violations end the session; session end releases all held keys/buttons, cancels transfers, stops video.
- **Android**: `RemoteLink` (auto-reconnect state machine with backoff, REVOKED/FAILED/HOST_OFFLINE phases) + `ControllerHub` (creates feature clients per verified session) ; `LinkTexts`/`StreamTexts` hold all state wording (unit-tested).
  `ConnectQuickApp` owns the single hub; link runs only while the app is visible (+60 s grace). Release builds are TLS-only (`src/debug/res/xml/network_security_config.xml` allows `ws://` in debug).
- **Windows**: `HostRuntime` is the composition root shared by the WinForms app and `HostHarness`; Win32 adapters sit behind interfaces (`IInputInjector`, `IClipboard`, `IMediaKeys`, `IPowerExecutor`, `IScreenCapturer`).
- **Different networks**: control always rides the backend; video needs TURN when there is no direct path. The backend embeds a
  TURN server (`Backend/src/turn/`, enabled by `TURN_EMBEDDED_PORT`) that `build-all.ps1 -Funnel` publishes on Funnel port 10000 (TLS)
  — phones get the `turns:` URL, hosts get none (relay is on the host's machine). Unverified against real libwebrtc/SIPSorcery.
- **Own cloud server** (owner's choice, Oct 2026: Google Cloud free tier, no Tailscale): `Deploy/gcp/setup.sh` (backend + Caddy +
  coturn on one e2-micro, `<ip>.sslip.io`), `Scripts/usar-servidor.ps1` repoints the PC; guide `Docs/SERVIDOR_GRATIS_GOOGLE_CLOUD.md`.
  **Working in production for the owner since 24 Oct 2026** (server `wss://35-209-191-155.sslip.io`; owner confirmed phone on mobile data ↔ PC works).
  Server updates: `sudo bash /opt/connectquick/Deploy/gcp/update.sh` on the VM after pushing to `main`.
- **Scaling decision** (measured, `Backend/scripts/loadtest.ts`): single instance; ~18k relayed frames/s at 500 sessions = 0.56 core/143 MB. No Redis/multi-instance. Design for later (route by hostId, Postgres) is in `Docs/ARCHITECTURE.md`.

## Session of 25 Oct 2026 (read this before anything else)

Done and pushed to `main`: A1 (fast dead-link detection + network-change reconnect on the PC), A3 (video self-heals), A2 (multi-relay: cloud + PC's own LAN relay, `registry.sync`, QR/`ctl.caps` `relays`),
B1 (several PCs per phone, `HostBook`), B5 (presentation screen), C1 (Windows CI). Details and verification status per item: `Docs/STATUS.md` ("Changes of 25 Oct 2026") and `Docs/PENDIENTES.md`.
**The CI is now the compile/test oracle for C# and Kotlin** (the sandbox still has neither `dotnet` nor Gradle plugin access). Use `mcp__github__actions_list` / `get_job_logs` to read runs; check them before building on top.
Backend is now 106 tests. Not done and why: A4 (touches the live GCP server, CI cannot check it), A5/A6/A7 (protocol changes on 3 platforms — do one at a time once CI is green; A7 session resume is the most valuable),
B2/B3/B4 (need real hardware), C2–C5 (need a device / owner decisions; old `claude/*` branches must be deleted by the owner on GitHub).

## State at hand-off (be honest about it)

Automated and green (as of the first session; see the 25 Oct section for the current counts): Backend 104 tests · .NET 156 · Kotlin controller-core 110 (JVM) · cross-platform `Tests/` 40 scenarios · `tsc` clean · `npm audit` 0.
**Never run on real hardware**: Win32 adapters (SendInput, clipboard, media keys, power, GDI capture, DPAPI), the WinForms UI (only compile-checked against reference assemblies), the whole Android app
(compile-checked against android-all + libwebrtc API, except `ScanActivity`, which needs CameraX/ML Kit AARs), real VP8 encode/libwebrtc decode, TURN, real NAT traversal.
`./gradlew assembleDebug` and `./gradlew test` were **never run** in the sandbox (Gradle plugin resolution was blocked). Expect first-build errors on the owner's PC. The owner started running
`Scripts/build-android.ps1 -Test` at the end of the session (output seen so far was normal Gradle progress; result unknown). `Docs/COMPILAR_Y_CONFIRMAR.md` §5 is the checklist of what the first real run must confirm.

**Branch policy (owner's standing instruction, reconfirmed 25 Oct 2026): ALWAYS push everything to `main`** — commit and push to `main`, no feature branches or PRs unless the owner asks, so the IDE (IntelliJ) never needs a branch switch.
This holds even when cloud tooling assigns a `claude/*` branch: work there if needed, then merge/fast-forward into `main` and push `main` in the same turn, without asking again.
History: the first session developed on `claude/intelligent-newton-upxevj`; at the end it was fast-forwarded into `main`.

## How to verify things (commands)

```sh
cd Backend && npm install && npm test && npm run typecheck && npm run loadtest
cd WindowsHost && dotnet test src/ConnectQuick.WindowsHost.Tests        # works on Linux; WinForms project does not build off Windows
cd Android && ./gradlew -p controller-core test                         # JVM only
cd Tests && npm install && CQ_CONTROLLER_CP="<classpath>" npm test      # see Tests/README.md; CQ_HOST_HARNESS optional
```
Run suites **one at a time**: the multi-process scenarios are timing-sensitive on a loaded machine (several flaky tests were fixed by waiting on state instead of sleeping — keep doing that, never weaken assertions).

### Sandbox tricks used when Gradle/Windows were unavailable (not in the repo; recreate if needed)
- Compile + run the Kotlin core with `kotlinc` (embeddable compiler 1.9.24 + serialization plugin jar) directly, JUnit via `JUnitCore`, copying `Protocol/schemas/*.json` onto the classpath.
- Compile-check the WinForms project as a plain `net8.0` library referencing the WindowsDesktop **reference assemblies** from the NuGet cache (plus a stub `ApplicationConfiguration`).
- Compile-check the Android app with `kotlinc` against `android-all-14-robolectric` and the `io.getstream:stream-webrtc-android:1.3.8` AAR's `classes.jar` (stub `ScanActivity`).
- Maven Central was reachable through the proxy (use retry loops; transient 404s/429s occurred).
- Oct 2026: `gradle -p controller-core test` (system Gradle 8.14) works in the cloud sandbox once `Backend/` has `npm install`
  (the pairing E2E test starts the real backend). The Kotlin compiler jars it caches under `~/.gradle/caches` can then compile-check
  the app with `K2JVMCompiler` against `org.robolectric:android-all:14-robolectric-10818077` + the webrtc AAR `classes.jar`.
  `dotnet` is NOT available there (download host blocked), so C# changes cannot be compiled in that sandbox.

## Conventions and decisions worth keeping

- No generic command execution anywhere; every capability is its own typed, schema-validated message and permission. `apps` permission is reserved and **not implemented** (never offered).
- UI must never pretend: a feature is enabled only with a verified session **and** the permission, and unavailable tiles state the reason. Media results say "sent", never "playing". A locked screen/UAC is reported, not shown as black; UAC is never bypassed.
- Never use file names from the peer as paths (`FileNames.safe` on Android, `FileSafety` on Windows). Clipboard is text-only with SHA-256; auto-push excludes items Windows marks as excluded from history.
- Do not log secrets, clipboard text, file contents, SDP. No secrets/keys in the repo.
- Node 22 type-stripping: avoid enums/namespaces/parameter properties in backend TS.
- Codec gotcha: the SIPSorcery/libvpx wrapper enum numbering differs (VP8 = 7 vs 8); there is a shim and a fake encoder for tests. Real libvpx on Windows is unverified.
- Windows `INPUT` struct size and key scancode maps are unit-tested; real injection into elevated windows is blocked by UIPI by design.
- Don't commit/push unless the owner asks (they asked explicitly at the end of the session; later pushes were also requested).

## Likely next work, in order

1. Get the owner's first real build/run results (Gradle `assembleDebug`, `dotnet publish`, WinForms run) and fix what breaks — mostly visual/OS-specific.
2. Walk `Docs/GUIA_DE_PRUEBAS.md` §B on real hardware: input, clipboard, files, power, screen, revoke, reconnect.
3. Stand up coturn (`Docs/ARCHITECTURE.md`, *Running TURN*) and test video across two networks.
4. Accessibility pass (TalkBack/Narrator), screenshots for the design system, DPI/font-scale fixes.
5. Release hardening: TLS deployment, signing, installer, external security review/fuzzing of the custom protocol, optional app lock.
6. Out of scope so far: multi-monitor, desktop audio, multiple PCs per phone, background Android service, backend multi-instance.
