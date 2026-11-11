'use strict';
const path = require('path');
const { EventEmitter } = require('events');
const { TransferService } = require('./transfer');
const { Discovery } = require('./discovery');
const { networkSignature } = require('./lan');
const { TransferType, utfByteLength, MAX_UTF_BYTES } = require('./protocol');
const { expandPaths } = require('./files');
const { writeFileAtomic, readJson } = require('./settings');

const CLIPBOARD_POLL_MS = 700;
const NETWORK_CHECK_MS = 10000;
const CLIPBOARD_REQUEST_TIMEOUT = 60000;
const MAX_HISTORY = 500;
const SNAPSHOT_TRANSFERS = 300;
const HISTORY_SAVE_MS = 1500;
const MAX_ATTEMPTS = 3;

const delay = (ms) => new Promise((r) => setTimeout(r, ms));
// Errors that another attempt can't fix (missing file, no permission, ...).
const isPermanent = (e) => ['ENOENT', 'EACCES', 'EPERM', 'EISDIR'].includes(e && e.code) || (e && e.message === 'Not a file');

/**
 * Owns discovery, transfers, clipboard sync and the UI-facing state.
 * `clipboard` = { readText(): string, writeText(s) } (injected so it is testable).
 * `historyFile` (optional) persists the transfer history and known devices across restarts.
 * Emits: 'state' (snapshot), 'progress' ({id, progress, bytes}), 'notify' ({title, body, transferId?}),
 *        'batch-status' (text|null).
 */
class Engine extends EventEmitter {
  constructor({ settings, clipboard, discovery, transfer, historyFile, getNetworkSignature }) {
    super();
    this.settings = settings;
    this.clipboard = clipboard;
    this.historyFile = historyFile || null;
    this.getNetworkSignature = getNetworkSignature || networkSignature;
    this.discovery = discovery || new Discovery();
    this.transfer = transfer || new TransferService({
      getReceiveDir: () => this.settings.get('receiveDir'),
      accept: (i) => this._acceptIncoming(i),
    });
    this.known = new Map(); // device name -> device (with online flag)
    this.transfers = [];
    this.pendingRequests = new Map(); // device name -> { from } (clipboard requests they sent us)
    this.pendingOut = new Map(); // device name -> timer (clipboard requests we sent, awaiting reply)
    this.queues = new Map(); // device name -> promise of the last queued send
    this.aborters = new Map(); // transfer id -> AbortController
    this.lastClipboard = '';
    this.timers = [];
    this.emitTimer = null;
    this.saveTimer = null;
    this.netSig = '';
    this.seq = 1;
    this._loadHistory();
  }

  _discoveryOpts() {
    return {
      deviceName: this.settings.get('deviceName'),
      avatarIndex: this.settings.get('avatarIndex'),
      port: this.transfer.port,
      mode: this.settings.get('connectionMode') || 'wifi',
      networkId: this.settings.get('networkInterface') || 'auto',
    };
  }

  async start() {
    await this.transfer.start(0);
    if (!this._wired) { // start() may be retried after a failure: don't double-subscribe
      this._wired = true;
      this._wireTransfer();
      this._wireDiscovery();
    }
    this.netSig = this.getNetworkSignature();
    await this.discovery.start(this._discoveryOpts());
    this.lastClipboard = this._readClipboard();
    this.timers.push(setInterval(() => this._pollClipboard(), CLIPBOARD_POLL_MS));
    this.timers.push(setInterval(() => this.checkNetwork(), NETWORK_CHECK_MS));
    this._changed();
  }

  stop() {
    this.timers.forEach(clearInterval);
    this.timers = [];
    for (const t of this.pendingOut.values()) clearTimeout(t);
    for (const ac of this.aborters.values()) ac.abort();
    this._saveHistoryNow();
    this.discovery.stop();
    this.transfer.stop();
  }

  /** Re-advertise / re-scan after name, avatar, mode or network changes. */
  async restartDiscovery() {
    for (const d of this.known.values()) d.online = false;
    this._changed();
    try {
      await this.discovery.start(this._discoveryOpts());
    } catch (e) { console.error('discovery restart failed', e && e.message); }
    this._changed();
  }

  /** Restarts discovery when the computer's network changed (new Wi-Fi, new IP, adapter up/down). */
  checkNetwork() {
    let sig;
    try { sig = this.getNetworkSignature(); } catch { return; }
    if (sig === this.netSig) return;
    this.netSig = sig;
    this.restartDiscovery();
  }

  refresh() { this.discovery.refresh(); }

  // ---- history -------------------------------------------------------------
  _loadHistory() {
    const data = this.historyFile && readJson(this.historyFile);
    if (!data) return;
    for (const d of Array.isArray(data.devices) ? data.devices : []) {
      if (!d || typeof d.name !== 'string') continue;
      this.known.set(d.name, {
        name: d.name, avatarIndex: Number.isInteger(d.avatarIndex) ? d.avatarIndex : 1,
        lastActivity: d.lastActivity || 0, online: false, addresses: [],
      });
    }
    this.transfers = (Array.isArray(data.transfers) ? data.transfers : [])
      .filter((t) => t && typeof t.id === 'string')
      .slice(-MAX_HISTORY)
      .map((t) => (t.state === 'transferring' || t.state === 'queued'
        ? { ...t, state: 'failed', error: 'Interrupted', retrying: undefined }
        : t));
    for (const t of this.transfers) {
      for (const id of [t.id, t.batchId]) {
        const m = /^[tb](\d+)$/.exec(id || '');
        if (m) this.seq = Math.max(this.seq, Number(m[1]) + 1);
      }
    }
  }

  _saveHistorySoon() {
    if (!this.historyFile || this.saveTimer) return;
    this.saveTimer = setTimeout(() => { this.saveTimer = null; this._saveHistoryNow(); }, HISTORY_SAVE_MS);
  }

  _saveHistoryNow() {
    if (!this.historyFile) return;
    clearTimeout(this.saveTimer);
    this.saveTimer = null;
    const devices = [...this.known.values()].map((d) => ({ name: d.name, avatarIndex: d.avatarIndex, lastActivity: d.lastActivity || 0 }));
    try {
      writeFileAtomic(this.historyFile, JSON.stringify({ devices, transfers: this.transfers }));
    } catch (e) { console.error('history save failed', e && e.message); }
  }

  // ---- state -------------------------------------------------------------
  snapshot() {
    const devices = [...this.known.values()]
      .map((d) => {
        const clipboardState = this._clipboardState(d);
        return {
          name: d.name, avatarIndex: d.avatarIndex, online: !!d.online, host: d.host,
          conflict: !!d.conflict, clipboardState, shareClipboard: clipboardState !== 'off',
          lastActivity: d.lastActivity || 0,
        };
      })
      .sort((a, b) => (b.online - a.online) || (b.lastActivity - a.lastActivity) || a.name.localeCompare(b.name));
    return {
      self: {
        name: this.settings.get('deviceName'),
        avatarIndex: this.settings.get('avatarIndex'),
        port: this.transfer.port,
      },
      network: {
        mode: this.settings.get('connectionMode') || 'wifi',
        name: this.discovery.network ? this.discovery.network.name : null,
        ip: this.discovery.network ? this.discovery.network.ip : null,
      },
      devices,
      transfers: this.transfers.slice(-SNAPSHOT_TRANSFERS),
      pendingRequests: [...this.pendingRequests.keys()],
    };
  }

  _changed() {
    this._saveHistorySoon();
    if (this.emitTimer) return;
    this.emitTimer = setTimeout(() => {
      this.emitTimer = null;
      this.emit('state', this.snapshot());
    }, 50);
  }

  _touch(device) {
    if (device) device.lastActivity = Date.now();
  }

  _deviceByAddress(addr) {
    const live = this.discovery.findByAddress(addr);
    if (live) return this.known.get(live.name) || null;
    for (const d of this.known.values()) if (d.addresses && d.addresses.includes(addr)) return d;
    return null;
  }

  getTransfer(id) { return this.transfers.find((x) => x.id === id) || null; }

  _record(rec) {
    const r = { id: `t${this.seq++}`, ts: Date.now(), progress: 0, state: 'transferring', ...rec };
    this.transfers.push(r);
    this._trimHistory();
    return r;
  }

  _trimHistory() {
    let excess = this.transfers.length - MAX_HISTORY;
    if (excess <= 0) return;
    this.transfers = this.transfers.filter((t) => {
      if (excess > 0 && t.state !== 'transferring' && t.state !== 'queued') { excess--; return false; }
      return true;
    });
  }

  /** Structural change (state, error, ...): the full snapshot is re-sent to the UI. */
  _update(id, patch) {
    const t = this.getTransfer(id);
    if (t) Object.assign(t, patch);
    this._changed();
    return t;
  }

  /** Progress-only change: a tiny 'progress' event instead of a whole snapshot. */
  _progress(id, patch) {
    const t = this.getTransfer(id);
    if (!t) return;
    Object.assign(t, patch);
    this.emit('progress', { id, ...patch });
  }

  // ---- wiring ------------------------------------------------------------
  _wireDiscovery() {
    this.discovery.on('up', (d) => {
      const prev = this.known.get(d.name);
      const dev = { ...(prev || {}), ...d, online: true, lastActivity: prev ? prev.lastActivity : 0 };
      this.known.set(d.name, dev);
      // Approvals saved by older versions have no address yet: pin the first one we see.
      const peer = this._peerOf(d.name);
      if (peer && !peer.host && !dev.conflict) this._setClipboardPeer(d.name, { host: dev.host });
      this._changed();
    });
    this.discovery.on('down', (d) => {
      const k = this.known.get(d.name);
      if (k) k.online = false;
      this._changed();
    });
  }

  _wireTransfer() {
    const t = this.transfer;
    t.on('file-start', (i) => {
      const dev = this._deviceByAddress(i.from);
      this._touch(dev);
      this._record({
        id: i.id, incoming: true, kind: 'file', device: dev ? dev.name : i.from, name: i.name,
        size: i.size, path: i.path, startedAt: Date.now(),
      });
      this._changed();
    });
    t.on('file-progress', (i) => this._progress(i.id, { progress: i.size ? i.done / i.size : 0, bytes: i.done }));
    t.on('file-done', (i) => {
      this._update(i.id, { progress: 1, state: 'done', bytes: i.size, doneAt: Date.now() });
      const dev = this._deviceByAddress(i.from);
      this.emit('notify', { title: `Received from ${dev ? dev.name : i.from}`, body: i.name, transferId: i.id });
    });
    t.on('file-error', (i) => {
      const cancelled = i.error && i.error.code === 'CANCELLED';
      this._update(i.id, cancelled ? { state: 'cancelled' } : { state: 'failed', error: String(i.error && i.error.message) });
    });
    t.on('receive-error', ({ error }) => console.error('receive error:', error && error.message));

    t.on('rejected', ({ from, kind, name, size, reason }) => {
      if (kind !== 'file' && kind !== 'text') return;
      const dev = this._deviceByAddress(from);
      if (dev && kind === 'file') {
        this._record({ incoming: true, kind: 'file', device: dev.name, name, size, state: 'failed', error: reason });
        this._changed();
      }
      const who = dev ? dev.name : from;
      this.emit('notify', {
        title: kind === 'file' ? 'File not received' : 'Message blocked',
        body: kind === 'file' ? `${name} from ${who}: ${reason}` : `From ${who}: ${reason}`,
      });
    });

    t.on('text', ({ from, text }) => {
      const dev = this._deviceByAddress(from);
      this._touch(dev);
      this._record({
        incoming: true, kind: 'text', device: dev ? dev.name : from, name: 'Message',
        size: text.length, text, state: 'done', progress: 1,
      });
      this.emit('notify', { title: `Message from ${dev ? dev.name : from}`, body: text.slice(0, 140) });
      this._changed();
    });

    t.on('clipboard', ({ from, text }) => {
      const dev = this._deviceByAddress(from);
      if (!dev || dev.conflict) return;
      const peer = this._peerOf(dev.name);
      // Only the exact address that was approved may write to our clipboard.
      if (!peer || peer.host !== from) return;
      this._touch(dev);
      if (text === this.lastClipboard) return;
      this.lastClipboard = text;
      this.clipboard.writeText(text);
      this._changed();
    });

    t.on('clipboard-request', ({ from }) => {
      const dev = this._deviceByAddress(from);
      if (!dev) return;
      this.pendingRequests.set(dev.name, { from });
      this.emit('notify', { title: 'Clipboard sync request', body: `${dev.name} wants to sync clipboards` });
      this._changed();
    });

    t.on('clipboard-response', ({ from, text }) => {
      const dev = this._deviceByAddress(from);
      // Ignore answers to requests we never sent: otherwise anyone could switch sync on.
      if (!dev || !this.pendingOut.has(dev.name)) return;
      clearTimeout(this.pendingOut.get(dev.name));
      this.pendingOut.delete(dev.name);
      const approved = text === 'true';
      if (approved) this._setClipboardPeer(dev.name, { host: from });
      else this._changed();
      this.emit('notify', {
        title: 'Clipboard sync',
        body: approved ? `${dev.name} accepted clipboard sync` : `${dev.name} declined clipboard sync`,
      });
    });
  }

  /** Policy for incoming messages (clipboard messages are checked by their own handlers). */
  async _acceptIncoming({ from, kind }) {
    if (kind !== 'file' && kind !== 'text') return { ok: true };
    if (this.settings.get('receiveFrom') === 'anyone') return { ok: true };
    return this._deviceByAddress(from) ? { ok: true } : { ok: false, reason: 'not a device in your Nearby list' };
  }

  // ---- clipboard sync ----------------------------------------------------
  _readClipboard() {
    try { return this.clipboard.readText() || ''; } catch { return ''; }
  }

  /** The saved approval for `name`: { host } (host is null for approvals from older versions). */
  _peerOf(name) {
    const p = (this.settings.get('clipboardPeers') || {})[name];
    if (!p) return null;
    return p === true ? { host: null } : p;
  }

  _setClipboardPeer(name, value) {
    const peers = { ...(this.settings.get('clipboardPeers') || {}) };
    if (value) peers[name] = value; else delete peers[name];
    this.settings.set({ clipboardPeers: peers });
    this._changed();
  }

  /**
   * 'off' | 'pending' (waiting for their approval) | 'on' |
   * 'moved' (device now at a different address than the approved one: paused until re-trusted) |
   * 'conflict' (several devices use this name: paused)
   */
  _clipboardState(d) {
    if (this.pendingOut.has(d.name)) return 'pending';
    const peer = this._peerOf(d.name);
    if (!peer) return 'off';
    if (d.online && d.conflict) return 'conflict';
    if (d.online && peer.host && !(d.addresses || []).includes(peer.host) && d.host !== peer.host) return 'moved';
    return 'on';
  }

  _pollClipboard() {
    const text = this._readClipboard();
    if (!text || text === this.lastClipboard) return;
    this.lastClipboard = text;
    if (utfByteLength(text) > MAX_UTF_BYTES) return; // protocol limit
    for (const d of this.known.values()) {
      if (!d.online || this._clipboardState(d) !== 'on') continue;
      const host = this._peerOf(d.name).host;
      if (host) this.transfer.sendText(host, d.port, text, TransferType.CLIPBOARD).catch(() => {});
    }
  }

  /** Turning sync on only sends a request; it becomes active once they approve. */
  async setClipboardSync(name, on) {
    const d = this.known.get(name);
    if (!d) return { ok: false, error: `${name} is not nearby` };
    clearTimeout(this.pendingOut.get(name));
    this.pendingOut.delete(name);
    if (!on) { this._setClipboardPeer(name, null); return { ok: true }; }
    if (!d.online) return { ok: false, error: `${name} is not nearby` };
    if (d.conflict) return { ok: false, error: `More than one device is called "${name}". Rename one of them first.` };
    this.pendingOut.set(name, setTimeout(() => { this.pendingOut.delete(name); this._changed(); }, CLIPBOARD_REQUEST_TIMEOUT));
    this._changed();
    try {
      await this.transfer.sendText(d.host, d.port, 'request', TransferType.CLIPBOARD_REQUEST);
      return { ok: true };
    } catch (e) {
      clearTimeout(this.pendingOut.get(name));
      this.pendingOut.delete(name);
      this._changed();
      return { ok: false, error: e.message };
    }
  }

  async respondClipboardRequest(name, approved) {
    const req = this.pendingRequests.get(name);
    this.pendingRequests.delete(name);
    const d = this.known.get(name);
    this._changed();
    if (!req || !d) return;
    if (approved) this._setClipboardPeer(name, { host: req.from });
    await this.transfer.sendText(req.from, d.port, String(!!approved), TransferType.CLIPBOARD_RESPONSE).catch(() => {});
  }

  /** After a device's address changed, the user confirms it is still the same device. */
  trustClipboardHost(name) {
    const d = this.known.get(name);
    if (!d || !d.online || d.conflict || !this._peerOf(name)) return;
    this._setClipboardPeer(name, { host: d.host });
  }

  // ---- sending -----------------------------------------------------------
  _online(name) {
    const d = this.known.get(name);
    if (!d || !d.online) throw new Error(`${name} is not nearby`);
    return d;
  }

  async sendText(name, text) {
    const d = this._online(name);
    if (utfByteLength(text) > MAX_UTF_BYTES) throw new Error('Message is too long (max ~64 KB)');
    const rec = this._record({
      incoming: false, kind: 'text', device: name, name: 'Message', size: text.length,
      text, state: 'done', progress: 1,
    });
    try {
      await this.transfer.sendText(d.host, d.port, text, TransferType.TEXT);
      this._touch(d);
    } catch (e) {
      this._update(rec.id, { state: 'failed', error: e.message });
      throw e;
    }
    this._changed();
  }

  /** Runs sends to one device one after another, so two batches never compete for the link. */
  _enqueue(name, job) {
    const prev = this.queues.get(name) || Promise.resolve();
    const next = prev.catch(() => {}).then(job);
    this.queues.set(name, next);
    const cleanup = () => { if (this.queues.get(name) === next) this.queues.delete(name); };
    next.then(cleanup, cleanup);
    return next;
  }

  /** Attempts that fail (e.g. a contended connection timing out) get retried a couple of
   * times with a short backoff before actually being marked failed — most timeouts under a
   * batch send are transient contention, not a dead peer. Returns 'done' | 'failed' | 'cancelled'. */
  async _sendOneFile(name, rec) {
    const ac = new AbortController();
    this.aborters.set(rec.id, ac);
    const cancelled = () => { this._update(rec.id, { state: 'cancelled', retrying: undefined, error: undefined }); return 'cancelled'; };
    try {
      for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
        if (ac.signal.aborted) return cancelled();
        const d = this.known.get(name);
        if (!d || !d.online) {
          this._update(rec.id, { state: 'failed', error: `${name} is not nearby`, retrying: undefined });
          return 'failed';
        }
        this._update(rec.id, { state: 'transferring', progress: 0, bytes: 0, startedAt: Date.now(), error: undefined });
        try {
          const size = await this.transfer.sendFile(d.host, d.port, rec.path, (done) => {
            this._progress(rec.id, { progress: rec.size ? done / rec.size : 0, bytes: done });
          }, ac.signal);
          this._update(rec.id, { size, progress: 1, state: 'done', bytes: size, doneAt: Date.now(), retrying: undefined });
          this._touch(d);
          return 'done';
        } catch (e) {
          if (ac.signal.aborted || (e && e.code === 'CANCELLED')) return cancelled();
          if (attempt < MAX_ATTEMPTS && !isPermanent(e)) {
            this._update(rec.id, { progress: 0, bytes: 0, retrying: attempt });
            await delay(500 * attempt);
          } else {
            this._update(rec.id, { state: 'failed', error: e.message, retrying: undefined });
            return 'failed';
          }
        }
      }
      return 'failed';
    } finally {
      this.aborters.delete(rec.id);
    }
  }

  /** Sends files and/or folders (folders are sent file by file). Resolves when all are done. */
  async sendFiles(name, filePaths) {
    this._online(name);
    // Take our place in the queue now, so batches go out in the order they were dropped
    // even when a big folder takes longer to list than a later single file.
    let recs = [];
    const ready = expandPaths(filePaths).then((files) => {
      const batchId = files.length > 1 ? `b${this.seq++}` : undefined;
      recs = files.map((f) => this._record({
        incoming: false, kind: 'file', device: name, name: path.basename(f.path), size: f.size, path: f.path,
        batchId, state: 'queued',
      }));
      this._changed();
    });

    await this._enqueue(name, async () => {
      await ready;
      let failed = 0;
      for (let i = 0; i < recs.length; i++) {
        if (recs[i].state === 'cancelled') continue;
        this._setBatchStatus(`Sending ${i + 1}/${recs.length} to ${name}`);
        if (await this._sendOneFile(name, recs[i]) === 'failed') failed++;
      }
      this._setBatchStatus(null);
      if (recs.length > 1) {
        const sent = recs.filter((r) => r.state === 'done').length;
        this.emit('notify', {
          title: sent === recs.length ? `${recs.length} files sent` : `Sent ${sent} of ${recs.length} files`,
          body: failed ? `${failed} failed to send to ${name} — open OnSpot to retry` : `Sent to ${name}`,
        });
      }
    });
  }

  /** Short text for the tray tooltip while a batch is active; null when idle. */
  _setBatchStatus(text) {
    this.emit('batch-status', text);
  }

  /** Re-sends a single failed or cancelled transfer (from the UI's "Retry" button). */
  async retryTransfer(id) {
    const rec = this.getTransfer(id);
    if (!rec || rec.incoming || rec.kind !== 'file' || (rec.state !== 'failed' && rec.state !== 'cancelled')) return;
    this._update(id, { state: 'queued', progress: 0, bytes: 0, error: undefined });
    await this._enqueue(rec.device, () => this._sendOneFile(rec.device, rec));
  }

  /** Stops a queued or running transfer, in either direction. */
  cancelTransfer(id) {
    const rec = this.getTransfer(id);
    if (!rec) return;
    if (rec.state === 'queued') { this._update(id, { state: 'cancelled' }); return; }
    if (rec.state !== 'transferring') return;
    if (rec.incoming) { if (this.transfer.cancel) this.transfer.cancel(id); } else {
      const ac = this.aborters.get(id);
      if (ac) ac.abort();
    }
  }

  clearHistory() {
    this.transfers = this.transfers.filter((t) => t.state === 'transferring' || t.state === 'queued');
    this._changed();
  }
}

module.exports = { Engine };
