'use strict';
// "Wi-Fi mode" discovery: finds every OnSpot device on the selected network
// even when the router blocks mDNS. UDP protocol on a fixed port shared with the
// Android app:
//   {"app":"onspot","v":1,"t":"probe"|"announce"|"bye","id","name","avatar","port"}
// We probe every host of the subnet (+ broadcast); receivers answer with an
// unicast "announce" carrying their name and TCP transfer port.
const dgram = require('dgram');
const os = require('os');
const crypto = require('crypto');
const { EventEmitter } = require('events');

const LAN_PORT = 45454;
const SCAN_MS = 3000;
// Broadcast goes out every SCAN_MS; the per-host unicast sweep (up to ~1000 packets) only
// every SWEEP_EVERY scans, or on every scan while nobody has been found yet.
const SWEEP_EVERY = 5;
const EXPIRE_MS = 26000;
const MAX_PACKET = 1024;

const ipToInt = (ip) => ip.split('.').reduce((a, o) => ((a << 8) + Number(o)) >>> 0, 0);
const intToIp = (n) => [n >>> 24, (n >>> 16) & 255, (n >>> 8) & 255, n & 255].join('.');
const prefixOf = (mask) => ipToInt(mask).toString(2).replace(/0/g, '').length;

/** All other host addresses of the subnet (capped at /22) plus its broadcast address. */
function subnetTargets(ip, netmask) {
  let prefix = prefixOf(netmask);
  if (prefix < 22) prefix = 24; // don't sweep huge networks
  if (prefix >= 31) return { hosts: [], broadcast: null };
  const mask = (~0 << (32 - prefix)) >>> 0;
  const self = ipToInt(ip);
  const net = (self & mask) >>> 0;
  const bcast = (net | ~mask) >>> 0;
  const hosts = [];
  for (let a = net + 1; a < bcast; a++) if (a !== self) hosts.push(intToIp(a));
  return { hosts, broadcast: intToIp(bcast) };
}

const VIRTUAL = /vethernet|virtualbox|vmware|vbox|hyper-v|wsl|docker|loopback|tap-|tun|bluetooth|vpn/i;
const WIFI = /wi-?fi|wlan|wireless/i;

function listNetworks() {
  const out = [];
  for (const [name, addrs] of Object.entries(os.networkInterfaces())) {
    for (const a of addrs || []) {
      if (a.family !== 'IPv4' || a.internal || a.address.startsWith('169.254.')) continue;
      out.push({ id: name, name, ip: a.address, netmask: a.netmask, wifi: WIFI.test(name), virtual: VIRTUAL.test(name) });
    }
  }
  return out;
}

/** Changes whenever an adapter appears, disappears or gets a new address. */
function networkSignature() {
  return listNetworks().map((n) => `${n.id}=${n.ip}/${n.netmask}`).sort().join(',');
}

/** The network to use for `id` ('auto' = best Wi-Fi/LAN adapter). */
function pickNetwork(id) {
  const all = listNetworks();
  if (id && id !== 'auto') {
    const m = all.find((n) => n.id === id);
    if (m) return m;
  }
  return all.find((n) => n.wifi && !n.virtual) || all.find((n) => !n.virtual) || all[0] || null;
}

/** Events: 'up' (peer), 'down' (peer). peer = {id, name, host, port, avatarIndex} */
class LanScanner extends EventEmitter {
  constructor({ bindPort = LAN_PORT, targetPort = LAN_PORT } = {}) {
    super();
    this.bindPort = bindPort;
    this.targetPort = targetPort;
    this.id = crypto.randomUUID();
    this.socket = null;
    this.peers = new Map();
    this.timers = [];
    this.self = null;
    this.hosts = []; // unicast sweep targets
    this.broadcasts = [];
    this.scans = 0;
  }

  /** `hosts` overrides subnet enumeration (used by tests). */
  async start({ name, avatarIndex, port, network, hosts }) {
    this.stop();
    this.self = { name, avatar: avatarIndex, port };
    this.scans = 0;
    if (hosts) { this.hosts = hosts; this.broadcasts = []; } else if (network) {
      const t = subnetTargets(network.ip, network.netmask);
      this.hosts = t.hosts;
      this.broadcasts = t.broadcast ? [t.broadcast, '255.255.255.255'] : [];
    } else { this.hosts = []; this.broadcasts = []; }

    // Prefer the shared port; if it is taken we can still probe out and read replies.
    this.socket = (await this._open(this.bindPort)) || (await this._open(0));
    if (!this.socket) return;
    this._scan(true);
    this.timers.push(setInterval(() => this._scan(), SCAN_MS));
    this.timers.push(setInterval(() => this._expire(), 5000));
  }

  _open(port) {
    return new Promise((resolve) => {
      const s = dgram.createSocket({ type: 'udp4', reuseAddr: true });
      const fail = () => { try { s.close(); } catch { /* ignore */ } resolve(null); };
      s.once('error', fail);
      s.bind(port, () => {
        s.removeListener('error', fail);
        s.on('error', (e) => this.emit('lan-error', e));
        s.on('message', (msg, rinfo) => this._onMessage(msg, rinfo));
        try { s.setBroadcast(true); } catch { /* ignore */ }
        resolve(s);
      });
    });
  }

  _packet(t) {
    return Buffer.from(JSON.stringify({
      app: 'onspot', v: 1, t, id: this.id, name: this.self.name, avatar: this.self.avatar, port: this.self.port,
    }));
  }

  _send(buf, host, port = this.targetPort) {
    try { this.socket.send(buf, port, host, () => {}); } catch { /* ignore */ }
  }

  _scan(full = false) {
    if (!this.socket) return;
    const sweep = full || this.peers.size === 0 || this.scans % SWEEP_EVERY === 0;
    this.scans++;
    const pkt = this._packet('probe');
    for (const h of this.broadcasts) this._send(pkt, h);
    if (sweep) for (const h of this.hosts) this._send(pkt, h);
  }

  _onMessage(msg, rinfo) {
    if (msg.length > MAX_PACKET) return;
    let m;
    try { m = JSON.parse(msg.toString('utf8')); } catch { return; }
    if (!m || m.app !== 'onspot' || m.id === this.id || typeof m.id !== 'string') return;
    if (m.t === 'bye') {
      const p = this.peers.get(m.id);
      if (p) { this.peers.delete(m.id); this.emit('down', p.info); }
      return;
    }
    if (m.t !== 'probe' && m.t !== 'announce') return;
    const port = Number(m.port);
    if (!Number.isInteger(port) || port < 1 || port > 65535 || typeof m.name !== 'string' || !m.name) return;
    const info = {
      id: m.id, name: String(m.name).slice(0, 60).replace(/^OnSpot_/, ''), host: rinfo.address, port,
      avatarIndex: Number.isInteger(m.avatar) ? m.avatar : 1,
    };
    const prev = this.peers.get(m.id);
    this.peers.set(m.id, { info, seen: Date.now() });
    if (!prev || prev.info.host !== info.host || prev.info.port !== info.port || prev.info.name !== info.name
      || prev.info.avatarIndex !== info.avatarIndex) this.emit('up', info);
    if (m.t === 'probe') this._send(this._packet('announce'), rinfo.address, rinfo.port);
  }

  _expire() {
    const now = Date.now();
    for (const [id, p] of this.peers) {
      if (now - p.seen > EXPIRE_MS) { this.peers.delete(id); this.emit('down', p.info); }
    }
  }

  /** Re-probe immediately (manual refresh). */
  rescan() { this._scan(true); }

  stop() {
    this.timers.forEach(clearInterval);
    this.timers = [];
    const sock = this.socket;
    this.socket = null;
    if (sock) {
      // Close only after the goodbye packets have actually left the socket.
      let pending = 0;
      const closeSock = () => { try { sock.close(); } catch { /* already closed */ } };
      try {
        const bye = this._packet('bye');
        for (const p of this.peers.values()) {
          pending++;
          sock.send(bye, this.targetPort, p.info.host, () => { if (--pending === 0) closeSock(); });
        }
      } catch { /* ignore */ }
      if (pending === 0) closeSock();
      else setTimeout(closeSock, 500).unref();
    }
    this.peers.clear();
  }
}

module.exports = { LanScanner, listNetworks, pickNetwork, networkSignature, subnetTargets, LAN_PORT };
