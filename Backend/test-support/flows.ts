import { randomUUID } from "node:crypto";
import { TestClient, generateIdentity, generateEphemeral, signBytes, type TestIdentity } from "./testClient.ts";
import { sessionAcceptMessage, sessionConnectMessage } from "../src/ws/handlers/util.ts";
import {
  SessionChannel,
  deriveSessionKeys,
  generateEphemeral as generateSessionEphemeral,
  sharedSecret,
  transcriptHash,
} from "./sessionCrypto.ts";

/** Performs the hello.challenge / hello / hello.ack transport handshake for one client. */
export async function authenticate(
  client: TestClient,
  role: "host" | "controller",
  identity: TestIdentity,
  opts: { hostName?: string; clientVersion?: string } = {},
): Promise<Record<string, unknown>> {
  await client.ready();
  const challenge = await client.waitFor("hello.challenge");
  const serverChallenge = challenge.payload["serverChallenge"] as string;
  const sig = signBytes(identity.privateKey, Buffer.from(serverChallenge, "base64url"));
  client.send("hello", {
    role,
    deviceId: identity.deviceId,
    identityPub: identity.publicKeyB64Url,
    challengeSig: sig,
    clientVersion: opts.clientVersion ?? "0.1.0-test",
    ...(opts.hostName ? { hostName: opts.hostName } : {}),
  });
  const ack = await client.waitFor("hello.ack");
  if (ack.payload["accepted"] !== true) {
    throw new Error(`hello rejected: ${JSON.stringify(ack.payload)}`);
  }
  return ack.payload;
}

export interface PairedFixture {
  hostClient: TestClient;
  hostIdentity: TestIdentity;
  hostId: string;
  controllerClient: TestClient;
  controllerIdentity: TestIdentity;
  pairingId: string;
  permissions: string[];
}

/** Runs the full §7 pairing flow end-to-end and returns both authenticated, paired clients. */
export async function pairHostAndController(
  url: string,
  opts: { hostName?: string; deviceName?: string; permissions?: string[] } = {},
): Promise<PairedFixture> {
  const hostIdentity = generateIdentity();
  const controllerIdentity = generateIdentity();
  const hostClient = new TestClient(url);
  await authenticate(hostClient, "host", hostIdentity, { hostName: opts.hostName ?? "PC Gaming" });

  const hostEph = generateEphemeral();
  hostClient.send("pairing.create", { hostName: opts.hostName ?? "PC Gaming", hostEphPub: hostEph.publicKeyB64Url });
  const created = await hostClient.waitFor("pairing.created");
  const pairingId = created.payload["pairingId"] as string;

  const controllerClient = new TestClient(url);
  await authenticate(controllerClient, "controller", controllerIdentity);

  const controllerEph = generateEphemeral();
  controllerClient.send("pairing.init", {
    pairingId,
    deviceId: controllerIdentity.deviceId,
    deviceName: opts.deviceName ?? "Android de Juan",
    identityPub: controllerIdentity.publicKeyB64Url,
    ephemeralPub: controllerEph.publicKeyB64Url,
  });

  const incoming = await hostClient.waitFor("pairing.incoming");
  const permissions = opts.permissions ?? [
    "remote_desktop",
    "mouse",
    "keyboard",
    "clipboard",
    "files",
    "media",
    "apps",
    "power",
  ];
  const message = Buffer.from(
    `${pairingId}|${incoming.payload["deviceId"]}|1|${permissions.join(",")}`,
    "utf8",
  );
  const hostSignature = signBytes(hostIdentity.privateKey, message);
  hostClient.send("pairing.decision", { pairingId, approved: true, permissions, hostSignature });

  await controllerClient.waitFor("pairing.result");

  return {
    hostClient,
    hostIdentity,
    hostId: hostIdentity.deviceId,
    controllerClient,
    controllerIdentity,
    pairingId,
    permissions,
  };
}

export interface OpenedSession {
  sessionId: string;
  /** The Controller's end of the encrypted channel. */
  controller: SessionChannel;
  /** The Host's end of the encrypted channel. */
  host: SessionChannel;
  permissions: string[];
}

/**
 * Builds a valid, signed session.connect payload for `identity` toward `hostId`. Exposed
 * separately so security tests can corrupt individual fields.
 */
export function buildSessionConnect(
  identity: TestIdentity,
  hostId: string,
  overrides: { sessionId?: string; issuedAt?: number } = {},
): { payload: Record<string, unknown>; sessionId: string; ephemeral: ReturnType<typeof generateSessionEphemeral> } {
  const sessionId = overrides.sessionId ?? randomUUID();
  const issuedAt = overrides.issuedAt ?? Date.now();
  const ephemeral = generateSessionEphemeral();
  const sig = signBytes(
    identity.privateKey,
    sessionConnectMessage(sessionId, hostId, identity.deviceId, ephemeral.publicB64Url, issuedAt),
  );
  return { payload: { hostId, sessionId, ephPub: ephemeral.publicB64Url, issuedAt, sig }, sessionId, ephemeral };
}

/** Performs the whole Controller->Host session handshake and returns both encrypted channels. */
export async function openSession(fixture: PairedFixture, grant?: string[]): Promise<OpenedSession> {
  const { payload, sessionId, ephemeral: controllerEph } = buildSessionConnect(
    fixture.controllerIdentity,
    fixture.hostId,
  );
  fixture.controllerClient.send("session.connect", payload);
  const incoming = await fixture.hostClient.waitFor("session.connect");

  const permissions = grant ?? fixture.permissions;
  const hostEph = generateSessionEphemeral();
  const sig = signBytes(
    fixture.hostIdentity.privateKey,
    sessionAcceptMessage(
      sessionId,
      fixture.hostId,
      fixture.controllerIdentity.deviceId,
      incoming.payload["ephPub"] as string,
      hostEph.publicB64Url,
      permissions,
    ),
  );
  fixture.hostClient.send("session.accept", { sessionId, ephPub: hostEph.publicB64Url, permissions, sig });
  const accepted = await fixture.controllerClient.waitFor("session.accept");

  const transcript = transcriptHash(
    sessionId,
    fixture.hostId,
    fixture.controllerIdentity.deviceId,
    controllerEph.publicB64Url,
    accepted.payload["ephPub"] as string,
    accepted.payload["permissions"] as string[],
  );
  const keysController = deriveSessionKeys(sharedSecret(controllerEph, accepted.payload["ephPub"] as string), transcript);
  const keysHost = deriveSessionKeys(sharedSecret(hostEph, incoming.payload["ephPub"] as string), transcript);
  return {
    sessionId,
    permissions,
    controller: SessionChannel.forController(sessionId, keysController),
    host: SessionChannel.forHost(sessionId, keysHost),
  };
}
