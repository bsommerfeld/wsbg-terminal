// WebSocket client with auto-reconnect. Dispatches typed messages to
// registered handlers.

export class Socket {
  constructor(url) {
    this.url = url;
    this.handlers = new Map();
    this.ws = null;
    this.retry = 0;
  }

  on(type, fn) {
    this.handlers.set(type, fn);
  }

  send(type, payload) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify({ type, payload }));
    }
  }

  connect() {
    this.ws = new WebSocket(this.url);
    this.ws.addEventListener('open',  () => { this.retry = 0; });
    this.ws.addEventListener('close', () => this._reconnect());
    this.ws.addEventListener('error', () => this._reconnect());
    this.ws.addEventListener('message', e => {
      let env;
      try { env = JSON.parse(e.data); } catch (_) { return; }
      const h = this.handlers.get(env.type);
      if (h) h(env.payload);
    });
  }

  _reconnect() {
    if (this.ws && this.ws.readyState === WebSocket.CONNECTING) return;
    // Exponential back-off capped at 5s — keeps the page responsive
    // through brief JCEF host restarts.
    const delay = Math.min(5000, 250 * Math.pow(2, this.retry++));
    setTimeout(() => this.connect(), delay);
  }
}
