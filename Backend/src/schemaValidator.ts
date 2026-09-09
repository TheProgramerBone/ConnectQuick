import { readFileSync } from "node:fs";
import { randomUUID } from "node:crypto";
import { fileURLToPath } from "node:url";
// Named import (rather than the default export) sidesteps a default-interop resolution quirk
// between ajv's CJS output and TS's NodeNext module resolution.
import { Ajv2020, type ValidateFunction } from "ajv/dist/2020.js";
// ajv-formats only has a default export, which hits the same quirk with no named alternative;
// the cast is purely a type-level workaround — the CJS module genuinely exports a single
// callable (verified: node_modules/ajv-formats/dist/index.js sets `exports.default = formatsPlugin`).
import ajvFormatsCjs from "ajv-formats";
const addFormats = ajvFormatsCjs as unknown as (ajv: Ajv2020) => void;
import { ValidationError } from "./errors.ts";

export interface Envelope {
  v: 1;
  id: string;
  ts: number;
  type: string;
  payload: Record<string, unknown>;
}

const protocolDir = fileURLToPath(new URL("../../Protocol/schemas", import.meta.url));

function readJson(path: string): object {
  return JSON.parse(readFileSync(path, "utf8")) as object;
}

const envelopeSchema = readJson(`${protocolDir}/envelope.schema.json`) as Record<string, unknown>;
const payloadsSchema = readJson(`${protocolDir}/payloads.schema.json`) as {
  $defs: Record<string, unknown>;
};

const ajv = new Ajv2020({ allErrors: true, strict: true });
addFormats(ajv);
ajv.addSchema(payloadsSchema, "payloads");

const validateEnvelopeFn: ValidateFunction = ajv.compile(envelopeSchema);

const payloadValidators = new Map<string, ValidateFunction>();
function payloadValidatorFor(type: string): ValidateFunction | undefined {
  const key = type.replace(/\./g, "_");
  let fn = payloadValidators.get(key);
  if (fn) return fn;
  const def = payloadsSchema.$defs[key];
  if (!def) return undefined;
  fn = ajv.compile(def);
  payloadValidators.set(key, fn);
  return fn;
}

const MAX_MESSAGE_BYTES = 64 * 1024;

/** Parses and validates a raw WS text frame into a well-formed, schema-valid Envelope. */
export function parseEnvelope(raw: string | Buffer, maxBytes = MAX_MESSAGE_BYTES): Envelope {
  const byteLength = typeof raw === "string" ? Buffer.byteLength(raw, "utf8") : raw.length;
  if (byteLength > maxBytes) {
    throw new ValidationError(`message exceeds ${maxBytes} bytes`);
  }
  let data: unknown;
  try {
    data = JSON.parse(raw.toString("utf8"));
  } catch {
    throw new ValidationError("malformed JSON");
  }
  if (!validateEnvelopeFn(data)) {
    const detail = ajv.errorsText(validateEnvelopeFn.errors, { separator: "; " });
    throw new ValidationError(`invalid envelope: ${detail}`);
  }
  const envelope = data as Envelope;
  const payloadValidator = payloadValidatorFor(envelope.type);
  if (!payloadValidator) {
    throw new ValidationError(`unknown message type: ${envelope.type}`);
  }
  if (!payloadValidator(envelope.payload)) {
    const detail = ajv.errorsText(payloadValidator.errors, { separator: "; " });
    throw new ValidationError(`invalid payload for ${envelope.type}: ${detail}`);
  }
  return envelope;
}

export function makeEnvelope(type: string, payload: Record<string, unknown>): Envelope {
  return { v: 1, id: randomUUID(), ts: Date.now(), type, payload };
}
