// ADB wire protocol (as spoken by adbd on port 5555) and RSA auth helpers.
// Used to control Fire TV and other Android devices with "ADB debugging"
// enabled. The Android app implements the same thing in Adb.kt.

import crypto from 'node:crypto';

export const CMD = {
  CNXN: 0x4e584e43,
  AUTH: 0x48545541,
  OPEN: 0x4e45504f,
  OKAY: 0x59414b4f,
  CLSE: 0x45534c43,
  WRTE: 0x45545257,
  STLS: 0x534c5453,
};
export const CMD_NAME = Object.fromEntries(Object.entries(CMD).map(([k, v]) => [v, k]));

export const AUTH_TOKEN = 1;
export const AUTH_SIGNATURE = 2;
export const AUTH_RSAPUBLICKEY = 3;

export const VERSION = 0x01000001;
export const MAX_DATA = 256 * 1024;
const HEADER = 24;

export function encodeMessage(command, arg0, arg1, data = Buffer.alloc(0)) {
  const payload = Buffer.isBuffer(data) ? data : Buffer.from(data);
  const header = Buffer.alloc(HEADER);
  let sum = 0;
  for (const b of payload) sum = (sum + b) >>> 0;
  header.writeUInt32LE(command, 0);
  header.writeUInt32LE(arg0 >>> 0, 4);
  header.writeUInt32LE(arg1 >>> 0, 8);
  header.writeUInt32LE(payload.length, 12);
  header.writeUInt32LE(sum, 16);
  header.writeUInt32LE((command ^ 0xffffffff) >>> 0, 20);
  return Buffer.concat([header, payload]);
}

/** Splits a byte stream into ADB messages. */
export class MessageReader {
  constructor() {
    this.buf = Buffer.alloc(0);
  }

  push(chunk) {
    this.buf = this.buf.length ? Buffer.concat([this.buf, chunk]) : chunk;
    const out = [];
    while (this.buf.length >= HEADER) {
      const command = this.buf.readUInt32LE(0);
      const magic = this.buf.readUInt32LE(20);
      if (((command ^ 0xffffffff) >>> 0) !== magic) throw new Error('Corrupt ADB message');
      const len = this.buf.readUInt32LE(12);
      if (len > MAX_DATA * 4) throw new Error('ADB message too large');
      if (this.buf.length < HEADER + len) break;
      out.push({
        command,
        arg0: this.buf.readUInt32LE(4),
        arg1: this.buf.readUInt32LE(8),
        data: this.buf.subarray(HEADER, HEADER + len),
      });
      this.buf = this.buf.subarray(HEADER + len);
    }
    return out;
  }
}

// DER prefix of a SHA-1 DigestInfo. adbd treats the 20-byte token as a SHA-1
// digest and verifies a PKCS#1 v1.5 signature over it.
const SHA1_DIGEST_INFO = Buffer.from('3021300906052b0e03021a05000414', 'hex');

/** Signs an AUTH token with the client's RSA private key (PEM). */
export function signToken(privateKeyPem, token) {
  return crypto.privateEncrypt(
    { key: privateKeyPem, padding: crypto.constants.RSA_PKCS1_PADDING },
    Buffer.concat([SHA1_DIGEST_INFO, token]),
  );
}

/** Verifies a token signature against an RSA public key (used by the mock device). */
export function verifyToken(publicKey, token, signature) {
  try {
    const plain = crypto.publicDecrypt({ key: publicKey, padding: crypto.constants.RSA_PKCS1_PADDING }, signature);
    return plain.equals(Buffer.concat([SHA1_DIGEST_INFO, token]));
  } catch {
    return false;
  }
}

const bigFromBuf = (b) => BigInt(`0x${b.toString('hex') || '0'}`);
const modPow = (base, exp, mod) => {
  let r = 1n;
  base %= mod;
  while (exp > 0n) {
    if (exp & 1n) r = (r * base) % mod;
    base = (base * base) % mod;
    exp >>= 1n;
  }
  return r;
};

/**
 * Encodes an RSA public key in adbd's format: base64 of
 * { u32 words, u32 n0inv, u32 n[words], u32 rr[words], u32 e } (little endian),
 * followed by " name". This is what the TV stores when you tap "Always allow".
 */
export function adbPublicKey(publicKeyOrPem, name = 'kalimote@kalimote') {
  const jwk = crypto.createPublicKey(publicKeyOrPem).export({ format: 'jwk' });
  const n = bigFromBuf(Buffer.from(jwk.n, 'base64url'));
  const e = Number(bigFromBuf(Buffer.from(jwk.e, 'base64url')));
  const words = Math.ceil(Buffer.from(jwk.n, 'base64url').length / 4);
  const r32 = 1n << 32n;
  // n0inv = -1 / n[0] mod 2^32
  const n0 = n % r32;
  let inv = 1n;
  for (let i = 0; i < 6; i++) inv = (((inv * (2n - n0 * inv)) % r32) + r32) % r32; // Newton: bits double each step
  const n0inv = (r32 - inv) % r32;
  const rr = modPow(2n, BigInt(words * 64), n); // (2^(32*words))^2 mod n
  const out = Buffer.alloc(4 + 4 + words * 4 * 2 + 4);
  let off = 0;
  out.writeUInt32LE(words, off);
  off += 4;
  out.writeUInt32LE(Number(n0inv), off);
  off += 4;
  for (const value of [n, rr]) {
    let v = value;
    for (let i = 0; i < words; i++) {
      out.writeUInt32LE(Number(v % r32), off);
      off += 4;
      v /= r32;
    }
  }
  out.writeUInt32LE(e, off);
  return `${out.toString('base64')} ${name}`;
}

/** Inverse of adbPublicKey (used by the mock device): returns a KeyObject. */
export function parseAdbPublicKey(text) {
  const b64 = String(text).replace(/\0+$/, '').split(' ')[0];
  const buf = Buffer.from(b64, 'base64');
  const words = buf.readUInt32LE(0);
  let n = 0n;
  for (let i = words - 1; i >= 0; i--) n = (n << 32n) | BigInt(buf.readUInt32LE(8 + i * 4));
  const e = buf.readUInt32LE(8 + words * 8);
  const toB64u = (big) => {
    let hex = big.toString(16);
    if (hex.length % 2) hex = `0${hex}`;
    return Buffer.from(hex, 'hex').toString('base64url');
  };
  return crypto.createPublicKey({ key: { kty: 'RSA', n: toB64u(n), e: toB64u(BigInt(e)) }, format: 'jwk' });
}
