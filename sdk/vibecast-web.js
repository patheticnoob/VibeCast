(function (global) {
  const STORAGE_KEY = "vibe_cast_receiver";

  class VibeCastClient extends EventTarget {
    constructor(options = {}) {
      super();
      this.host = options.host || null;
      this.port = options.port || 8080;
      this.socket = null;
      this.state = null;
      this.reconnect = options.reconnect !== false;
      this.reconnectDelayMs = options.reconnectDelayMs || 1500;
      this._manualClose = false;
    }

    async connect(options = {}) {
      const receiver = options.host
        ? { host: options.host, port: options.port || this.port }
        : VibeCastClient.loadRememberedReceiver();

      if (!receiver || !receiver.host) {
        throw new Error("No Vibe Cast receiver configured. Use QR pairing or pass host/port.");
      }

      this.host = receiver.host;
      this.port = receiver.port || 8080;
      this._manualClose = false;

      if (options.remember !== false) {
        VibeCastClient.rememberReceiver(this.host, this.port);
      }

      await this._openSocket();
      return this;
    }

    disconnect() {
      this._manualClose = true;
      if (this.socket) {
        this.socket.close();
        this.socket = null;
      }
    }

    getHttpBaseUrl() {
      this._assertReceiver();
      return `http://${this.host}:${this.port}`;
    }

    getWebSocketUrl() {
      this._assertReceiver();
      return `ws://${this.host}:${this.port}/ws`;
    }

    getLaunchUrl(mediaUrl = "") {
      const base = this.getHttpBaseUrl();
      if (!mediaUrl) {
        return `${base}/`;
      }
      return `${base}/?play=${encodeURIComponent(mediaUrl)}`;
    }

    launchController(mediaUrl = "", target = "_blank") {
      const url = this.getLaunchUrl(mediaUrl);
      window.open(url, target, "noopener,noreferrer");
      return url;
    }

    async fetchState() {
      this._assertReceiver();
      const response = await fetch(`${this.getHttpBaseUrl()}/state`, {
        method: "GET",
        mode: "cors",
      });

      if (!response.ok) {
        throw new Error(`Receiver state request failed with ${response.status}`);
      }

      const state = await response.json();
      this.state = state;
      return state;
    }

    send(action, payload = {}) {
      if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
        throw new Error("Vibe Cast socket is not connected.");
      }

      this.socket.send(JSON.stringify({ action, ...payload }));
    }

    play(url, extra = {}) {
      this.send("play", { url, ...extra });
    }

    pause() {
      this.send("pause");
    }

    resume() {
      this.send("resume");
    }

    stop() {
      this.send("stop");
    }

    seek(positionMs) {
      this.send("seek", { positionMs });
    }

    volume(value) {
      this.send("volume", { value });
    }

    async _openSocket() {
      this._assertReceiver();

      if (this.socket && this.socket.readyState === WebSocket.OPEN) {
        return;
      }

      const url = this.getWebSocketUrl();

      await new Promise((resolve, reject) => {
        const socket = new WebSocket(url);
        let settled = false;

        socket.addEventListener("open", () => {
          this.socket = socket;
          settled = true;
          this.dispatchEvent(new CustomEvent("open", { detail: { host: this.host, port: this.port } }));
          this.send("get_state");
          resolve();
        });

        socket.addEventListener("message", (event) => {
          try {
            const payload = JSON.parse(event.data);
            this.state = payload;
            this.dispatchEvent(new CustomEvent("state", { detail: payload }));
          } catch (error) {
            this.dispatchEvent(new CustomEvent("error", { detail: error }));
          }
        });

        socket.addEventListener("error", (event) => {
          if (!settled) {
            settled = true;
            reject(new Error(`Vibe Cast WebSocket connection failed: ${url}`));
          }
          this.dispatchEvent(new CustomEvent("error", { detail: event }));
        });

        socket.addEventListener("close", (event) => {
          this.dispatchEvent(new CustomEvent("close", { detail: event }));
          this.socket = null;

          if (!this._manualClose && this.reconnect) {
            setTimeout(() => {
              this._openSocket().catch((error) => {
                this.dispatchEvent(new CustomEvent("error", { detail: error }));
              });
            }, this.reconnectDelayMs);
          }
        });
      });
    }

    _assertReceiver() {
      if (!this.host) {
        throw new Error("Vibe Cast receiver host is missing.");
      }
    }

    static rememberReceiver(host, port = 8080) {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({ host, port }));
    }

    static loadRememberedReceiver() {
      try {
        const raw = localStorage.getItem(STORAGE_KEY);
        return raw ? JSON.parse(raw) : null;
      } catch (error) {
        return null;
      }
    }

    static clearRememberedReceiver() {
      localStorage.removeItem(STORAGE_KEY);
    }
  }

  global.VibeCastWeb = {
    VibeCastClient,
  };
})(window);
