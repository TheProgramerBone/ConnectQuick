# Security

## Threat model

| Adversary | Goal | Outcome |
|---|---|---|
| The relay operator (or anyone who compromises the backend) | Read or inject control traffic, impersonate a PC or a phone, grant itself access | **Cannot.** Sessions are end-to-end encrypted and authenticated with keys it never holds; it can drop, delay or refuse service. It does learn *who* talks to *whom*, *when* and how much. |
| A network attacker on either side's path | Eavesdrop, replay, tamper | TLS to the relay (required in release builds) plus the E2E layer; replayed or reordered frames are rejected. |
| Someone who photographs/relays the pairing QR | Pair their own device | The PC shows an authorize dialog; both screens show the same 6-digit comparison code derived on each side from the exchanged keys. A mismatch means someone is in the middle: reject. |
| A paired-but-malicious or compromised phone | Do more than it was granted | Cannot exceed the permissions granted at pairing, intersected with what the PC currently allows; cannot run commands (no such message exists); cannot read outside the shared/inbox folders; sensitive actions need extra confirmation. |
| A compromised or buggy PC talking to the phone | Push bad data to the phone | The phone validates every inbound message, verifies clipboard hashes and sizes, never uses a PC-supplied file name as a path. |
| Malware already running as the user on the PC | Take over | **Out of scope**: it can already do what ConnectQuick can do, and can use the DPAPI-protected identity. Mitigation is limited to not widening what it already has. |
| A thief with the unlocked phone | Control the PC | Out of scope for now (no app lock). Revoke the phone from the PC. |

## Device identity

Each Host and Controller generates an **Ed25519** key pair on first run. `deviceId = base64url(SHA-256("connectquick:device:" || identityPub))[0:22]` — the id is a
commitment to the key, so nobody can claim an id without the private key. The key never leaves the device. Windows: the seed is DPAPI-protected (current-user scope).
Android: the seed is wrapped with an AES-256-GCM key in the Android Keystore (StrongBox when available, TEE otherwise).

## Transport authentication

The backend sends a fresh 32-byte challenge per connection; the client signs it (`hello`). A host/device id is pinned to the first key seen for it; a different key for the same id is rejected.
A second connection with the same identity replaces the first, which is told to stop (close code 4010) instead of fighting for the slot.

## Pairing

The QR carries only public or short-lived material: `pairingId`, nonce, the Host's public identity key and a fresh ephemeral key, the relay URL, an expiry. The backend enforces a
60 s TTL and consumes a `pairingId` exactly once. The Host's decision is signed over `pairingId|deviceId|approved|permissions`; the phone verifies that signature itself, so a
backend cannot fabricate a grant. The comparison code (SAS) is computed independently on both sides and shown on both screens (**tested**: both derive identical digits).
The PC's authorize dialog lists permissions as separate switches; remote power control is **never pre-selected**, and pressing Enter does not approve.

## End-to-end sessions (`Protocol/protocol-spec.md` §7)

- Fresh **X25519** keys per session ⇒ forward secrecy; keys derived with **HKDF-SHA256** salted with a transcript hash that binds session id, both ids, both ephemeral keys and the granted permissions.
- The handshake is **mutually authenticated by long-term Ed25519 signatures**: the Controller's over its request (bound to host, device, ephemeral key, timestamp), the Host's over *both* ephemeral keys and the permissions.
  The Controller verifies the Host's signature against the key stored at pairing; the Host verifies the Controller against its **own** record of approved devices, not the relay's word.
- Data: **AES-256-GCM**, one key per direction, 96-bit nonce from a strictly increasing 64-bit counter, AAD = session + direction + counter; a frame that fails authentication does not advance the counter; replay and reordering are dropped. At most 2³²−1 frames per session.
- **Key confirmation**: the session is "established" only once the first encrypted frame from the Host decrypts. Liveness pings detect a silent peer.
- Three independent implementations (TypeScript reference, .NET, Kotlin) reproduce the same **published vectors** (`Protocol/test-vectors/`), and cross-platform tests drive real processes through a real relay, including tampered, replayed and key-swapped handshakes.

## What the backend sees and can do

Sees: public keys, device names, permission lists, who connects to whom and when, frame sizes and rates, IP addresses. Does **not** see: mouse/keyboard, clipboard, files, power commands, SDP/ICE, anything in `session.data`.
Can: refuse service, drop or delay frames, end sessions, hand out wrong ICE servers (which at worst forces a failed connection; media stays DTLS-SRTP-encrypted end to end). Cannot: read, alter without detection, or inject.

## The PC as the policy point

Effective access for a session = **permissions granted at pairing ∩ switches currently enabled on the PC**. Turning a switch off on the PC takes effect on the very next message, without re-pairing.
Defaults are conservative: power = **lock only**; pairing pre-selects everything *except* power; clipboard auto-push is **off**; "ask me for every connection" is available.
"Disconnect phone now" cuts a live session immediately; revoking a device cuts its session and removes its trust even if the relay is unreachable.

Every inbound message passes: decrypt → JSON-Schema validation → route lookup → permission → per-type rate limit → handler. Invalid, unknown or forbidden messages get `ctl.error`; 25 violations end the session.
At session end, **every held key and mouse button is released** (also if the phone dies, the network drops or the relay goes away — tested).

## Features

- **Input**: physical scancodes; text is Unicode without control characters; ranges clamped; held state tracked for guaranteed release. Windows UIPI means elevated windows ignore injected input; ConnectQuick does not attempt to bypass UAC.
- **Screen**: captured only while a stream is active and the permission is on; a UAC prompt / lock screen is reported ("secure desktop") instead of captured; stops with the session. No audio, no recording.
- **Clipboard**: text only, size-capped, hash-verified, no echo loops, auto-push off by default, items that Windows marks as excluded from history (password managers) are not auto-sent.
- **Files**: the phone can write only into the PC's inbox and read only what is in the PC's "share" folder, **by name matched against a directory listing** — no path ever crosses the wire. Names are sanitised (separators, `..`, control and bidi characters, reserved Windows device names, trailing dots/spaces, length); the destination and each existing target are checked for symlinks/junctions;
  data lands in a private `.part` file and becomes visible only after the SHA-256 matches; more bytes than announced, gaps, or a hash mismatch discard everything; size and free-space limits; resume; duplicate detection; Zone.Identifier (mark-of-the-web) is set on received files. The phone applies the same name sanitisation to files coming from the PC.
- **Power**: lock and cancel are immediate; sleep/restart/shutdown need a one-time token returned **after** an explicit confirmation on the phone; tokens expire quickly and are bound to the session; restart/shutdown have a cancellable grace period; the PC notifies on screen when a power action executes. Policy can be disabled or lock-only on the PC. **There is no message that runs a command, a script or a program** — the "apps" permission has no implementation and is never granted or shown.

## Backend hardening

- Strict envelope/payload schemas (`additionalProperties: false`); 64 KiB message cap at the WebSocket layer and again in the parser; per-connection and per-IP rate limits; `pairing.create` limits counted in the database (they survive a restart) and per-IP `pairing.init` limits, both configurable for shared NATs (`PAIRING_*`).
- Per-session frame/byte budgets, bounded send queues (`peer_too_slow`), idle/accept/max-duration timeouts, a replay window and unique-`sessionId` check on `session.connect`, one session per device and per host.
- Origin allow-list for browsers (`ALLOWED_ORIGINS`), `TRUST_PROXY` only when explicitly set, TLS via `CERT_PATH`/`KEY_PATH` or a terminating proxy, `/healthz`, `/readyz`, bearer-protected `/metrics`, graceful drain.
- TURN credentials are ephemeral (`user = expiry:deviceId`, HMAC-SHA1 with a shared secret) and the backend refuses to advertise TURN without a secret.
- Logging redacts keys, tokens, signatures and clipboard-like fields; the Host's own log (`%APPDATA%\ConnectQuick\logs`) records identifiers and counts only — never clipboard text, file contents, SDP or key material — and caps single messages at 300 characters.
- Release Android builds only allow TLS (`wss://`); plain `ws://` is enabled for **debug builds only** (`src/debug/res/xml/network_security_config.xml`).

## Dependencies

`npm audit` reports 0 vulnerabilities after `npm audit fix` (the only finding was a moderate `fast-uri`). In the .NET streaming stack the SIPSorcery dependency chain resolved `System.Net.Http 4.3.0` and
`System.Text.RegularExpressions 4.3.0` (both high severity); the project now pins the patched 4.3.4 / 4.3.1 and `dotnet list package --vulnerable --include-transitive` reports none.
NuGet/Maven/npm packages are not signed or reproducibly pinned beyond lock files; there is no SBOM yet.

## Known gaps and honest limits

- **Not externally reviewed or fuzzed.** The cryptographic design follows standard constructions (X25519, HKDF, AES-GCM, Ed25519) but is a custom protocol; get it reviewed before relying on it for anything sensitive.
- **Win32 adapters, the WinForms UI and the whole Android UI have never run on real hardware** (`Docs/STATUS.md`). Security-relevant behaviour implemented in those layers (DPAPI, `SendInput`, junction checks, Zone.Identifier, MediaStore) is code-reviewed and unit-tested where it can be, but not exercised on Windows/Android.
- No certificate pinning on the phone; no app lock/biometric gate on the phone; no remote attestation of the PC; the identity seed is as safe as the user account (DPAPI) / the Keystore.
- Metadata (who, when, how much, IPs, device names) is visible to the relay operator. Device names are chosen by the devices and are shown in dialogs — treat them as untrusted text.
- The relay can deny service; a malicious relay can also point peers at a hostile ICE/TURN server — media remains DTLS-SRTP encrypted, but connectivity can be denied.
- Video is only as private as the screen it shows: an unlocked, authorised phone sees everything on the primary monitor. Only the primary monitor is captured.
- Clipboard and keystrokes are visible to whatever else runs on the PC as the same user. Passwords typed through ConnectQuick pass through the phone's keyboard.
- Releases are not code-signed; the first run on Windows will show SmartScreen.
- Single backend instance (see `Docs/ARCHITECTURE.md`, *Scaling*).

## Reporting

There is no public disclosure channel yet. Until one exists, report security issues privately to the project owner rather than in a public issue.
