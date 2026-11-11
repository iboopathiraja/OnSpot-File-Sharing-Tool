'use strict';
const { EventEmitter } = require('events');
const { Bonjour } = require('bonjour-service');
const { SERVICE_TYPE, SERVICE_PREFIX } = require('./protocol');
const { cleanAddress } = require('./transfer');
const { LanScanner, pickNetwork } = require('./lan');

function pickAddresses(svc) {
  const all = (svc.addresses || []).map(cleanAddress);
  const v4 = all.filter((a) => /^\d+\.\d+\.\d+\.\d+$/.test(a) && !a.startsWith('169.254.'));
  const v6 = all.filter((a) => a.includes(':'));
  return { primary: v4[0] || v6[0] || null, all };
}

function txtValue(txt, key) {
  if (!txt) return undefined;
  for (const k of Object.keys(txt)) if (k.toLowerCase() === key.toLowerCase()) return txt[k];
  return undefined;
}

/**
 * Finds nearby devices and merges two sources into one list:
 *   - mDNS (`_onspot._tcp`)            : always on            (hotspot + wifi mode)
 *   - LAN sweep (UDP, see lan.js)      : Wi-Fi mode only      (finds everyone on the network)
 * Events: up(device), down(device); device = {name, serviceName, id, host, addresses, port, avatarIndex}
 */
class Discovery extends EventEmitter {
  constructor({ lan } = {}) {
    super();
    this.lan = lan || new LanScanner();
    this.bonjour = null;
    this.browser = null;
    this.published = null;
    this.ownName = null;
    this.entries = new Map(); // device name -> { sources: Map<source, device> }
    this.network = null; // network used in wifi mode
    this.mode = 'wifi';
    this._lanWired = false;
    this.lanNames = new Map(); // lan peer id -> name it last announced
    this.avatarSeq = 0; // increases each time any source reports a new picture
  }

  async start({ deviceName, avatarIndex, port, mode = 'wifi', networkId = 'auto' }) {
    this.stop();
    this.mode = mode === 'hotspot' ? 'hotspot' : 'wifi';
    this.network = this.mode === 'wifi' ? pickNetwork(networkId) : null;
    this.ownName = SERVICE_PREFIX + deviceName;

    this.bonjour = new Bonjour(this.network ? { interface: this.network.ip } : undefined);
    this.published = this.bonjour.publish({
      name: this.ownName, type: SERVICE_TYPE, protocol: 'tcp', port,
      txt: { avatarIndex: String(avatarIndex) },
    });
    this.browser = this.bonjour.find({ type: SERVICE_TYPE, protocol: 'tcp' });
    this.browser.on('up', (svc) => this._mdnsUp(svc));
    this.browser.on('down', (svc) => this._mdnsDown(svc));
    // A device that changes its picture re-sends its TXT record under the same name.
    this.browser.on('txt-update', (svc) => this._mdnsUp(svc));

    if (this.mode === 'wifi') {
      if (!this._lanWired) {
        this._lanWired = true;
        // Each LAN peer is its own source (keyed by its random id), so two devices that
        // announce the same name show up as a conflict instead of silently overwriting.
        this.lan.on('up', (p) => {
          const prevName = this.lanNames.get(p.id);
          if (prevName && prevName !== p.name) this._remove(`lan:${p.id}`, prevName);
          this.lanNames.set(p.id, p.name);
          this._add(`lan:${p.id}`, this._fromLan(p));
        });
        this.lan.on('down', (p) => { this.lanNames.delete(p.id); this._remove(`lan:${p.id}`, p.name); });
        this.lan.on('lan-error', () => {});
      }
      await this.lan.start({ name: deviceName, avatarIndex, port, network: this.network });
    }
  }

  _fromLan(p) {
    return {
      name: p.name, serviceName: SERVICE_PREFIX + p.name, id: p.host, host: p.host,
      addresses: [p.host], port: p.port, avatarIndex: p.avatarIndex,
    };
  }

  _mdnsUp(svc) {
    if (svc.name === this.ownName) return;
    const { primary, all } = pickAddresses(svc);
    if (!primary) return;
    const avatar = parseInt(txtValue(svc.txt, 'avatarIndex'), 10);
    this._add('mdns', {
      name: svc.name.startsWith(SERVICE_PREFIX) ? svc.name.slice(SERVICE_PREFIX.length) : svc.name,
      serviceName: svc.name, id: primary, host: primary, addresses: all,
      port: svc.port, avatarIndex: Number.isFinite(avatar) ? avatar : 1,
    });
  }

  _mdnsDown(svc) {
    const name = svc.name.startsWith(SERVICE_PREFIX) ? svc.name.slice(SERVICE_PREFIX.length) : svc.name;
    this._remove('mdns', name);
  }

  _add(source, dev) {
    if (dev.name === this.ownName || SERVICE_PREFIX + dev.name === this.ownName) return;
    let entry = this.entries.get(dev.name);
    if (!entry) { entry = { sources: new Map() }; this.entries.set(dev.name, entry); }
    const before = entry.sources.get(source);
    // Remember when each source last reported a new picture, so the newest one wins.
    const avatarAt = before && before.avatarIndex === dev.avatarIndex ? before.avatarAt : ++this.avatarSeq;
    entry.sources.set(source, { ...dev, avatarAt });
    const merged = this._merge(entry);
    entry.merged = merged;
    this.emit('up', merged);
  }

  _remove(source, name) {
    const entry = this.entries.get(name);
    if (!entry) return;
    entry.sources.delete(source);
    if (entry.sources.size === 0) {
      this.entries.delete(name);
      this.emit('down', entry.merged);
    } else {
      entry.merged = this._merge(entry);
      this.emit('up', entry.merged);
    }
  }

  /**
   * One device seen through several sources. `conflict` is set when the sources point at
   * different IPv4 hosts, i.e. more than one machine is using this name right now.
   */
  _merge(entry) {
    const list = [...entry.sources.values()];
    const isV4 = (h) => /^\d+\.\d+\.\d+\.\d+$/.test(h);
    const primary = list.find((d) => isV4(d.host)) || list[0];
    const hosts = new Set(list.map((d) => d.host).filter(isV4));
    const newest = list.reduce((a, b) => ((b.avatarAt || 0) > (a.avatarAt || 0) ? b : a));
    const { avatarAt, ...base } = primary; // eslint-disable-line no-unused-vars
    return {
      ...base, avatarIndex: newest.avatarIndex,
      addresses: [...new Set(list.flatMap((d) => d.addresses))], conflict: hosts.size > 1,
    };
  }

  /** All devices currently seen (used by tests / diagnostics). */
  get devices() {
    return new Map([...this.entries].map(([k, v]) => [k, v.merged]));
  }

  refresh() {
    try { if (this.browser) this.browser.update(); } catch { /* ignore */ }
    if (this.mode === 'wifi') this.lan.rescan();
  }

  findByAddress(addr) {
    const a = cleanAddress(addr);
    for (const e of this.entries.values()) if (e.merged && e.merged.addresses.includes(a)) return e.merged;
    return null;
  }

  stop() {
    try { if (this.published && this.published.stop) this.published.stop(); } catch { /* ignore */ }
    try { if (this.browser) this.browser.stop(); } catch { /* ignore */ }
    try { if (this.bonjour) this.bonjour.destroy(); } catch { /* ignore */ }
    try { this.lan.stop(); } catch { /* ignore */ }
    this.bonjour = null;
    this.browser = null;
    this.published = null;
    this.entries.clear();
    this.lanNames.clear();
  }
}

module.exports = { Discovery };
