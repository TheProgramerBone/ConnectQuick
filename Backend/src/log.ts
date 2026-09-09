/**
 * Structured logger. Never log secrets: private keys, session keys, tokens, signatures,
 * clipboard content, file contents, or raw message payloads that might carry them.
 * Call sites pass a plain `fields` object; keep it to identifiers/metadata only.
 */

export type LogLevel = "TRACE" | "DEBUG" | "INFO" | "WARN" | "ERROR";

const LEVEL_ORDER: Record<LogLevel, number> = {
  TRACE: 0,
  DEBUG: 1,
  INFO: 2,
  WARN: 3,
  ERROR: 4,
};

const REDACTED_KEYS = new Set([
  "privateKey",
  "identityPriv",
  "sessionKey",
  "password",
  "token",
  "challengeSig",
  "hostSignature",
  "signature",
  "clipboard",
]);

function redact(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(redact);
  if (value && typeof value === "object") {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value)) {
      out[k] = REDACTED_KEYS.has(k) ? "[redacted]" : redact(v);
    }
    return out;
  }
  return value;
}

export class Logger {
  private readonly scope: string;
  private readonly minLevel: LogLevel;

  constructor(scope: string, minLevel: LogLevel = (process.env["LOG_LEVEL"] as LogLevel) || "INFO") {
    this.scope = scope;
    this.minLevel = minLevel;
  }

  private log(level: LogLevel, message: string, fields?: Record<string, unknown>): void {
    if (LEVEL_ORDER[level] < LEVEL_ORDER[this.minLevel]) return;
    const entry = {
      ts: new Date().toISOString(),
      level,
      scope: this.scope,
      message,
      ...(fields ? { fields: redact(fields) } : {}),
    };
    const line = JSON.stringify(entry);
    if (level === "ERROR" || level === "WARN") {
      console.error(line);
    } else {
      console.log(line);
    }
  }

  trace(message: string, fields?: Record<string, unknown>): void {
    this.log("TRACE", message, fields);
  }
  debug(message: string, fields?: Record<string, unknown>): void {
    this.log("DEBUG", message, fields);
  }
  info(message: string, fields?: Record<string, unknown>): void {
    this.log("INFO", message, fields);
  }
  warn(message: string, fields?: Record<string, unknown>): void {
    this.log("WARN", message, fields);
  }
  error(message: string, fields?: Record<string, unknown>): void {
    this.log("ERROR", message, fields);
  }

  child(subScope: string): Logger {
    return new Logger(`${this.scope}:${subScope}`, this.minLevel);
  }
}
