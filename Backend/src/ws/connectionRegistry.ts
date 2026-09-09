import type { Connection } from "./connection.ts";

/**
 * In-memory index of live, possibly-authenticated connections. A process restart drops every
 * entry here — clients reconnect and re-authenticate with their durable keys; nothing that
 * matters is lost. Three indices are kept because lookups happen by all three keys elsewhere:
 * by raw connection id (routing session relay), by hostId (finding "the" host for pairing/
 * session), and by controller deviceId (finding "the" controller for presence/pairing results).
 */
export class ConnectionRegistry {
  private readonly byId = new Map<string, Connection>();
  private readonly hostConnections = new Map<string, Connection>();
  private readonly controllerConnections = new Map<string, Connection>();

  add(conn: Connection): void {
    this.byId.set(conn.id, conn);
  }

  remove(conn: Connection): void {
    this.byId.delete(conn.id);
  }

  getById(id: string): Connection | undefined {
    return this.byId.get(id);
  }

  all(): Connection[] {
    return [...this.byId.values()];
  }

  addHost(hostId: string, conn: Connection): void {
    this.hostConnections.set(hostId, conn);
  }

  removeHost(hostId: string, conn: Connection): void {
    if (this.hostConnections.get(hostId) === conn) this.hostConnections.delete(hostId);
  }

  getHost(hostId: string): Connection | undefined {
    return this.hostConnections.get(hostId);
  }

  addController(deviceId: string, conn: Connection): void {
    this.controllerConnections.set(deviceId, conn);
  }

  removeController(deviceId: string, conn: Connection): void {
    if (this.controllerConnections.get(deviceId) === conn) this.controllerConnections.delete(deviceId);
  }

  getController(deviceId: string): Connection | undefined {
    return this.controllerConnections.get(deviceId);
  }
}
