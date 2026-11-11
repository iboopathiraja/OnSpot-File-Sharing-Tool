'use strict';
const net = require('net');
const fs = require('fs');
const path = require('path');
const { EventEmitter } = require('events');
const P = require('./protocol');
const { sanitizeFileName, reserveUniquePath, ensureFreeSpace } = require('./files');

// 15s rather than 8s: sending several files back-to-back to the same peer can leave a
// later connection attempt queued behind earlier ones for a few seconds under real
// Wi-Fi conditions, and 8s was tripping "connection timed out" failures that were really
// just contention, not a dead peer.
const CONNECT_TIMEOUT = 15000;
// A connection with no traffic in either direction for this long is considered dead, so a
// sender that vanishes mid-file can't hold a socket and a half-written file open forever.
const IDLE_TIMEOUT = 30000;
const PROGRESS_INTERVAL = 100;

function cleanAddress(addr) {
  return String(addr || '').replace(/^::ffff:/i, '').replace(/%.*$/, '');
}

function cancelledError() {
  const e = new Error('Cancelled');
  e.code = 'CANCELLED';
  return e;
}

/**
 * Receives and sends OnSpot messages.
 * `accept({from, kind, name?, size?})` decides whether an incoming message is allowed;
 * it returns {ok: true} or {ok: false, reason}.
 * Events: file-start, file-progress, file-done, file-error, text, clipboard,
 *         clipboard-request, clipboard-response, rejected, receive-error
 */
class TransferService extends EventEmitter {
  constructor({ getReceiveDir, accept, idleTimeout = IDLE_TIMEOUT }) {
    super();
    this.idleTimeout = idleTimeout;
    this.getReceiveDir = getReceiveDir;
    this.accept = accept || (async () => ({ ok: true }));
    this.server = null;
    this.port = -1;
    this.nextId = 1;
    this.runId = Date.now().toString(36); // keeps ids unique across app restarts (history)
    this.sockets = new Set();
    this.incoming = new Map(); // transfer id -> socket
  }

  start(port = 0) {
    return new Promise((resolve, reject) => {
      this.server = net.createServer((s) => this._handle(s));
      this.server.once('error', reject);
      this.server.listen(port, () => {
        this.port = this.server.address().port;
        this.server.removeListener('error', reject);
        this.server.on('error', (e) => this.emit('server-error', e));
        resolve(this.port);
      });
    });
  }

  stop() {
    try { if (this.server) this.server.close(); } catch { /* ignore */ }
    for (const s of this.sockets) s.destroy();
    this.sockets.clear();
  }

  /** Aborts an incoming file transfer. */
  cancel(id) {
    const s = this.incoming.get(id);
    if (s) s.destroy(cancelledError());
  }

  _track(socket) {
    this.sockets.add(socket);
    socket.once('close', () => this.sockets.delete(socket));
    socket.on('error', () => {}); // callers handle errors; this just prevents an uncaught throw
    socket.setTimeout(this.idleTimeout);
    socket.on('timeout', () => socket.destroy(new Error('Connection stalled')));
  }

  async _handle(socket) {
    socket.setNoDelay(true);
    this._track(socket);
    const from = cleanAddress(socket.remoteAddress);
    const reader = new P.ByteReader(socket);
    try {
      const type = await reader.readInt();
      if (type === P.TransferType.FILE) {
        await this._receiveFile(reader, socket, from);
      } else if (type >= 1 && type <= 4) {
        const text = await reader.readUTF();
        const kind = ['', 'text', 'clipboard', 'clipboard-request', 'clipboard-response'][type];
        const verdict = await this.accept({ from, kind });
        if (verdict.ok) this.emit(kind, { from, text });
        else this.emit('rejected', { from, kind, reason: verdict.reason });
      }
    } catch (e) {
      this.emit('receive-error', { from, error: e });
    } finally {
      socket.destroy();
    }
  }

  async _receiveFile(reader, socket, from) {
    const rawName = await reader.readUTF();
    const size = await reader.readLong();
    if (size < 0) throw new Error('Invalid file size');
    const name = sanitizeFileName(rawName);
    const verdict = await this.accept({ from, kind: 'file', name, size });
    if (!verdict.ok) { this.emit('rejected', { from, kind: 'file', name, size, reason: verdict.reason }); return; }
    const dir = this.getReceiveDir();
    try {
      await ensureFreeSpace(dir, size);
    } catch (e) {
      this.emit('rejected', { from, kind: 'file', name, size, reason: e.message });
      return;
    }
    const id = `in-${this.runId}-${this.nextId++}`;
    const { fh, filePath } = await reserveUniquePath(dir, name);
    const info = { id, from, name: path.basename(filePath), size, path: filePath };
    this.incoming.set(id, socket);
    this.emit('file-start', info);
    let done = 0;
    let last = 0;
    try {
      while (done < size) {
        const chunk = await reader.readSome(Math.min(1 << 20, size - done));
        await fh.write(chunk);
        done += chunk.length;
        const now = Date.now();
        if (now - last > PROGRESS_INTERVAL) {
          last = now;
          this.emit('file-progress', { ...info, done });
        }
      }
      await fh.close();
      this.emit('file-done', { ...info, done });
    } catch (e) {
      await fh.close().catch(() => {});
      await fs.promises.unlink(filePath).catch(() => {});
      this.emit('file-error', { ...info, error: e });
    } finally {
      this.incoming.delete(id);
    }
  }

  _connect(host, port) {
    return new Promise((resolve, reject) => {
      const s = net.connect({ host: cleanAddress(host), port });
      const t = setTimeout(() => { s.destroy(); reject(new Error('Connection timed out')); }, CONNECT_TIMEOUT);
      s.once('connect', () => {
        clearTimeout(t);
        s.setNoDelay(true);
        this._track(s);
        resolve(s);
      });
      s.once('error', (e) => { clearTimeout(t); reject(e); });
    });
  }

  _end(socket) {
    return new Promise((resolve, reject) => {
      socket.once('error', reject);
      socket.end(() => resolve());
    });
  }

  async sendText(host, port, text, type = P.TransferType.TEXT) {
    const payload = Buffer.concat([P.writeInt(type), P.writeUTF(text)]); // throws if too long
    const s = await this._connect(host, port);
    s.write(payload);
    await this._end(s);
  }

  /** Streams a local file to a peer. onProgress(doneBytes). `signal` (AbortSignal) cancels. */
  async sendFile(host, port, filePath, onProgress = () => {}, signal) {
    if (signal && signal.aborted) throw cancelledError();
    const stat = await fs.promises.stat(filePath);
    if (!stat.isFile()) throw new Error('Not a file');
    const s = await this._connect(host, port);
    const header = Buffer.concat([
      P.writeInt(P.TransferType.FILE),
      P.writeUTF(path.basename(filePath)),
      P.writeLong(stat.size),
    ]);
    const total = header.length + stat.size;
    const rs = fs.createReadStream(filePath, { highWaterMark: 256 * 1024 });
    const onAbort = () => s.destroy(cancelledError());
    if (signal) signal.addEventListener('abort', onAbort, { once: true });
    let streamed = false;
    try {
      s.write(header);
      let sent = 0;
      let last = 0;
      await new Promise((resolve, reject) => {
        s.once('error', reject);
        s.once('close', () => reject(signal && signal.aborted ? cancelledError() : new Error('Connection closed')));
        rs.once('error', reject);
        rs.on('data', (c) => {
          sent += c.length;
          if (!s.write(c)) { rs.pause(); s.once('drain', () => rs.resume()); }
          const now = Date.now();
          if (now - last > PROGRESS_INTERVAL) { last = now; onProgress(sent); }
        });
        rs.once('end', resolve);
      });
      streamed = true;
      await this._end(s);
      onProgress(stat.size);
      return stat.size;
    } catch (e) {
      rs.destroy();
      s.destroy();
      // Every byte was handed to the network and only the close failed (the phone usually
      // hangs up as soon as it has the whole file). Retrying here would just create a
      // duplicate "name (1).ext" on the other side, so treat it as delivered.
      if (streamed && s.bytesWritten >= total && !(signal && signal.aborted)) {
        onProgress(stat.size);
        return stat.size;
      }
      throw e;
    } finally {
      if (signal) signal.removeEventListener('abort', onAbort);
    }
  }
}

module.exports = { TransferService, cleanAddress };
