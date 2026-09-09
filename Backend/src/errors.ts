export class ProtocolError extends Error {
  code: string;

  constructor(code: string, message: string) {
    super(message);
    this.name = "ProtocolError";
    this.code = code;
  }
}

export class ValidationError extends ProtocolError {
  constructor(message: string) {
    super("invalid_message", message);
    this.name = "ValidationError";
  }
}

export class AuthError extends ProtocolError {
  constructor(message: string) {
    super("auth_failed", message);
    this.name = "AuthError";
  }
}

export class RateLimitedError extends ProtocolError {
  constructor(message: string) {
    super("rate_limited", message);
    this.name = "RateLimitedError";
  }
}

export class NotFoundError extends ProtocolError {
  constructor(message: string) {
    super("not_found", message);
    this.name = "NotFoundError";
  }
}

export class ExpiredError extends ProtocolError {
  constructor(message: string) {
    super("expired", message);
    this.name = "ExpiredError";
  }
}

export class ForbiddenError extends ProtocolError {
  constructor(message: string) {
    super("forbidden", message);
    this.name = "ForbiddenError";
  }
}
