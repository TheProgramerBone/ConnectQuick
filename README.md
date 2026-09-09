# ConnectQuick

Secure remote control of a Windows PC from an Android phone, over the Internet, with no port
forwarding, no static IP, and no router configuration — pairing by QR code.

**Current state**: the pairing/signaling backend and protocol are implemented and tested for
real. The Windows Host and Android Controller apps do not exist yet. See
[`Docs/STATUS.md`](Docs/STATUS.md) for the exact phase-by-phase breakdown before assuming
anything here works end-to-end today.

## Layout

```
Backend/     Node.js/TypeScript signaling + relay + device-registry server (real, tested)
Protocol/    The wire protocol both platforms must speak: spec, JSON Schemas, test vectors
WindowsHost/ Windows tray app (Host) — not started yet
Android/     Android app (Controller) — not started yet
Docs/        Architecture, security, and status documentation
Tests/       Reserved for cross-platform integration tests once there's more than one platform
```

## Start here

- [`Docs/ARCHITECTURE.md`](Docs/ARCHITECTURE.md) — why WebSocket for signaling, why WebRTC for
  the (not-yet-built) desktop stream, why no port forwarding is needed.
- [`Docs/SECURITY.md`](Docs/SECURITY.md) — device identity, pairing, what the relay can and
  cannot see, what's hardened today versus still open.
- [`Docs/STATUS.md`](Docs/STATUS.md) — what's real, what's a placeholder directory, what's next.
- [`Protocol/protocol-spec.md`](Protocol/protocol-spec.md) — the message catalog and wire format.
- [`Backend/README.md`](Backend/README.md) — running and testing the backend locally.

## Quickstart (backend only — the only runnable piece today)

```sh
cd Backend
npm install
npm test          # 57 tests, real WebSocket server + real Ed25519 crypto, no mocks
npm run dev        # ws://localhost:8443 (plaintext — local dev only, see Backend/README.md)
```
