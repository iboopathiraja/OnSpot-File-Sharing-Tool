'use strict';
const fs = require('fs');
const path = require('path');
const os = require('os');

/** Writes via a temp file + rename so a crash mid-write can't leave a truncated file behind. */
function writeFileAtomic(file, data) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const tmp = `${file}.${process.pid}.tmp`;
  fs.writeFileSync(tmp, data);
  fs.renameSync(tmp, file);
}

/** Reads JSON; a corrupt file is moved aside (not silently replaced) and null is returned. */
function readJson(file) {
  let raw;
  try { raw = fs.readFileSync(file, 'utf8'); } catch { return null; } // first run
  try { return JSON.parse(raw); } catch (e) {
    console.error(`${path.basename(file)} is corrupt, starting fresh`, e.message);
    try { fs.renameSync(file, `${file}.corrupt-${Date.now()}`); } catch { /* ignore */ }
    return null;
  }
}

class Settings {
  constructor(dir, defaults = {}) {
    this.file = path.join(dir, 'settings.json');
    this.data = { ...defaults, ...(readJson(this.file) || {}) };
  }

  get(k) { return this.data[k]; }

  all() { return { ...this.data }; }

  set(patch) {
    Object.assign(this.data, patch);
    try {
      writeFileAtomic(this.file, JSON.stringify(this.data, null, 2));
    } catch (e) { console.error('settings save failed', e); }
  }
}

const defaultsFor = (downloads) => ({
  deviceName: os.hostname(),
  avatarIndex: 1 + Math.floor(Math.random() * 11),
  receiveDir: path.join(downloads, 'OnSpot'),
  onboarded: false,
  startWithWindows: false,
  startMinimized: true,
  connectionMode: 'wifi', // 'wifi' = whole network, 'hotspot' = direct hotspot link
  networkInterface: 'auto',
  receiveFrom: 'nearby', // 'nearby' = only devices in the Nearby list, 'anyone' = any sender
  clipboardPeers: {}, // device name -> { host } pinned when clipboard sync was approved
});

module.exports = { Settings, defaultsFor, writeFileAtomic, readJson };
