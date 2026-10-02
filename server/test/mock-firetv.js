#!/usr/bin/env node
// A fake Fire TV speaking the adbd side of the ADB protocol, with real RSA
// auth: unknown keys trigger the "Allow USB debugging?" prompt (auto-accepted
// unless `autoApprove` is false), trusted keys are verified by signature.
// Shell commands Kalimote uses are emulated.
//   node test/mock-firetv.js     (PORT, default 5555)

import net from 'node:net';
import crypto from 'node:crypto';
import zlib from 'node:zlib';
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

const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});
const crc32 = (buf) => {
  let c = 0xffffffff;
  for (const b of buf) c = CRC_TABLE[(c ^ b) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
};

/** A small PNG of a "TV screen" (gradient + app colour bar), so screenshots look plausible. */
export function screenPng(seed = 0, width = 320, height = 180) {
  const rows = [];
  for (let y = 0; y < height; y++) {
    const row = Buffer.alloc(1 + width * 3);
    for (let x = 0; x < width; x++) {
      const bar = y > height * 0.72 && y < height * 0.9 && x > width * 0.08 && x < width * 0.92;
      row[1 + x * 3] = bar ? 255 : Math.round(20 + (x / width) * 60 + (seed * 37) % 80);
      row[2 + x * 3] = bar ? 153 : Math.round(25 + (y / height) * 40);
      row[3 + x * 3] = bar ? 0 : Math.round(60 + (x / width) * 120);
    }
    rows.push(row);
  }
  const chunk = (type, data) => {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type), data]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(td));
    return Buffer.concat([len, td, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 2; // RGB
  return Buffer.concat([
    Buffer.from('89504e470d0a1a0a', 'hex'),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(Buffer.concat(rows))),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

const INSTALLED = [
  'com.netflix.ninja',
  'com.amazon.firetv.youtube',
  'org.jellyfin.androidtv',
  'org.jellyfin.androidtv.debug',
  'org.videolan.vlc',
  'com.example.coolapp',
];

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
    this.installed = [...INSTALLED];
    this.files = new Map(); // path -> Buffer (pushed with sync)
    this.screenshots = 0;
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
    const syncStreams = new Map();
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
          if (service === 'sync:') {
            syncStreams.set(local, { remote: m.arg0, buf: Buffer.alloc(0), file: null });
            continue;
          }
          let output = '';
          if (service.startsWith('shell:')) output = this.shell(service.slice(6));
          else if (service.startsWith('exec:')) output = this.exec(service.slice(5));
          if (output.length) send(CMD.WRTE, local, m.arg0, output);
          send(CMD.CLSE, local, m.arg0);
        } else if (m.command === CMD.WRTE && syncStreams.has(m.arg1)) {
          const st = syncStreams.get(m.arg1);
          send(CMD.OKAY, m.arg1, m.arg0);
          st.buf = Buffer.concat([st.buf, m.data]);
          this.sync(st, (data) => send(CMD.WRTE, m.arg1, st.remote, data), () => {
            syncStreams.delete(m.arg1);
            send(CMD.CLSE, m.arg1, st.remote);
          });
        }
        // OKAY / CLSE from the client need no action here.
      }
    });
  }

  /** Handles buffered ADB sync requests (SEND / DATA / DONE / QUIT). */
  sync(st, reply, close) {
    for (;;) {
      if (st.buf.length < 8) return;
      const id = st.buf.toString('ascii', 0, 4);
      const len = st.buf.readUInt32LE(4);
      if (id === 'DONE' || id === 'QUIT') {
        st.buf = st.buf.subarray(8);
        if (id === 'QUIT') return close();
        this.files.set(st.file.path, Buffer.concat(st.file.chunks));
        this.emit('pushed', st.file.path);
        reply(Buffer.from('OKAY\0\0\0\0', 'binary'));
        continue;
      }
      if (st.buf.length < 8 + len) return;
      const payload = st.buf.subarray(8, 8 + len);
      st.buf = st.buf.subarray(8 + len);
      if (id === 'SEND') st.file = { path: payload.toString().split(',')[0], chunks: [] };
      else if (id === 'DATA') st.file.chunks.push(Buffer.from(payload));
    }
  }

  exec(cmd) {
    this.commands.push(`exec:${cmd}`);
    if (cmd === 'screencap -p') return screenPng(this.screenshots++);
    return this.shell(cmd);
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
    if (/^cmd package query-activities/.test(cmd)) {
      // Real output lists "package/activity" lines under each match.
      return this.installed.map((p) => `priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false\n  ${p}/.MainActivity\n`).join('');
    }
    if (cmd === 'pm list packages -3') return this.installed.map((p) => `package:${p}`).join('\n');
    if ((m = /^pm install -r (\S+)$/.exec(cmd))) {
      const apk = this.files.get(m[1]);
      if (!apk) return 'Failure [INSTALL_FAILED_INVALID_URI]\n';
      this.emit('installed', apk);
      return 'Performing Streamed Install\nSuccess\n';
    }
    if ((m = /^rm -f (\S+)$/.exec(cmd))) {
      this.files.delete(m[1]);
      return '';
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
  tv.on('installed', (apk) => console.log(`installed apk ${apk.length} bytes`));
}
