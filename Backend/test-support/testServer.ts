import { startServer, type StartedServer } from "../src/server.ts";
import { testConfig, type ConfigOverrides } from "./testConfig.ts";

/** Starts a real server on an ephemeral loopback port with an in-memory DB. */
export async function startTestServer(overrides: ConfigOverrides = {}): Promise<{
  server: StartedServer;
  url: string;
}> {
  const server = await startServer(testConfig(overrides));
  return { server, url: `ws://127.0.0.1:${server.address().port}` };
}
