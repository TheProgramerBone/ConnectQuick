import type { Envelope } from "../../schemaValidator.ts";
import type { Connection } from "../connection.ts";
import type { AppContext } from "../context.ts";

export function handleHeartbeatPing(_ctx: AppContext, conn: Connection, envelope: Envelope): void {
  const { seq } = envelope.payload as { seq: number };
  conn.send("heartbeat.pong", { seq });
}
