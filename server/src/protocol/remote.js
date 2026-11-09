import tls from 'node:tls';
import { EventEmitter } from 'node:events';
import { FrameReader, frame } from './proto.js';
import { remoteMsg, parseRemote, Direction, FEATURES, FEATURE_IME } from './messages.js';
import { resolveKey, typingKeys } from './keycodes.js';

export const REMOTE_PORT = 6466;

/**
 * Persistent control connection to a paired TV. Reconnects automatically.
 *
 * Events:
 *   'state'  -> full state object whenever something changes
 *   'error'  -> Error (connection level; reconnect is scheduled)
 *   'unpaired' -> the TV rejected our certificate; pairing is required
 */
export class RemoteConnection extends EventEmitter {
  constructor({ host, port = REMOTE_PORT, key, cert, deviceInfo = {}, autoReconnect = true }) {
    super();
    Object.assign(this, { host, port, key, cert, deviceInfo, autoReconnect });
    this.state = {
      connected: false,
      powered: null,
      currentApp: null,
      volume: null, // { level, max, muted }
    };
    this.imeCounter = 0;
    this.fieldCounter = 0;
    this.features = FEATURES;
    // Text box the TV told us about (input method); null: type as key presses.
    this.imeValue = null;
    this.lastSent = null;
    this.stopped = true;
    this.retryMs = 1000;
  }

  connect() {
    this.stopped = false;
    clearTimeout(this.retryTimer);
    return new Promise((resolve, reject) => {
      let settled = false;
      const reader = new FrameReader();
      const socket = tls.connect({
        host: this.host,
        port: this.port,
        key: this.key,
        cert: this.cert,
        rejectUnauthorized: false,
      });
      this.socket = socket;
      socket.setTimeout(10000);
      socket.on('timeout', () => socket.destroy(new Error('Connection to TV timed out')));
      socket.on('data', (chunk) => {
        let frames;
        try {
          frames = reader.push(chunk);
        } catch (e) {
          socket.destroy(e);
          return;
        }
        for (const f of frames) {
          const became = this.handle(f, socket);
          if (became && !settled) {
            settled = true;
            this.retryMs = 1000;
            resolve();
          }
        }
      });
      let handshaken = false;
      let gotData = false;
      let lastError = null;
      socket.once('secureConnect', () => {
        handshaken = true;
      });
      socket.on('data', () => {
        gotData = true;
      });
      socket.on('error', (e) => {
        lastError = e;
      });
      socket.on('close', () => {
        if (this.socket !== socket) return;
        // If the TLS handshake finished but the TV hung up (or sent an alert)
        // before any protocol message, it does not accept our certificate:
        // we are not paired, or the pairing was revoked on the TV.
        const unpaired =
          !this.state.connected &&
          !gotData &&
          (handshaken || /alert|certificate/i.test(String(lastError?.message)));
        const err = unpaired
          ? Object.assign(new Error('TV rejected the connection; pairing required'), { unpaired: true })
          : lastError ?? new Error('Connection to TV closed');
        if (!settled) {
          settled = true;
          reject(err);
        }
        this.update({ connected: false });
        if (unpaired) {
          this.stopped = true;
          this.emit('unpaired', err);
        } else {
          if (this.listenerCount('error')) this.emit('error', err);
          this.scheduleReconnect();
        }
      });
    });
  }

  scheduleReconnect() {
    if (this.stopped || !this.autoReconnect) return;
    clearTimeout(this.retryTimer);
    this.retryTimer = setTimeout(() => {
      this.connect().catch(() => {});
    }, this.retryMs);
    this.retryMs = Math.min(this.retryMs * 2, 30000);
  }

  disconnect() {
    this.stopped = true;
    clearTimeout(this.retryTimer);
    const s = this.socket;
    this.socket = null;
    s?.destroy();
    this.update({ connected: false });
  }

  /** Returns true when the connection just became ready. */
  handle(buf, socket) {
    const { kind, body } = parseRemote(buf);
    // The TV pings every ~5s; any traffic resets our idle timer.
    socket.setTimeout(20000);
    switch (kind) {
      case 'CONFIGURE': {
        // Agree on the features both sides support, as the official remote does.
        const tv = body.uint(1);
        this.features = tv > 0 ? FEATURES & tv : FEATURES;
        this.imeValue = null;
        this.send(remoteMsg.configure(this.deviceInfo, this.features));
        break;
      }
      case 'SET_ACTIVE':
        this.send(remoteMsg.setActive(this.features));
        if (!this.state.connected) {
          this.update({ connected: true });
          return true;
        }
        break;
      case 'PING_REQUEST':
        this.send(remoteMsg.pingResponse(body.uint(1)));
        break;
      case 'START':
        this.update({ powered: body.bool(1) });
        break;
      case 'SET_VOLUME_LEVEL':
        this.update({
          volume: { level: body.uint(7), max: body.uint(6), muted: body.bool(8) },
        });
        break;
      case 'IME_KEY_INJECT': {
        // With a text field status (field 2) a text box has focus: how the
        // Android TV Remote Service announces it. The counters follow in batch edits.
        const app = body.message(1)?.string(12);
        if (app && app !== this.state.currentApp) this.imeValue = null;
        const status = body.message(2);
        if (status) this.imeValue = status.string(2);
        if (app) this.update({ currentApp: app });
        break;
      }
      case 'IME_BATCH_EDIT': {
        this.imeCounter = body.int(1);
        this.fieldCounter = body.int(2);
        const status = body.message(3)?.message(2);
        const value = status?.has(3) ? status.string(3) : null;
        if (value !== null && value === this.lastSent) this.lastSent = null;
        else if (value !== null && this.lastSent === null) this.imeValue = value; // the TV reporting its text box
        else if (value === null && this.lastSent === null && this.imeValue === null) this.imeValue = ''; // an input session opened
        break;
      }
      case 'IME_SHOW_REQUEST': {
        const field = body.message(2);
        if (field) {
          this.fieldCounter = field.int(1);
          this.imeValue = field.string(2);
        }
        break;
      }
      case 'ERROR':
        this.emit('remote-error', body);
        break;
      default:
        break;
    }
    return false;
  }

  update(patch) {
    const next = { ...this.state, ...patch };
    if (JSON.stringify(next) === JSON.stringify(this.state)) return;
    this.state = next;
    this.emit('state', this.state);
  }

  send(payload) {
    if (!this.socket || this.socket.destroyed) throw new Error('Not connected to TV');
    this.socket.write(frame(payload));
  }

  ensureConnected() {
    if (!this.state.connected) throw new Error('Not connected to TV');
  }

  sendKey(key, direction = 'SHORT') {
    this.ensureConnected();
    const dir = typeof direction === 'number' ? direction : Direction[String(direction).toUpperCase()];
    if (!dir) throw new Error(`Unknown key direction: ${direction}`);
    this.send(remoteMsg.key(resolveKey(key), dir));
  }

  /**
   * Types text at the cursor. When the TV has told us about its text box, it
   * goes through the input method (a batch edit replaces the box's content,
   * so we send what it holds plus the new text). Many TVs never do, and then
   * it is typed as key presses: lower case, characters with their own key.
   */
  sendText(text) {
    this.ensureConnected();
    if (!text) return;
    if (this.imeValue !== null && this.features & FEATURE_IME) {
      const value = this.imeValue + String(text);
      this.imeValue = value;
      this.lastSent = value;
      this.send(remoteMsg.imeBatchEdit(this.imeCounter, this.fieldCounter, value));
      return;
    }
    for (const code of typingKeys(text)) this.send(remoteMsg.key(code, Direction.SHORT));
  }

  launchApp(url) {
    this.ensureConnected();
    if (!url) throw new Error('App link is required');
    this.send(remoteMsg.appLink(String(url)));
  }
}
