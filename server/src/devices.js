import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { EventEmitter } from 'node:events';
import { generateClientCertificate } from './protocol/certs.js';
import { PairingSession, PAIRING_PORT } from './protocol/pairing.js';
import { RemoteConnection, REMOTE_PORT } from './protocol/remote.js';
import { parseMacro, runMacro } from './macros.js';
import { wake, lookupMac, normalizeMac } from './wol.js';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * Keeps track of known TVs, their pairing status and live connections.
 * Emits 'change' whenever the device list or any device state changes.
 */
export class DeviceManager extends EventEmitter {
  constructor({ dataDir, clientName = 'Kalimote Web' }) {
    super();
    this.dataDir = dataDir;
    this.clientName = clientName;
    this.devices = new Map(); // id -> stored device
    this.connections = new Map(); // id -> RemoteConnection
    this.pairings = new Map(); // id -> PairingSession
    this.discovered = new Map(); // host -> { name, host, port }
    this.errors = new Map(); // id -> last error message
    this.sleepTimers = new Map(); // id -> { at, timer }
    this.running = new Map(); // id -> { name, controller } of the macro being run
    fs.mkdirSync(dataDir, { recursive: true });
    this.identity = this.loadIdentity();
    for (const d of this.readJson('devices.json', [])) this.devices.set(d.id, d);
    this.macros = this.readJson('macros.json', []);
  }

  readJson(file, fallback) {
    try {
      return JSON.parse(fs.readFileSync(path.join(this.dataDir, file), 'utf8'));
    } catch {
      return fallback;
    }
  }

  writeJson(file, value, mode) {
    const target = path.join(this.dataDir, file);
    fs.writeFileSync(`${target}.tmp`, JSON.stringify(value, null, 2), { mode });
    fs.renameSync(`${target}.tmp`, target);
  }

  loadIdentity() {
    const existing = this.readJson('identity.json', null);
    if (existing?.key && existing?.cert) return existing;
    const identity = generateClientCertificate('kalimote');
    this.writeJson('identity.json', identity, 0o600);
    return identity;
  }

  save() {
    this.writeJson('devices.json', [...this.devices.values()]);
  }

  changed() {
    this.emit('change', this.list());
  }

  list() {
    const known = [...this.devices.values()].map((d) => ({
      ...d,
      pairing: this.pairings.has(d.id),
      error: this.errors.get(d.id) ?? null,
      state: this.connections.get(d.id)?.state ?? { connected: false },
      sleepAt: this.sleepTimers.get(d.id)?.at ?? null,
      runningMacro: this.running.get(d.id)?.name ?? null,
    }));
    const knownHosts = new Set(known.map((d) => d.host));
    const discovered = [...this.discovered.values()].filter((d) => !knownHosts.has(d.host));
    return { devices: known, discovered, macros: this.macros };
  }

  /** Looks a device up by id, or by name (case-insensitive) for API convenience. */
  get(ref) {
    const d =
      this.devices.get(ref) ??
      [...this.devices.values()].find((x) => x.name.toLowerCase() === String(ref ?? '').toLowerCase());
    if (!d) throw new Error('Unknown device');
    return d;
  }

  add({ host, name, pairingPort, remotePort }) {
    host = String(host ?? '').trim();
    if (!/^[\w.:-]+$/.test(host)) throw new Error('A valid host name or IP address is required');
    const existing = [...this.devices.values()].find((d) => d.host === host);
    if (existing) return existing;
    const device = {
      id: crypto.randomUUID(),
      name: String(name || this.discovered.get(host)?.name || host).slice(0, 80),
      host,
      pairingPort: Number(pairingPort) || PAIRING_PORT,
      remotePort: Number(remotePort) || this.discovered.get(host)?.port || REMOTE_PORT,
      paired: false,
    };
    this.devices.set(device.id, device);
    this.save();
    this.changed();
    return device;
  }

  rename(id, name) {
    this.update(id, { name });
  }

  update(id, { name, mac }) {
    const d = this.get(id);
    if (name !== undefined) d.name = String(name || d.host).slice(0, 80);
    if (mac !== undefined) {
      if (mac && !normalizeMac(mac)) throw new Error('MAC address must look like aa:bb:cc:dd:ee:ff');
      d.mac = normalizeMac(mac);
      d.macLearned = false;
    }
    this.save();
    this.changed();
    return d;
  }

  remove(id) {
    id = this.get(id).id;
    this.cancelSleepTimer(id);
    this.running.get(id)?.controller.abort();
    this.connections.get(id)?.disconnect();
    this.connections.delete(id);
    this.pairings.get(id)?.close();
    this.pairings.delete(id);
    this.errors.delete(id);
    this.devices.delete(id);
    this.save();
    this.changed();
  }

  setError(id, err) {
    if (err) this.errors.set(id, err.message ?? String(err));
    else this.errors.delete(id);
    this.changed();
  }

  async startPairing(id) {
    const d = this.get(id);
    this.pairings.get(id)?.close();
    this.connections.get(id)?.disconnect();
    const session = new PairingSession({
      host: d.host,
      port: d.pairingPort,
      ...this.identity,
      clientName: this.clientName,
    });
    this.pairings.set(id, session);
    this.setError(id, null);
    try {
      await session.start();
    } catch (e) {
      this.pairings.delete(id);
      this.setError(id, e);
      throw e;
    }
    this.changed();
  }

  async finishPairing(id, code) {
    const d = this.get(id);
    const session = this.pairings.get(id);
    if (!session) throw new Error('Start pairing first');
    try {
      await session.finish(code);
    } catch (e) {
      // A mistyped code keeps the session alive only if the checksum failed
      // locally; otherwise the TV closed it and pairing has to restart.
      if (!session.active) this.pairings.delete(id);
      this.setError(id, e);
      throw e;
    }
    this.pairings.delete(id);
    d.paired = true;
    this.save();
    this.setError(id, null);
    await this.connect(id);
  }

  cancelPairing(id) {
    this.pairings.get(id)?.close();
    this.pairings.delete(id);
    this.changed();
  }

  connection(id) {
    const d = this.get(id);
    let conn = this.connections.get(id);
    if (!conn) {
      conn = new RemoteConnection({
        host: d.host,
        port: d.remotePort,
        ...this.identity,
        deviceInfo: { model: this.clientName, vendor: 'Kalimote' },
      });
      conn.on('state', (st) => {
        // Remember the TV's MAC address for Wake-on-LAN once we have talked to it.
        if (st.connected && !d.mac) {
          const mac = lookupMac(d.host);
          if (mac) {
            d.mac = mac;
            d.macLearned = true;
            this.save();
          }
        }
        this.changed();
      });
      conn.on('error', (e) => this.setError(id, e));
      conn.on('unpaired', () => {
        d.paired = false;
        this.save();
        this.setError(id, new Error('The TV does not recognise this remote. Pair again.'));
      });
      this.connections.set(id, conn);
    }
    return conn;
  }

  async connect(id) {
    const conn = this.connection(id);
    if (conn.state.connected) return;
    try {
      await conn.connect();
      this.setError(id, null);
    } catch (e) {
      this.setError(id, e);
      throw e;
    }
  }

  /** Connects to every paired device in the background. */
  connectAll() {
    for (const d of this.devices.values()) {
      if (d.paired) this.connect(d.id).catch(() => {});
    }
  }

  live(id) {
    const conn = this.connections.get(this.get(id).id);
    if (!conn?.state.connected) throw new Error('TV is not connected');
    return conn;
  }

  // ---------------------------------------------------------------- power

  /** Sends a Wake-on-LAN packet (if the MAC is known) and reconnects. */
  async wake(id) {
    const d = this.get(id);
    if (!d.mac) throw new Error('Unknown MAC address. Connect once while the TV is on, or enter it in the TV settings.');
    await wake(d.mac);
    this.connection(d.id).connect().catch(() => {});
  }

  /** state: 'on' | 'off' | 'toggle'. Only sends POWER when it changes something. */
  async power(id, state = 'toggle') {
    const d = this.get(id);
    const conn = this.connections.get(d.id);
    const connected = !!conn?.state.connected;
    const powered = conn?.state.powered;
    if (state === 'on') {
      if (connected && powered === false) conn.sendKey('POWER');
      else if (!connected) await this.wake(d.id);
      return;
    }
    if (!connected) throw new Error('TV is not connected');
    if (state === 'off' && powered === false) return;
    conn.sendKey('POWER');
  }

  // ---------------------------------------------------------------- sleep timer

  setSleepTimer(id, minutes) {
    const d = this.get(id);
    this.cancelSleepTimer(d.id, false);
    const mins = Number(minutes);
    if (mins > 0) {
      if (mins > 24 * 60) throw new Error('Sleep timer is limited to 24 hours');
      const at = Date.now() + mins * 60000;
      const timer = setTimeout(() => {
        this.sleepTimers.delete(d.id);
        this.power(d.id, 'off').catch((e) => this.setError(d.id, new Error(`Sleep timer: ${e.message}`)));
        this.changed();
      }, mins * 60000);
      timer.unref?.();
      this.sleepTimers.set(d.id, { at, timer });
    }
    this.changed();
    return this.sleepTimers.get(d.id)?.at ?? null;
  }

  cancelSleepTimer(id, notify = true) {
    const t = this.sleepTimers.get(id);
    if (t) clearTimeout(t.timer);
    this.sleepTimers.delete(id);
    if (notify) this.changed();
  }

  // ---------------------------------------------------------------- volume

  /** Steps the volume to an absolute level using volume key presses. */
  async setVolume(id, level) {
    const conn = this.live(id);
    const v = conn.state.volume;
    if (!v) throw new Error('The TV has not reported its volume yet');
    const target = Math.max(0, Math.min(v.max || 100, Math.round(Number(level))));
    let delta = target - v.level;
    const key = delta > 0 ? 'VOLUME_UP' : 'VOLUME_DOWN';
    delta = Math.min(Math.abs(delta), 100);
    for (let i = 0; i < delta; i++) {
      conn.sendKey(key);
      await sleep(60);
    }
  }

  // ---------------------------------------------------------------- macros

  saveMacro({ id, name, script }) {
    name = String(name ?? '').trim().slice(0, 60);
    if (!name) throw new Error('Macro needs a name');
    const steps = parseMacro(script);
    if (!steps.length) throw new Error('Macro is empty');
    const existing = id && this.macros.find((m) => m.id === id);
    if (existing) Object.assign(existing, { name, script: String(script) });
    else this.macros.push({ id: crypto.randomUUID(), name, script: String(script) });
    this.writeJson('macros.json', this.macros);
    this.changed();
    return existing ?? this.macros.at(-1);
  }

  deleteMacro(id) {
    this.macros = this.macros.filter((m) => m.id !== id);
    this.writeJson('macros.json', this.macros);
    this.changed();
  }

  /** Runs a saved macro (by id or name) or an ad-hoc script on a device. */
  async runMacro(deviceRef, { macro, script }) {
    const d = this.get(deviceRef);
    let name = 'Script';
    if (macro) {
      const m = this.macros.find((x) => x.id === macro || x.name.toLowerCase() === String(macro).toLowerCase());
      if (!m) throw new Error('Unknown macro');
      ({ name, script } = m);
    }
    const steps = parseMacro(script);
    const conn = this.live(d.id);
    this.running.get(d.id)?.controller.abort();
    const controller = new AbortController();
    const entry = { name, controller };
    this.running.set(d.id, entry);
    this.changed();
    try {
      await runMacro(conn, steps, { signal: controller.signal });
    } finally {
      if (this.running.get(d.id) === entry) this.running.delete(d.id);
      this.changed();
    }
  }

  stopMacro(id) {
    this.running.get(this.get(id).id)?.controller.abort();
  }

  startDiscovery() {
    import('bonjour-service')
      .then(({ Bonjour }) => {
        this.bonjour = new Bonjour();
        this.browser = this.bonjour.find({ type: 'androidtvremote2' }, (svc) => {
          const host = svc.addresses?.find((a) => /^\d+\.\d+\.\d+\.\d+$/.test(a)) ?? svc.referer?.address;
          if (!host) return;
          this.discovered.set(host, { name: svc.name, host, port: svc.port });
          this.changed();
        });
        this.browser.on?.('down', (svc) => {
          for (const [host, d] of this.discovered) if (d.name === svc.name) this.discovered.delete(host);
          this.changed();
        });
      })
      .catch((e) => console.warn(`mDNS discovery unavailable: ${e.message}`));
  }

  rescan() {
    this.discovered.clear();
    this.browser?.update?.();
    this.changed();
  }

  close() {
    for (const t of this.sleepTimers.values()) clearTimeout(t.timer);
    for (const r of this.running.values()) r.controller.abort();
    for (const c of this.connections.values()) c.disconnect();
    for (const p of this.pairings.values()) p.close();
    this.browser?.stop?.();
    this.bonjour?.destroy?.();
  }
}
