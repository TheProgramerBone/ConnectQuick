/**
 * Tracks which controller connections are linked to which host connection for the lifetime of
 * a `session.connect` negotiation. This is pure signaling relay — the backend never sees the
 * desktop stream or input events it enables (those are future phases and, per Docs/SECURITY.md,
 * are designed to ride end-to-end encrypted on top of this same link once implemented).
 */
export class ActiveSessionStore {
  private readonly controllerToHost = new Map<string, string>();
  private readonly hostToControllers = new Map<string, Set<string>>();

  link(controllerConnId: string, hostConnId: string): void {
    this.controllerToHost.set(controllerConnId, hostConnId);
    let set = this.hostToControllers.get(hostConnId);
    if (!set) {
      set = new Set();
      this.hostToControllers.set(hostConnId, set);
    }
    set.add(controllerConnId);
  }

  hostFor(controllerConnId: string): string | undefined {
    return this.controllerToHost.get(controllerConnId);
  }

  controllersFor(hostConnId: string): Set<string> {
    return this.hostToControllers.get(hostConnId) ?? new Set();
  }

  unlinkController(controllerConnId: string): void {
    const hostConnId = this.controllerToHost.get(controllerConnId);
    this.controllerToHost.delete(controllerConnId);
    if (hostConnId) this.hostToControllers.get(hostConnId)?.delete(controllerConnId);
  }

  unlinkHost(hostConnId: string): void {
    const controllers = this.hostToControllers.get(hostConnId);
    if (controllers) {
      for (const c of controllers) this.controllerToHost.delete(c);
    }
    this.hostToControllers.delete(hostConnId);
  }
}
