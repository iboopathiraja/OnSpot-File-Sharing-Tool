'use strict';
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');
const P = require('../src/core/protocol');
const { sanitizeFileName } = require('../src/core/files');
const { TransferService } = require('../src/core/transfer');
const { Engine } = require('../src/core/engine');

test('writeUTF matches java.io.DataOutputStream.writeUTF', () => {
  // Java: writeUTF("Hi") -> 00 02 48 69 ; writeUTF("é") -> 00 02 C3 A9 ; NUL -> C0 80
  assert.deepStrictEqual([...P.writeUTF('Hi')], [0, 2, 0x48, 0x69]);
  assert.deepStrictEqual([...P.writeUTF('é')], [0, 2, 0xc3, 0xa9]);
  assert.deepStrictEqual([...P.writeUTF('\u0000')], [0, 2, 0xc0, 0x80]);
  // emoji = surrogate pair, each encoded as 3 bytes
  assert.strictEqual(P.writeUTF('😀').length, 2 + 6);
});

test('modified utf8 round-trips', () => {
  for (const s of ['', 'hello', 'நன்றி', '日本語', 'a😀b', 'x\u0000y']) {
    assert.strictEqual(P.decodeModifiedUtf8(P.encodeModifiedUtf8(s)), s);
  }
});

test('too-long text is rejected', () => {
  assert.throws(() => P.writeUTF('a'.repeat(70000)), RangeError);
});

test('sanitizeFileName blocks traversal and reserved names', () => {
  assert.strictEqual(sanitizeFileName('../../evil.txt'), 'evil.txt');
  assert.strictEqual(sanitizeFileName('C:\\Windows\\a.txt'), 'a.txt');
  assert.strictEqual(sanitizeFileName('a:b?.txt'), 'a_b_.txt');
  assert.strictEqual(sanitizeFileName('CON.txt'), '_CON.txt');
  assert.strictEqual(sanitizeFileName('..'), 'file');
});

function tmp() { return fs.mkdtempSync(path.join(os.tmpdir(), 'fs-')); }

test('file + text transfer over loopback', async () => {
  const dir = tmp();
  const rx = new TransferService({ getReceiveDir: () => dir });
  const tx = new TransferService({ getReceiveDir: () => dir });
  const port = await rx.start(0);

  const src = path.join(tmp(), 'big.bin');
  const data = crypto.randomBytes(5 * 1024 * 1024 + 123);
  fs.writeFileSync(src, data);

  const done = new Promise((r) => rx.once('file-done', r));
  await tx.sendFile('127.0.0.1', port, src);
  const info = await done;
  assert.ok(fs.readFileSync(info.path).equals(data));

  // second send with the same name must not overwrite
  const done2 = new Promise((r) => rx.once('file-done', r));
  await tx.sendFile('127.0.0.1', port, src);
  const info2 = await done2;
  assert.notStrictEqual(info2.path, info.path);

  const gotText = new Promise((r) => rx.once('text', r));
  await tx.sendText('127.0.0.1', port, 'வணக்கம் 😀');
  assert.strictEqual((await gotText).text, 'வணக்கம் 😀');
  rx.stop();
});

test('malicious file name cannot escape receive dir', async () => {
  const dir = tmp();
  const rx = new TransferService({ getReceiveDir: () => dir });
  const port = await rx.start(0);
  const net = require('net');
  const done = new Promise((r) => rx.once('file-done', r));
  const s = net.connect(port, '127.0.0.1');
  s.write(Buffer.concat([P.writeInt(0), P.writeUTF('..\\..\\pwn.txt'), P.writeLong(3), Buffer.from('abc')]));
  s.end();
  const info = await done;
  assert.strictEqual(path.dirname(info.path), dir);
  rx.stop();
});

test('truncated file is discarded', async () => {
  const dir = tmp();
  const rx = new TransferService({ getReceiveDir: () => dir });
  const port = await rx.start(0);
  const net = require('net');
  const err = new Promise((r) => rx.once('file-error', r));
  const s = net.connect(port, '127.0.0.1');
  s.write(Buffer.concat([P.writeInt(0), P.writeUTF('x.bin'), P.writeLong(100), Buffer.from('short')]));
  s.end();
  await err;
  assert.deepStrictEqual(fs.readdirSync(dir), []);
  rx.stop();
});

test('engine: clipboard handshake and sync between two peers', async () => {
  const mkPeer = (name) => {
    const clip = { v: '', readText() { return this.v; }, writeText(t) { this.v = t; } };
    const settings = { d: { deviceName: name, avatarIndex: 1, receiveDir: tmp(), clipboardPeers: {} },
      get(k) { return this.d[k]; }, set(p) { Object.assign(this.d, p); } };
    // stub discovery: we inject peers manually
    const discovery = new (require('events'))();
    discovery.start = () => {}; discovery.stop = () => {}; discovery.refresh = () => {};
    discovery.devices = new Map();
    discovery.findByAddress = (a) => [...discovery.devices.values()].find((d) => d.addresses.includes(a)) || null;
    const engine = new Engine({ settings, clipboard: clip, discovery });
    return { clip, settings, discovery, engine, name };
  };
  const A = mkPeer('A');
  const B = mkPeer('B');
  await A.engine.start();
  await B.engine.start();
  const link = (from, to) => {
    const d = { name: to.name, serviceName: 'OnSpot_' + to.name, id: '127.0.0.1', host: '127.0.0.1',
      addresses: ['127.0.0.1'], port: to.engine.transfer.port, avatarIndex: 1 };
    from.discovery.devices.set(d.name, d);
    from.discovery.emit('up', d);
  };
  link(A, B); link(B, A);

  const pending = new Promise((r) => B.engine.once('notify', r));
  await A.engine.setClipboardSync('B', true);
  await pending;
  assert.ok(B.engine.pendingRequests.has('A'));
  // nothing is shared until B approves
  assert.strictEqual(A.settings.get('clipboardPeers').B, undefined);
  assert.strictEqual(A.engine.snapshot().devices[0].clipboardState, 'pending');
  await B.engine.respondClipboardRequest('A', true);
  await new Promise((r) => setTimeout(r, 200));
  assert.deepStrictEqual(A.settings.get('clipboardPeers').B, { host: '127.0.0.1' });
  assert.deepStrictEqual(B.settings.get('clipboardPeers').A, { host: '127.0.0.1' });
  assert.strictEqual(A.engine.snapshot().devices[0].clipboardState, 'on');

  A.clip.v = 'copied on A';
  await new Promise((r) => setTimeout(r, 1500));
  assert.strictEqual(B.clip.v, 'copied on A');

  A.engine.stop(); B.engine.stop();
});

function mkEngineWithFakeTransfer(name, sendFileImpl) {
  const settings = { d: { deviceName: name, avatarIndex: 1, receiveDir: tmp(), clipboardPeers: {} },
    get(k) { return this.d[k]; }, set(p) { Object.assign(this.d, p); } };
  const clip = { readText: () => '', writeText: () => {} };
  const discovery = new (require('events'))();
  discovery.start = () => {}; discovery.stop = () => {}; discovery.refresh = () => {};
  discovery.devices = new Map();
  discovery.findByAddress = () => null;
  const transfer = new (require('events'))();
  transfer.port = 1234;
  transfer.start = async () => 1234;
  transfer.stop = () => {};
  transfer.sendFile = sendFileImpl;
  const engine = new Engine({ settings, clipboard: clip, discovery, transfer });
  return { engine, discovery, settings };
}

function linkOnlineDevice(discovery, name) {
  const d = { name, host: '127.0.0.1', port: 1, addresses: ['127.0.0.1'], avatarIndex: 1 };
  discovery.devices.set(name, d);
  discovery.emit('up', d);
}

test('engine: sendFiles tags a multi-file batch with a shared batchId; a single file gets none', async () => {
  const sent = [];
  const { engine, discovery } = mkEngineWithFakeTransfer('A', async (host, port, fp, onProgress) => {
    sent.push(fp); onProgress(1); return 1;
  });
  await engine.start();
  linkOnlineDevice(discovery, 'B');

  const f1 = path.join(tmp(), 'a.txt'); fs.writeFileSync(f1, 'x');
  const f2 = path.join(tmp(), 'b.txt'); fs.writeFileSync(f2, 'y');
  await engine.sendFiles('B', [f1, f2]);
  const [r1, r2] = engine.transfers.filter((t) => t.kind === 'file');
  assert.ok(r1.batchId, 'multi-file send should get a batchId');
  assert.strictEqual(r1.batchId, r2.batchId);
  assert.strictEqual(r1.state, 'done');
  assert.strictEqual(r2.state, 'done');

  const single = path.join(tmp(), 'c.txt'); fs.writeFileSync(single, 'z');
  await engine.sendFiles('B', [single]);
  const singleRec = engine.transfers.find((t) => t.path === single);
  assert.strictEqual(singleRec.batchId, undefined, 'a lone file should not be wrapped in a batch');
  assert.deepStrictEqual(sent, [f1, f2, single]);
  engine.stop();
});

test('engine: a transient send failure is retried automatically before succeeding', async () => {
  let calls = 0;
  const { engine, discovery } = mkEngineWithFakeTransfer('A', async (host, port, fp, onProgress) => {
    calls++;
    if (calls < 3) throw new Error('Connection timed out');
    onProgress(5);
    return 5;
  });
  await engine.start();
  linkOnlineDevice(discovery, 'B');
  const f = path.join(tmp(), 'retry.txt'); fs.writeFileSync(f, 'zzzzz');
  await engine.sendFiles('B', [f]);
  const rec = engine.transfers.find((t) => t.path === f);
  assert.strictEqual(rec.state, 'done');
  assert.strictEqual(calls, 3);
  engine.stop();
});

test('engine: a transfer that exhausts retries is marked failed, and can be retried manually', async () => {
  let calls = 0;
  const { engine, discovery } = mkEngineWithFakeTransfer('A', async (host, port, fp, onProgress) => {
    calls++;
    if (calls <= 3) throw new Error('Connection timed out');
    onProgress(5);
    return 5;
  });
  await engine.start();
  linkOnlineDevice(discovery, 'B');
  const f = path.join(tmp(), 'exhausted.txt'); fs.writeFileSync(f, 'zzzzz');
  await engine.sendFiles('B', [f]);
  let rec = engine.transfers.find((t) => t.path === f);
  assert.strictEqual(rec.state, 'failed');
  assert.strictEqual(calls, 3);

  await engine.retryTransfer(rec.id);
  rec = engine.transfers.find((t) => t.path === f);
  assert.strictEqual(rec.state, 'done');
  assert.strictEqual(calls, 4);
  engine.stop();
});

const { LanScanner, subnetTargets } = require('../src/core/lan');

test('subnetTargets enumerates hosts and broadcast', () => {
  const t = subnetTargets('192.168.1.20', '255.255.255.0');
  assert.strictEqual(t.hosts.length, 253); // 254 hosts minus self
  assert.ok(!t.hosts.includes('192.168.1.20'));
  assert.strictEqual(t.broadcast, '192.168.1.255');
  assert.strictEqual(subnetTargets('10.0.0.5', '255.0.0.0').hosts.length, 253); // huge nets capped to /24
});

test('LAN scanner: two peers find each other and expire on bye', async () => {
  const a = new LanScanner({ bindPort: 45601, targetPort: 45602 });
  const b = new LanScanner({ bindPort: 45602, targetPort: 45601 });
  const seenByA = new Promise((r) => a.once('up', r));
  await b.start({ name: 'Pixel', avatarIndex: 4, port: 5555, hosts: ['127.0.0.1'] });
  await a.start({ name: 'Laptop', avatarIndex: 2, port: 6666, hosts: ['127.0.0.1'] });
  const peer = await Promise.race([seenByA, new Promise((_, j) => setTimeout(() => j(new Error('no peer')), 3000))]);
  assert.strictEqual(peer.name, 'Pixel');
  assert.strictEqual(peer.port, 5555);
  assert.strictEqual(peer.avatarIndex, 4);
  assert.strictEqual(peer.host, '127.0.0.1');
  const gone = new Promise((r) => a.once('down', r));
  b.stop();
  assert.strictEqual((await gone).name, 'Pixel');
  a.stop();
});

test('LAN scanner: rescan() probes immediately instead of waiting for the scheduled interval', async () => {
  // A listens for a device but never starts its own scanner, so the only way it can see B is
  // if B's rescan() actually sends a fresh probe right away rather than waiting ~3s.
  const probes = [];
  const dgram = require('dgram');
  const listener = dgram.createSocket('udp4');
  await new Promise((resolve) => listener.bind(45610, resolve));
  listener.on('message', (msg) => { try { probes.push(JSON.parse(msg.toString('utf8'))); } catch { /* ignore */ } });

  const b = new LanScanner({ bindPort: 45611, targetPort: 45610 });
  await b.start({ name: 'Laptop', avatarIndex: 1, port: 1234, hosts: ['127.0.0.1'] });
  // consume the automatic first probe from start()
  await new Promise((r) => setTimeout(r, 150));
  probes.length = 0;

  b.rescan();
  await new Promise((r) => setTimeout(r, 150));
  assert.strictEqual(probes.length, 1, 'rescan() should send exactly one fresh probe immediately');
  assert.strictEqual(probes[0].t, 'probe');
  assert.strictEqual(probes[0].name, 'Laptop');

  b.stop();
  listener.close();
});

test('LAN scanner ignores junk packets', async () => {
  const a = new LanScanner({ bindPort: 45603, targetPort: 45603 });
  await a.start({ name: 'X', avatarIndex: 1, port: 1, hosts: [] });
  let ups = 0;
  a.on('up', () => ups++);
  const dgram = require('dgram');
  const c = dgram.createSocket('udp4');
  for (const m of ['garbage', '{"app":"other"}', '{"app":"onspot","t":"probe","id":"z","name":"","port":1}',
    '{"app":"onspot","t":"probe","id":"z","name":"ok","port":99999}']) c.send(Buffer.from(m), 45603, '127.0.0.1');
  await new Promise((r) => setTimeout(r, 300));
  c.close(); a.stop();
  assert.strictEqual(ups, 0);
});

// ---- security & robustness fixes -------------------------------------------------

function mkStubEngine(name, extra = {}) {
  const clip = { v: '', readText() { return this.v; }, writeText(t) { this.v = t; } };
  const settings = { d: { deviceName: name, avatarIndex: 1, receiveDir: tmp(), clipboardPeers: {}, ...extra },
    get(k) { return this.d[k]; }, set(p) { Object.assign(this.d, p); } };
  const discovery = new (require('events'))();
  discovery.start = () => {}; discovery.stop = () => {}; discovery.refresh = () => {};
  discovery.devices = new Map();
  discovery.findByAddress = (a) => [...discovery.devices.values()].find((d) => d.addresses.includes(a)) || null;
  const engine = new Engine({ settings, clipboard: clip, discovery, getNetworkSignature: () => 'x' });
  const show = (d) => { discovery.devices.set(d.name, d); discovery.emit('up', d); };
  return { clip, settings, discovery, engine, show };
}

test('engine: an unsolicited clipboard "true" response does not enable sync', async () => {
  const A = mkStubEngine('A');
  await A.engine.start();
  A.show({ name: 'Evil', host: '127.0.0.1', addresses: ['127.0.0.1'], port: 1, avatarIndex: 1 });
  const tx = new TransferService({ getReceiveDir: tmp });
  await tx.sendText('127.0.0.1', A.engine.transfer.port, 'true', P.TransferType.CLIPBOARD_RESPONSE);
  await new Promise((r) => setTimeout(r, 150));
  assert.strictEqual(A.settings.get('clipboardPeers').Evil, undefined);
  A.engine.stop();
});

test('engine: clipboard is paused when an approved device shows up at another address', async () => {
  const A = mkStubEngine('A', { clipboardPeers: { Phone: { host: '10.0.0.5' } } });
  await A.engine.start();
  A.show({ name: 'Phone', host: '127.0.0.1', addresses: ['127.0.0.1'], port: 1, avatarIndex: 1 });
  assert.strictEqual(A.engine.snapshot().devices[0].clipboardState, 'moved');
  // a clipboard push from the unapproved address is ignored
  const tx = new TransferService({ getReceiveDir: tmp });
  await tx.sendText('127.0.0.1', A.engine.transfer.port, 'secret?', P.TransferType.CLIPBOARD);
  await new Promise((r) => setTimeout(r, 150));
  assert.strictEqual(A.clip.v, '');
  // the user confirms it is their device: sync resumes, pinned to the new address
  A.engine.trustClipboardHost('Phone');
  assert.deepStrictEqual(A.settings.get('clipboardPeers').Phone, { host: '127.0.0.1' });
  assert.strictEqual(A.engine.snapshot().devices[0].clipboardState, 'on');
  A.engine.stop();
});

test('engine: old "true" clipboard approvals are migrated by pinning the first address seen', async () => {
  const A = mkStubEngine('A', { clipboardPeers: { Phone: true } });
  await A.engine.start();
  A.show({ name: 'Phone', host: '127.0.0.1', addresses: ['127.0.0.1'], port: 1, avatarIndex: 1 });
  assert.deepStrictEqual(A.settings.get('clipboardPeers').Phone, { host: '127.0.0.1' });
  A.engine.stop();
});

test('engine: files from senders outside the Nearby list are refused (unless "anyone")', async () => {
  const A = mkStubEngine('A');
  await A.engine.start();
  const tx = new TransferService({ getReceiveDir: tmp });
  const src = path.join(tmp(), 'x.txt'); fs.writeFileSync(src, 'hello');
  const notified = new Promise((r) => A.engine.once('notify', r));
  await tx.sendFile('127.0.0.1', A.engine.transfer.port, src).catch(() => {});
  const n = await notified;
  assert.match(n.title, /not received/i);
  assert.deepStrictEqual(fs.readdirSync(A.settings.get('receiveDir')), []);

  A.settings.set({ receiveFrom: 'anyone' });
  const done = new Promise((r) => A.engine.transfer.once('file-done', r));
  await tx.sendFile('127.0.0.1', A.engine.transfer.port, src);
  assert.ok(fs.existsSync((await done).path));
  A.engine.stop();
});

test('a sender that stalls mid-file is dropped after the idle timeout and the partial file removed', async () => {
  const dir = tmp();
  const rx = new TransferService({ getReceiveDir: () => dir, idleTimeout: 300 });
  const port = await rx.start(0);
  const net = require('net');
  const err = new Promise((r) => rx.once('file-error', r));
  const s = net.connect(port, '127.0.0.1');
  s.write(Buffer.concat([P.writeInt(0), P.writeUTF('slow.bin'), P.writeLong(1000), Buffer.from('partial')]));
  // never send the rest, never close
  const e = await err;
  assert.match(e.error.message, /stalled|closed/i);
  assert.deepStrictEqual(fs.readdirSync(dir), []);
  s.destroy(); rx.stop();
});

test('engine: sends to the same device are queued, and a queued file can be cancelled', async () => {
  let active = 0, maxActive = 0;
  const sent = [];
  const { engine, discovery } = mkEngineWithFakeTransfer('A', async (host, port, fp, onProgress) => {
    active++; maxActive = Math.max(maxActive, active);
    await new Promise((r) => setTimeout(r, 40));
    active--; sent.push(path.basename(fp)); onProgress(1); return 1;
  });
  await engine.start();
  linkOnlineDevice(discovery, 'B');
  const d = tmp();
  const f = (n) => { const p = path.join(d, n); fs.writeFileSync(p, 'x'); return p; };
  const first = engine.sendFiles('B', [f('1.txt'), f('2.txt')]);
  const second = engine.sendFiles('B', [f('3.txt')]);
  await new Promise((r) => setTimeout(r, 10));
  const rec3 = engine.transfers.find((t) => t.name === '3.txt');
  assert.strictEqual(rec3.state, 'queued');
  engine.cancelTransfer(rec3.id);
  await Promise.all([first, second]);
  assert.strictEqual(maxActive, 1, 'only one file in flight per device');
  assert.deepStrictEqual(sent, ['1.txt', '2.txt']);
  assert.strictEqual(rec3.state, 'cancelled');
  engine.stop();
});

test('engine: a running send can be cancelled and is not retried', async () => {
  let calls = 0;
  const { engine, discovery } = mkEngineWithFakeTransfer('A', (host, port, fp, onProgress, signal) => new Promise((resolve, reject) => {
    calls++;
    signal.addEventListener('abort', () => { const e = new Error('Cancelled'); e.code = 'CANCELLED'; reject(e); });
  }));
  await engine.start();
  linkOnlineDevice(discovery, 'B');
  const f = path.join(tmp(), 'big.iso'); fs.writeFileSync(f, 'x');
  const p = engine.sendFiles('B', [f]);
  await new Promise((r) => setTimeout(r, 20));
  const rec = engine.transfers.find((t) => t.path === f);
  engine.cancelTransfer(rec.id);
  await p;
  assert.strictEqual(rec.state, 'cancelled');
  assert.strictEqual(calls, 1);
  engine.stop();
});

test('engine: sending a folder sends every file inside it', async () => {
  const sent = [];
  const { engine, discovery } = mkEngineWithFakeTransfer('A', async (host, port, fp, onProgress) => { sent.push(fp); onProgress(1); return 1; });
  await engine.start();
  linkOnlineDevice(discovery, 'B');
  const root = tmp();
  fs.mkdirSync(path.join(root, 'sub'));
  fs.writeFileSync(path.join(root, 'a.txt'), 'a');
  fs.writeFileSync(path.join(root, 'sub', 'b.txt'), 'b');
  fs.writeFileSync(path.join(root, '.hidden'), 'h');
  await engine.sendFiles('B', [root]);
  assert.deepStrictEqual(sent.map((p) => path.basename(p)).sort(), ['a.txt', 'b.txt']);
  engine.stop();
});

function stubDiscovery(onStart = () => {}) {
  const discovery = new (require('events'))();
  discovery.start = onStart; discovery.stop = () => {}; discovery.refresh = () => {}; discovery.findByAddress = () => null;
  return discovery;
}
const stubSettings = () => ({ d: { deviceName: 'A', avatarIndex: 1, receiveDir: tmp(), clipboardPeers: {} },
  get(k) { return this.d[k]; }, set(p) { Object.assign(this.d, p); } });

test('engine: history survives a restart; unfinished transfers come back as interrupted', () => {
  const historyFile = path.join(tmp(), 'history.json');
  const settings = stubSettings();
  const mk = () => new Engine({ settings, clipboard: { readText: () => '', writeText() {} }, discovery: stubDiscovery(), historyFile, getNetworkSignature: () => 'x' });
  const e1 = mk();
  e1.known.set('Phone', { name: 'Phone', avatarIndex: 3, online: false });
  e1._record({ incoming: true, kind: 'text', device: 'Phone', name: 'Message', text: 'hi', state: 'done' });
  e1._record({ incoming: false, kind: 'file', device: 'Phone', name: 'a.bin', path: 'C:\\a.bin', state: 'transferring' });
  e1._saveHistoryNow();
  const e2 = mk();
  assert.strictEqual(e2.transfers.length, 2);
  assert.strictEqual(e2.transfers[1].state, 'failed');
  assert.strictEqual(e2.transfers[1].error, 'Interrupted');
  assert.strictEqual(e2.known.get('Phone').avatarIndex, 3);
  assert.ok(e2.seq > 2, 'new ids must not collide with loaded ones');
});

test('engine: a network change restarts discovery', async () => {
  let sig = 'wifi-1', starts = 0;
  const engine = new Engine({ settings: stubSettings(), clipboard: { readText: () => '', writeText() {} },
    discovery: stubDiscovery(() => { starts++; }), getNetworkSignature: () => sig });
  await engine.start();
  engine.checkNetwork();
  assert.strictEqual(starts, 1);
  sig = 'wifi-2';
  engine.checkNetwork();
  await new Promise((r) => setImmediate(r));
  assert.strictEqual(starts, 2);
  engine.stop();
});

test('discovery: two machines announcing the same name are flagged as a conflict', () => {
  const { Discovery } = require('../src/core/discovery');
  const lan = new (require('events'))();
  lan.stop = () => {};
  const d = new Discovery({ lan });
  d._add('lan:1', { name: 'Pixel', host: '192.168.1.10', addresses: ['192.168.1.10'], port: 1 });
  assert.strictEqual(d.devices.get('Pixel').conflict, false);
  d._add('mdns', { name: 'Pixel', host: '192.168.1.10', addresses: ['192.168.1.10'], port: 1 });
  assert.strictEqual(d.devices.get('Pixel').conflict, false, 'same machine via two sources is fine');
  d._add('lan:2', { name: 'Pixel', host: '192.168.1.66', addresses: ['192.168.1.66'], port: 1 });
  assert.strictEqual(d.devices.get('Pixel').conflict, true);
  d._remove('lan:2', 'Pixel');
  assert.strictEqual(d.devices.get('Pixel').conflict, false);
});

test('settings: saved atomically, and a corrupt file is set aside instead of crashing', () => {
  const { Settings } = require('../src/core/settings');
  const dir = tmp();
  const s = new Settings(dir, { a: 1 });
  s.set({ a: 2 });
  assert.strictEqual(new Settings(dir, { a: 1 }).get('a'), 2);
  assert.deepStrictEqual(fs.readdirSync(dir), ['settings.json'], 'no temp files left behind');
  fs.writeFileSync(path.join(dir, 'settings.json'), '{"a": 3, trunc');
  const s2 = new Settings(dir, { a: 1 });
  assert.strictEqual(s2.get('a'), 1);
  assert.ok(fs.readdirSync(dir).some((f) => f.startsWith('settings.json.corrupt-')));
});

test('isDangerousFile flags programs and scripts', () => {
  const { isDangerousFile } = require('../src/core/files');
  for (const n of ['setup.exe', 'a.BAT', 'x.ps1', 'link.lnk', 'm.msi']) assert.ok(isDangerousFile(n), n);
  for (const n of ['photo.jpg', 'doc.pdf', 'song.mp3', 'exe.txt']) assert.ok(!isDangerousFile(n), n);
});

test('discovery: the newest picture wins when a device is seen two ways', () => {
  const { Discovery } = require('../src/core/discovery');
  const d = new Discovery();
  d.ownName = 'OnSpot_Me';
  const seen = [];
  d.on('up', (x) => seen.push(x.avatarIndex));
  const base = { name: 'Phone', serviceName: 'OnSpot_Phone', id: '10.0.0.5', host: '10.0.0.5', addresses: ['10.0.0.5'], port: 5000 };
  d._add('mdns', { ...base, avatarIndex: 2 });
  d._add('lan:x', { ...base, avatarIndex: 2 });
  d._add('lan:x', { ...base, avatarIndex: 7 }); // the phone picked a new picture
  assert.strictEqual(d.devices.get('Phone').avatarIndex, 7);
  d._add('mdns', { ...base, avatarIndex: 2 }); // a stale repeat must not undo it
  assert.strictEqual(d.devices.get('Phone').avatarIndex, 7);
  assert.deepStrictEqual(seen, [2, 2, 7, 7]);
});
