import { EventEmitter } from 'node:events';
import { AdbClient, AdbError, ADB_PORT } from './client.js';
import { resolveKey } from '../protocol/keycodes.js';

const POLL_MS = 3000;
// One round trip: foreground window + power state.
const STATUS_CMD =
  "dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp' ; dumpsys power | grep -E 'mWakefulness=|Display Power: state='";

/** Quotes a string for the device's /system/bin/sh. */
export const shQuote = (s) => `'${String(s).replace(/'/g, `'\\''`)}'`;

/** Parses the foreground package from `dumpsys window` output. */
export function parseCurrentApp(out) {
  const m =
    /mCurrentFocus=Window\{\S+ \S+ ([\w.]+)\//.exec(out) ??
    /mFocusedApp=.*? ([\w.]+)\/[\w.$]+/.exec(out);
  return m ? m[1] : null;
}

/** Parses power state from `dumpsys power` output (null if unknown). */
export function parsePowered(out) {
  const w = /mWakefulness=(\w+)/.exec(out);
  if (w) return w[1] === 'Awake';
  const d = /Display Power: state=(\w+)/.exec(out);
  return d ? d[1] === 'ON' : null;
}

/** Text for `input text`: shell-quoted, with spaces as %s. */
export const inputText = (text) => `input text ${shQuote(String(text).replace(/%/g, '\\%').replace(/ /g, '%s'))}`;

/** Shell command that opens a Kalimote app link on an Android device. */
export function launchCommand(url) {
  const pkg = /^market:\/\/launch\?id=([\w.]+)/.exec(url)?.[1];
  if (pkg) return `monkey -p ${pkg} -c android.intent.category.LAUNCHER 1 || monkey -p ${pkg} 1`;
  return `am start -a android.intent.action.VIEW -d ${shQuote(url)}`;
}

/**
 * Fire TV (or any Android device with network ADB) behind the same interface
 * as RemoteConnection: connect(), disconnect(), sendKey(), sendText(),
 * launchApp(), `state` and the 'state' / 'unpaired' / 'error' events.
 */
export class FireTvConnection extends EventEmitter {
  constructor({ host, port = ADB_PORT, key, autoReconnect = true }) {
    super();
    Object.assign(this, { host, port, key, autoReconnect });
    this.state = { connected: false, powered: null, currentApp: null, volume: null };
    this.stopped = true;
    this.retryMs = 1000;
    this.queue = Promise.resolve();
  }

  /**
   * Connects. With `approvalTimeoutMs`, an untrusted key is offered to the TV
   * and we wait for the user to accept the on-screen prompt (pairing).
   */
  async connect({ approvalTimeoutMs = 0 } = {}) {
    this.stopped = false;
    clearTimeout(this.retryTimer);
    this.client?.close();
    const client = new AdbClient({
      host: this.host,
      port: this.port,
      key: this.key,
      approvalTimeoutMs,
      name: 'kalimote@kalimote',
    });
    this.client = client;
    client.on('awaiting-approval', () => this.emit('awaiting-approval'));
    client.on('close', () => {
      if (this.client !== client) return;
      clearInterval(this.pollTimer);
      this.update({ connected: false });
      this.scheduleReconnect();
    });
    try {
      await client.connect({ timeoutMs: approvalTimeoutMs ? approvalTimeoutMs + 8000 : 8000 });
    } catch (e) {
      if (this.client !== client) throw e;
      if (e.code === 'unauthorized') {
        this.stopped = true;
        this.update({ connected: false });
        this.emit('unpaired', e);
        throw Object.assign(e, { unpaired: true });
      }
      this.update({ connected: false });
      if (this.listenerCount('error')) this.emit('error', e);
      this.scheduleReconnect();
      throw e;
    }
    this.retryMs = 1000;
    this.update({ connected: true });
    await this.poll();
    clearInterval(this.pollTimer);
    this.pollTimer = setInterval(() => this.poll(), POLL_MS);
    this.pollTimer.unref?.();
  }

  scheduleReconnect() {
    if (this.stopped || !this.autoReconnect) return;
    clearTimeout(this.retryTimer);
    this.retryTimer = setTimeout(() => this.connect().catch(() => {}), this.retryMs);
    this.retryMs = Math.min(this.retryMs * 2, 30000);
  }

  disconnect() {
    this.stopped = true;
    clearTimeout(this.retryTimer);
    clearInterval(this.pollTimer);
    const c = this.client;
    this.client = null;
    c?.close();
    this.update({ connected: false });
  }

  async poll() {
    try {
      const out = await this.client.shell(STATUS_CMD, { timeoutMs: 5000 });
      this.update({ currentApp: parseCurrentApp(out) ?? this.state.currentApp, powered: parsePowered(out) });
    } catch {
      /* transient; a dead connection is handled by 'close' */
    }
  }

  update(patch) {
    const next = { ...this.state, ...patch };
    if (JSON.stringify(next) === JSON.stringify(this.state)) return;
    this.state = next;
    this.emit('state', this.state);
  }

  /** Runs shell commands one at a time, so key presses stay in order. */
  run(command) {
    if (!this.state.connected || !this.client) throw new Error('Not connected to TV');
    const client = this.client;
    const p = this.queue.then(() => client.shell(command));
    this.queue = p.catch(() => {});
    // Refresh state soon after anything that may change the foreground app or power.
    p.then(() => setTimeout(() => this.poll(), 400)).catch(() => {});
    return p;
  }

  sendKey(key, direction = 'SHORT') {
    const code = resolveKey(key);
    const dir = String(direction).toUpperCase();
    if (dir === 'END_LONG') return; // `input --longpress` already includes the release
    this.run(dir === 'START_LONG' ? `input keyevent --longpress ${code}` : `input keyevent ${code}`);
  }

  sendText(text) {
    if (!text) return;
    this.run(inputText(text));
  }

  launchApp(url) {
    if (!url) throw new Error('App link is required');
    this.run(launchCommand(String(url)));
  }
}

export { AdbError };
