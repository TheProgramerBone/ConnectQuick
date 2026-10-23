import { test } from "node:test";
import assert from "node:assert/strict";
import dgram from "node:dgram";
import net from "node:net";
import { buildIceConfig } from "../../src/ice.ts";
import { loadConfig } from "../../src/config.ts";
import { Logger } from "../../src/log.ts";
import {
  Attr, Class, Method, buildChannelData, buildStun, decodeXorAddress, encodeXorAddress, getAttr, longTermKey,
  newTransactionId, parseStun, splitFrames, u32, verifyIntegrity, type StunMessage,
} from "../../src/turn/stun.ts";
import { restPassword, startTurnServer } from "../../src/turn/turnServer.ts";
import { testConfig } from "../../test-support/testConfig.ts";

const SECRET = "test-secret";
const REALM = "connectquick";
const quietLog = (): Logger => new Logger("turn-test", "ERROR");

/** A tiny TURN-over-TCP client: enough to drive the server the way libwebrtc does. */
class Client {
  private buf = Buffer.alloc(0);
  private waiting: Array<(b: Buffer) => void> = [];
  private queue: Buffer[] = [];
  readonly sock: net.Socket;
  nonce = "";
  key: Buffer | null = null;
  username = "";

  constructor(port: number) {
    this.sock = net.connect(port, "127.0.0.1");
    this.sock.on("data", (d: Buffer) => {
      this.buf = Buffer.concat([this.buf, d]);
      const { frames, rest } = splitFrames(this.buf);
      this.buf = Buffer.from(rest);
      for (const f of frames) {
        const w = this.waiting.shift();
        if (w) w(Buffer.from(f)); else this.queue.push(Buffer.from(f));
      }
    });
  }

  ready(): Promise<void> { return new Promise((r) => this.sock.once("connect", () => r())); }

  next(timeoutMs = 2000): Promise<Buffer> {
    const q = this.queue.shift();
    if (q) return Promise.resolve(q);
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => reject(new Error("timeout waiting for a frame")), timeoutMs);
      this.waiting.push((b) => { clearTimeout(t); resolve(b); });
    });
  }

  async request(method: number, attrs: Array<[number, Buffer]>, auth = true): Promise<StunMessage> {
    const all: Array<[number, Buffer]> = [...attrs];
    if (auth && this.key) all.push([Attr.USERNAME, Buffer.from(this.username)], [Attr.REALM, Buffer.from(REALM)], [Attr.NONCE, Buffer.from(this.nonce)]);
    this.sock.write(buildStun(method, Class.REQUEST, newTransactionId(), all, { integrityKey: auth && this.key ? this.key : undefined }));
    const m = parseStun(await this.next());
    assert.ok(m, "server reply must be valid STUN");
    return m!;
  }

  async allocate(username: string, password: string): Promise<StunMessage> {
    const transport: [number, Buffer] = [Attr.REQUESTED_TRANSPORT, Buffer.from([17, 0, 0, 0])];
    const first = await this.request(Method.ALLOCATE, [transport], false);
    assert.equal(first.cls, Class.ERROR);
    assert.equal(errorCode(first), 401);
    this.nonce = getAttr(first, Attr.NONCE)!.toString();
    assert.equal(getAttr(first, Attr.REALM)!.toString(), REALM);
    this.username = username;
    this.key = longTermKey(username, REALM, password);
    return this.request(Method.ALLOCATE, [transport]);
  }

  close(): void { this.sock.destroy(); }
}

function errorCode(m: StunMessage): number {
  const v = getAttr(m, Attr.ERROR_CODE)!;
  return v[2]! * 100 + v[3]!;
}

function creds(deviceId = "dev1", ttl = 600): { username: string; password: string } {
  const username = `${Math.floor(Date.now() / 1000) + ttl}:${deviceId}`;
  return { username, password: restPassword(SECRET, username) };
}

async function withServer(fn: (port: number) => Promise<void>, allowAll = true): Promise<void> {
  const s = await startTurnServer({ port: 0, relayIp: "127.0.0.1", secret: SECRET, realm: REALM, log: quietLog(),
    isAllowedPeer: allowAll ? (ip) => ip === "127.0.0.1" : undefined });
  try { await fn(s.port); } finally { await s.close(); }
}

function udpPeer(): Promise<dgram.Socket> {
  return new Promise((r) => { const s = dgram.createSocket("udp4"); s.bind(0, "127.0.0.1", () => r(s)); });
}

test("stun codec: XOR addresses, integrity and fingerprint round-trip", () => {
  const xa = encodeXorAddress({ ip: "192.168.1.20", port: 50000 });
  assert.deepEqual(decodeXorAddress(xa), { ip: "192.168.1.20", port: 50000 });
  const key = longTermKey("u", "r", "p");
  const msg = buildStun(Method.BINDING, Class.REQUEST, newTransactionId(), [[Attr.SOFTWARE, Buffer.from("x")]], { integrityKey: key });
  const parsed = parseStun(msg)!;
  assert.equal(parsed.method, Method.BINDING);
  assert.equal(parsed.cls, Class.REQUEST);
  assert.ok(verifyIntegrity(parsed, key));
  assert.ok(!verifyIntegrity(parsed, longTermKey("u", "r", "wrong")));
  const tampered = Buffer.from(msg); tampered[25] = tampered[25]! ^ 1;
  assert.ok(!verifyIntegrity(parseStun(tampered)!, key));
});

test("stream splitting handles partial frames and channel data padding", () => {
  const a = buildStun(Method.BINDING, Class.REQUEST, newTransactionId(), []);
  const c = buildChannelData(0x4001, Buffer.from([1, 2, 3, 4, 5]));
  assert.equal(c.length, 12); // 4 header + 5 data + 3 pad
  const both = Buffer.concat([a, c]);
  const half = splitFrames(both.subarray(0, a.length + 3));
  assert.equal(half.frames.length, 1);
  const all = splitFrames(both);
  assert.equal(all.frames.length, 2);
  assert.equal(all.rest.length, 0);
  assert.ok(splitFrames(Buffer.from([0xff, 0, 0, 0])).invalid);
});

test("allocate requires valid, unexpired REST credentials", async () => {
  await withServer(async (port) => {
    const bad = new Client(port); await bad.ready();
    const wrong = await bad.allocate(creds().username, "not-the-password");
    assert.equal(wrong.cls, Class.ERROR); assert.equal(errorCode(wrong), 401);
    bad.close();

    const old = new Client(port); await old.ready();
    const expired = creds("dev1", -10);
    const r = await old.allocate(expired.username, expired.password);
    assert.equal(errorCode(r), 401);
    old.close();

    const ok = new Client(port); await ok.ready();
    const good = creds();
    const res = await ok.allocate(good.username, good.password);
    assert.equal(res.cls, Class.SUCCESS);
    assert.ok(verifyIntegrity(res, ok.key!), "responses are signed with the same key");
    const relay = decodeXorAddress(getAttr(res, Attr.XOR_RELAYED_ADDRESS)!)!;
    assert.equal(relay.ip, "127.0.0.1");
    assert.ok(relay.port > 0);
    assert.equal(getAttr(res, Attr.LIFETIME)!.readUInt32BE(0), 600);
    const again = await ok.request(Method.ALLOCATE, [[Attr.REQUESTED_TRANSPORT, Buffer.from([17, 0, 0, 0])]]);
    assert.equal(errorCode(again), 437);
    ok.close();
  });
});

test("relays both ways with Send/Data indications and with channels, only to permitted peers", async () => {
  await withServer(async (port) => {
    const peer = await udpPeer();
    const peerAddr = { ip: "127.0.0.1", port: (peer.address() as { port: number }).port };
    const c = new Client(port); await c.ready();
    const { username, password } = creds();
    const res = await c.allocate(username, password);
    const relay = decodeXorAddress(getAttr(res, Attr.XOR_RELAYED_ADDRESS)!)!;

    // Without a permission nothing passes.
    c.sock.write(buildStun(Method.SEND, Class.INDICATION, newTransactionId(), [[Attr.XOR_PEER_ADDRESS, encodeXorAddress(peerAddr)], [Attr.DATA, Buffer.from("early")]]));
    const got: Buffer[] = [];
    peer.on("message", (m) => got.push(m));
    await new Promise((r) => setTimeout(r, 100));
    assert.equal(got.length, 0);

    const perm = await c.request(Method.CREATE_PERMISSION, [[Attr.XOR_PEER_ADDRESS, encodeXorAddress(peerAddr)]]);
    assert.equal(perm.cls, Class.SUCCESS);

    c.sock.write(buildStun(Method.SEND, Class.INDICATION, newTransactionId(), [[Attr.XOR_PEER_ADDRESS, encodeXorAddress(peerAddr)], [Attr.DATA, Buffer.from("hello pc")]]));
    await new Promise((r) => setTimeout(r, 100));
    assert.deepEqual(got.map(String), ["hello pc"]);

    peer.send(Buffer.from("hello phone"), relay.port, "127.0.0.1");
    const data = parseStun(await c.next())!;
    assert.equal(data.method, Method.DATA);
    assert.equal(data.cls, Class.INDICATION);
    assert.equal(getAttr(data, Attr.DATA)!.toString(), "hello phone");
    assert.deepEqual(decodeXorAddress(getAttr(data, Attr.XOR_PEER_ADDRESS)!), peerAddr);

    const bind = await c.request(Method.CHANNEL_BIND, [[Attr.CHANNEL_NUMBER, Buffer.from([0x40, 0x00, 0, 0])], [Attr.XOR_PEER_ADDRESS, encodeXorAddress(peerAddr)]]);
    assert.equal(bind.cls, Class.SUCCESS);
    c.sock.write(buildChannelData(0x4000, Buffer.from("via channel")));
    await new Promise((r) => setTimeout(r, 100));
    assert.equal(String(got.at(-1)), "via channel");
    peer.send(Buffer.from("back on channel"), relay.port, "127.0.0.1");
    const frame = await c.next();
    assert.equal(frame.readUInt16BE(0), 0x4000);
    assert.equal(frame.subarray(4, 4 + frame.readUInt16BE(2)).toString(), "back on channel");

    const refresh = await c.request(Method.REFRESH, [[Attr.LIFETIME, u32(0)]]);
    assert.equal(refresh.cls, Class.SUCCESS);
    c.close();
    peer.close();
  });
});

test("never relays to an address that is not this machine (no open relay)", async () => {
  await withServer(async (port) => {
    const c = new Client(port); await c.ready();
    const { username, password } = creds();
    await c.allocate(username, password);
    const r = await c.request(Method.CREATE_PERMISSION, [[Attr.XOR_PEER_ADDRESS, encodeXorAddress({ ip: "8.8.8.8", port: 53 })]]);
    assert.equal(r.cls, Class.ERROR);
    assert.equal(errorCode(r), 403);
    const b = await c.request(Method.CHANNEL_BIND, [[Attr.CHANNEL_NUMBER, Buffer.from([0x40, 0x01, 0, 0])], [Attr.XOR_PEER_ADDRESS, encodeXorAddress({ ip: "1.1.1.1", port: 443 })]]);
    assert.equal(errorCode(b), 403);
    c.close();
  }, false);
});

test("requests after allocate must be signed with the allocation's credentials", async () => {
  await withServer(async (port) => {
    const c = new Client(port); await c.ready();
    const { username, password } = creds();
    await c.allocate(username, password);
    c.key = longTermKey(username, REALM, "forged");
    const r = await c.request(Method.CREATE_PERMISSION, [[Attr.XOR_PEER_ADDRESS, encodeXorAddress({ ip: "127.0.0.1", port: 9 })]]);
    assert.equal(errorCode(r), 401);
    c.close();
  });
});

test("embedded TURN: controllers get the public turns URL with credentials, hosts get none", () => {
  const cfg = testConfig({ ice: { stunUrls: ["stun:s.example:3478"], turnUrls: ["turns:pc.example.ts.net:10000?transport=tcp"], turnSecret: SECRET, embeddedTurn: { port: 3478, relayIp: null } } });
  const phone = buildIceConfig(cfg, "phone1", Date.now(), "controller");
  assert.equal(phone.turn, true);
  assert.deepEqual(phone.iceServers[1]!.urls, ["turns:pc.example.ts.net:10000?transport=tcp"]);
  assert.equal(phone.iceServers[1]!.credential, restPassword(SECRET, phone.iceServers[1]!.username!));
  const host = buildIceConfig(cfg, "host1", Date.now(), "host");
  assert.equal(host.turn, true, "a relay path exists (it ends on the host's own machine)");
  assert.deepEqual(host.iceServers, [{ urls: ["stun:s.example:3478"] }]);
});

test("config: TURN_EMBEDDED_PORT enables the embedded server with a generated secret", () => {
  const cfg = loadConfig({ TURN_EMBEDDED_PORT: "3478", TURN_URLS: "turns:pc.example.ts.net:10000?transport=tcp" });
  assert.deepEqual(cfg.ice.embeddedTurn, { port: 3478, relayIp: null });
  assert.ok(cfg.ice.turnSecret && cfg.ice.turnSecret.length >= 32);
  assert.equal(loadConfig({}).ice.embeddedTurn, null);
  assert.equal(loadConfig({ TURN_EMBEDDED_PORT: "3478", TURN_RELAY_IP: "192.168.1.5" }).ice.embeddedTurn?.relayIp, "192.168.1.5");
});

test("startServer brings the embedded TURN up and points controllers at it when no public URL is set", async () => {
  const { startServer } = await import("../../src/server.ts");
  const cfg = testConfig({ ice: { stunUrls: [], turnUrls: [], turnSecret: SECRET, embeddedTurn: { port: 0, relayIp: "127.0.0.1" } } });
  const server = await startServer(cfg);
  try {
    assert.equal(cfg.ice.turnUrls.length, 1);
    const m = /^turn:127\.0\.0\.1:(\d+)\?transport=tcp$/.exec(cfg.ice.turnUrls[0]!);
    assert.ok(m, cfg.ice.turnUrls[0]);
    const c = new Client(Number(m![1])); await c.ready();
    const { username, password } = creds();
    const res = await c.allocate(username, password);
    assert.equal(res.cls, Class.SUCCESS);
    c.close();
  } finally {
    await server.close();
  }
});
