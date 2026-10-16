import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { Ajv2020 } from "ajv/dist/2020.js";

const schemaPath = fileURLToPath(new URL("../../Protocol/schemas/session-messages.schema.json", import.meta.url));
const schema = JSON.parse(readFileSync(schemaPath, "utf8")) as { $defs: Record<string, object> };

const ajv = new Ajv2020({ allErrors: true, strict: true });
const validators = new Map(Object.entries(schema.$defs).map(([key, def]) => [key, ajv.compile(def)]));

export const MAX_INNER_BYTES = 40_000;
export const INNER_TYPES = Object.keys(schema.$defs).map((k) => k.replace("_", "."));

/** Validates one decoded inner message. Returns null when valid, else a short reason. */
export function validateInner(json: string): string | null {
  if (Buffer.byteLength(json, "utf8") > MAX_INNER_BYTES) return "too_large";
  let value: unknown;
  try {
    value = JSON.parse(json);
  } catch {
    return "malformed_json";
  }
  if (typeof value !== "object" || value === null || Array.isArray(value)) return "not_an_object";
  const t = (value as { t?: unknown }).t;
  if (typeof t !== "string") return "missing_type";
  const validate = validators.get(t.replace(".", "_"));
  if (!validate) return "unknown_type";
  return validate(value) ? null : "schema";
}
