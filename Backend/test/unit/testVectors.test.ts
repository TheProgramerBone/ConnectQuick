import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { deriveDeviceId, verifyEd25519, computeSas } from "../../src/crypto.ts";
import { pairingDecisionMessage } from "../../src/ws/handlers/util.ts";

const vectorsPath = fileURLToPath(new URL("../../../Protocol/test-vectors/crypto-vectors.json", import.meta.url));
const vectors = JSON.parse(readFileSync(vectorsPath, "utf8")) as {
  deriveDeviceId: { cases: { identityPub: string; expected: string }[] };
  challengeSignature: {
    serverChallengeB64Url: string;
    hostSignatureB64Url: string;
    controllerSignatureB64Url: string;
  };
  identities: { host: { publicKeyB64Url: string }; controller: { publicKeyB64Url: string } };
  sas: { hostPub: string; deviceEphemeralPub: string; nonceB64Url: string; expectedSasCode: string };
  pairingDecisionSignature: {
    pairingId: string;
    deviceId: string;
    approve: { approved: boolean; permissions: string[]; signedMessage: string; signatureB64Url: string };
    reject: { approved: boolean; permissions: string[]; signedMessage: string; signatureB64Url: string };
  };
};

test("Protocol/test-vectors: deriveDeviceId matches every case", () => {
  for (const c of vectors.deriveDeviceId.cases) {
    assert.equal(deriveDeviceId(c.identityPub), c.expected);
  }
});

test("Protocol/test-vectors: challenge signatures verify against their identity keys", () => {
  const challenge = Buffer.from(vectors.challengeSignature.serverChallengeB64Url, "base64url");
  assert.equal(
    verifyEd25519(vectors.identities.host.publicKeyB64Url, challenge, vectors.challengeSignature.hostSignatureB64Url),
    true,
  );
  assert.equal(
    verifyEd25519(
      vectors.identities.controller.publicKeyB64Url,
      challenge,
      vectors.challengeSignature.controllerSignatureB64Url,
    ),
    true,
  );
});

test("Protocol/test-vectors: SAS code matches", () => {
  const { hostPub, deviceEphemeralPub, nonceB64Url, expectedSasCode } = vectors.sas;
  assert.equal(computeSas(hostPub, deviceEphemeralPub, nonceB64Url), expectedSasCode);
});

test("Protocol/test-vectors: pairing-decision signed message and signature match (approve)", () => {
  const { pairingId, deviceId, approve } = vectors.pairingDecisionSignature;
  const message = pairingDecisionMessage(pairingId, deviceId, approve.approved, approve.permissions);
  assert.equal(message.toString("utf8"), approve.signedMessage);
  assert.equal(verifyEd25519(vectors.identities.host.publicKeyB64Url, message, approve.signatureB64Url), true);
});

test("Protocol/test-vectors: pairing-decision signed message and signature match (reject)", () => {
  const { pairingId, deviceId, reject } = vectors.pairingDecisionSignature;
  const message = pairingDecisionMessage(pairingId, deviceId, reject.approved, reject.permissions);
  assert.equal(message.toString("utf8"), reject.signedMessage);
  assert.equal(verifyEd25519(vectors.identities.host.publicKeyB64Url, message, reject.signatureB64Url), true);
});
