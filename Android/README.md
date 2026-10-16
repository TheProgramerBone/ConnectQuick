# ConnectQuick Android Controller

The phone side of ConnectQuick: pair with a Windows PC by QR, then control it — touchpad, keyboard,
live screen, clipboard, media keys, files, and (explicitly confirmed) power — over an end-to-end
encrypted session. Everything the app shows is backed by a working function; anything the PC did not grant is
shown as unavailable *with the reason*.

> **Verification status — read this first.** The protocol, crypto, connection manager, feature clients, gesture engine and all
> user-facing wording live in `controller-core` and are unit-tested (89 JVM tests) and exercised against a real backend and a
> real .NET Host process (`Tests/`). The Android **app** layer (Views, Keystore identity, camera scan, libwebrtc rendering,
> MediaStore adapters) is compiled against the Android 14 framework API and the libwebrtc API, **but has never been installed or run on
> a device or emulator**, and in the development sandbox Gradle could not resolve its plugins, so `./gradlew assembleDebug` was
> **not** run in this session (the CameraX/ML Kit `ScanActivity` could not even be compile-checked). Treat the first real build and run as
> the first integration of that layer. See `Docs/STATUS.md`.

## Layout

```
controller-core/   pure JVM Kotlin (builds and tests on any machine with a JDK; included in the app via a Gradle composite build)
  crypto/ identity/ net/ pairing/   Ed25519/X25519 (BouncyCastle), device identity, WebSocket client, QR pairing + SAS
  protocol/                         inner-message validation against Protocol/schemas/session-messages.schema.json
  session/                          SessionCrypto, ControllerSession (E2E handshake), RemoteLink (auto-reconnect state machine),
                                    ControllerHub (feature clients per session), LinkTexts (state wording)
  input/ clipboard/ media/ power/ files/ stream/   feature clients, TouchpadEngine, StreamController (+ RtcEngine abstraction)
app/               Android UI and the platform adapters
  ConnectQuickApp           holds the one ControllerHub; link runs while the app is visible (+60 s grace); network-change callback
  ui/Design.kt              "Liquid Crystal" design system built from framework drawables (no assets, no extra libraries)
  ui/MainActivity           real link state + a tile per capability that says why it is unavailable
  ui/PairActivity           scan → comparison code → authorized
  ui/Touchpad|Screen|Clipboard|Media|Files|PowerActivity
  stream/WebRtcEngine       libwebrtc (receive-only) behind the RtcEngine contract
  files/AndroidFiles        picker source; Downloads/ConnectQuick sink via MediaStore (API 29+) or app folder (older)
  identity/KeystoreDeviceIdentity   Ed25519 seed wrapped by an AES-256-GCM Android Keystore key (StrongBox if present)
```

## Build and test

Requires JDK 17 and the Android SDK (`compileSdk`/`targetSdk` 34; set `sdk.dir` in `local.properties` or `ANDROID_HOME`).

```sh
cd Android
./gradlew -p controller-core test     # 89 JVM tests: vectors, handshake, gestures, clients, wording, spawns the real Backend
./gradlew assembleDebug               # -> app/build/outputs/apk/debug/app-debug.apk (arm64-v8a)
adb install app/build/outputs/apk/debug/app-debug.apk
```

- **Debug builds** may use plain `ws://` (local development). **Release builds only allow `wss://`**
  (`app/src/debug/res/xml/network_security_config.xml` overrides the stricter default in `src/main`).
- Dependencies are the minimum for what the app does: CameraX + ML Kit (QR scan), `kotlinx-coroutines-android`,
  `io.getstream:stream-webrtc-android` (the `org.webrtc` API for the live screen) and AndroidX appcompat/material only because the scan screen needs them.
  All other screens use framework views.
- The app is Spanish-only for now; user-facing strings are in code (`LinkTexts`, `StreamTexts`, activities), not `strings.xml`.

## Using it

1. On the PC: ConnectQuick → *Vincular un teléfono* (shows a QR).
2. Phone: *Vincular un PC* → scan → compare the 6-digit code with the PC → *Sí, autorizar* on the PC.
3. Home shows **Conectado a «PC»** only when a verified encrypted session exists. If the PC is off it says so and connects by itself when it appears;
   if the network changes it reconnects; if the PC revoked the phone it says *Acceso revocado* and offers to pair again.
4. The live screen needs, on a different network from the PC's, a TURN server on the backend (`Docs/ARCHITECTURE.md`, *Running TURN*); on the same Wi-Fi it works without one.

## Limits worth knowing

- Only the PC's primary monitor; no audio. A UAC prompt / lock screen is reported as such (Windows does not allow capturing it).
- The connection lives while the app is on screen (+60 s). There is no background service by design.
- Files from the PC land in *Downloads/ConnectQuick* (Android 10+) or the app's own Downloads folder (older). Interrupted receives resume only on Android 9 and below.
- No app lock/biometric gate yet.
