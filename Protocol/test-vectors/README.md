# Test vectors

`crypto-vectors.json` pins fixed inputs/outputs for every cryptographic primitive the protocol
depends on: `deriveDeviceId`, Ed25519 challenge signing, the pairing SAS code, and the
pairing-decision signature (protocol-spec.md §3, §5, "Firma de la decisión de pairing").

**Why this exists**: three independent implementations (`/Backend` in TypeScript today,
`/WindowsHost` in C#, `/Android` in Kotlin, once they exist) must derive byte-identical results
from the same inputs — a Host signs a challenge, a Controller must be able to verify it, and the
backend must agree with both. A vectors file catches drift (wrong base64 alphabet, wrong hash
input order, off-by-one truncation, etc.) immediately, in a unit test, instead of as a mysterious
pairing failure between two real devices.

Keys are given as raw Ed25519/X25519 JWK octets (`d` = private, `x` = public), base64url,
no padding — the exact wire format used throughout the protocol (see protocol-spec.md §3). Any
implementation can reconstruct the same keypair from `d` alone (`x` is included for convenience/
cross-checking) and must reproduce every `expected*`/`signatureB64Url` field exactly.

`Backend/test/unit/testVectors.test.ts` asserts the TypeScript implementation against this file.
When `/WindowsHost` and `/Android` gain their own crypto layers, they should add an equivalent
test reading this same JSON file — never a platform-local copy, so all three stay in sync by
construction.

These are fixed constants for testing only — never derived from or usable as real device
credentials.
