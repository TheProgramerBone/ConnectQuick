# ConnectQuick WindowsHost

Tray app for Windows: generates the pairing QR, shows the "¿Autorizar?" dialog, and speaks the
real wire protocol (`Protocol/protocol-spec.md`) to the Backend. This is the Phase 2 client-side
slice described in `Docs/STATUS.md` — **pairing only**. There is no desktop capture, input
injection, clipboard, file transfer, or power control here yet (Phases 4-9, still not started).

## What's implemented

- Ed25519 device identity, generated on first run and persisted encrypted with DPAPI
  (current-user scope) at `%AppData%\ConnectQuick\host_identity.bin` — never leaves the machine,
  matches `Protocol/protocol-spec.md` §3.
- Transport authentication: `hello.challenge` → sign with the identity key → `hello` → `hello.ack`.
- Reconnect loop with exponential backoff + jitter (capped at 30s, per §6) and a 15s
  `heartbeat.ping`.
- Full pairing flow (§4/§7): "Vincular nuevo dispositivo" → `pairing.create` → QR code (QRCoder)
  → `pairing.incoming` → "¿Autorizar?" dialog with the 6-digit SAS code → signed
  `pairing.decision` → local record of authorized devices (`%AppData%\ConnectQuick\authorized_devices.json`)
  for the tray's "Dispositivos vinculados" / revoke menu.
- All crypto (`Crypto/CryptoUtil.cs`) is unit-tested bit-for-bit against
  `Protocol/test-vectors/crypto-vectors.json` — the same file the Backend's TypeScript tests use.

## Project layout

```
src/
  ConnectQuick.WindowsHost/        WinForms tray app (net8.0-windows)
    Crypto/       Ed25519/X25519 wire-format primitives (NSec/libsodium-backed)
    Identity/     device identity generation + DPAPI-encrypted storage
    Net/          WebSocket client (envelope framing, reconnect, heartbeat)
    Pairing/      protocol orchestration (PairingCoordinator) + local device store
    Ui/           tray icon, QR window, authorize dialog
  ConnectQuick.WindowsHost.Tests/  xUnit: crypto vectors + a real end-to-end pairing test
                                   that spawns the actual Backend as a subprocess
```

## Running

Requires the .NET 8 SDK and a running Backend (see `Backend/README.md`):

```sh
cd Backend && npm run dev   # ws://localhost:8443, plaintext, local dev only
```

```sh
cd WindowsHost
dotnet run --project src/ConnectQuick.WindowsHost
```

Environment variables (optional):

| Variable                    | Default                  | Meaning                          |
|------------------------------|---------------------------|-----------------------------------|
| `CONNECTQUICK_RELAY`         | `ws://localhost:8443`     | Backend WebSocket URL             |
| `CONNECTQUICK_HOST_NAME`     | machine name               | Display name shown in the QR/dialog |

The app has no window of its own on launch — look for the ConnectQuick icon in the system tray.
Right-click it for "Vincular nuevo dispositivo".

## Testing

```sh
dotnet test src/ConnectQuick.WindowsHost.Tests/ConnectQuick.WindowsHost.Tests.csproj
```

`PairingEndToEndTests` spawns the real `Backend/src/server.ts` as a subprocess (ephemeral port,
in-memory DB) and runs the actual `PairingCoordinator` production code against it, with a Controller
side simulated using the same vector-verified crypto primitives (the Android app doesn't exist yet
— see `Docs/STATUS.md`). Nothing here is mocked at the protocol or network layer, consistent with
how `Backend/test-support` tests the TypeScript side.

## What's deliberately not here yet

Device management beyond a local mirror (there's no `devices.list` protocol message yet — see
`Protocol/protocol-spec.md` §7), desktop streaming, input injection, clipboard, files, media,
power actions, and app packaging/installer/code-signing. See `Docs/STATUS.md` for the phase
breakdown and `Docs/SECURITY.md` for what's hardened versus still open.
