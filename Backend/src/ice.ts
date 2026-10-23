import { createHmac } from "node:crypto";
import type { Config } from "./config.ts";

export interface IceServer {
  urls: string[];
  username?: string;
  credential?: string;
}

export interface IceConfigPayload {
  iceServers: IceServer[];
  ttlSeconds: number;
  stun: boolean;
  turn: boolean;
}

/**
 * Builds the ICE server list a client should hand to its WebRTC stack.
 *
 * TURN credentials use the coturn "REST API" scheme (`use-auth-secret` / `static-auth-secret`):
 * username = "<expiryUnixSeconds>:<deviceId>", credential = base64(HMAC-SHA1(secret, username)).
 * They are minted per request, per device, and stop working at expiry, so the long-lived
 * secret never leaves the backend and a leaked credential is only useful for `ttlSeconds`.
 */
export function buildIceConfig(config: Config, deviceId: string, nowMs = Date.now(), role: "host" | "controller" | null = null): IceConfigPayload {
  const iceServers: IceServer[] = [];
  const { stunUrls, turnUrls, turnSecret, turnTtlSeconds, embeddedTurn } = config.ice;
  if (stunUrls.length > 0) iceServers.push({ urls: stunUrls.slice(0, 4) });
  let turn = false;
  if (embeddedTurn && role === "host" && turnUrls.length > 0 && turnSecret) {
    // Embedded TURN relays end on this machine, the host's own: the host reaches them through its
    // host candidates and needs no TURN client (and must not hairpin out through the public address).
    // A relay path still exists, so the host is told so (it changes the failure wording).
    return { iceServers, ttlSeconds: 0, stun: stunUrls.length > 0, turn: true };
  }
  if (turnUrls.length > 0 && turnSecret) {
    const expiry = Math.floor(nowMs / 1000) + turnTtlSeconds;
    const username = `${expiry}:${deviceId}`;
    const credential = createHmac("sha1", turnSecret).update(username).digest("base64");
    iceServers.push({ urls: turnUrls.slice(0, 4), username, credential });
    turn = true;
  }
  return { iceServers, ttlSeconds: turn ? turnTtlSeconds : 0, stun: stunUrls.length > 0, turn };
}
