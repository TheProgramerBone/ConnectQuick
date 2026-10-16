# ConnectQuick WindowsHost

The PC side of ConnectQuick: a tray app that pairs phones by QR and then lets an authorised phone
control this PC over an end-to-end encrypted session — mouse, keyboard, live screen, clipboard, media keys, files, and (explicitly
confirmed) power — with every capability individually switchable here, and a "disconnect phone now" button.

> **Verification status — read this first.** All behaviour lives in `ConnectQuick.WindowsHost.Core` and is tested (156 xUnit tests) and driven
> end to end by the cross-platform suite (`Tests/`). The thin **Win32 adapters** (`SendInput`, clipboard, media keys, power, GDI capture, DPAPI) and the
> **WinForms UI** have **never been run on Windows** in this development environment (it has none). The UI compiles against the WinForms
> reference assemblies; the adapters' struct layouts and key maps are unit-tested. The first run on a real PC is the first integration of those layers —
> follow `Docs/GUIA_DE_PRUEBAS.md`. See `Docs/STATUS.md`.

## Layout

```
src/
  ConnectQuick.WindowsHost.Core       platform-neutral logic (builds and tests on any OS)
     Crypto/ Identity/ Net/ Pairing/ Protocol/ Session/   handshake, E2E channel, validation, session manager
     Input/ Clipboard/ Media/ Files/ Power/ Streaming/    one feature each; Win32 adapters behind interfaces
     Settings/ Diagnostics/ Runtime/                      switches, log (no secrets), HostRuntime composition root
  ConnectQuick.WindowsHost.Streaming  GDI capture → scaler → VP8 → SIPSorcery WebRTC peer
  ConnectQuick.WindowsHost            WinForms app (net8.0-windows): the only project that needs Windows to build
     Ui/Theme, GlassControls          "Liquid Crystal" design system (glass surfaces, state shapes, High Contrast fallback)
     Ui/MainWindow, Pages             Status · Devices · Permissions & settings · Files · Log
     Ui/Dialogs                       authorize (per-permission switches), per-connection approval, QR with countdown
     Ui/TrayApplicationContext        tray icon (shape = state), menu, notifications, "disconnect phone"
  ConnectQuick.WindowsHost.Testing    in-memory adapters for tests
  ConnectQuick.WindowsHost.HostHarness  headless host (same HostRuntime) driven over stdin/stdout by Tests/
  ConnectQuick.WindowsHost.Tests      xUnit
```

## Run (Windows)

Requires the .NET 8 SDK and a reachable Backend (`Backend/README.md`).

```powershell
cd WindowsHost
$env:CONNECTQUICK_RELAY = "ws://<IP-PC>:8443"      # phone must reach it; localhost only works for the PC itself
dotnet run --project src/ConnectQuick.WindowsHost
```

| Variable | Default | Meaning |
|---|---|---|
| `CONNECTQUICK_RELAY` | `ws://localhost:8443` | Backend WebSocket URL (`wss://` in production) |
| `CONNECTQUICK_HOST_NAME` | machine name | Name shown on phones and in dialogs |

Data lives under `%APPDATA%\ConnectQuick`: `host_identity.bin` (DPAPI-protected key), `authorized_devices.json`, `settings.json`, `logs\connectquick.log`.
Received files go to `Downloads\ConnectQuick`; files the phone may fetch are read from `~\ConnectQuick\Outbox` (both changeable in the app).

## Defaults (conservative)

Power: **lock only**. Pairing pre-selects everything except power. Clipboard auto-push: off. Every switch applies to the next message — no restart, no re-pairing.
UAC prompts and the lock screen cannot be captured or controlled; ConnectQuick does not try to bypass them.

## Test

```sh
cd WindowsHost
dotnet test src/ConnectQuick.WindowsHost.Tests          # 156 tests; spawns the real Backend for the pairing test
cd ../Tests && npm test                                  # cross-platform: real backend + this host (headless) + the Kotlin controller
```

On Linux/macOS the WinForms project cannot be built (no Windows Desktop SDK); everything else builds and tests.
