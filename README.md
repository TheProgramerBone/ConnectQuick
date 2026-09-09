# ConnectQuick

Secure remote control of a Windows PC from an Android phone, over the Internet, with no port
forwarding, no static IP, and no router configuration — pairing by QR code.

**Current state**: the pairing/signaling backend and protocol are implemented and tested for
real, and both client apps now pair with it end-to-end — the Windows Host (QR generation, the
"¿Autorizar?" dialog, signed authorization, DPAPI-protected identity) and the Android Controller
(QR scanning, independent signature verification, Keystore-protected identity). The two apps
haven't yet been run against *each other* in this environment (no Android device/emulator
available) — each was proven against the real backend with the other side simulated. See
[`Docs/STATUS.md`](Docs/STATUS.md) for the exact phase-by-phase breakdown before assuming
anything here works end-to-end today.

## Layout

```
Backend/     Node.js/TypeScript signaling + relay + device-registry server (real, tested)
Protocol/    The wire protocol both platforms must speak: spec, JSON Schemas, test vectors
WindowsHost/ Windows tray app (Host) — identity + pairing implemented and tested; no
             desktop capture/input/clipboard/files/media/power yet
Android/     Android app (Controller) — identity + pairing implemented and tested, builds a
             real APK; same missing-phases list as WindowsHost
Docs/        Architecture, security, and status documentation
Tests/       Reserved for cross-platform integration tests once there's more than one platform
```

## Start here

- [`Docs/ARCHITECTURE.md`](Docs/ARCHITECTURE.md) — why WebSocket for signaling, why WebRTC for
  the (not-yet-built) desktop stream, why no port forwarding is needed.
- [`Docs/SECURITY.md`](Docs/SECURITY.md) — device identity, pairing, what the relay can and
  cannot see, what's hardened today versus still open.
- [`Docs/STATUS.md`](Docs/STATUS.md) — what's real, what's a placeholder directory, what's next.
- [`Docs/GUIA_DE_PRUEBAS.md`](Docs/GUIA_DE_PRUEBAS.md) — step-by-step (in Spanish) to pair a real
  Windows PC with a real Android phone over your LAN.
- [`Protocol/protocol-spec.md`](Protocol/protocol-spec.md) — the message catalog and wire format.
- [`Backend/README.md`](Backend/README.md) — running and testing the backend locally.
- [`WindowsHost/README.md`](WindowsHost/README.md) — running and testing the Windows tray app.
- [`Android/README.md`](Android/README.md) — building, testing, and installing the Android app.

## Quickstart (backend + both client apps — the three runnable pieces today)

```sh
cd Backend
npm install
npm test          # 57 tests, real WebSocket server + real Ed25519 crypto, no mocks
npm run dev        # ws://localhost:8443 (plaintext — local dev only, see Backend/README.md)
```

```sh
cd WindowsHost
dotnet test src/ConnectQuick.WindowsHost.Tests/ConnectQuick.WindowsHost.Tests.csproj
dotnet run --project src/ConnectQuick.WindowsHost   # look for the tray icon, right-click it
```

```sh
cd Android
./gradlew testDebugUnitTest   # crypto vectors + a real pairing E2E test, no device needed
./gradlew assembleDebug       # -> app/build/outputs/apk/debug/app-debug.apk
```
