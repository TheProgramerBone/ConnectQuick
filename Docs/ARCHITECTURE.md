# Architecture

## The problem this shapes

The Host (Windows PC) must be controllable from the Internet without port forwarding, without a
static/public IP, and while sitting behind NAT or CGNAT. The only architecture that satisfies that
without asking the user to touch their router is: **both the Host and the Controller make outbound
connections to a rendezvous service they both trust.** Nothing ever connects *inbound* to the PC.

```
                        Internet
                           |
              +------------+------------+
              |                         |
          Android                  Windows PC
         Controller                   Host
              |                         |
              +-----------+-------------+
                          |
                 Backend (Node.js)
              signaling + relay + registry
                          |
                      SQLite
              (public keys, names, pairing bookkeeping)
```

Both sides dial out to the same `wss://` endpoint; the backend is the only party either of them
needs to be able to reach. That's the whole trick behind not needing port forwarding — it's true
for the control/signaling channel today, and it stays true once a real-time media transport
(WebRTC) is layered in for desktop streaming, because WebRTC's own ICE/STUN/TURN negotiation is
*also* NAT-traversal-first and, worst case, relays media through a TURN server the same way this
backend relays signaling now.

## Why WebSocket for the control/signaling plane (decided, implemented)

The control plane (auth, pairing, presence, session negotiation — everything in
[`Protocol/protocol-spec.md`](../Protocol/protocol-spec.md) today) needs: a persistent full-duplex
connection so the backend can push (`pairing.incoming`, `presence.update`) without polling;
low message *rate* but not necessarily low *latency* (a pairing dialog appearing 200ms late is
fine — it's not a mouse cursor); and something every one of Node/.NET/Android has a solid,
boring, well-understood client for. WebSocket-over-TLS (`wss://`) is the obvious fit and is what's
implemented. QUIC/HTTP-3 was considered and rejected for this plane specifically: it would buy
faster connection setup and better head-of-line-blocking behavior, at the cost of materially less
mature/uniform library support across Node, .NET, and Android — not worth it for a plane that
isn't latency-critical. HTTP polling was ruled out per the brief's own constraint (§4) and because
it can't push `pairing.incoming`/`presence.update` without either polling aggressively (wasteful,
bad for phone battery) or long-polling (which is just a worse WebSocket).

## Why WebRTC for the desktop-stream/input plane (decided, not yet implemented)

This is *not yet built* — see [`STATUS.md`](STATUS.md) — but the decision is made now so nothing
already-built conflicts with it. Once a Controller and Host have a `session.state: CONNECTED`
session (already implemented, see below), the actual desktop video + mouse/keyboard/clipboard
traffic should ride a **WebRTC PeerConnection** negotiated through this same backend as the
signaling channel (SDP offer/answer and ICE candidates as new envelope `type`s, e.g.
`webrtc.offer`/`webrtc.answer`/`webrtc.ice`, reserved conceptually alongside the other
Phase-5+ types in protocol-spec.md §7):

- **Video**: a `MediaStreamTrack` fed by hardware-encoded H.264 from Windows Graphics Capture,
  so the browser-grade codec/jitter-buffer/adaptive-bitrate machinery that WebRTC already has
  doesn't need to be reinvented, and Android's `MediaCodec` decoder consumes it natively.
- **Input/clipboard**: an unreliable-ok-for-mouse-moves, reliable-for-keys `RTCDataChannel`,
  which also gets DTLS-SRTP encryption "for free" — meaning the backend relay, once ICE has
  found a direct or TURN-relayed path, is **not in the media path at all** for a direct
  connection, and even in the TURN-relayed fallback case it relays *encrypted* bytes it cannot
  read (see `Docs/SECURITY.md`). This is the concrete mechanism behind "the relay server cannot
  control the PC" (spec §5): it never possesses the keys the video/input channel is encrypted
  with (WebRTC's DTLS-SRTP is negotiated end-to-end via the SDP exchanged over the WS control
  channel, but the backend only ever forwards opaque SDP blobs; it does not participate in the
  DTLS handshake itself).
- A raw-frame-over-WebSocket fallback (adaptive JPEG, not "PNG every frame" as the brief
  explicitly rules out) is worth keeping as a compatibility path for networks that block WebRTC's
  UDP entirely, but WebRTC is the primary path, not an optional nice-to-have.

## What's implemented today vs. what this section anticipates

Everything above the "desktop-stream/input plane" heading is **implemented and tested**
(`/Backend`, `/Protocol`). Everything in that section is **architecture decided, not yet built** —
no WindowsHost or Android project exists yet. See [`STATUS.md`](STATUS.md) for the exact
phase-by-phase breakdown; this file intentionally does not repeat that checklist.

## Component boundaries (as implemented)

```
Backend/src/
  server.ts            entry point: HTTP(S)+WS bootstrap, per-connection lifecycle, idle sweep
  ws/
    connection.ts       per-socket state (role, deviceId, auth state, send())
    connectionRegistry.ts  live-connection lookup by id / hostId / controller deviceId
    router.ts           envelope type -> handler dispatch, enforces auth-before-anything-else
    pendingPairings.ts   in-memory: pairing awaiting the Host's approve/reject
    activeSessions.ts    in-memory: which controller connection(s) are linked to which host
    handlers/           one module per concern (hello, heartbeat, pairing, presence, session)
  registry/             SQLite-backed repositories (hosts, authorized_devices, pairing_requests)
  crypto.ts, schemaValidator.ts, rateLimiter.ts, config.ts, log.ts, errors.ts, db.ts
```

Everything under `ws/` that isn't a SQLite repository is **deliberately in-memory and
ephemeral** — a process restart drops live connections and in-flight pairing/session state, never
durable authorization or identity data. Clients reconnect and re-authenticate with their existing
keys; nothing that matters to the user is lost. See `Backend/README.md` for why this also means
the backend runs as a single instance today (no cross-instance presence/session sharing yet).

## Backend infrastructure: what's actually needed

One process, one SQLite file. Not three separate services. The brief's own §33 asks "can they be
combined initially" — yes: this backend *is* simultaneously the pairing API, the WebSocket
signaling server, and (once WebRTC lands) the fallback TURN-adjacent relay: notice there is no
separate "API server" for pairing versus "relay server" for connections in this codebase, because
splitting them would add a network hop and a consistency problem (pairing state and connection
state need to agree on the same in-memory `PendingPairingStore`/`ConnectionRegistry`) for no
benefit at this scale. The one piece of infrastructure this design does eventually need that isn't
running yet is a **TURN server** (e.g. coturn) for the minority of WebRTC sessions that can't
establish a direct/STUN path — that's a commodity, horizontally-scalable, stateless component,
unrelated to this backend's own scaling story.
