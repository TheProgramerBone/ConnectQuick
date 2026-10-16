# ConnectQuick

Secure remote control of a Windows PC from an Android phone, over the Internet, with no port
forwarding, no static IP, and no router configuration — pairing by QR code.

**Current state** (details and the honest list of what has *not* been run in [`Docs/STATUS.md`](Docs/STATUS.md)):
the whole stack is implemented — pairing, an end-to-end encrypted session layer, mouse and keyboard, live screen over
WebRTC, clipboard, media keys, files, and confirmed power control — with a Windows tray app and an Android app, both
with a "Liquid Crystal" glass UI. The logic is covered by ~380 automated tests, including a suite that runs the real
backend, the real Windows-host runtime and the real Android controller core as separate processes. **The Win32
adapters, the WinForms UI and the Android app have never been run on real hardware** (the development environment has no
Windows and no phone): they compile and their logic is tested, and the first real run is the first integration of those layers —
use [`Docs/GUIA_DE_PRUEBAS.md`](Docs/GUIA_DE_PRUEBAS.md).

## Layout

```
Backend/     Node.js/TypeScript relay + registry (E2E session routing, ICE/TURN credentials, limits) — load-tested
Protocol/    Wire protocol: spec, JSON Schemas (envelope + inner messages), shared cross-platform test vectors
WindowsHost/ Windows tray app (Host): platform-neutral Core, WebRTC streaming, WinForms UI, headless harness, tests
Android/     Android app (Controller): pure-JVM controller-core (tested anywhere) + Views app
Tests/       Cross-platform suite: real backend + .NET Host process + Kotlin controller process
Docs/        Architecture, security, status, test guide
```

## Start here

- [`Docs/ARCHITECTURE.md`](Docs/ARCHITECTURE.md) — why WebSocket for signaling, why video uses
  WebRTC while control rides the encrypted relay session, scaling measurements, how to run TURN.
- [`Docs/SECURITY.md`](Docs/SECURITY.md) — device identity, pairing, what the relay can and
  cannot see, what's hardened today versus still open.
- [`Docs/STATUS.md`](Docs/STATUS.md) — what is real, what has not been run on real hardware, what's next.
- [`Docs/GUIA_DE_PRUEBAS.md`](Docs/GUIA_DE_PRUEBAS.md) — step-by-step (in Spanish) to pair a real
  Windows PC with a real Android phone over your LAN.
- [`Protocol/protocol-spec.md`](Protocol/protocol-spec.md) — the message catalog and wire format.
- [`Backend/README.md`](Backend/README.md) — running and testing the backend locally.
- [`WindowsHost/README.md`](WindowsHost/README.md) — running and testing the Windows tray app.
- [`Android/README.md`](Android/README.md) — building, testing, and installing the Android app.

## Quickstart

```sh
cd Backend && npm install && npm test && npm run dev            # relay on ws://localhost:8443 (plaintext: local dev only)
cd WindowsHost && dotnet test src/ConnectQuick.WindowsHost.Tests  # 156 tests (any OS)
cd Android && ./gradlew -p controller-core test                    # 89 JVM tests (any OS)
cd Tests && npm install && npm test                                # 40 cross-platform scenarios (see Tests/README.md)
```

On Windows: `dotnet run --project WindowsHost/src/ConnectQuick.WindowsHost` (tray app). Android: `cd Android && ./gradlew assembleDebug`.
Step-by-step manual test with a real PC and phone: [`Docs/GUIA_DE_PRUEBAS.md`](Docs/GUIA_DE_PRUEBAS.md).
