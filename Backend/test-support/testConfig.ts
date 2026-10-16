import { loadConfig, type Config } from "../src/config.ts";

export type ConfigOverrides = Omit<Partial<Config>, "rateLimit" | "session" | "ice" | "limits"> & {
  rateLimit?: Partial<Config["rateLimit"]>;
  session?: Partial<Config["session"]>;
  ice?: Partial<Config["ice"]>;
  limits?: Partial<Config["limits"]>;
};

/**
 * A full Config for tests. It starts from loadConfig({}) with an EMPTY environment, so tests are
 * hermetic (never influenced by the developer's shell) yet always track the real defaults.
 */
export function testConfig(overrides: ConfigOverrides = {}): Config {
  const base = loadConfig({});
  const { rateLimit, session, ice, limits, ...rest } = overrides;
  return {
    ...base,
    port: 0, // ephemeral — OS picks a free port
    dbPath: ":memory:",
    ...rest,
    rateLimit: { ...base.rateLimit, ...rateLimit },
    session: { ...base.session, ...session },
    ice: { ...base.ice, ...ice },
    limits: { ...base.limits, ...limits },
  };
}
