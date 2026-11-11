'use strict';
// Wire protocol shared with the Android app (java.io.DataOutputStream framing).
//   int32 BE  type ordinal
//   FILE      : UTF name, int64 BE size, <size> raw bytes
//   others    : UTF text
// UTF = uint16 BE length + Java "modified UTF-8".

const TransferType = Object.freeze({
  FILE: 0,
  TEXT: 1,
  CLIPBOARD: 2,
  CLIPBOARD_REQUEST: 3,
  CLIPBOARD_RESPONSE: 4,
});

const SERVICE_TYPE = 'onspot'; // _onspot._tcp
const SERVICE_PREFIX = 'OnSpot_';
const MAX_UTF_BYTES = 0xffff;

// Java modified UTF-8: NUL -> C0 80, supplementary chars as two 3-byte surrogates.
function encodeModifiedUtf8(str) {
  const out = [];
  for (let i = 0; i < str.length; i++) {
    const c = str.charCodeAt(i);
    if (c >= 0x01 && c <= 0x7f) out.push(c);
    else if (c <= 0x7ff) out.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f));
    else out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
  }
  return Buffer.from(out);
}

function decodeModifiedUtf8(buf) {
  const units = [];
  for (let i = 0; i < buf.length; ) {
    const a = buf[i];
    if (a < 0x80) { units.push(a); i += 1; }
    else if ((a & 0xe0) === 0xc0) { units.push(((a & 0x1f) << 6) | (buf[i + 1] & 0x3f)); i += 2; }
    else if ((a & 0xf0) === 0xe0) {
      units.push(((a & 0x0f) << 12) | ((buf[i + 1] & 0x3f) << 6) | (buf[i + 2] & 0x3f));
      i += 3;
    } else { units.push(0xfffd); i += 1; }
  }
  let s = '';
  for (let i = 0; i < units.length; i += 8192) s += String.fromCharCode(...units.slice(i, i + 8192));
  return s;
}

function utfByteLength(str) { return encodeModifiedUtf8(str).length; }

function writeUTF(str) {
  const body = encodeModifiedUtf8(str);
  if (body.length > MAX_UTF_BYTES) throw new RangeError('Text too long for the OnSpot protocol (max 65535 bytes)');
  const head = Buffer.alloc(2);
  head.writeUInt16BE(body.length);
  return Buffer.concat([head, body]);
}

function writeInt(n) { const b = Buffer.alloc(4); b.writeInt32BE(n); return b; }
function writeLong(n) { const b = Buffer.alloc(8); b.writeBigInt64BE(BigInt(n)); return b; }

/** Pull-based reader over a net.Socket with backpressure. */
class ByteReader {
  constructor(socket) {
    this.socket = socket;
    this.chunks = [];
    this.buffered = 0;
    this.ended = false;
    this.error = null;
    this.waiter = null;
    socket.on('data', (c) => {
      this.chunks.push(c); this.buffered += c.length;
      if (this.buffered > (1 << 20)) socket.pause();
      this._wake();
    });
    socket.on('end', () => { this.ended = true; this._wake(); });
    socket.on('close', () => { this.ended = true; this._wake(); });
    socket.on('error', (e) => { this.error = e; this.ended = true; this._wake(); });
  }
  _wake() { if (this.waiter) { const w = this.waiter; this.waiter = null; w(); } }
  async _fill() {
    if (this.error) throw this.error;
    if (this.ended) throw new Error('Connection closed unexpectedly');
    await new Promise((r) => { this.waiter = r; });
    if (this.error) throw this.error;
  }
  _take(n) {
    const parts = [];
    let need = n;
    while (need > 0) {
      const c = this.chunks[0];
      if (c.length <= need) { parts.push(c); need -= c.length; this.chunks.shift(); }
      else { parts.push(c.subarray(0, need)); this.chunks[0] = c.subarray(need); need = 0; }
    }
    this.buffered -= n;
    if (this.buffered < (1 << 20) && this.socket.isPaused()) this.socket.resume();
    return parts.length === 1 ? parts[0] : Buffer.concat(parts);
  }
  async read(n) {
    while (this.buffered < n) await this._fill();
    return this._take(n);
  }
  /** Read up to `max` bytes (at least 1). */
  async readSome(max) {
    while (this.buffered === 0) await this._fill();
    return this._take(Math.min(max, this.buffered));
  }
  async readInt() { return (await this.read(4)).readInt32BE(); }
  async readLong() { return Number((await this.read(8)).readBigInt64BE()); }
  async readUTF() {
    const len = (await this.read(2)).readUInt16BE();
    return decodeModifiedUtf8(await this.read(len));
  }
}

module.exports = {
  TransferType, SERVICE_TYPE, SERVICE_PREFIX, MAX_UTF_BYTES,
  encodeModifiedUtf8, decodeModifiedUtf8, utfByteLength,
  writeUTF, writeInt, writeLong, ByteReader,
};
