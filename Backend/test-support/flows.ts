import { TestClient, generateIdentity, generateEphemeral, signBytes, type TestIdentity } from "./testClient.ts";

/** Performs the hello.challenge / hello / hello.ack transport handshake for one client. */
export async function authenticate(
  client: TestClient,
  role: "host" | "controller",
  identity: TestIdentity,
  opts: { hostName?: string; clientVersion?: string } = {},
): Promise<void> {
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
