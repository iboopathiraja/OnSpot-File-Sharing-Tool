'use strict';
const fs = require('fs');
const path = require('path');

const RESERVED = /^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\..*)?$/i;

// Files that run code when opened. Received ones get an extra confirmation before "Open".
const DANGEROUS = /\.(exe|msi|msix|msp|com|scr|pif|bat|cmd|ps1|psm1|vbs|vbe|js|jse|wsf|wsh|hta|cpl|lnk|reg|jar|appx|appxbundle|application|gadget|inf|scf|url|dll|sys|iso|img|vhd|vhdx)$/i;

const MAX_EXPANDED_FILES = 2000;
const FREE_SPACE_MARGIN = 256 * 1024 * 1024; // keep at least this much free after a receive

/** Make a remote-supplied file name safe to use on this filesystem. */
function sanitizeFileName(name) {
  let n = String(name || '').replace(/\\/g, '/');
  n = n.substring(n.lastIndexOf('/') + 1);
  n = n.replace(/[<>:"|?*\x00-\x1f]/g, '_').replace(/[. ]+$/, '');
  if (!n || n === '.' || n === '..') n = 'file';
  if (RESERVED.test(n)) n = '_' + n;
  return n.length > 200 ? n.slice(0, 200) : n;
}

function isDangerousFile(name) { return DANGEROUS.test(String(name || '')); }

/** Pick a non-existing path (photo.jpg, photo (1).jpg, ...) and reserve it atomically. */
async function reserveUniquePath(dir, fileName) {
  await fs.promises.mkdir(dir, { recursive: true });
  const ext = path.extname(fileName);
  const base = fileName.slice(0, fileName.length - ext.length);
  for (let i = 0; i < 10000; i++) {
    const candidate = path.join(dir, i === 0 ? fileName : `${base} (${i})${ext}`);
    try {
      const fh = await fs.promises.open(candidate, 'wx');
      return { fh, filePath: candidate };
    } catch (e) {
      if (e.code !== 'EEXIST') throw e;
    }
  }
  throw new Error('Could not allocate a file name');
}

/** Throws if writing `size` more bytes into `dir` would (nearly) fill the disk. */
async function ensureFreeSpace(dir, size) {
  await fs.promises.mkdir(dir, { recursive: true });
  let free;
  try {
    const s = await fs.promises.statfs(dir);
    free = s.bavail * s.bsize;
  } catch { return; } // statfs unsupported here: don't block the transfer
  if (size + FREE_SPACE_MARGIN > free) {
    const e = new Error('Not enough free disk space');
    e.code = 'ENOSPC';
    throw e;
  }
}

/**
 * Turns a list of files and folders into a flat list of files ({path, size}).
 * Folders are walked recursively (hidden entries and symlinks skipped).
 */
async function expandPaths(paths, limit = MAX_EXPANDED_FILES) {
  const out = [];
  const walk = async (p) => {
    if (out.length >= limit) return;
    let st;
    try { st = await fs.promises.lstat(p); } catch { out.push({ path: p, size: 0 }); return; }
    if (st.isSymbolicLink()) return;
    if (st.isFile()) { out.push({ path: p, size: st.size }); return; }
    if (!st.isDirectory()) return;
    const entries = await fs.promises.readdir(p).catch(() => []);
    entries.sort((a, b) => a.localeCompare(b));
    for (const name of entries) {
      if (name.startsWith('.')) continue;
      await walk(path.join(p, name));
    }
  };
  for (const p of paths) await walk(p);
  return out;
}

module.exports = { sanitizeFileName, reserveUniquePath, isDangerousFile, ensureFreeSpace, expandPaths, MAX_EXPANDED_FILES };
