import { connect as tcpConnect, createServer } from "node:net";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { spawn, type ChildProcess } from "node:child_process";
import { JsonLineProcess, type Json } from "./jsonLineProcess.ts";

const here = dirname(fileURLToPath(import.meta.url));
export const repoRoot = resolve(here, "..", "..");

export async function freePort(): Promise<number> {
  return new Promise((res, rej) => {
    const s = createServer();
    s.listen(0, "127.0.0.1", () => {
      const port = (s.address() as { port: number }).port;
      s.close(() => res(port));
    });
    s.on("error", rej);
  });
}

export async function eventually<T>(fn: () => T | Promise<T>, what: string, timeoutMs = 10_000, everyMs = 50): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  let last: unknown;
  while (Date.now() < deadline) {
    try {
      const v = await fn();
      if (v) return v;
    } catch (e) {
      last = e;
    }
    await new Promise((r) => setTimeout(r, everyMs));
  }
  throw new Error(`timed out waiting for: ${what}${last ? ` (last error: ${String(last)})` : ""}`);
}

/** The real backend (Backend/src/server.ts) as a child process, restartable on the same port and database. */
export class BackendProcess {
  readonly port: number;
  readonly dbPath: string;
  private child: ChildProcess | null = null;
  private readonly extraEnv: Record<string, string>;
  readonly dir: string;

  private constructor(port: number, dir: string, extraEnv: Record<string, string>) {
    this.port = port;
    this.dir = dir;
    this.dbPath = join(dir, "backend.sqlite");
    this.extraEnv = extraEnv;
  }

  static async start(extraEnv: Record<string, string> = {}): Promise<BackendProcess> {
    const b = new BackendProcess(await freePort(), mkdtempSync(join(tmpdir(), "cq-backend-")), extraEnv);
    await b.launch();
    return b;
  }

  get url(): string {
    return `ws://127.0.0.1:${this.port}`;
  }

  private async launch(): Promise<void> {
    this.child = spawn("node", ["src/server.ts"], {
      cwd: join(repoRoot, "Backend"),
      env: { ...process.env, PORT: String(this.port), DB_PATH: this.dbPath, LOG_LEVEL: "WARN", ...this.extraEnv },
      stdio: ["ignore", "ignore", "ignore"],
    });
    await eventually(async () => (await fetch(`http://127.0.0.1:${this.port}/healthz`)).ok, "backend /healthz", 15_000, 100);
  }

  /** Simulates a crash / deploy: the process dies abruptly, then comes back on the same port with the same database. */
  async restart(downMs = 0): Promise<void> {
    await this.kill();
    if (downMs) await new Promise((r) => setTimeout(r, downMs));
    await this.launch();
  }

  kill(): Promise<void> {
    return new Promise((res) => {
      // A process killed by a signal has exitCode === null but signalCode set: both mean "already gone".
      if (!this.child || this.child.exitCode !== null || this.child.signalCode !== null) return res();
      this.child.once("exit", () => res());
      this.child.kill("SIGKILL");
    });
  }

  async stop(): Promise<void> {
    await this.kill();
    rmSync(this.dir, { recursive: true, force: true });
  }
}

function hostHarnessCommand(): { command: string; args: string[] } {
  const dll = process.env["CQ_HOST_HARNESS"] ?? join(repoRoot, "WindowsHost/src/ConnectQuick.WindowsHost.HostHarness/bin/Debug/net8.0/ConnectQuick.WindowsHost.HostHarness.dll");
  return { command: "dotnet", args: [dll] };
}

function controllerHarnessCommand(): { command: string; args: string[] } {
  const cp = process.env["CQ_CONTROLLER_CP"];
  if (!cp) throw new Error("set CQ_CONTROLLER_CP (see Tests/README.md: gradle -q printHarnessClasspath)");
  return { command: "java", args: ["-cp", cp, `-Dconnectquick.repoRoot=${repoRoot}`, "dev.connectquick.controller.harness.ControllerHarnessKt"] };
}

export interface Peers {
  host: JsonLineProcess;
  phone: JsonLineProcess;
  hostId: string;
  phoneId: string;
}

export function startHost(relay: string, opts: { name?: string; seed?: string; dataDir?: string; env?: Record<string, string> } = {}): JsonLineProcess {
  const { command, args } = hostHarnessCommand();
  return new JsonLineProcess(opts.name ?? "host", command, args, {
    CQ_RELAY: relay,
    CQ_HOST_NAME: "Harness PC",
    ...(opts.seed ? { CQ_SEED_B64URL: opts.seed } : {}),
    ...(opts.dataDir ? { CQ_DATA_DIR: opts.dataDir } : {}),
    DOTNET_CLI_TELEMETRY_OPTOUT: "1",
    ...(opts.env ?? {}),
  });
}

export function startPhone(name = "phone", env: Record<string, string> = {}): JsonLineProcess {
  const { command, args } = controllerHarnessCommand();
  return new JsonLineProcess(name, command, args, env);
}

/** Starts both ends, waits until each is authenticated with the backend, and returns them. */
export async function startPair(
  backend: { url: string },
  opts: { dataDir?: string; seed?: string; phoneSeed?: string; env?: Record<string, string> } = {},
): Promise<Peers> {
  const host = startHost(backend.url, opts);
  const ready = await host.waitEvent((e) => e["evt"] === "ready", 30_000);
  await host.waitEvent((e) => e["evt"] === "signaling" && e["state"] === "Ready", 15_000);
  const phone = startPhone("phone", opts.env);
  await phone.waitEvent((e) => e["evt"] === "ready", 30_000);
  const init = await phone.call({ cmd: "init", relay: backend.url, ...(opts.phoneSeed ? { seed: opts.phoneSeed } : {}) });
  await phone.waitEvent((e) => e["evt"] === "signaling" && e["state"] === "READY", 15_000);
  return { host, phone, hostId: ready["hostId"] as string, phoneId: init["deviceId"] as string };
}

/** Full pairing through the real QR payload flow; returns the permissions the phone ended up with. */
export async function pair(p: Peers, permissions?: string[]): Promise<Json> {
  const qrEvent = p.host.waitEvent((e) => e["evt"] === "qr", 15_000, p.host.mark());
  await p.host.call({ cmd: "pair", ...(permissions ? { permissions } : {}) });
  const qr = await qrEvent;
  const result = await p.phone.call({ cmd: "pair", qr: qr["payload"] }, 45_000);
  if (result["error"]) throw new Error(`pairing failed: ${JSON.stringify(result)}`);
  return result;
}

export async function connect(p: Peers): Promise<Json> {
  const r = await p.phone.call({ cmd: "connect" }, 45_000);
  if (r["error"]) throw new Error(`session failed: ${JSON.stringify(r)}\n${p.host.diagnostics()}\n${p.phone.diagnostics()}`);
  return r;
}

export async function hostState(p: { host: JsonLineProcess }): Promise<Json> {
  return p.host.call({ cmd: "state" });
}

export async function phoneState(p: Peers): Promise<Json> {
  return p.phone.call({ cmd: "state" });
}

export async function stopAll(...things: ({ stop(): Promise<void> } | undefined)[]): Promise<void> {
  for (const t of things) {
    try {
      await t?.stop();
    } catch {
      /* best effort */
    }
  }
}

/**
 * A TCP proxy between the clients and the backend that can simulate the failure real networks have
 * and a closed socket does not: a partition. While blackholed it accepts and keeps connections open
 * but silently drops every byte in both directions, so the clients get no error and no FIN — only
 * silence, which they must notice on their own.
 */
export class NetworkProxy {
  private readonly server = createServer((client) => this.onClient(client));
  private readonly sockets = new Set<import("node:net").Socket>();
  private blackholed = false;
  private upstreamPort: number;
  port = 0;

  constructor(upstreamPort: number) {
    this.upstreamPort = upstreamPort;
  }

  static async start(upstreamPort: number): Promise<NetworkProxy> {
    const p = new NetworkProxy(upstreamPort);
    await new Promise<void>((res) => p.server.listen(0, "127.0.0.1", () => res()));
    p.port = (p.server.address() as { port: number }).port;
    return p;
  }

  get url(): string {
    return `ws://127.0.0.1:${this.port}`;
  }

  private onClient(client: import("node:net").Socket): void {
    const upstream = tcpConnect({ host: "127.0.0.1", port: this.upstreamPort });
    this.sockets.add(client);
    this.sockets.add(upstream);
    const pipe = (from: import("node:net").Socket, to: import("node:net").Socket): void => {
      from.on("data", (d) => {
        if (!this.blackholed) to.write(d);
      });
      from.on("close", () => to.destroy());
      from.on("error", () => to.destroy());
    };
    pipe(client, upstream);
    pipe(upstream, client);
    const forget = (): void => {
      this.sockets.delete(client);
      this.sockets.delete(upstream);
    };
    client.on("close", forget);
    upstream.on("close", forget);
  }

  /** From now on nothing gets through, in either direction, and nobody is told. */
  blackhole(): void {
    this.blackholed = true;
  }

  /** The network is back: stale connections are dropped (as a NAT would after a timeout) and new ones flow again. */
  heal(): void {
    for (const s of this.sockets) s.destroy();
    this.sockets.clear();
    this.blackholed = false;
  }

  async stop(): Promise<void> {
    for (const s of this.sockets) s.destroy();
    await new Promise<void>((res) => this.server.close(() => res()));
  }
}
