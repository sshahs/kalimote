#!/usr/bin/env node
// A fake Android TV implementing the TV side of the remote protocol v2.
// Used by the automated tests and handy for developing the UIs without a TV:
//   node test/mock-tv.js            (pairing on 6467, remote on 6466)
// The pairing code is printed to stdout, as a real TV would show on screen.

import tls from 'node:tls';
import crypto from 'node:crypto';
import { EventEmitter } from 'node:events';
import { fileURLToPath } from 'node:url';
import { FrameReader, frame } from '../src/protocol/proto.js';
import { pairingMsg, parsePairing, remoteMsg, parseRemote, Status } from '../src/protocol/messages.js';
import { generateClientCertificate, rsaPublicParts } from '../src/protocol/certs.js';

export class MockTv extends EventEmitter {
  constructor({ name = 'Mock TV' } = {}) {
    super();
    this.name = name;
    this.identity = generateClientCertificate('mock-tv');
    this.paired = new Set(); // fingerprints of paired client certs
    this.keys = []; // received { code, direction }
    this.texts = [];
    this.links = [];
    this.powered = true;
    this.volume = { level: 10, max: 100, muted: false };
    this.remoteSockets = new Set();
  }

  async listen({ pairingPort = 0, remotePort = 0, host = '127.0.0.1' } = {}) {
    const opts = { ...this.identity, requestCert: true, rejectUnauthorized: false };
    this.pairingServer = tls.createServer(opts, (s) => this.onPairing(s));
    this.remoteServer = tls.createServer(opts, (s) => this.onRemote(s));
    await Promise.all([
      new Promise((r) => this.pairingServer.listen(pairingPort, host, r)),
      new Promise((r) => this.remoteServer.listen(remotePort, host, r)),
    ]);
    this.pairingPort = this.pairingServer.address().port;
    this.remotePort = this.remoteServer.address().port;
    return this;
  }

  close() {
    for (const s of this.remoteSockets) s.destroy();
    this.pairingServer?.close();
    this.remoteServer?.close();
  }

  onPairing(socket) {
    const reader = new FrameReader();
    const clientCert = socket.getPeerCertificate().raw;
    const send = (m) => socket.write(frame(m));
    socket.on('error', () => {});
    socket.on('data', (chunk) => {
      for (const f of reader.push(chunk)) {
        const msg = parsePairing(f);
        switch (msg.kind) {
          case 'REQUEST':
            send(pairingMsg.requestAck(this.name));
            break;
          case 'OPTION':
            send(pairingMsg.option());
            break;
          case 'CONFIGURATION': {
            const nonce = crypto.randomBytes(2);
            this.expectedSecret = this.secretFor(clientCert, nonce);
            this.code = (this.expectedSecret.subarray(0, 1).toString('hex') + nonce.toString('hex')).toUpperCase();
            send(pairingMsg.configurationAck());
            this.emit('code', this.code);
            break;
          }
          case 'SECRET':
            if (this.expectedSecret && msg.body.bytes(1)?.equals(this.expectedSecret)) {
              this.paired.add(fingerprint(clientCert));
              send(pairingMsg.secretAck(this.expectedSecret));
              this.emit('paired');
            } else {
              send(pairingMsg.error(Status.BAD_SECRET));
            }
            socket.end();
            break;
          default:
            send(pairingMsg.error(Status.ERROR));
            socket.end();
        }
      }
    });
  }

  secretFor(clientCert, nonce) {
    const c = rsaPublicParts(clientCert);
    const s = rsaPublicParts(this.identity.cert);
    return crypto.createHash('sha256').update(c.n).update(c.e).update(s.n).update(s.e).update(nonce).digest();
  }

  onRemote(socket) {
    socket.on('error', () => {});
    const cert = socket.getPeerCertificate().raw;
    if (!cert || !this.paired.has(fingerprint(cert))) {
      socket.destroy();
      return;
    }
    const reader = new FrameReader();
    const send = (m) => socket.write(frame(m));
    this.remoteSockets.add(socket);
    socket.on('close', () => this.remoteSockets.delete(socket));
    socket.on('data', (chunk) => {
      for (const f of reader.push(chunk)) {
        const { kind, body } = parseRemote(f);
        switch (kind) {
          case 'CONFIGURE':
            send(remoteMsg.setActive());
            break;
          case 'SET_ACTIVE':
            send(remoteMsg.start(this.powered));
            send(remoteMsg.volume(this.volume));
            send(remoteMsg.imeKeyInject('com.google.android.tvlauncher', 1, 1));
            this.emit('connected');
            break;
          case 'KEY_INJECT': {
            const key = { code: body.uint(1), direction: body.uint(2) };
            this.keys.push(key);
            this.onKey(key, send);
            this.emit('key', key);
            break;
          }
          case 'IME_BATCH_EDIT': {
            const text = body.message(3)?.message(2)?.string(3) ?? '';
            this.texts.push(text);
            this.emit('text', text);
            break;
          }
          case 'APP_LINK_LAUNCH':
            this.links.push(body.string(1));
            send(remoteMsg.imeKeyInject(body.string(1), 1, 1));
            this.emit('link', body.string(1));
            break;
          default:
            break;
        }
      }
    });
    send(remoteMsg.configure());
  }

  onKey({ code, direction }, send) {
    if (direction !== 3 && direction !== 1) return;
    if (code === 26) {
      this.powered = !this.powered;
      send(remoteMsg.start(this.powered));
    } else if (code === 24 || code === 25) {
      const delta = code === 24 ? 1 : -1;
      this.volume = { ...this.volume, level: Math.min(this.volume.max, Math.max(0, this.volume.level + delta)) };
      send(remoteMsg.volume(this.volume));
    } else if (code === 164) {
      this.volume = { ...this.volume, muted: !this.volume.muted };
      send(remoteMsg.volume(this.volume));
    }
  }

  /** Simulates the user removing this client from the TV's paired devices. */
  forgetAll() {
    this.paired.clear();
    for (const s of this.remoteSockets) s.destroy();
  }
}

function fingerprint(der) {
  return crypto.createHash('sha256').update(der).digest('hex');
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const tv = new MockTv({ name: process.env.MOCK_TV_NAME ?? 'Mock TV' });
  await tv.listen({
    pairingPort: Number(process.env.PAIRING_PORT ?? 6467),
    remotePort: Number(process.env.REMOTE_PORT ?? 6466),
    host: process.env.HOST ?? '0.0.0.0',
  });
  console.log(`Mock TV listening: pairing ${tv.pairingPort}, remote ${tv.remotePort}`);
  tv.on('code', (c) => console.log(`>>> Pairing code shown on TV: ${c}`));
  tv.on('paired', () => console.log('Client paired'));
  tv.on('connected', () => console.log('Remote connected'));
  tv.on('key', (k) => console.log(`key ${k.code} dir ${k.direction}`));
  tv.on('text', (t) => console.log(`text "${t}"`));
  tv.on('link', (l) => console.log(`launch ${l}`));
}
