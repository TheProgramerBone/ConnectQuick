import type { Envelope } from "../../schemaValidator.ts";
import type { Connection } from "../connection.ts";
import type { AppContext } from "../context.ts";
import { buildIceConfig } from "../../ice.ts";

/** Any authenticated Host or Controller may ask for ICE servers; credentials are per-device and short-lived. */
export function handleIceRequest(ctx: AppContext, conn: Connection, _envelope: Envelope): void {
  conn.send("ice.config", { ...buildIceConfig(ctx.config, conn.deviceId!) });
}
