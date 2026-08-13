// Request/response client for the DebugBridge (dev only).
//
// The bridge answers ONE broadcast per request (`debug` in, `debug-<command>`
// out) and there is no server tick — every number on screen exists because
// this client asked for it. That is the whole performance contract: while the
// dashboard is closed nothing here runs, and while it is open only the panel
// you are looking at asks.
//
// Requests are matched by `requestId`, which the bridge echoes verbatim.

const TIMEOUT_MS = 8000;

export class DebugClient {
  constructor(socket) {
    this.socket = socket;
    this.seq = 0;
    this.pending = new Map();   // requestId -> {resolve, reject, timer}
    this.wired = new Set();     // response types we already listen to
  }

  /**
   * Asks one command. Resolves with the `data` section, rejects on timeout,
   * on a dead socket or on `debug-unknown`. A section that failed server-side
   * still resolves — it carries `{error: "..."}` in its own slot, by contract.
   */
  ask(command, extra = {}) {
    this._wire(`debug-${command}`);
    this._wire('debug-unknown');
    const requestId = `ui-${++this.seq}`;
    if (!this.socket.send('debug', { command, requestId, ...extra })) {
      return Promise.reject(new Error('socket closed'));
    }
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(requestId);
        reject(new Error(`timeout: ${command}`));
      }, TIMEOUT_MS);
      this.pending.set(requestId, { resolve, reject, timer });
    });
  }

  /** Registers the socket handler for a response type exactly once. */
  _wire(type) {
    if (this.wired.has(type)) return;
    this.wired.add(type);
    this.socket.on(type, payload => this._deliver(type, payload));
  }

  _deliver(type, payload) {
    const entry = payload && this.pending.get(payload.requestId);
    if (!entry) return;               // probe answer, or an answer we timed out on
    this.pending.delete(payload.requestId);
    clearTimeout(entry.timer);
    if (type === 'debug-unknown') {
      entry.reject(new Error(`unknown command (valid: ${(payload.data?.commands || []).join(', ')})`));
      return;
    }
    entry.resolve({ at: payload.at, data: payload.data || {} });
  }

  /** Drops every in-flight request — used when the dashboard closes. */
  abandon() {
    for (const { reject, timer } of this.pending.values()) {
      clearTimeout(timer);
      reject(new Error('closed'));
    }
    this.pending.clear();
  }
}
