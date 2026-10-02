#!/usr/bin/env node
// A fake Fire TV speaking the adbd side of the ADB protocol, with real RSA
// auth: unknown keys trigger the "Allow USB debugging?" prompt (auto-accepted
// unless `autoApprove` is false), trusted keys are verified by signature.
// Shell commands Kalimote uses are emulated.
//   node test/mock-firetv.js     (PORT, default 5555)

import net from 'node:net';
import crypto from 'node:crypto';
import { EventEmitter } from 'node:events';
import { fileURLToPath } from 'node:url';
import {
  CMD,
  AUTH_TOKEN,
  AUTH_SIGNATURE,
  AUTH_RSAPUBLICKEY,
  VERSION,
  MAX_DATA,
  MessageReader,
  encodeMessage,
  verifyToken,
  parseAdbPublicKey,
} from '../src/adb/protocol.js';

const LAUNCHER = 'com.amazon.tv.launcher';

export class MockFireTv extends EventEmitter {
  constructor({ autoApprove = true } = {}) {
    super();
    this.autoApprove = autoApprove;
    this.trusted = []; // KeyObjects
    this.commands = [];
    this.keys = [];
    this.texts = [];
    this.awake = true;
    this.currentApp = LAUNCHER;
    this.pending = [];
    this.sockets = new Set();
    this.server = net.createServer((s) => this.onConnection(s));
  }

  listen(port = 0, host = '127.0.0.1') {
    return new Promise((resolve) => this.server.listen(port, host, () => resolve(this)));
  }

  get port() {
    return this.server.address().port;
  }

  close() {
    for (const s of this.sockets) s.destroy();
    this.server.close();
  }

  /** Simulates the user tapping "Allow" / "Cancel" on the TV prompt. */
  approve() {
    for (const p of this.pending.splice(0)) p(true);
  }

  deny() {
    for (const p of this.pending.splice(0)) p(false);
  }

  /** Simulates "Revoke USB debugging authorisations". */
  revokeAll() {
    this.trusted = [];
    for (const s of this.sockets) s.destroy();
  }

  banner() {
    return 'device::ro.product.name=mantis;ro.product.model=AFTMM;ro.product.device=mantis;features=shell_v2,cmd\0';
  }

  onConnection(socket) {
    this.sockets.add(socket);
    socket.on('close', () => this.sockets.delete(socket));
    socket.on('error', () => {});
    const reader = new MessageReader();
    const send = (...args) => !socket.destroyed && socket.write(encodeMessage(...args));
    let token = null;
    let authed = false;
    let nextLocal = 1000;
    const newToken = () => {
      token = crypto.randomBytes(20);
      send(CMD.AUTH, AUTH_TOKEN, 0, token);
    };

    socket.on('data', (chunk) => {
      let messages;
      try {
        messages = reader.push(chunk);
      } catch {
        socket.destroy();
        return;
      }
      for (const m of messages) {
        if (!authed) {
          if (m.command === CMD.CNXN) {
            newToken();
          } else if (m.command === CMD.AUTH && m.arg0 === AUTH_SIGNATURE) {
            if (this.trusted.some((k) => verifyToken(k, token, m.data))) {
              authed = true;
              send(CMD.CNXN, VERSION, MAX_DATA, this.banner());
            } else {
              newToken();
            }
          } else if (m.command === CMD.AUTH && m.arg0 === AUTH_RSAPUBLICKEY) {
            const key = parseAdbPublicKey(m.data.toString('utf8'));
            const decide = (ok) => {
              if (!ok) return socket.destroy();
              this.trusted.push(key);
              authed = true;
              send(CMD.CNXN, VERSION, MAX_DATA, this.banner());
              this.emit('approved');
            };
            this.emit('prompt');
            if (this.autoApprove) decide(true);
            else this.pending.push(decide);
          }
          continue;
        }
        if (m.command === CMD.OPEN) {
          const service = m.data.toString('utf8').replace(/\0+$/, '');
          const local = nextLocal++;
          send(CMD.OKAY, local, m.arg0);
          const output = service.startsWith('shell:') ? this.shell(service.slice(6)) : '';
          if (output) send(CMD.WRTE, local, m.arg0, output);
          send(CMD.CLSE, local, m.arg0);
        }
        // OKAY / CLSE from the client need no action here.
      }
    });
  }

  shell(cmd) {
    this.commands.push(cmd);
    if (/^dumpsys window/.test(cmd)) {
      return (
        `  mCurrentFocus=Window{1a2b3c u0 ${this.currentApp}/${this.currentApp}.MainActivity}\n` +
        `  mFocusedApp=ActivityRecord{4d5e6f u0 ${this.currentApp}/.MainActivity t12}\n` +
        `  mWakefulness=${this.awake ? 'Awake' : 'Asleep'}\n  Display Power: state=${this.awake ? 'ON' : 'OFF'}\n`
      );
    }
    let m;
    if ((m = /^input keyevent (--longpress )?(\d+)$/.exec(cmd))) {
      const key = { code: Number(m[2]), long: !!m[1] };
      this.keys.push(key);
      if (key.code === 26) this.awake = !this.awake;
      if (key.code === 223) this.awake = false;
      if (key.code === 224) this.awake = true;
      if (key.code === 3) this.currentApp = LAUNCHER;
      this.emit('key', key);
      return '';
    }
    if ((m = /^input text '(.*)'$/s.exec(cmd))) {
      const text = m[1].replace(/'\\''/g, "'").replace(/%s/g, ' ').replace(/\\%/g, '%');
      this.texts.push(text);
      this.emit('text', text);
      return '';
    }
    if ((m = /^am start -a android\.intent\.action\.VIEW -d '(.*)'$/.exec(cmd))) {
      this.currentApp = m[1].replace(/'\\''/g, "'");
      this.emit('launch', this.currentApp);
      return 'Starting: Intent { act=android.intent.action.VIEW }\n';
    }
    if ((m = /^monkey -p ([\w.]+)/.exec(cmd))) {
      this.currentApp = m[1];
      this.emit('launch', this.currentApp);
      return 'Events injected: 1\n';
    }
    return `/system/bin/sh: ${cmd.split(' ')[0]}: inaccessible or not found\n`;
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  // APPROVE_AFTER_MS simulates the user accepting the prompt after a delay.
  const delay = process.env.APPROVE_AFTER_MS ? Number(process.env.APPROVE_AFTER_MS) : null;
  const tv = await new MockFireTv({ autoApprove: delay === null && process.env.AUTO_APPROVE !== '0' }).listen(
    Number(process.env.PORT ?? 5555),
    process.env.HOST ?? '127.0.0.1',
  );
  console.log(`Mock Fire TV ADB listening on ${tv.port}`);
  tv.on('prompt', () => {
    console.log('>>> TV shows "Allow USB debugging?"');
    if (delay !== null) setTimeout(() => tv.approve(), delay);
  });
  tv.on('approved', () => console.log('Debugging allowed'));
  tv.on('key', (k) => console.log(`key ${k.code}${k.long ? ' long' : ''}`));
  tv.on('text', (t) => console.log(`text "${t}"`));
  tv.on('launch', (a) => console.log(`launch ${a}`));
}
