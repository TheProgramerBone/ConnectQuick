import { createHash, createHmac, randomBytes, timingSafeEqual } from "node:crypto";
import { crc32 } from "node:zlib";

/**
 * Minimal STUN message codec (RFC 5389) with the TURN attributes of RFC 5766. Only what the
 * embedded TURN server needs: IPv4 XOR addresses, long-term-credential MESSAGE-INTEGRITY and
 * FINGERPRINT. Plain objects and functions (Node type stripping: no enums/namespaces).
 */

export const MAGIC_COOKIE = 0x2112a442;

export const Method = {
  BINDING: 0x001,
  ALLOCATE: 0x003,
  REFRESH: 0x004,
  SEND: 0x006,
  DATA: 0x007,
  CREATE_PERMISSION: 0x008,
  CHANNEL_BIND: 0x009,
} as const;

export const Class = { REQUEST: 0, INDICATION: 1, SUCCESS: 2, ERROR: 3 } as const;

export const Attr = {
  MAPPED_ADDRESS: 0x0001,
  USERNAME: 0x0006,
  MESSAGE_INTEGRITY: 0x0008,
  ERROR_CODE: 0x0009,
  CHANNEL_NUMBER: 0x000c,
  LIFETIME: 0x000d,
  XOR_PEER_ADDRESS: 0x0012,
  DATA: 0x0013,
  REALM: 0x0014,
  NONCE: 0x0015,
  XOR_RELAYED_ADDRESS: 0x0016,
  REQUESTED_TRANSPORT: 0x0019,
  XOR_MAPPED_ADDRESS: 0x0020,
  SOFTWARE: 0x8022,
  FINGERPRINT: 0x8028,
} as const;

export interface StunAttribute {
  type: number;
  value: Buffer;
  /** Byte offset of this attribute's header inside the original message (for integrity checks). */
  offset: number;
}

export interface StunMessage {
  method: number;
  cls: number;
  transactionId: Buffer;
  attributes: StunAttribute[];
  /** The exact bytes the message was parsed from. */
  raw: Buffer;
}

export interface Address {
  ip: string;
  port: number;
}

export function messageType(method: number, cls: number): number {
  return ((method & 0x0f80) << 2) | ((method & 0x0070) << 1) | (method & 0x000f) | ((cls & 0x2) << 7) | ((cls & 0x1) << 4);
}

function splitType(type: number): { method: number; cls: number } {
  const method = ((type & 0x3e00) >> 2) | ((type & 0x00e0) >> 1) | (type & 0x000f);
  const cls = ((type & 0x0100) >> 7) | ((type & 0x0010) >> 4);
  return { method, cls };
}

/** Parses one complete STUN message, or returns null if it is not a well-formed STUN message. */
export function parseStun(buf: Buffer): StunMessage | null {
  if (buf.length < 20 || (buf[0]! & 0xc0) !== 0) return null;
  const length = buf.readUInt16BE(2);
  if (buf.readUInt32BE(4) !== MAGIC_COOKIE || length % 4 !== 0 || buf.length !== 20 + length) return null;
  const { method, cls } = splitType(buf.readUInt16BE(0));
  const attributes: StunAttribute[] = [];
  let off = 20;
  while (off + 4 <= buf.length) {
    const type = buf.readUInt16BE(off);
    const len = buf.readUInt16BE(off + 2);
    if (off + 4 + len > buf.length) return null;
    attributes.push({ type, value: buf.subarray(off + 4, off + 4 + len), offset: off });
    off += 4 + len + ((4 - (len % 4)) % 4);
  }
  if (off !== buf.length) return null;
  return { method, cls, transactionId: Buffer.from(buf.subarray(8, 20)), attributes, raw: buf };
}

export function getAttr(m: StunMessage, type: number): Buffer | undefined {
  return m.attributes.find((a) => a.type === type)?.value;
}

export function decodeXorAddress(value: Buffer): Address | null {
  if (value.length < 8 || value[1] !== 0x01) return null; // IPv4 only
  const port = value.readUInt16BE(2) ^ (MAGIC_COOKIE >>> 16);
  const ipNum = (value.readUInt32BE(4) ^ MAGIC_COOKIE) >>> 0;
  return { ip: [ipNum >>> 24, (ipNum >>> 16) & 255, (ipNum >>> 8) & 255, ipNum & 255].join("."), port };
}

export function encodeXorAddress(a: Address): Buffer {
  const b = Buffer.alloc(8);
  b[1] = 0x01;
  b.writeUInt16BE(a.port ^ (MAGIC_COOKIE >>> 16), 2);
  const parts = a.ip.split(".").map(Number);
  const ipNum = ((parts[0]! << 24) | (parts[1]! << 16) | (parts[2]! << 8) | parts[3]!) >>> 0;
  b.writeUInt32BE((ipNum ^ MAGIC_COOKIE) >>> 0, 4);
  return b;
}

/** Long-term credential key (RFC 5389 §15.4): MD5(username ":" realm ":" password). */
export function longTermKey(username: string, realm: string, password: string): Buffer {
  return createHash("md5").update(`${username}:${realm}:${password}`).digest();
}

export interface BuildOptions {
  /** Adds MESSAGE-INTEGRITY with this key (then FINGERPRINT). */
  integrityKey?: Buffer;
  fingerprint?: boolean;
}

/** Serialises a message. Attributes are written in order; MESSAGE-INTEGRITY and FINGERPRINT are appended last. */
export function buildStun(method: number, cls: number, transactionId: Buffer, attrs: Array<[number, Buffer]>, opts: BuildOptions = {}): Buffer {
  const parts: Buffer[] = [];
  for (const [type, value] of attrs) parts.push(tlv(type, value));
  let body = Buffer.concat(parts);
  const header = (extra: number): Buffer => {
    const h = Buffer.alloc(20);
    h.writeUInt16BE(messageType(method, cls), 0);
    h.writeUInt16BE(body.length + extra, 2);
    h.writeUInt32BE(MAGIC_COOKIE, 4);
    transactionId.copy(h, 8, 0, 12);
    return h;
  };
  if (opts.integrityKey) {
    const mac = createHmac("sha1", opts.integrityKey).update(Buffer.concat([header(24), body])).digest();
    body = Buffer.concat([body, tlv(Attr.MESSAGE_INTEGRITY, mac)]);
  }
  if (opts.fingerprint ?? true) {
    const crc = (crc32(Buffer.concat([header(8), body])) ^ 0x5354554e) >>> 0;
    const v = Buffer.alloc(4);
    v.writeUInt32BE(crc, 0);
    body = Buffer.concat([body, tlv(Attr.FINGERPRINT, v)]);
  }
  return Buffer.concat([header(0), body]);
}

function tlv(type: number, value: Buffer): Buffer {
  const pad = (4 - (value.length % 4)) % 4;
  const b = Buffer.alloc(4 + value.length + pad);
  b.writeUInt16BE(type, 0);
  b.writeUInt16BE(value.length, 2);
  value.copy(b, 4);
  return b;
}

/** Checks MESSAGE-INTEGRITY: HMAC over the message up to that attribute, with the length field covering it. */
export function verifyIntegrity(m: StunMessage, key: Buffer): boolean {
  const mi = m.attributes.find((a) => a.type === Attr.MESSAGE_INTEGRITY);
  if (!mi || mi.value.length !== 20) return false;
  const signed = Buffer.from(m.raw.subarray(0, mi.offset));
  signed.writeUInt16BE(mi.offset + 24 - 20, 2);
  const expected = createHmac("sha1", key).update(signed).digest();
  return timingSafeEqual(expected, mi.value);
}

export function errorCodeValue(code: number, reason: string): Buffer {
  const r = Buffer.from(reason, "utf8");
  const b = Buffer.alloc(4 + r.length);
  b[2] = Math.floor(code / 100);
  b[3] = code % 100;
  r.copy(b, 4);
  return b;
}

export function u32(n: number): Buffer {
  const b = Buffer.alloc(4);
  b.writeUInt32BE(n >>> 0, 0);
  return b;
}

export function newTransactionId(): Buffer {
  return randomBytes(12);
}

/** TURN ChannelData frame (RFC 5766 §11.4), padded to 4 bytes as required over TCP. */
export function buildChannelData(channel: number, data: Buffer): Buffer {
  const pad = (4 - (data.length % 4)) % 4;
  const b = Buffer.alloc(4 + data.length + pad);
  b.writeUInt16BE(channel, 0);
  b.writeUInt16BE(data.length, 2);
  data.copy(b, 4);
  return b;
}

/**
 * Splits a TCP byte stream into STUN messages and ChannelData frames. Returns complete frames and the
 * unconsumed tail. A frame that can never be valid (bad first bits) is reported so the caller can
 * close the connection instead of buffering garbage.
 */
export function splitFrames(buf: Buffer): { frames: Buffer[]; rest: Buffer; invalid: boolean } {
  const frames: Buffer[] = [];
  let off = 0;
  while (buf.length - off >= 4) {
    const first = buf[off]!;
    let total: number;
    if ((first & 0xc0) === 0x00) {
      total = 20 + buf.readUInt16BE(off + 2);
    } else if ((first & 0xc0) === 0x40) {
      const len = buf.readUInt16BE(off + 2);
      total = 4 + len + ((4 - (len % 4)) % 4);
    } else {
      return { frames, rest: Buffer.alloc(0), invalid: true };
    }
    if (buf.length - off < total) break;
    frames.push(buf.subarray(off, off + total));
    off += total;
  }
  return { frames, rest: buf.subarray(off), invalid: false };
}
