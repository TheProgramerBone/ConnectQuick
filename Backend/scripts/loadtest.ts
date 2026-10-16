/**
 * Load test for the relay: N paired host/controller sessions, each pushing end-to-end encrypted
 * frames in both directions, against a REAL server process. Reports delivery latency and the
 * server's own CPU and memory, so the "do we need more than one instance?" decision rests on numbers.
 *
 *   node scripts/loadtest.ts [--sessions 100] [--seconds 20] [--fps 20] [--bytes 256]
 *
 * The generator is a single Node process: if its CPU nears 100% the run is generator-bound and the
 * server figures are a lower bound (the report says so).
 */
import { spawn } from "node:child_process";
import { readFileSync } from "node:fs";
import { setTimeout as sleep } from "node:timers/promises";
import { randomBytes } from "node:crypto";
import { pairHostAndController, openSession, type PairedFixture, type OpenedSession } from "../test-support/flows.ts";

const arg = (name: string, fallback: number): number => {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 ? Number(process.argv[i + 1]) : fallback;
};
const SESSIONS = arg("sessions", 100);
const SECONDS = arg("seconds", 20);
const FPS = arg("fps", 20);
const BYTES = arg("bytes", 256);
const port = 20000 + Math.floor(Math.random() * 20000);

function cpuSeconds(pid: number): number {
  const f = readFileSync(`/proc/${pid}/stat`, "utf8").split(") ")[1]!.split(" ");
  return (Number(f[11]) + Number(f[12])) / 100; // utime + stime in clock ticks
}
const rssMb = (pid: number): number => Number(/VmRSS:\s+(\d+)/.exec(readFileSync(`/proc/${pid}/status`, "utf8"))![1]) / 1024;
const pct = (sorted: number[], q: number): number => sorted.length ? sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * q))]! : NaN;

const server = spawn(process.execPath, ["src/server.ts"], {
  env: {
    ...process.env, PORT: String(port), DB_PATH: ":memory:", LOG_LEVEL: "ERROR",
    PAIRING_INIT_PER_MINUTE_PER_IP: "1000000", PAIRING_CREATE_PER_MINUTE_PER_HOST: "1000000",
    MAX_CONNECTIONS: "100000", MAX_CONNECTIONS_PER_IP: "100000", RAW_FRAMES_PER_SECOND: "100000",
    SESSION_FRAMES_PER_SECOND: "100000", SESSION_BYTES_PER_SECOND: "1000000000",
  },
  stdio: ["ignore", "ignore", "inherit"],
});
server.on("exit", (code) => { if (code) console.error(`server exited with ${code}`); });

async function waitHealthy(): Promise<void> {
  for (let i = 0; i < 100; i++) {
    try { if ((await fetch(`http://127.0.0.1:${port}/healthz`)).ok) return; } catch { /* not up yet */ }
    await sleep(100);
  }
  throw new Error("server did not start");
}

interface Live { fixture: PairedFixture; session: OpenedSession }

async function main(): Promise<void> {
  await waitHealthy();
  const url = `ws://127.0.0.1:${port}`;
  const live: Live[] = [];
  const t0 = Date.now();
  const queue = Array.from({ length: SESSIONS }, (_, i) => i);
  await Promise.all(Array.from({ length: 10 }, async () => {
    for (let i = queue.shift(); i !== undefined; i = queue.shift()) {
      const fixture = await pairHostAndController(url, { hostName: `PC ${i}` });
      live.push({ fixture, session: await openSession(fixture) });
    }
  }));
  const setupMs = Date.now() - t0;

  const latencies: number[] = [];
  let sent = 0, received = 0, bad = 0;
  const filler = randomBytes(Math.max(0, BYTES - 8));
  for (const { fixture, session } of live) {
    fixture.hostClient.socket.on("message", (raw) => {
      const env = JSON.parse(raw.toString("utf8"));
      if (env.type !== "session.data") return;
      try { const plain = session.host.open(env.payload.seq, env.payload.ct); latencies.push(Date.now() - Number(plain.readBigUInt64BE(0))); received++; } catch { bad++; }
    });
    fixture.controllerClient.socket.on("message", (raw) => {
      const env = JSON.parse(raw.toString("utf8"));
      if (env.type !== "session.data") return;
      try { const plain = session.controller.open(env.payload.seq, env.payload.ct); latencies.push(Date.now() - Number(plain.readBigUInt64BE(0))); received++; } catch { bad++; }
    });
  }

  const pid = server.pid!;
  const cpu0 = cpuSeconds(pid), me0 = process.cpuUsage(), wall0 = Date.now();
  const stamp = (): Buffer => { const b = Buffer.alloc(8); b.writeBigUInt64BE(BigInt(Date.now())); return Buffer.concat([b, filler]); };
  const tick = setInterval(() => {
    for (const { fixture, session } of live) {
      const a = session.controller.seal(stamp());
      fixture.controllerClient.send("session.data", { sessionId: session.sessionId, seq: a.seq, ct: a.ct });
      const b = session.host.seal(stamp());
      fixture.hostClient.send("session.data", { sessionId: session.sessionId, seq: b.seq, ct: b.ct });
      sent += 2;
    }
  }, 1000 / FPS);
  let peakRss = 0;
  const sampler = setInterval(() => { peakRss = Math.max(peakRss, rssMb(pid)); }, 500);
  await sleep(SECONDS * 1000);
  clearInterval(tick);
  await sleep(1500); // let in-flight frames land
  clearInterval(sampler);

  const wall = (Date.now() - wall0) / 1000;
  const serverCpu = (cpuSeconds(pid) - cpu0) / wall;
  const me = process.cpuUsage(me0);
  const genCpu = (me.user + me.system) / 1e6 / wall;
  latencies.sort((x, y) => x - y);
  console.log(JSON.stringify({
    sessions: live.length, setupSeconds: +(setupMs / 1000).toFixed(1), seconds: SECONDS, fpsPerDirection: FPS, payloadBytes: BYTES,
    framesSent: sent, framesDelivered: received, lost: sent - received, undecryptable: bad,
    framesPerSecond: Math.round(received / SECONDS),
    latencyMs: { p50: pct(latencies, 0.5), p95: pct(latencies, 0.95), p99: pct(latencies, 0.99), max: latencies[latencies.length - 1] },
    serverCpuCores: +serverCpu.toFixed(2), serverPeakRssMb: Math.round(peakRss), generatorCpuCores: +genCpu.toFixed(2),
    generatorBound: genCpu > 0.9,
  }, null, 2));
  for (const { fixture } of live) { fixture.hostClient.close(); fixture.controllerClient.close(); }
}

main().catch((e) => { console.error(e); process.exitCode = 1; }).finally(() => { server.kill(); });
