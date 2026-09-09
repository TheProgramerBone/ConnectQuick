# ConnectQuick Backend

Signaling/relay/pairing server. WebSocket-only (see [`Docs/ARCHITECTURE.md`](../Docs/ARCHITECTURE.md)
for why), TypeScript on Node's built-in test runner and `node:sqlite` — no framework, no ORM.

## Requirements

- Node.js **>= 22.5.0** (developed against Node 24). `node:sqlite` and native `.ts` execution
  (via type-stripping, no build step for `erasableSyntaxOnly`-compatible code — this codebase
  avoids enums/namespaces/parameter properties for exactly this reason) both require a recent
  Node; there is no separate compile step to run the server or tests.

## Setup

```sh
npm install
```

## Running

```sh
npm run dev     # node --watch src/server.ts — restarts on file changes
npm start        # node src/server.ts
```

Without `CERT_PATH`/`KEY_PATH` set, the server logs a WARN and serves plaintext `ws://` — fine for
local development, **never** for anything reachable from the Internet (see Production below).

Environment variables (all optional, see [`src/config.ts`](src/config.ts) for defaults):

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8443` | TCP port to listen on |
| `DB_PATH` | `./data/connectquick.sqlite` | SQLite file (`:memory:` is valid, used by tests) |
| `LOG_LEVEL` | `INFO` | `TRACE`\|`DEBUG`\|`INFO`\|`WARN`\|`ERROR` |
| `CERT_PATH` / `KEY_PATH` | unset | PEM cert/key for TLS; both required together |

## Testing

```sh
npm run typecheck      # tsc --noEmit — no build artifacts, just type checking
npm test                 # unit + integration, with coverage
npm run test:unit        # crypto/rateLimiter/repositories/schema/test-vectors only, fast
npm run test:integration # spins up a real server (ephemeral port, in-memory DB) per test
```

Integration tests use [`test-support/`](test-support): `TestClient` is a minimal typed WS client,
`flows.ts` runs the real §6-7 pairing handshake end-to-end (two real WebSocket connections, real
Ed25519 signing, real backend), and `testServer.ts` boots the actual `createApp`/`startServer`
code path — nothing here is mocked at the protocol or network layer.

## What this server does and does not do

Does: transport authentication (challenge/response over each device's own Ed25519 identity key),
pairing (§6-7), device authorization storage, presence broadcast, and session-negotiation relay
(`session.connect`/`session.state`) between an already-paired Host and Controller.

Does not (by design, see [`Docs/SECURITY.md`](../Docs/SECURITY.md)): see or store desktop frames,
input events, clipboard content, or file contents. Those are end-to-end concerns for phases not
yet implemented (see [`Docs/STATUS.md`](../Docs/STATUS.md)) — this server is the pairing/signaling
plane, not a proxy for the actual remote-control traffic.

## Production notes

- Terminate TLS either here (`CERT_PATH`/`KEY_PATH`) or at a reverse proxy in front of this
  process (Caddy, Nginx, a cloud load balancer) — either is fine, but one of them is mandatory.
  Clients only ever speak `wss://`.
- The process is designed to run as a single instance per deployment today: connection state
  (`ConnectionRegistry`, `PendingPairingStore`, `ActiveSessionStore`) is in-memory, so horizontal
  scaling would need a shared presence/session layer (e.g. Redis pub/sub) that does not exist yet.
  A single small instance comfortably handles signaling traffic for a large number of
  devices — this is not the desktop-video data path.
- `DB_PATH` should point at a persistent volume; the SQLite file only holds host/device public
  keys, names, and pairing bookkeeping (see §"Privacidad" in the protocol spec) — it is small and
  fine to back up/restore wholesale.
