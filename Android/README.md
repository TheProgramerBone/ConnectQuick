# ConnectQuick Android Controller

Minimal Android app: scans the WindowsHost's pairing QR and speaks the real wire protocol
(`Protocol/protocol-spec.md`) to the Backend. This is the Phase 2 client-side slice described in
`Docs/STATUS.md` — **pairing only**. There is no remote-desktop view, touchpad/keyboard input,
clipboard, file transfer, or media/power control here yet (Phases 4-9, still not started).

## What's implemented

- Ed25519 device identity, generated on first run and persisted encrypted with an AES-256-GCM key
  held in the Android Keystore (StrongBox-backed when available, else TEE-backed), matching
  `Protocol/protocol-spec.md` §3.
- Transport authentication: `hello.challenge` → sign with the identity key → `hello` → `hello.ack`.
- Reconnect loop with exponential backoff + jitter (capped at 30s, per §6) and a 15s
  `heartbeat.ping` — same shape as `WindowsHost/.../Net/BackendClient.cs`.
- QR scan (CameraX + on-device ML Kit barcode scanning) → `pairing.init` → `pairing.result`, with
  the Host's signature over the decision **verified independently** before reporting success —
  never just trusting the backend's word (`Docs/SECURITY.md`).
- All crypto (`crypto/CryptoUtil.kt`) is unit-tested bit-for-bit against
  `Protocol/test-vectors/crypto-vectors.json` — the same file the Backend and WindowsHost tests use.
- `res/xml/network_security_config.xml` permits plaintext `ws://` — the Backend itself defaults to
  unencrypted `ws://` with an explicit "local development only" warning when no TLS cert is
  configured (`Backend/README.md` "Production notes"); this mirrors that same stance on the
  client rather than a stricter or looser one. Real deployments use `wss://`, which this doesn't
  block.

## Why BouncyCastle instead of lazysodium

`Protocol/protocol-spec.md` §3 suggests lazysodium (a libsodium JNI binding) for Android. This app
uses BouncyCastle instead: Ed25519/X25519 (RFC 8032/7748) are deterministic standards, so any
correct implementation produces identical bytes for identical inputs — `CryptoVectorsTest` proves
that bit-for-bit against the shared vectors. BouncyCastle is pure JVM bytecode, so it (and every
class built on it — `BackendClient`, `PairingController`) runs unmodified in a plain JVM unit
test, with no emulator or physical device required. That's what makes `PairingEndToEndTest`
possible in an environment without an Android emulator. Swapping to lazysodium later, if a
specific device/perf reason comes up, would not change the wire format.

## Project layout

```
app/src/main/kotlin/dev/connectquick/controller/
  crypto/     Ed25519/X25519 wire-format primitives (BouncyCastle-backed)
  identity/   DeviceIdentity interface + Android-Keystore-backed and in-memory implementations
  net/        WebSocket client (envelope framing, reconnect, heartbeat) — role-agnostic
  pairing/    protocol orchestration (PairingController) + QR payload model
  ui/         MainActivity (scan button + status) + ScanActivity (CameraX/ML Kit)
app/src/test/kotlin/dev/connectquick/controller/
  CryptoVectorsTest.kt      crypto vectors, bit-for-bit
  PairingEndToEndTest.kt    real pairing handshake against a spawned real Backend
```

## Building

Requires JDK 17 and the Android SDK (`compileSdk`/`targetSdk` 34, `build-tools;34.0.0`,
`platforms;android-34` — set `sdk.dir` in `local.properties`, gitignored, or export
`ANDROID_HOME`/`ANDROID_SDK_ROOT`).

```sh
cd Android
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

The debug build is restricted to `arm64-v8a` (`defaultConfig.ndk.abiFilters` in
`app/build.gradle.kts`) to keep the sideloadable APK small — covers the large majority of real
Android phones. A release build would need to either drop that filter (universal APK, larger) or
ship an Android App Bundle / per-ABI split APKs instead.

To install on a connected device/emulator: `adb install app/build/outputs/apk/debug/app-debug.apk`,
or sideload the APK file directly and allow "install from unknown sources".

## Testing

```sh
cd Android
./gradlew testDebugUnitTest
```

Runs entirely on the JVM — no emulator/device needed:

- `CryptoVectorsTest`: Kotlin crypto matches `Protocol/test-vectors/crypto-vectors.json`
  bit-for-bit.
- `PairingEndToEndTest`: spawns the real `Backend/src/server.ts` as a subprocess (ephemeral port,
  in-memory DB) and drives the actual `PairingController` production code against it, with the
  Host side simulated using the same vector-verified crypto primitives (the WindowsHost app isn't
  spawnable headlessly in this setup). Nothing here is mocked at the protocol or network layer,
  consistent with how `Backend/test-support` and `WindowsHost/.../PairingEndToEndTests.cs` test
  the other two platforms.

## What's deliberately not here yet

A real device/emulator run of the app was not exercised in this environment (no Android
emulator installed) — verification stops at "the real production pairing code passes a real
handshake against the real backend on the JVM" plus "the APK builds and its manifest/permissions
are correct" (checked with `aapt dump badging`). Session UI, desktop streaming, input injection,
clipboard, files, media, power actions, and a signed release build remain future phases — see
`Docs/STATUS.md`.
