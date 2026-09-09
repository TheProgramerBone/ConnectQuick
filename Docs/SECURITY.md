# Security

## Threat model

The backend is treated as **not fully trusted** — compromised, subpoenaed, or simply curious
operators of the relay should not be able to control a Host or read what flows between a paired
Host and Controller. Concretely, the backend is trusted for: routing messages between the right
two parties, enforcing rate limits, and storing public keys/metadata. It is *not* trusted to (and,
by construction, mostly *cannot*) impersonate either party, forge an authorization it never saw
granted, or read the content of a future desktop/input/clipboard/file stream.

## Device identity (implemented)

Every Host and Controller generates an **Ed25519** keypair on first run. `deviceId =
base64url(SHA-256("connectquick:device:" || identityPub))[0:22]` — the id is a commitment to the
public key, not an assignment from the server, so nobody (including the backend) can claim an id
without the matching private key (see `Protocol/protocol-spec.md` §3, and
`Protocol/test-vectors/` for the exact bytes). The private key never leaves the device and never
crosses the network in any form; the backend stores only public keys.

Where the private key lives is platform-specific and **not yet implemented** (no WindowsHost/
Android project exists yet — see `STATUS.md`): the plan is DPAPI (`ProtectedData`, current-user
scope) on Windows and Android Keystore (StrongBox/TEE-backed when available) on Android, per the
protocol spec. The backend-side crypto (`Backend/src/crypto.ts`) and its tests already pin the
exact wire format (`Protocol/test-vectors/crypto-vectors.json`) both platforms must produce.

## Transport authentication (implemented)

On connect, the backend sends a random 32-byte challenge; the client proves possession of its
private key by signing it (`hello.challengeSig`). This is a fresh challenge per TCP/WS connection,
so a captured signature can't be replayed on a new connection. This is *authentication* (who is
this socket), separate from *authorization* (what is this socket allowed to do) — see below.

A hostId is pinned to the first public key ever seen for it (TOFU); a later `hello` claiming the
same hostId with a *different* key is rejected (`Backend/src/ws/handlers/hello.ts`, tested in
`Backend/test/integration/auth.test.ts`). This can't happen accidentally (the id is derived from
the key), so in practice this check only matters against active tampering.

## Pairing (implemented)

QR contents (`Protocol/protocol-spec.md` §5) carry only public/short-lived material: a
`pairingId`, a random `nonce`, the Host's long-term public key and a fresh ephemeral X25519 public
key, the relay endpoint, and an expiry — never a password, private key, or reusable token. The
backend enforces a 60s TTL server-side (not just as a UI hint) and a `pairingId` is
**consumed exactly once** (`PairingRepository.tryConsume`, race-safe via a single conditional
`UPDATE`) — a second `pairing.init` for the same id, whether a genuine retry or a replay attempt,
is rejected with `already_used`.

The Host's decision (`pairing.decision`) is itself signed with the Host's long-term key over
`pairingId|deviceId|approved|permissions` (exact byte format and worked examples in
`Protocol/protocol-spec.md` "Firma de la decisión de pairing" and the test vectors) — the backend
verifies this before writing an authorization row, so a compromised backend cannot fabricate a
device authorization the Host never actually approved; at most it can *withhold or drop* a
legitimate one (a denial-of-service on pairing, not a spoofed grant).

A **SAS (short authentication string)** — a 6-digit code derived from both parties' public
material and the pairing nonce (`computeSas`) — is included in `pairing.incoming` so a Host's own
UI can eventually display it for out-of-band comparison. This is groundwork for defending against
a QR relayed through a malicious backend (the SAS would differ from what's shown on the Controller
mid-scan); displaying it in the actual Host/Controller UI is a client-side concern for the
not-yet-built WindowsHost/Android UIs.

## What the backend never sees (by design)

Payload schemas (`Protocol/schemas/payloads.schema.json`) only carry what pairing/presence/session
negotiation strictly needs: public keys, device names, permission lists, connection state
strings. There is no `type` today for desktop frames, keystrokes, clipboard, or file bytes — those
are future phases, and the architecture decision for them (`ARCHITECTURE.md`, "desktop-stream/
input plane") is specifically chosen so that when they exist, they ride a WebRTC DataChannel/media
track whose DTLS-SRTP keys are negotiated between Host and Controller — the backend forwards
opaque SDP/ICE blobs to set that channel up, but is not a party to the encryption itself. Until
that's built, the honest current state is: this backend has nothing to relay for those functions
because they don't exist yet, not because of a not-yet-verified encryption promise.

## Abuse resistance (implemented)

- **Rate limiting**: `pairing.create` capped per host (via DB row counts, not just an in-memory
  bucket — survives a process restart mid-abuse), `pairing.init` capped per source IP (token
  bucket), and a generic per-connection message-rate cap independent of message type. All three
  are covered in `Backend/test/integration/rateLimit.test.ts` against the real server.
- **Strict schema validation**: every envelope and payload is validated against
  `Protocol/schemas/*.json` with `additionalProperties: false` — an unknown field, wrong type, or
  unrecognized `type` is rejected before any handler runs (`Backend/src/schemaValidator.ts`).
  There is deliberately no catch-all/"execute what you understand" fallback.
- **Message size cap**: 64 KiB, enforced both at the WebSocket layer (`maxPayload`) and again in
  `parseEnvelope` (defense in depth — the second check matters for anything that constructs an
  envelope in-process rather than off the wire, e.g. future test/tooling code).
- **No generic command execution, ever**: the protocol has no `ExecuteShellCommand`-shaped
  message and never will (`Protocol/protocol-spec.md` §1); every Host-side action is (or, for
  not-yet-built phases, will be) its own explicit, schema-validated message type. This rules out
  an entire class of command-injection bugs by construction rather than by input sanitization.
- **Idle/auth timeouts**: a connection that never completes `hello` within 10s is dropped; a
  connection (authenticated or not) that goes silent (no traffic, including heartbeats) for
  `heartbeatTimeoutMs` is swept — bounds resource use from slow-loris-style or simply abandoned
  connections.

## Logging (implemented)

`Backend/src/log.ts` redacts a fixed set of sensitive field names (`privateKey`, `sessionKey`,
`password`, `token`, `signature`, `clipboard`, …) recursively in any structured log call. Handlers
log identifiers (deviceId, hostId, message type) for diagnosability, never key material or
payload contents wholesale.

## Known gaps (not yet addressed — tracked, not hidden)

- **No path-traversal/file-permission/power-action hardening exists yet** because the
  files/power/apps message types themselves don't exist yet (see `STATUS.md`). When they're
  built, the requirement is: normalize + resolve real paths server-*and*-Host-side, reject
  anything that escapes an allow-listed root, and treat every filesystem/power/process action as
  its own typed, explicitly-permissioned message — never a path or command string trusted as-is.
- **Session media is not yet end-to-end encrypted** because there is no session media yet — the
  `session.connect`/`session.state` primitives implemented today only negotiate *that* a session
  exists, not any data plane on top of it.
- **Horizontal scaling of the backend has no shared-state story yet** (see `ARCHITECTURE.md`) —
  not a confidentiality issue, but worth flagging as an availability/scale gap.
