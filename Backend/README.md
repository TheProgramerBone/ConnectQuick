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
| `DB_PATH` | `./data/connectquick.sqlite` | SQLite file (`:memory:` is valid, used by tests and the load test) |
| `LOG_LEVEL` | `INFO` | `TRACE`\|`DEBUG`\|`INFO`\|`WARN`\|`ERROR` |
| `CERT_PATH` / `KEY_PATH` | unset | PEM cert/key for TLS; both required together |
| `TRUST_PROXY` | off | `1` = take the client IP from `X-Forwarded-For` (only behind a proxy you control) |
| `ALLOWED_ORIGINS` | empty | Comma list of browser `Origin`s allowed to connect (non-browser clients send none) |
| `MAX_CONNECTIONS` / `MAX_CONNECTIONS_PER_IP` | `5000` / `50` | Concurrent WebSocket limits |
| `PAIRING_CREATE_PER_MINUTE_PER_HOST` / `PAIRING_INIT_PER_MINUTE_PER_IP` | `10` / `5` | Pairing rate limits (raise the second for many users behind one NAT) |
| `SESSION_FRAMES_PER_SECOND` / `SESSION_BYTES_PER_SECOND` | `400` / `8388608` | Per-session relay budget |
| `SESSION_ACCEPT_TIMEOUT_MS` / `SESSION_IDLE_TIMEOUT_MS` / `SESSION_MAX_DURATION_MS` | `30000` / `60000` / `43200000` | Session lifetimes |
| `SESSION_MAX_BUFFERED_BYTES` | `4194304` | Queue limit per receiver before the session is ended (`peer_too_slow`) |
| `RAW_FRAMES_PER_SECOND` | `600` | Per-connection raw-frame limit |
| `STUN_URLS` | Google public STUN | Comma list handed to clients for WebRTC |
| `TURN_URLS` / `TURN_SECRET` / `TURN_TTL_SECONDS` | unset / unset / `3600` | coturn relay + shared secret for ephemeral credentials (URLs without a secret are refused) |
| `TURN_EMBEDDED_PORT` / `TURN_RELAY_IP` | unset / first LAN IPv4 | runs the built-in TURN server (TCP) on this port; `TURN_URLS` is then the public address phones dial (e.g. `turns:pc.tailnet.ts.net:10000?transport=tcp`), the secret is generated if unset, hosts get no TURN. Peers are limited to this machine's own addresses |
| `SHUTDOWN_GRACE_MS` | `5000` | How long `SIGTERM` waits after telling clients to reconnect |
| `METRICS_TOKEN` | unset | Bearer token required for `/metrics` (unset = not exposed) |

Endpoints besides the WebSocket: `GET /healthz` (liveness), `GET /readyz` (readiness; false while draining), `GET /metrics`.

## Testing

```sh
npm run typecheck      # tsc --noEmit — no build artifacts, just type checking
npm test                 # unit + integration, with coverage
npm run test:unit        # crypto/rateLimiter/repositories/schema/vectors/config only, fast
npm run loadtest         # N paired sessions pushing encrypted frames through a real server process; prints latency/CPU/RSS
npm run test:integration # spins up a real server (ephemeral port, in-memory DB) per test
```

Integration tests use [`test-support/`](test-support): `TestClient` is a minimal typed WS client,
`flows.ts` runs the real §6-7 pairing handshake end-to-end (two real WebSocket connections, real
Ed25519 signing, real backend), and `testServer.ts` boots the actual `createApp`/`startServer`
code path — nothing here is mocked at the protocol or network layer.

## What this server does and does not do

Does: transport authentication (challenge/response over each device's own Ed25519 identity), pairing, device
authorization storage, presence, the **E2E session relay** (`session.connect/accept/data/end`: verifies the
Controller's signature and authorization, routes opaque encrypted frames, enforces budgets and timeouts), and ICE
configuration (STUN + ephemeral TURN credentials).

Does not (by design, see [`Docs/SECURITY.md`](../Docs/SECURITY.md)): read, store or validate the content of a session — mouse,
keyboard, clipboard, files, power commands and WebRTC signalling are encrypted between the PC and the phone with keys the
server never has — and it never carries desktop video.

## Production notes

- Terminate TLS either here (`CERT_PATH`/`KEY_PATH`) or at a reverse proxy in front of this
  process (Caddy, Nginx, a cloud load balancer) — either is fine, but one of them is mandatory.
  Clients only ever speak `wss://`.
- Run **one instance** per deployment. Connection state is in memory by design. Measured on a 4-vCPU VM: ~18 000 relayed
  frames/s at 500 live sessions used 0.56 of one core and 143 MB (see `Docs/ARCHITECTURE.md`, *Scaling*, for the table, the
  triggers for going multi-instance and the design). Restarts are survived by clients automatically; `SIGTERM` drains gracefully.
- For streaming across different networks either set `TURN_EMBEDDED_PORT` (backend on the PC itself, published with Tailscale Funnel, `Docs/ACCESO_REMOTO.md`) or run coturn and set `TURN_URLS`/`TURN_SECRET` (`Docs/ARCHITECTURE.md`, *Running TURN*).
- `DB_PATH` should point at a persistent volume; the SQLite file only holds host/device public
  keys, names, and pairing bookkeeping (see §"Privacidad" in the protocol spec) — it is small and
  fine to back up/restore wholesale.
