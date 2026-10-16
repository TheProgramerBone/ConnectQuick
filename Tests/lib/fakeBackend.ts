import { randomUUID } from "node:crypto";
import { WebSocketServer, type WebSocket } from "ws";

export type Json = Record<string, any>;
export interface FakeClient {
  socket: WebSocket;
  role: string | null;
  deviceId: string | null;
}

/**
 * A minimal fake of the relay: it authenticates anybody (no signature checks on purpose) and lets the
 * test inject ANY message toward either end. This is how the suite attacks each endpoint directly:
 * a compromised or malicious relay must not be able to make the PC or the phone do anything its own
 * checks forbid. The real backend filters most of these already; the endpoints must not rely on that.
 */
export class FakeBackend {
  readonly clients: FakeClient[] = [];
  readonly received: { role: string | null; type: string; payload: Json }[] = [];
  onMessage: (client: FakeClient, type: string, payload: Json) => void = () => undefined;
  private readonly wss: WebSocketServer;
  port = 0;

  private constructor(wss: WebSocketServer) {
    this.wss = wss;
    wss.on("connection", (socket) => {
      const client: FakeClient = { socket, role: null, deviceId: null };
      this.clients.push(client);
      this.send(client, "hello.challenge", { serverChallenge: randomUUID().replace(/-/g, "").slice(0, 43).padEnd(43, "A"), protocolVersion: 1 });
      socket.on("message", (data) => {
        const env = JSON.parse(data.toString()) as { type: string; payload: Json };
        this.received.push({ role: client.role, type: env.type, payload: env.payload });
        if (env.type === "hello") {
          client.role = env.payload["role"];
          client.deviceId = env.payload["deviceId"];
          this.send(client, "hello.ack", { accepted: true, serverChallenge: "x", protocolVersion: 1, capabilities: ["session.e2e.v1"] });
        } else if (env.type === "heartbeat.ping") {
          this.send(client, "heartbeat.pong", { seq: env.payload["seq"] });
        }
        this.onMessage(client, env.type, env.payload);
      });
      socket.on("close", () => {
        const i = this.clients.indexOf(client);
        if (i >= 0) this.clients.splice(i, 1);
      });
    });
  }

  static async start(): Promise<FakeBackend> {
    const wss = new WebSocketServer({ port: 0, host: "127.0.0.1" });
    await new Promise<void>((res) => wss.once("listening", () => res()));
    const b = new FakeBackend(wss);
    b.port = (wss.address() as { port: number }).port;
    return b;
  }

  get url(): string {
    return `ws://127.0.0.1:${this.port}`;
  }

  send(client: FakeClient, type: string, payload: Json): void {
    client.socket.send(JSON.stringify({ v: 1, id: randomUUID(), ts: Date.now(), type, payload }));
  }

  async waitForClient(role: string, timeoutMs = 15_000): Promise<FakeClient> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      const c = this.clients.find((x) => x.role === role);
      if (c) return c;
      await new Promise((r) => setTimeout(r, 50));
    }
    throw new Error(`no ${role} connected to the fake backend`);
  }

  async waitForMessage(pred: (m: { role: string | null; type: string; payload: Json }) => boolean, timeoutMs = 10_000, from = 0): Promise<{ role: string | null; type: string; payload: Json }> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      const m = this.received.slice(from).find(pred);
      if (m) return m;
      await new Promise((r) => setTimeout(r, 30));
    }
    throw new Error("expected message never reached the fake backend");
  }

  async stop(): Promise<void> {
    for (const c of this.clients) c.socket.terminate();
    await new Promise<void>((res) => this.wss.close(() => res()));
  }
}
