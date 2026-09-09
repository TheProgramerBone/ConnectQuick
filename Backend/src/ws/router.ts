import type { Envelope } from "../schemaValidator.ts";
import type { Connection } from "./connection.ts";
import type { AppContext } from "./context.ts";
import { AuthError, ValidationError } from "../errors.ts";
import { handleHello } from "./handlers/hello.ts";
import { handleHeartbeatPing } from "./handlers/heartbeat.ts";
import {
  handlePairingCreate,
  handlePairingInit,
  handlePairingDecision,
  handlePairingRevoke,
} from "./handlers/pairing.ts";
import { handleSessionConnect, handleSessionState } from "./handlers/session.ts";

type Handler = (ctx: AppContext, conn: Connection, envelope: Envelope) => void;

/**
 * Only these types may ever originate from a client. Everything else in the envelope schema's
 * `type` enum (hello.challenge, hello.ack, heartbeat.pong, error, pairing.created,
 * pairing.incoming, pairing.result, pairing.revoked, presence.update) is backend/relay-only —
 * a client sending one is rejected here, never silently accepted.
 */
const CLIENT_ORIGINATED_HANDLERS: Record<string, Handler> = {
  hello: handleHello,
  "heartbeat.ping": handleHeartbeatPing,
  "pairing.create": handlePairingCreate,
  "pairing.init": handlePairingInit,
  "pairing.decision": handlePairingDecision,
  "pairing.revoke": handlePairingRevoke,
  "session.connect": handleSessionConnect,
  "session.state": handleSessionState,
};

export function route(ctx: AppContext, conn: Connection, envelope: Envelope): void {
  if (envelope.type !== "hello" && conn.state !== "CONNECTED") {
    throw new AuthError(`message type ${envelope.type} not allowed before authentication`);
  }
  const handler = CLIENT_ORIGINATED_HANDLERS[envelope.type];
  if (!handler) {
    throw new ValidationError(`message type ${envelope.type} is not accepted from clients`);
  }
  handler(ctx, conn, envelope);
}
