// Minimal protobuf wire-format codec. The Android TV remote protocol only
// needs varints and length-delimited fields, so this avoids a full protobuf
// runtime and .proto compilation step.

const WIRE_VARINT = 0;
const WIRE_LEN = 2;

export class Writer {
  constructor() {
    this.parts = [];
  }

  static varintBytes(value) {
    let v = BigInt.asUintN(64, BigInt(value));
    const out = [];
    while (v > 0x7fn) {
      out.push(Number(v & 0x7fn) | 0x80);
      v >>= 7n;
    }
    out.push(Number(v));
    return Buffer.from(out);
  }

  tag(field, wire) {
    this.parts.push(Writer.varintBytes((field << 3) | wire));
  }

  varint(field, value) {
    if (value === undefined || value === null) return this;
    this.tag(field, WIRE_VARINT);
    this.parts.push(Writer.varintBytes(typeof value === 'boolean' ? Number(value) : value));
    return this;
  }

  bytes(field, value) {
    if (value === undefined || value === null) return this;
    const buf = Buffer.isBuffer(value) ? value : Buffer.from(value);
    this.tag(field, WIRE_LEN);
    this.parts.push(Writer.varintBytes(buf.length), buf);
    return this;
  }

  string(field, value) {
    if (value === undefined || value === null) return this;
    return this.bytes(field, Buffer.from(String(value), 'utf8'));
  }

  message(field, writerOrBuffer) {
    if (writerOrBuffer === undefined || writerOrBuffer === null) return this;
    const buf = writerOrBuffer instanceof Writer ? writerOrBuffer.finish() : writerOrBuffer;
    return this.bytes(field, buf);
  }

  finish() {
    return Buffer.concat(this.parts);
  }
}

function readVarint(buf, pos) {
  let result = 0n;
  let shift = 0n;
  for (;;) {
    if (pos >= buf.length) throw new RangeError('truncated varint');
    const b = buf[pos++];
    result |= BigInt(b & 0x7f) << shift;
    if ((b & 0x80) === 0) break;
    shift += 7n;
    if (shift > 63n) throw new RangeError('varint too long');
  }
  return { value: result, pos };
}

/** Decoded message: field number -> list of raw values (BigInt or Buffer). */
export class Message {
  constructor(buf) {
    this.fields = new Map();
    let pos = 0;
    while (pos < buf.length) {
      const t = readVarint(buf, pos);
      pos = t.pos;
      const field = Number(t.value >> 3n);
      const wire = Number(t.value & 7n);
      let value;
      if (wire === WIRE_VARINT) {
        const v = readVarint(buf, pos);
        pos = v.pos;
        value = v.value;
      } else if (wire === WIRE_LEN) {
        const l = readVarint(buf, pos);
        const len = Number(l.value);
        pos = l.pos;
        if (pos + len > buf.length) throw new RangeError('truncated field');
        value = buf.subarray(pos, pos + len);
        pos += len;
      } else if (wire === 1) {
        value = buf.subarray(pos, pos + 8);
        pos += 8;
      } else if (wire === 5) {
        value = buf.subarray(pos, pos + 4);
        pos += 4;
      } else {
        throw new Error(`unsupported wire type ${wire}`);
      }
      if (!this.fields.has(field)) this.fields.set(field, []);
      this.fields.get(field).push(value);
    }
  }

  has(field) {
    return this.fields.has(field);
  }

  int(field, def = 0) {
    const v = this.fields.get(field)?.at(-1);
    return typeof v === 'bigint' ? Number(BigInt.asIntN(32, v)) : def;
  }

  uint(field, def = 0) {
    const v = this.fields.get(field)?.at(-1);
    return typeof v === 'bigint' ? Number(v) : def;
  }

  bool(field) {
    return this.uint(field) !== 0;
  }

  bytes(field) {
    const v = this.fields.get(field)?.at(-1);
    return Buffer.isBuffer(v) ? v : undefined;
  }

  string(field, def = '') {
    const v = this.bytes(field);
    return v ? v.toString('utf8') : def;
  }

  message(field) {
    const v = this.bytes(field);
    return v ? new Message(v) : undefined;
  }

  messages(field) {
    return (this.fields.get(field) ?? []).filter(Buffer.isBuffer).map((b) => new Message(b));
  }
}

/** Prefix a payload with its varint length (protobuf "delimited" framing). */
export function frame(payload) {
  return Buffer.concat([Writer.varintBytes(payload.length), payload]);
}

/**
 * Accumulates stream data and yields complete length-delimited frames.
 */
export class FrameReader {
  constructor() {
    this.buf = Buffer.alloc(0);
  }

  push(chunk) {
    this.buf = this.buf.length ? Buffer.concat([this.buf, chunk]) : chunk;
    const frames = [];
    for (;;) {
      let len;
      let pos;
      try {
        ({ value: len, pos } = readVarint(this.buf, 0));
      } catch (e) {
        if (e instanceof RangeError && this.buf.length < 10) break;
        throw e;
      }
      const n = Number(len);
      if (this.buf.length < pos + n) break;
      frames.push(this.buf.subarray(pos, pos + n));
      this.buf = this.buf.subarray(pos + n);
    }
    return frames;
  }
}
