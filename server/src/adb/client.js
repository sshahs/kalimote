import net from 'node:net';
import { EventEmitter } from 'node:events';
import {
  CMD,
  AUTH_TOKEN,
  AUTH_SIGNATURE,
  AUTH_RSAPUBLICKEY,
  VERSION,
  MAX_DATA,
  MessageReader,
  encodeMessage,
  signToken,
  adbPublicKey,
} from './protocol.js';

export const ADB_PORT = 5555;

/** One ADB stream: writes wait for the device's OKAY (flow control). */
class AdbStream {
  constructor(client, localId) {
    this.client = client;
    this.localId = localId;
    this.remoteId = 0;
    this.buf = Buffer.alloc(0);
    this.closed = false;
    this.waiters = []; // functions re-checked whenever something happens
    this.okayWaiter = null;
  }

  notify() {
    for (const w of this.waiters.slice()) w();
  }

  wait(check, timeoutMs, what) {
    return new Promise((resolve, reject) => {
      const done = (fn, v) => {
        clearTimeout(timer);
        this.waiters = this.waiters.filter((x) => x !== tick);
        fn(v);
      };
      const tick = () => {
        try {
          const r = check();
          if (r !== undefined) done(resolve, r);
        } catch (e) {
          done(reject, e);
        }
      };
      const timer = setTimeout(() => done(reject, new AdbError(`Timed out: ${what}`, 'timeout')), timeoutMs);
      this.waiters.push(tick);
      tick();
    });
  }

  whenOpen(timeoutMs, service) {
    return this.wait(
      () => {
        if (this.remoteId) return this;
        if (this.closed) throw new AdbError(`Device refused ${service.split(':')[0]}`, 'failed');
        return undefined;
      },
      timeoutMs,
      service,
    );
  }

  onOkay(remoteId) {
    if (!this.remoteId) this.remoteId = remoteId;
    else if (this.okayWaiter) {
      const w = this.okayWaiter;
      this.okayWaiter = null;
      w();
    }
    this.notify();
  }

  onData(chunk) {
    this.buf = this.buf.length ? Buffer.concat([this.buf, chunk]) : chunk;
    this.notify();
  }

  onClose() {
    this.closed = true;
    this.notify();
    if (this.okayWaiter) this.okayWaiter(new AdbError('Stream closed', 'closed'));
  }

  write(data, timeoutMs = 30000) {
    if (this.closed) return Promise.reject(new AdbError('Stream closed', 'closed'));
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.okayWaiter = null;
        reject(new AdbError('Timed out writing to device', 'timeout'));
      }, timeoutMs);
      this.okayWaiter = (err) => {
        clearTimeout(timer);
        err ? reject(err) : resolve();
      };
      this.client.socket.write(encodeMessage(CMD.WRTE, this.localId, this.remoteId, data));
    });
  }

  /** Resolves with exactly n bytes. */
  read(n, timeoutMs = 10000) {
    return this.wait(
      () => {
        if (this.error) throw this.error;
        if (this.buf.length >= n) {
          const out = this.buf.subarray(0, n);
          this.buf = this.buf.subarray(n);
          return out;
        }
        if (this.closed) throw new AdbError('Stream closed', 'closed');
        return undefined;
      },
      timeoutMs,
      'reading from device',
    );
  }

  /** Resolves with everything until the device closes the stream. */
  readAll(timeoutMs, what) {
    return this.wait(
      () => {
        if (this.error) throw this.error;
        return this.closed ? this.buf : undefined;
      },
      timeoutMs,
      what,
    );
  }

  close() {
    if (this.closed) return;
    this.closed = true;
    this.client.streams.delete(this.localId);
    if (this.client.connected) this.client.socket.write(encodeMessage(CMD.CLSE, this.localId, this.remoteId));
    this.notify();
  }

  fail(err) {
    this.closed = true;
    this.error = err;
    this.notify();
    if (this.okayWaiter) this.okayWaiter(err);
  }
}

export class AdbError extends Error {
  constructor(message, code) {
    super(message);
    this.code = code; // 'unauthorized' | 'tls' | 'closed' | 'timeout'
  }
}

/**
 * One ADB connection. connect() resolves once the device accepts us. If our
 * key is not yet trusted, the device shows "Allow USB debugging?" and
 * connect() waits up to `approvalTimeoutMs` for the user to accept.
 * Emits 'close'.
 */
export class AdbClient extends EventEmitter {
  constructor({ host, port = ADB_PORT, key, name = 'kalimote@kalimote', approvalTimeoutMs = 0 }) {
    super();
    Object.assign(this, { host, port, key, name, approvalTimeoutMs });
    this.streams = new Map(); // localId -> { chunks, resolve, reject, remoteId }
    this.nextId = 1;
    this.connected = false;
    this.banner = '';
  }

  connect({ timeoutMs = 8000 } = {}) {
    return new Promise((resolve, reject) => {
      let settled = false;
      let sentPublicKey = false;
      const fail = (err) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        this.socket?.destroy();
        reject(err);
      };
      let timer = setTimeout(() => fail(new AdbError('Timed out connecting to the device', 'timeout')), timeoutMs);
      const reader = new MessageReader();
      const socket = net.connect({ host: this.host, port: this.port });
      this.socket = socket;
      socket.setNoDelay(true);
      socket.on('connect', () => socket.write(encodeMessage(CMD.CNXN, VERSION, MAX_DATA, 'host::\0')));
      socket.on('data', (chunk) => {
        let messages;
        try {
          messages = reader.push(chunk);
        } catch (e) {
          socket.destroy(e);
          return;
        }
        for (const m of messages) {
          if (!this.connected) {
            if (m.command === CMD.AUTH && m.arg0 === AUTH_TOKEN) {
              if (!sentPublicKey && !this.triedSignature) {
                this.triedSignature = true;
                socket.write(encodeMessage(CMD.AUTH, AUTH_SIGNATURE, 0, signToken(this.key, m.data)));
              } else if (!sentPublicKey) {
                // Our key is not trusted yet: offer it; the TV asks the user.
                if (!this.approvalTimeoutMs) {
                  fail(new AdbError('The device has not authorised this remote', 'unauthorized'));
                  return;
                }
                sentPublicKey = true;
                socket.write(encodeMessage(CMD.AUTH, AUTH_RSAPUBLICKEY, 0, `${adbPublicKey(this.key, this.name)}\0`));
                clearTimeout(timer);
                timer = setTimeout(
                  () => fail(new AdbError('The prompt on the TV was not accepted in time', 'unauthorized')),
                  this.approvalTimeoutMs,
                );
                this.emit('awaiting-approval');
              }
            } else if (m.command === CMD.CNXN) {
              this.connected = true;
              this.banner = m.data.toString('utf8').replace(/\0+$/, '');
              this.maxData = Math.min(m.arg1 || MAX_DATA, MAX_DATA);
              settled = true;
              clearTimeout(timer);
              resolve(this.banner);
            } else if (m.command === CMD.STLS) {
              fail(new AdbError('The device requires TLS (Wireless debugging); use classic ADB debugging on port 5555', 'tls'));
            }
            continue;
          }
          this.onMessage(m);
        }
      });
      socket.on('error', (e) => fail(new AdbError(`Cannot reach ${this.host}:${this.port} (${e.code ?? e.message})`, 'closed')));
      socket.on('close', () => {
        const wasConnected = this.connected;
        this.connected = false;
        for (const st of this.streams.values()) st.fail(new AdbError('Connection closed', 'closed'));
        this.streams.clear();
        if (!settled) {
          fail(
            sentPublicKey
              ? new AdbError('The TV did not allow the connection', 'unauthorized')
              : new AdbError('The device closed the connection', 'closed'),
          );
        }
        if (wasConnected) this.emit('close');
      });
    });
  }

  onMessage(m) {
    const stream = this.streams.get(m.arg1);
    if (!stream) {
      // Unknown stream: tell the device to close it.
      if (m.command === CMD.WRTE || m.command === CMD.OKAY) {
        this.socket.write(encodeMessage(CMD.CLSE, 0, m.arg0));
      }
      return;
    }
    switch (m.command) {
      case CMD.OKAY:
        stream.onOkay(m.arg0);
        break;
      case CMD.WRTE:
        this.socket.write(encodeMessage(CMD.OKAY, m.arg1, m.arg0));
        stream.onData(Buffer.from(m.data));
        break;
      case CMD.CLSE:
        this.streams.delete(m.arg1);
        if (stream.remoteId) this.socket.write(encodeMessage(CMD.CLSE, m.arg1, m.arg0));
        stream.onClose();
        break;
      default:
        break;
    }
  }

  /** Opens a stream to an adbd service ("shell:…", "exec:…", "sync:"). */
  open(service, { timeoutMs = 10000 } = {}) {
    if (!this.connected) return Promise.reject(new AdbError('Not connected', 'closed'));
    const localId = this.nextId++;
    const stream = new AdbStream(this, localId);
    this.streams.set(localId, stream);
    this.socket.write(encodeMessage(CMD.OPEN, localId, 0, `${service}\0`));
    return stream.whenOpen(timeoutMs, service);
  }

  /** Runs a command and resolves with its raw output. */
  async exec(command, { timeoutMs = 10000, service = 'exec' } = {}) {
    const stream = await this.open(`${service}:${command}`, { timeoutMs });
    return stream.readAll(timeoutMs, command);
  }

  /** Runs a shell command and resolves with its output as text. */
  async shell(command, { timeoutMs = 10000 } = {}) {
    return (await this.exec(command, { timeoutMs, service: 'shell' })).toString('utf8');
  }

  /**
   * Uploads `data` to `remotePath` with the ADB sync protocol.
   * onProgress(sentBytes, totalBytes) is called as chunks are acknowledged.
   */
  async push(data, remotePath, { mode = 0o644, onProgress = () => {}, timeoutMs = 30000 } = {}) {
    const stream = await this.open('sync:', { timeoutMs });
    const req = (id, length, payload = Buffer.alloc(0)) => {
      const h = Buffer.alloc(8);
      h.write(id, 0, 'ascii');
      h.writeUInt32LE(length, 4);
      return Buffer.concat([h, payload]);
    };
    const spec = Buffer.from(`${remotePath},${mode}`);
    await stream.write(req('SEND', spec.length, spec), timeoutMs);
    const CHUNK = 64 * 1024;
    for (let off = 0; off < data.length; off += CHUNK) {
      const chunk = data.subarray(off, off + CHUNK);
      await stream.write(req('DATA', chunk.length, chunk), timeoutMs);
      onProgress(Math.min(off + CHUNK, data.length), data.length);
    }
    await stream.write(req('DONE', Math.floor(Date.now() / 1000)), timeoutMs);
    const reply = await stream.read(8, timeoutMs);
    const id = reply.toString('ascii', 0, 4);
    if (id === 'FAIL') {
      const msg = await stream.read(reply.readUInt32LE(4), timeoutMs);
      stream.close();
      throw new AdbError(`Upload failed: ${msg.toString('utf8')}`, 'failed');
    }
    if (id !== 'OKAY') throw new AdbError(`Unexpected sync reply ${id}`, 'failed');
    await stream.write(req('QUIT', 0), timeoutMs).catch(() => {});
    stream.close();
  }

  close() {
    this.connected = false;
    this.socket?.destroy();
  }
}
