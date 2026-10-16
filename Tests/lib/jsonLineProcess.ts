import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { createInterface } from "node:readline";

export type Json = Record<string, any>;

/**
 * A child process that speaks JSON lines: commands in on stdin, events and replies out on stdout.
 * Both headless harnesses (Windows Host, Android Controller) use this protocol, so scenarios read
 * the same way regardless of which platform is being driven.
 */
export class JsonLineProcess {
  readonly name: string;
  readonly events: Json[] = [];
  readonly stderr: string[] = [];
  private readonly child: ChildProcessWithoutNullStreams;
  private readonly pending = new Map<string, { resolve: (v: Json) => void; reject: (e: Error) => void; timer: NodeJS.Timeout }>();
  private readonly waiters: { pred: (e: Json) => boolean; resolve: (e: Json) => void; reject: (e: Error) => void; timer: NodeJS.Timeout; from: number }[] = [];
  private counter = 0;
  exited = false;
  exitCode: number | null = null;

  constructor(name: string, command: string, args: string[], env: Record<string, string> = {}, cwd?: string) {
    this.name = name;
    this.child = spawn(command, args, { env: { ...process.env, ...env }, cwd, stdio: ["pipe", "pipe", "pipe"] });
    createInterface({ input: this.child.stdout }).on("line", (line) => this.onLine(line));
    createInterface({ input: this.child.stderr }).on("line", (line) => {
      if (!line.includes("Picked up JAVA_TOOL_OPTIONS")) this.stderr.push(line);
    });
    this.child.on("exit", (code) => {
      this.exited = true;
      this.exitCode = code;
      for (const [, p] of this.pending) {
        clearTimeout(p.timer);
        p.reject(new Error(`${this.name} exited (${code}) before replying`));
      }
      this.pending.clear();
    });
  }

  private onLine(line: string): void {
    let msg: Json;
    try {
      msg = JSON.parse(line) as Json;
    } catch {
      this.stderr.push(`[non-json stdout] ${line}`);
      return;
    }
    if (msg["evt"] === "reply" && typeof msg["id"] === "string") {
      const p = this.pending.get(msg["id"]);
      if (p) {
        clearTimeout(p.timer);
        this.pending.delete(msg["id"]);
        p.resolve(msg);
      }
      return;
    }
    this.events.push(msg);
    for (let i = this.waiters.length - 1; i >= 0; i--) {
      const w = this.waiters[i]!;
      if (w.pred(msg)) {
        clearTimeout(w.timer);
        this.waiters.splice(i, 1);
        w.resolve(msg);
      }
    }
  }

  /** Sends one command and resolves with its reply (the "evt"/"id" bookkeeping fields are kept). */
  call(cmd: Json, timeoutMs = 30_000): Promise<Json> {
    const id = `c${++this.counter}`;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`${this.name}: no reply to ${JSON.stringify(cmd).slice(0, 120)} within ${timeoutMs} ms\n${this.diagnostics()}`));
      }, timeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      this.child.stdin.write(JSON.stringify({ ...cmd, id }) + "\n");
    });
  }

  /** Index to pass to waitEvent so only events from now on are considered. */
  mark(): number {
    return this.events.length;
  }

  waitEvent(pred: (e: Json) => boolean, timeoutMs = 15_000, from = 0): Promise<Json> {
    const existing = this.events.slice(from).find(pred);
    if (existing) return Promise.resolve(existing);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        const i = this.waiters.findIndex((w) => w.timer === timer);
        if (i >= 0) this.waiters.splice(i, 1);
        reject(new Error(`${this.name}: expected event never arrived within ${timeoutMs} ms\n${this.diagnostics()}`));
      }, timeoutMs);
      this.waiters.push({ pred, resolve, reject, timer, from });
    });
  }

  diagnostics(): string {
    const recent = this.events.slice(-8).map((e) => JSON.stringify(e).slice(0, 200)).join("\n  ");
    return `--- ${this.name} recent events:\n  ${recent}\n--- ${this.name} stderr:\n  ${this.stderr.slice(-8).join("\n  ")}`;
  }

  /** Abrupt termination (simulates a crash / power loss): no goodbye of any kind. */
  kill(): Promise<void> {
    return new Promise((resolve) => {
      if (this.exited) return resolve();
      this.child.once("exit", () => resolve());
      this.child.kill("SIGKILL");
    });
  }

  /** Polite shutdown: ask the harness to quit, fall back to kill. */
  async stop(): Promise<void> {
    if (this.exited) return;
    try {
      await this.call({ cmd: "quit" }, 4000);
    } catch {
      /* fall through */
    }
    await Promise.race([new Promise((r) => this.child.once("exit", r)), new Promise((r) => setTimeout(r, 3000))]);
    if (!this.exited) await this.kill();
  }
}
