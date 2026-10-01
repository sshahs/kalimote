import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { EventEmitter } from 'node:events';
import { generateClientCertificate } from './protocol/certs.js';
import { PairingSession, PAIRING_PORT } from './protocol/pairing.js';
import { RemoteConnection, REMOTE_PORT } from './protocol/remote.js';

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
    fs.mkdirSync(dataDir, { recursive: true });
    this.identity = this.loadIdentity();
    for (const d of this.readJson('devices.json', [])) this.devices.set(d.id, d);
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
    }));
    const knownHosts = new Set(known.map((d) => d.host));
    const discovered = [...this.discovered.values()].filter((d) => !knownHosts.has(d.host));
    return { devices: known, discovered };
  }

  get(id) {
    const d = this.devices.get(id);
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
    const d = this.get(id);
    d.name = String(name || d.host).slice(0, 80);
    this.save();
    this.changed();
  }

  remove(id) {
    this.get(id);
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
      conn.on('state', () => this.changed());
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
    const conn = this.connections.get(id);
    if (!conn?.state.connected) throw new Error('TV is not connected');
    return conn;
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
    for (const c of this.connections.values()) c.disconnect();
    for (const p of this.pairings.values()) p.close();
    this.browser?.stop?.();
    this.bonjour?.destroy?.();
  }
}
