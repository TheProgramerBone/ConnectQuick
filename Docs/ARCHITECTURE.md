# Architecture

## The problem this shapes

The Host (Windows PC) must be controllable from the Internet without port forwarding, without a
static/public IP, and while sitting behind NAT or CGNAT. The architecture that satisfies that without
asking the user to touch their router: **both the Host and the Controller make outbound connections
to a rendezvous service.** Nothing ever connects *inbound* to the PC.

```
   Android phone (Controller)                         Windows PC (Host)
   ─────────────────────────                         ─────────────────
   app (Views)                                        WinForms tray app
   ControllerHub / RemoteLink                         HostRuntime
   ControllerSession ◄══ E2E-encrypted frames ══►     HostSession
        │            (AES-256-GCM, keys known only        │
        │             to these two endpoints)             │
        ▼                                                 ▼
   wss ──────────────►  Backend (relay + registry)  ◄────────── wss
                        sees: who, when, how many bytes
                        routes opaque `session.data`

   video:  PC ══ WebRTC (DTLS-SRTP, VP8) ══► phone      direct UDP, or via TURN if the network demands it
           (the Backend never carries video; SDP/ICE ride inside the encrypted channel above)
```

## Planes

| Plane | Transport | Carries | Who can read it |
|---|---|---|---|
| Signalling / registry | WebSocket (`wss://`) to the Backend | hello/auth, pairing, presence, session setup, ICE config | Backend (metadata only) |
| Control session | the same WebSocket, as opaque `session.data` frames | input, clipboard, files, media, power, WebRTC signalling | **Only the two endpoints** (E2E, `Protocol/protocol-spec.md` §7) |
| Desktop video | WebRTC (UDP, DTLS-SRTP), TURN as fallback | VP8 video | The two endpoints (a TURN relay sees ciphertext) |

### Why WebSocket for signalling and control
Persistent full-duplex connection so the backend can push (`pairing.incoming`, `presence.update`) without
polling; low message *rate*; a solid boring client on Node, .NET and Android. QUIC/HTTP-3 was rejected for this
plane (less uniform library support, no latency need). HTTP polling cannot push without wasting phone battery.

### Why control rides the relay instead of a WebRTC data channel
Input needs ordered, reliable, low-rate delivery, and it must keep working while no video is running. Carrying it over the
same authenticated, end-to-end-encrypted WebSocket session means one security model to audit, no dependence on UDP reachability
for basic control, and instant release-all-keys when the link drops. Video, which is bandwidth-heavy and loss-tolerant, uses
WebRTC where the congestion control and jitter handling already exist.

### Why video is not relayed by the backend
Bandwidth (a stream is ~1–8 Mbit/s per session) would turn the relay into the cost and the bottleneck. WebRTC finds a direct path
(host/STUN) when it can; only the minority of sessions behind symmetric NAT / CGNAT need TURN, which is a stateless commodity
service (coturn) that scales independently of this backend.

## Components

```
Backend/src/
  server.ts            bootstrap: HTTP(S)+WS, origin allow-list, per-IP limits, health/metrics, graceful drain
  maintenance.ts       session/pairing timeouts and sweeps
  ice.ts               STUN list + ephemeral TURN credentials (coturn REST scheme)
  ws/                  connection, registry, router, pending pairings, active sessions
    handlers/          hello, pairing, session (relay), ice, presence, heartbeat
  registry/            SQLite repositories (hosts, authorized_devices, pairing_requests)

Protocol/              spec, schemas (envelope, payloads, session-messages), shared test vectors

WindowsHost/src/
  ConnectQuick.WindowsHost.Core       platform-neutral logic + thin Win32 adapters behind interfaces
     Session/  HostSession (decrypt → validate → permission ∩ host settings → rate limit → dispatch), SessionManager
     Input/ Clipboard/ Media/ Files/ Power/ Streaming/   one IHostFeature each
     Runtime/HostRuntime              composition root shared by the app and the test harness
  ConnectQuick.WindowsHost.Streaming  GDI capture, scaler, VP8, SIPSorcery peer (kept apart from Core)
  ConnectQuick.WindowsHost            WinForms app: tray, control centre, dialogs (the only Windows-only build)
  .Testing / .HostHarness / .Tests    in-memory adapters, headless host for the cross-platform suite, xUnit

Android/
  controller-core   pure JVM Kotlin: protocol, crypto, session, RemoteLink, ControllerHub, feature clients,
                    TouchpadEngine, wording (LinkTexts/StreamTexts) — unit-tested on any machine
  app               Views UI, Keystore identity, CameraX/ML Kit scan, libwebrtc engine, MediaStore file adapters

Tests/              cross-platform suite: real backend + .NET Host process + Kotlin controller process
```

Everything that decides behaviour is in the platform-neutral cores, so it is exercised by tests on any OS. Anything that touches Windows or
Android APIs is a thin adapter behind an interface (`IInputInjector`, `IClipboard`, `IMediaKeys`, `IPowerExecutor`, `IScreenCapturer`,
`RtcEngine`, `FileSink`/`FileSource`, `DeviceIdentity`) — those adapters are the part that needs real hardware to verify (`Docs/STATUS.md`).

### Host request pipeline (every inbound message)
`decrypt (AEAD, sequence)` → `schema-validate (shared inner-message schema)` → `route lookup` →
`permission granted at pairing ∩ enabled on this PC` → `per-type rate limit` → `feature handler`.
Anything unknown, invalid or not permitted is answered with `ctl.error` and counts toward a violation limit that ends the session.
Session end — for any reason — runs `SessionEnded` on every feature: held keys and buttons are released, transfers cancelled, video stopped.

## State that matters

- **Durable** (SQLite on the backend; JSON/DPAPI files on the PC; Keystore-wrapped seed + preferences on the phone): public keys, device names, the authorization
  records, permissions. A backend restart loses only live connections.
- **Ephemeral** (memory): live connections, in-flight pairings, active sessions, rate-limit buckets, session keys. Clients reconnect and re-authenticate on their own
  (`RemoteLink`, `BackendClient`); tested through backend restart, PC crash, network change, and a silent partition.
- The **PC is the authority** on who may control it: it keeps its own list of approved phones and never relies on the backend's claim of authorization.

## Scaling — measured, and what would change

Measured with `Backend/scripts/loadtest.ts` (a real server process; N paired host/controller sessions, each sending encrypted 256-byte frames in both directions;
sandbox VM, 4 vCPU, Node 22):

| Live sessions | Frames/s relayed | Server CPU | Peak RSS | Delivery latency p50 / p99 | Loss |
|---:|---:|---:|---:|---:|---:|
| 200 | 7 960 | 0.26 core | 132 MB | 9 / 20 ms | 0 |
| 500 | 18 300 | 0.56 core | 143 MB | 26 / 87 ms (generator-bound) | 0 |
| 1 000 (10 fps) | 17 900 | 0.59 core | 154 MB | 54 / 124 ms (generator-bound) | 0 |

The generator saturated its own core in the last two rows, so those are lower bounds for the server. A real control session is a few tens of frames/s
(mouse moves are coalesced to ≥8 ms; the PC enforces a per-session budget) and video never touches the backend. **One modest instance serves thousands of concurrent sessions.**

**Decision: single instance; no Redis, no multi-instance code.** It would add failure modes (split-brain presence, cross-instance ordering) to solve a problem that does not exist yet.
Availability is addressed differently: the process restarts in seconds, clients reconnect by themselves, and graceful drain (`SIGTERM`) tells them to.

**Triggers to revisit**: sustained server CPU above ~60 % of a core, ~3 000+ concurrent sessions, a requirement for zero-downtime deploys, or a regional latency need.

**Design for when it is needed** (not built): (1) move the registry from SQLite to Postgres; (2) route every WebSocket by `hostId` with consistent hashing at the load balancer, so a
host and all its controllers meet on one instance — this needs the controller to put the `hostId` it already knows (from the QR / paired record) in the connection URL, a small
protocol addition; pairing connects the phone to the instance that holds the host's pending pairing the same way; (3) only if instance-to-instance routing without affinity is required,
add pub/sub (Redis) for `session.*` forwarding. Presence for one host is then owned by exactly one instance, which keeps today's single-writer simplicity.

## Running TURN (needed for streaming across different networks)

On the same LAN, or when STUN finds a direct path, nothing else is needed. Behind symmetric NAT / CGNAT / strict firewalls the stream needs a relay:

1. Run coturn with `use-auth-secret`, `static-auth-secret=<SECRET>`, `realm=<your domain>`, TLS on 5349 and UDP/TCP 3478 open, and a relay port range open in the firewall.
2. Start the backend with `TURN_URLS=turn:turn.example.com:3478?transport=udp,turns:turn.example.com:5349` and `TURN_SECRET=<SECRET>` (and `TURN_TTL_SECONDS` if desired).
   The backend refuses to start with `TURN_URLS` set and no secret; credentials it hands out expire (default 1 h) and are bound to the requesting device.
3. Verify from two different networks (e.g. phone on mobile data, PC on home Wi-Fi). Without TURN the app reports "ice_failed_no_relay" instead of hanging.
Nothing in this repo provisions TURN; **this path has not been exercised against a real TURN server**.
