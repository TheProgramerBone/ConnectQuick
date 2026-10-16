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
export function buildIceConfig(config: Config, deviceId: string, nowMs = Date.now()): IceConfigPayload {
  const iceServers: IceServer[] = [];
  const { stunUrls, turnUrls, turnSecret, turnTtlSeconds } = config.ice;
  if (stunUrls.length > 0) iceServers.push({ urls: stunUrls.slice(0, 4) });
  let turn = false;
  if (turnUrls.length > 0 && turnSecret) {
    const expiry = Math.floor(nowMs / 1000) + turnTtlSeconds;
    const username = `${expiry}:${deviceId}`;
    const credential = createHmac("sha1", turnSecret).update(username).digest("base64");
    iceServers.push({ urls: turnUrls.slice(0, 4), username, credential });
    turn = true;
  }
  return { iceServers, ttlSeconds: turn ? turnTtlSeconds : 0, stun: stunUrls.length > 0, turn };
}
