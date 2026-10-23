import { createHmac, randomBytes } from "node:crypto";
import dgram from "node:dgram";
import net from "node:net";
import os from "node:os";
import type { Logger } from "../log.ts";
import {
  Attr,
  Class,
  Method,
  buildChannelData,
  buildStun,
  decodeXorAddress,
  encodeXorAddress,
  errorCodeValue,
  getAttr,
  longTermKey,
  parseStun,
  splitFrames,
  u32,
  verifyIntegrity,
  type Address,
  type StunMessage,
} from "./stun.ts";

/**
 * A deliberately small TURN server (RFC 5766: client over TCP, relayed transport UDP) embedded in
 * the backend, so a PC that is its own relay (Tailscale Funnel, `Docs/ACCESO_REMOTO.md`) can also
 * carry the video when the phone and the PC are on different networks, with no coturn to install.
 *
 * Shape of the path: phone ══TLS (Funnel :10000)══► this TCP port ──UDP relay socket on the PC──► the
 * PC's own WebRTC host candidate. Because the only legitimate peer is the PC itself, peers are
 * restricted to this machine's own addresses: it can never be used as an open relay to the Internet.
 * Credentials are the same short-lived HMAC (coturn REST) ones `ice.ts` mints for authenticated devices.
 * The relay only ever sees DTLS-SRTP ciphertext.
 */
export interface TurnServerOptions {
  port: number;
  /** Address put in XOR-RELAYED-ADDRESS; must be reachable by the PC's WebRTC stack (its LAN IP or 127.0.0.1). */
  relayIp: string;
  secret: string;
  realm: string;
  log: Logger;
  maxAllocations?: number;
  maxAllocationsPerUser?: number;
  maxConnections?: number;
  /** Override for tests: which peer IPs count as "this machine". */
  isAllowedPeer?: (ip: string) => boolean;
  nowMs?: () => number;
}

const DEFAULT_LIFETIME = 600;
const MAX_LIFETIME = 3600;
const PERMISSION_LIFETIME_MS = 300_000;
const CHANNEL_LIFETIME_MS = 600_000;
const MAX_FRAME_BUFFER = 70_000;

interface Allocation {
  socket: dgram.Socket;
  relay: Address;
  username: string;
  key: Buffer;
  expiresAt: number;
  permissions: Map<string, number>;
  channels: Map<number, { peer: Address; expiresAt: number }>;
  peerChannels: Map<string, number>;
}

interface ClientState {
  sock: net.Socket;
  nonce: string;
  buffer: Buffer;
  allocation: Allocation | null;
}

export interface StartedTurnServer {
  port: number;
  close: () => Promise<void>;
  /** Live allocation count (for tests and metrics). */
  allocations: () => number;
}

/** IPv4 addresses of this machine (plus loopback): the only peers a relay may talk to. */
export function localIPv4Addresses(): Set<string> {
  const out = new Set<string>(["127.0.0.1"]);
  for (const list of Object.values(os.networkInterfaces())) {
    for (const a of list ?? []) if (a.family === "IPv4") out.add(a.address);
  }
  return out;
}

/** First non-internal IPv4 address, or loopback. */
export function defaultRelayIp(): string {
  for (const list of Object.values(os.networkInterfaces())) {
    for (const a of list ?? []) if (a.family === "IPv4" && !a.internal) return a.address;
  }
  return "127.0.0.1";
}

/** coturn REST password for a username (same formula as `ice.ts`). */
export function restPassword(secret: string, username: string): string {
  return createHmac("sha1", secret).update(username).digest("base64");
}

export function startTurnServer(opts: TurnServerOptions): Promise<StartedTurnServer> {
  const now = opts.nowMs ?? Date.now;
  const maxAllocations = opts.maxAllocations ?? 32;
  const maxPerUser = opts.maxAllocationsPerUser ?? 4;
  const maxConnections = opts.maxConnections ?? 64;
  const isAllowedPeer = opts.isAllowedPeer ?? ((ip: string) => ip.startsWith("127.") || localIPv4Addresses().has(ip));
  const clients = new Set<ClientState>();
  const log = opts.log;

  const allAllocations = (): Allocation[] => [...clients].map((c) => c.allocation).filter((a): a is Allocation => a !== null);

  const freeAllocation = (c: ClientState): void => {
    const a = c.allocation;
    if (!a) return;
    c.allocation = null;
    try { a.socket.close(); } catch { /* already closed */ }
  };

  const send = (c: ClientState, b: Buffer): void => {
    if (!c.sock.destroyed) c.sock.write(b);
  };

  const reply = (c: ClientState, req: StunMessage, attrs: Array<[number, Buffer]>, key?: Buffer): void =>
    send(c, buildStun(req.method, Class.SUCCESS, req.transactionId, attrs, { integrityKey: key }));

  const fail = (c: ClientState, req: StunMessage, code: number, reason: string, key?: Buffer, extra: Array<[number, Buffer]> = []): void =>
    send(c, buildStun(req.method, Class.ERROR, req.transactionId, [[Attr.ERROR_CODE, errorCodeValue(code, reason)], ...extra], { integrityKey: key }));

  const unauthorized = (c: ClientState, req: StunMessage): void =>
    fail(c, req, 401, "Unauthorized", undefined, [[Attr.REALM, Buffer.from(opts.realm)], [Attr.NONCE, Buffer.from(c.nonce)]]);

  /** Long-term credential check with the REST username "<expiry>:<id>". Returns the key, or null after replying 401. */
  const authenticate = (c: ClientState, req: StunMessage): { username: string; key: Buffer } | null => {
    const user = getAttr(req, Attr.USERNAME)?.toString("utf8");
    const realm = getAttr(req, Attr.REALM)?.toString("utf8");
    const nonce = getAttr(req, Attr.NONCE)?.toString("utf8");
    if (!user || realm !== opts.realm || nonce !== c.nonce || !getAttr(req, Attr.MESSAGE_INTEGRITY)) {
      unauthorized(c, req);
      return null;
    }
    const expiry = Number(user.split(":")[0]);
    if (!Number.isFinite(expiry) || expiry * 1000 < now()) {
      unauthorized(c, req);
      return null;
    }
    const key = longTermKey(user, opts.realm, restPassword(opts.secret, user));
    if (!verifyIntegrity(req, key)) {
      unauthorized(c, req);
      return null;
    }
    return { username: user, key };
  };

  const onPeerPacket = (c: ClientState, a: Allocation, msg: Buffer, rinfo: dgram.RemoteInfo): void => {
    const perm = a.permissions.get(rinfo.address);
    if (!perm || perm < now()) return; // RFC 5766 §10.3: drop silently without a permission
    const ch = a.peerChannels.get(`${rinfo.address}:${rinfo.port}`);
    if (ch !== undefined) {
      send(c, buildChannelData(ch, msg));
    } else {
      send(c, buildStun(Method.DATA, Class.INDICATION, randomBytes(12), [
        [Attr.XOR_PEER_ADDRESS, encodeXorAddress({ ip: rinfo.address, port: rinfo.port })],
        [Attr.DATA, msg],
      ], { fingerprint: false }));
    }
  };

  const handleAllocate = (c: ClientState, req: StunMessage): void => {
    const auth = authenticate(c, req);
    if (!auth) return;
    if (c.allocation) return fail(c, req, 437, "Allocation Mismatch", auth.key);
    const transport = getAttr(req, Attr.REQUESTED_TRANSPORT);
    if (!transport || transport[0] !== 17) return fail(c, req, 442, "Unsupported Transport Protocol", auth.key);
    const all = allAllocations();
    if (all.length >= maxAllocations || all.filter((x) => x.username.split(":")[1] === auth.username.split(":")[1]).length >= maxPerUser) {
      return fail(c, req, 486, "Allocation Quota Reached", auth.key);
    }
    const requested = getAttr(req, Attr.LIFETIME);
    const lifetime = Math.min(MAX_LIFETIME, requested ? requested.readUInt32BE(0) || DEFAULT_LIFETIME : DEFAULT_LIFETIME);
    const socket = dgram.createSocket("udp4");
    socket.once("error", (err) => {
      log.warn("turn relay socket error", { message: err.message });
      freeAllocation(c);
    });
    socket.bind(0, () => {
      if (c.sock.destroyed) { try { socket.close(); } catch { /* ignore */ } return; }
      const relay = { ip: opts.relayIp, port: socket.address().port };
      const a: Allocation = {
        socket, relay, username: auth.username, key: auth.key, expiresAt: now() + lifetime * 1000,
        permissions: new Map(), channels: new Map(), peerChannels: new Map(),
      };
      c.allocation = a;
      socket.on("message", (msg, rinfo) => onPeerPacket(c, a, msg, rinfo));
      const mapped = { ip: (c.sock.remoteAddress ?? "127.0.0.1").replace(/^::ffff:/, ""), port: c.sock.remotePort ?? 0 };
      reply(c, req, [
        [Attr.XOR_RELAYED_ADDRESS, encodeXorAddress(relay)],
        [Attr.XOR_MAPPED_ADDRESS, encodeXorAddress(mapped.ip.includes(":") ? { ip: "127.0.0.1", port: mapped.port } : mapped)],
        [Attr.LIFETIME, u32(lifetime)],
      ], auth.key);
      log.info("turn allocation", { relayPort: relay.port, lifetime });
    });
  };

  /** Requests after Allocate must carry valid integrity with the allocation's own credentials. */
  const authorizeExisting = (c: ClientState, req: StunMessage): Allocation | null => {
    const a = c.allocation;
    if (!a) { fail(c, req, 437, "Allocation Mismatch"); return null; }
    const user = getAttr(req, Attr.USERNAME)?.toString("utf8");
    if (user !== a.username || getAttr(req, Attr.NONCE)?.toString("utf8") !== c.nonce || !verifyIntegrity(req, a.key)) {
      unauthorized(c, req);
      return null;
    }
    return a;
  };

  const handleRefresh = (c: ClientState, req: StunMessage): void => {
    const a = authorizeExisting(c, req);
    if (!a) return;
    const requested = getAttr(req, Attr.LIFETIME);
    const lifetime = requested ? Math.min(MAX_LIFETIME, requested.readUInt32BE(0)) : DEFAULT_LIFETIME;
    if (lifetime === 0) freeAllocation(c);
    else a.expiresAt = now() + lifetime * 1000;
    reply(c, req, [[Attr.LIFETIME, u32(lifetime)]], a.key);
  };

  const peersOf = (req: StunMessage): Array<Address | null> =>
    req.attributes.filter((x) => x.type === Attr.XOR_PEER_ADDRESS).map((x) => decodeXorAddress(x.value));

  const handleCreatePermission = (c: ClientState, req: StunMessage): void => {
    const a = authorizeExisting(c, req);
    if (!a) return;
    const peers = peersOf(req);
    if (peers.length === 0) return fail(c, req, 400, "Bad Request", a.key);
    if (peers.some((p) => p === null)) return fail(c, req, 443, "Peer Address Family Mismatch", a.key);
    if (peers.some((p) => !isAllowedPeer(p!.ip))) return fail(c, req, 403, "Forbidden", a.key);
    for (const p of peers) a.permissions.set(p!.ip, now() + PERMISSION_LIFETIME_MS);
    reply(c, req, [], a.key);
  };

  const handleChannelBind = (c: ClientState, req: StunMessage): void => {
    const a = authorizeExisting(c, req);
    if (!a) return;
    const chAttr = getAttr(req, Attr.CHANNEL_NUMBER);
    const peer = peersOf(req)[0];
    const ch = chAttr ? chAttr.readUInt16BE(0) : 0;
    if (!chAttr || ch < 0x4000 || ch > 0x7ffe || peer === undefined) return fail(c, req, 400, "Bad Request", a.key);
    if (peer === null) return fail(c, req, 443, "Peer Address Family Mismatch", a.key);
    if (!isAllowedPeer(peer.ip)) return fail(c, req, 403, "Forbidden", a.key);
    const peerKey = `${peer.ip}:${peer.port}`;
    const existing = a.channels.get(ch);
    const existingForPeer = a.peerChannels.get(peerKey);
    if ((existing && `${existing.peer.ip}:${existing.peer.port}` !== peerKey) || (existingForPeer !== undefined && existingForPeer !== ch)) {
      return fail(c, req, 400, "Bad Request", a.key);
    }
    a.channels.set(ch, { peer, expiresAt: now() + CHANNEL_LIFETIME_MS });
    a.peerChannels.set(peerKey, ch);
    a.permissions.set(peer.ip, now() + PERMISSION_LIFETIME_MS);
    reply(c, req, [], a.key);
  };

  const relayToPeer = (a: Allocation, peer: Address, data: Buffer): void => {
    const perm = a.permissions.get(peer.ip);
    if (!perm || perm < now() || !isAllowedPeer(peer.ip)) return;
    a.socket.send(data, peer.port, peer.ip);
  };

  const handleFrame = (c: ClientState, frame: Buffer): void => {
    if ((frame[0]! & 0xc0) === 0x40) {
      const a = c.allocation;
      if (!a) return;
      const ch = a.channels.get(frame.readUInt16BE(0));
      if (!ch || ch.expiresAt < now()) return;
      relayToPeer(a, ch.peer, frame.subarray(4, 4 + frame.readUInt16BE(2)));
      return;
    }
    const m = parseStun(frame);
    if (!m) return;
    if (m.cls === Class.INDICATION && m.method === Method.SEND) {
      const a = c.allocation;
      const peerAttr = getAttr(m, Attr.XOR_PEER_ADDRESS);
      const data = getAttr(m, Attr.DATA);
      const peer = peerAttr ? decodeXorAddress(peerAttr) : null;
      if (a && peer && data) relayToPeer(a, peer, data);
      return;
    }
    if (m.cls !== Class.REQUEST) return;
    switch (m.method) {
      case Method.BINDING: {
        const ip = (c.sock.remoteAddress ?? "127.0.0.1").replace(/^::ffff:/, "");
        reply(c, m, [[Attr.XOR_MAPPED_ADDRESS, encodeXorAddress({ ip: ip.includes(":") ? "127.0.0.1" : ip, port: c.sock.remotePort ?? 0 })]]);
        return;
      }
      case Method.ALLOCATE: return handleAllocate(c, m);
      case Method.REFRESH: return handleRefresh(c, m);
      case Method.CREATE_PERMISSION: return handleCreatePermission(c, m);
      case Method.CHANNEL_BIND: return handleChannelBind(c, m);
      default: return fail(c, m, 400, "Bad Request");
    }
  };

  const server = net.createServer((sock) => {
    if (clients.size >= maxConnections) { sock.destroy(); return; }
    sock.setNoDelay(true);
    const c: ClientState = { sock, nonce: randomBytes(12).toString("hex"), buffer: Buffer.alloc(0), allocation: null };
    clients.add(c);
    sock.on("data", (chunk: Buffer) => {
      c.buffer = c.buffer.length === 0 ? chunk : Buffer.concat([c.buffer, chunk]);
      const { frames, rest, invalid } = splitFrames(c.buffer);
      if (invalid || rest.length > MAX_FRAME_BUFFER) { sock.destroy(); return; }
      c.buffer = Buffer.from(rest);
      for (const f of frames) {
        try { handleFrame(c, f); } catch (err) { log.warn("turn frame failed", { message: (err as Error).message }); }
      }
    });
    const cleanup = (): void => { freeAllocation(c); clients.delete(c); };
    sock.on("close", cleanup);
    sock.on("error", () => sock.destroy());
  });

  const sweep = setInterval(() => {
    const t = now();
    for (const c of clients) {
      const a = c.allocation;
      if (!a) continue;
      if (a.expiresAt < t) { freeAllocation(c); continue; }
      for (const [ip, exp] of a.permissions) if (exp < t) a.permissions.delete(ip);
      for (const [ch, info] of a.channels) {
        if (info.expiresAt < t) { a.channels.delete(ch); a.peerChannels.delete(`${info.peer.ip}:${info.peer.port}`); }
      }
    }
  }, 10_000);
  sweep.unref();

  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(opts.port, () => {
      const addr = server.address();
      const port = typeof addr === "object" && addr ? addr.port : opts.port;
      log.info("turn listening", { port, relayIp: opts.relayIp });
      resolve({
        port,
        allocations: () => allAllocations().length,
        close: () => new Promise<void>((res) => {
          clearInterval(sweep);
          for (const c of clients) { freeAllocation(c); c.sock.destroy(); }
          server.close(() => res());
        }),
      });
    });
  });
}
