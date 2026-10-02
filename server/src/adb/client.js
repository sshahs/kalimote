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
        for (const s of this.streams.values()) s.reject(new AdbError('Connection closed', 'closed'));
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
        stream.remoteId = m.arg0;
        break;
      case CMD.WRTE:
        stream.chunks.push(Buffer.from(m.data));
        this.socket.write(encodeMessage(CMD.OKAY, m.arg1, m.arg0));
        break;
      case CMD.CLSE:
        this.streams.delete(m.arg1);
        if (stream.remoteId) this.socket.write(encodeMessage(CMD.CLSE, m.arg1, m.arg0));
        clearTimeout(stream.timer);
        stream.resolve(Buffer.concat(stream.chunks).toString('utf8'));
        break;
      default:
        break;
    }
  }

  /** Runs a shell command and resolves with its output. */
  shell(command, { timeoutMs = 10000 } = {}) {
    if (!this.connected) return Promise.reject(new AdbError('Not connected', 'closed'));
    return new Promise((resolve, reject) => {
      const localId = this.nextId++;
      const stream = { chunks: [], resolve, reject, remoteId: 0 };
      stream.timer = setTimeout(() => {
        this.streams.delete(localId);
        reject(new AdbError(`Command timed out: ${command}`, 'timeout'));
      }, timeoutMs);
      this.streams.set(localId, stream);
      this.socket.write(encodeMessage(CMD.OPEN, localId, 0, `shell:${command}\0`));
    });
  }

  close() {
    this.connected = false;
    this.socket?.destroy();
  }
}
