import tls from 'node:tls';
import { FrameReader, frame } from './proto.js';
import { pairingMsg, parsePairing, Status } from './messages.js';
import { computePairingSecret } from './certs.js';

export const PAIRING_PORT = 6467;

/**
 * One pairing session with a TV. Usage:
 *   const p = new PairingSession({ host, key, cert });
 *   await p.start();          // TV now shows a 6 character code
 *   await p.finish('A1B2C3'); // code typed by the user
 */
export class PairingSession {
  constructor({ host, port = PAIRING_PORT, key, cert, clientName = 'Kalimote', timeoutMs = 15000 }) {
    Object.assign(this, { host, port, key, cert, clientName, timeoutMs });
    this.reader = new FrameReader();
    this.waiters = [];
    this.closed = false;
  }

  start() {
    return new Promise((resolve, reject) => {
      const socket = tls.connect({
        host: this.host,
        port: this.port,
        key: this.key,
        cert: this.cert,
        rejectUnauthorized: false, // TVs use self-signed certificates
        timeout: this.timeoutMs,
      });
      this.socket = socket;
      socket.once('secureConnect', async () => {
        try {
          this.serverCert = socket.getPeerCertificate().raw;
          await this.exchange(pairingMsg.request(this.clientName), 'REQUEST_ACK');
          await this.exchange(pairingMsg.option(), 'OPTION');
          await this.exchange(pairingMsg.configuration(), 'CONFIGURATION_ACK');
          socket.setTimeout(5 * 60 * 1000); // give the user time to type the code
          resolve();
        } catch (e) {
          this.close();
          reject(e);
        }
      });
      socket.on('data', (chunk) => this.onData(chunk));
      socket.on('timeout', () => socket.destroy(new Error('Pairing timed out')));
      socket.on('error', (e) => {
        this.failAll(e);
        reject(e);
      });
      socket.on('close', () => {
        this.closed = true;
        this.failAll(new Error('Pairing connection closed'));
      });
    });
  }

  get active() {
    return !this.closed && !!this.socket && !this.socket.destroyed;
  }

  async finish(code) {
    if (!this.active) throw new Error('Pairing session is not active');
    const secret = computePairingSecret(this.cert, this.serverCert, code);
    try {
      await this.exchange(pairingMsg.secret(secret), 'SECRET_ACK');
    } finally {
      this.close();
    }
  }

  close() {
    this.socket?.destroy();
  }

  exchange(payload, expectedKind) {
    return new Promise((resolve, reject) => {
      this.waiters.push({ resolve, reject, expectedKind });
      this.socket.write(frame(payload));
    });
  }

  onData(chunk) {
    let frames;
    try {
      frames = this.reader.push(chunk);
    } catch (e) {
      this.socket.destroy(e);
      return;
    }
    for (const f of frames) {
      const waiter = this.waiters.shift();
      if (!waiter) continue;
      const msg = parsePairing(f);
      if (msg.status !== Status.OK) {
        const reason =
          msg.status === Status.BAD_SECRET ? 'Pairing code rejected by TV' : `TV returned status ${msg.status}`;
        waiter.reject(new Error(reason));
      } else if (msg.kind !== waiter.expectedKind) {
        waiter.reject(new Error(`Unexpected pairing message ${msg.kind}, wanted ${waiter.expectedKind}`));
      } else {
        waiter.resolve(msg);
      }
    }
  }

  failAll(err) {
    for (const w of this.waiters.splice(0)) w.reject(err);
  }
}
