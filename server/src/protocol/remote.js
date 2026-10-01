import tls from 'node:tls';
import { EventEmitter } from 'node:events';
import { FrameReader, frame } from './proto.js';
import { remoteMsg, parseRemote, Direction } from './messages.js';
import { resolveKey } from './keycodes.js';

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
      case 'CONFIGURE':
        this.send(remoteMsg.configure(this.deviceInfo));
        break;
      case 'SET_ACTIVE':
        this.send(remoteMsg.setActive());
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
        const app = body.message(1)?.string(12);
        if (app) this.update({ currentApp: app });
        break;
      }
      case 'IME_BATCH_EDIT':
        this.imeCounter = body.int(1);
        this.fieldCounter = body.int(2);
        break;
      case 'IME_SHOW_REQUEST': {
        const field = body.message(2);
        if (field) this.fieldCounter = field.int(1);
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

  sendText(text) {
    this.ensureConnected();
    if (!text) return;
    this.send(remoteMsg.imeBatchEdit(this.imeCounter, this.fieldCounter, String(text)));
  }

  launchApp(url) {
    this.ensureConnected();
    if (!url) throw new Error('App link is required');
    this.send(remoteMsg.appLink(String(url)));
  }
}
