'use strict';
const $ = (id) => document.getElementById(id);
const api = window.onspot;

let state = { self: {}, devices: [], transfers: [], pendingRequests: [], network: {} };
let settings = {};
let page = 'home'; // home | device | received | activity
let currentDevice = null;
let tray = []; // {key, type:'file'|'folder'|'text', path?, name, size, files, text?, label?}
let historyFilter = 'all'; // all | sent | received | text | failed | dev:<name>
let historySearch = '';
let recvFilter = null; // device name or null
let receiveSeenAt = Date.now();
let editAvatar = 1;
let editMode = 'wifi';
let pickedDir = null;
let dragDepth = 0;
let textSeq = 0;

const avatarSrc = (i) => `avatars/avatar_${i || 1}.png`;
const isImage = (n) => /\.(png|jpe?g|gif|bmp|webp)$/i.test(n || '');
const deviceByName = (name) => state.devices.find((d) => d.name === name);
const isActive = (t) => t.state === 'transferring' || t.state === 'queued';
const plural = (n, w) => `${n} ${w}${n === 1 ? '' : 's'}`;
const TINTS = ['blue', 'yellow', 'pink', 'lav', 'mint', 'peach'];
function tintOf(name) {
  let h = 7;
  for (const c of String(name)) h = (h * 31 + c.charCodeAt(0)) | 0;
  return TINTS[Math.abs(h) % TINTS.length];
}
function avatarOf(name) {
  const d = deviceByName(name);
  return d ? d.avatarIndex : 1;
}

function el(tag, props = {}, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (k === 'class') e.className = v;
    else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else e[k] = v;
  }
  for (const k of kids) if (k != null && k !== false) e.append(k);
  return e;
}
function insertAfter(parent, node, ref) {
  const want = ref ? ref.nextSibling : parent.firstChild;
  if (want !== node) parent.insertBefore(node, want);
}
/** Keyed list update: existing nodes are reused and reordered, so entrance animations don't replay. */
function reconcile(container, cache, entries) {
  const seen = new Set();
  let prev = null;
  for (const en of entries) {
    seen.add(en.key);
    let v = cache.get(en.key);
    if (!v) { v = en.make(en.data); cache.set(en.key, v); }
    if (v.update) v.update(en.data);
    insertAfter(container, v.el, prev);
    prev = v.el;
  }
  for (const [k, v] of cache) if (!seen.has(k)) { v.el.remove(); cache.delete(k); }
}

// ---- icons (Segoe Fluent Icons, with Segoe MDL2 Assets as the Windows 10 fallback) --------
const ICONS = {
  x: '\uE711', retry: '\uE72C', open: '\uE8A7', folder: '\uE838', copy: '\uE8C8', chevron: '\uE76C',
  check: '\uE73E', up: '\uE74A', down: '\uE74B', text: '\uE8D2', add: '\uE710', send: '\uE724',
  share: '\uE72D', clip: '\uE77F', stack: '\uE8F1',
};
function icon(name, size = 16) {
  const i = el('i', { class: 'fi', textContent: ICONS[name] || '' });
  i.style.fontSize = `${size}px`;
  return i;
}
function svgRing(viewBox, r) {
  const ns = 'http://www.w3.org/2000/svg';
  const s = document.createElementNS(ns, 'svg');
  s.setAttribute('viewBox', `0 0 ${viewBox} ${viewBox}`);
  const mk = (c) => {
    const ci = document.createElementNS(ns, 'circle');
    ci.setAttribute('cx', viewBox / 2); ci.setAttribute('cy', viewBox / 2); ci.setAttribute('r', r);
    ci.setAttribute('class', c);
    return ci;
  };
  const trk = mk('trk'), arc = mk('arc');
  const C = 2 * Math.PI * r;
  arc.style.strokeDasharray = String(C);
  arc.style.strokeDashoffset = String(C);
  s.append(trk, arc);
  return { svg: s, set: (f) => { arc.style.strokeDashoffset = String(C * (1 - Math.max(0, Math.min(1, f)))); } };
}

const FILE_TYPES = [
  ['image', /\.(png|jpe?g|gif|bmp|webp|heic|svg|tiff?)$/i],
  ['video', /\.(mp4|mkv|mov|avi|webm|m4v|3gp)$/i],
  ['audio', /\.(mp3|wav|flac|aac|ogg|m4a|opus)$/i],
  ['pdf', /\.pdf$/i],
  ['doc', /\.(docx?|xlsx?|pptx?|txt|rtf|odt|csv|md)$/i],
  ['archive', /\.(zip|rar|7z|tar|gz|bz2|xz)$/i],
  ['app', /\.(apk|exe|msi|appx|dmg)$/i],
  ['code', /\.(js|ts|py|java|kt|c|cpp|h|cs|go|rs|html|css|json|xml|sh)$/i],
];
const fileType = (n) => (FILE_TYPES.find(([, re]) => re.test(n || '')) || ['other'])[0];
const extLabel = (n) => ((n && n.includes('.') ? n.split('.').pop() : '') || 'FILE').slice(0, 4).toUpperCase();

function fileIcon(name, kind) {
  if (kind === 'text') return el('div', { class: 'file-ic ft-text' }, icon('text', 18));
  if (kind === 'folder') return el('div', { class: 'file-ic ft-folder' }, icon('folder', 18));
  return el('div', { class: `file-ic ft-${fileType(name)}`, textContent: extLabel(name) });
}

function fmtSize(n) {
  if (!n) return '0 B';
  const u = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.min(u.length - 1, Math.floor(Math.log(n) / Math.log(1024)));
  return `${(n / 1024 ** i).toFixed(i ? 1 : 0)} ${u[i]}`;
}
const fmtTime = (ts) => new Date(ts).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
function fmtDay(ts) {
  const d = new Date(ts), n = new Date();
  if (d.toDateString() === n.toDateString()) return 'Today';
  const y = new Date(n - 864e5);
  return d.toDateString() === y.toDateString() ? 'Yesterday' : d.toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'short' });
}
function fmtEta(sec) {
  if (!isFinite(sec) || sec < 0) return '';
  if (sec < 60) return `${Math.ceil(sec)}s left`;
  return `${Math.floor(sec / 60)}m ${Math.ceil(sec % 60)}s left`;
}
function speedOf(list) {
  if (!list.length) return 0;
  const t0 = Math.min(...list.map((t) => t.startedAt || t.ts));
  const bytes = list.reduce((s, t) => s + (t.bytes || 0), 0);
  const secs = (Date.now() - t0) / 1000;
  return secs > 0.5 && bytes ? bytes / secs : 0;
}

function toast(msg) {
  if (!msg) return;
  const t = $('toast');
  t.textContent = msg;
  t.classList.remove('hidden');
  t.style.animation = 'none'; void t.offsetWidth; t.style.animation = '';
  clearTimeout(toast.h);
  toast.h = setTimeout(() => t.classList.add('hidden'), 2600);
}

function linkify(text) {
  const frag = document.createDocumentFragment();
  const re = /https?:\/\/[^\s<>"']+/g;
  let last = 0, m;
  while ((m = re.exec(text))) {
    frag.append(text.slice(last, m.index));
    const url = m[0];
    frag.append(el('a', { href: '#', textContent: url, onclick: (e) => { e.preventDefault(); e.stopPropagation(); api.openExternal(url); } }));
    last = m.index + url.length;
  }
  frag.append(text.slice(last));
  return frag;
}

// thumbnails are cached per transfer id / tray path
const thumbs = new Map();
function thumbFor(key, loader) {
  if (!thumbs.has(key)) thumbs.set(key, loader().catch(() => null));
  return thumbs.get(key);
}
/** Fills a photo-like box with a transfer's picture: a thumbnail, a coloured file badge or a text snippet. */
function fillMedia(box, t) {
  if (t.kind === 'text') {
    box.classList.add('txt');
    box.append(el('span', { textContent: (t.text || '').slice(0, 260) }));
    return box;
  }
  box.classList.add(`ft-${fileType(t.name)}`);
  box.append(el('span', { class: 'ext', textContent: extLabel(t.name) }));
  if (isImage(t.name) && t.state === 'done' && (t.incoming || t.path)) {
    thumbFor(t.id, () => api.thumbnail(t.id)).then((u) => { if (u) box.append(el('img', { src: u, alt: '' })); });
  }
  return box;
}

// ---- theme & accent (follows Windows unless the user picks Light or Dark) -----------------
let themePref = 'system';
let accentHex = null;
const darkQuery = matchMedia('(prefers-color-scheme: dark)');
function hexToRgb(h) { const n = parseInt(h.slice(1), 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255]; }
function mix(h, target, t) {
  const a = hexToRgb(h), b = hexToRgb(target);
  return `#${a.map((v, i) => Math.round(v + (b[i] - v) * t).toString(16).padStart(2, '0')).join('')}`;
}
function applyAccent() {
  const root = document.documentElement.style;
  if (!accentHex) { ['--accent', '--accent-hover', '--on-accent', '--accent-soft'].forEach((k) => root.removeProperty(k)); return; }
  const dark = document.documentElement.dataset.theme === 'dark';
  // Like Windows: a darker shade of the accent on light backgrounds, a lighter one on dark.
  const a = dark ? mix(accentHex, '#ffffff', 0.45) : mix(accentHex, '#000000', 0.18);
  const [r, g, b] = hexToRgb(a);
  root.setProperty('--accent', a);
  root.setProperty('--accent-hover', dark ? mix(a, '#000000', 0.1) : mix(a, '#ffffff', 0.12));
  root.setProperty('--on-accent', (0.299 * r + 0.587 * g + 0.114 * b) > 150 ? '#000000' : '#ffffff');
  root.setProperty('--accent-soft', `rgba(${r},${g},${b},.14)`);
}
function applyTheme() {
  const t = themePref === 'system' ? (darkQuery.matches ? 'dark' : 'light') : themePref;
  document.documentElement.dataset.theme = t;
  applyAccent();
  document.querySelectorAll('#themeSeg button').forEach((b) => b.classList.toggle('sel', b.dataset.themeOpt === themePref));
}
function setThemePref(pref) {
  themePref = ['light', 'dark'].includes(pref) ? pref : 'system';
  try { localStorage.setItem('theme', themePref); } catch { /* ignore */ }
  // The main process recolours Mica and the window buttons; prefers-color-scheme follows it.
  Promise.resolve(api.setTheme && api.setTheme(themePref)).catch(() => {}).then(applyTheme);
  applyTheme();
}
(function initTheme() {
  let t = null;
  try { t = localStorage.getItem('theme'); } catch { /* ignore */ }
  themePref = ['light', 'dark'].includes(t) ? t : 'system';
  applyTheme();
  if (api.setTheme) Promise.resolve(api.setTheme(themePref)).catch(() => {}).then(applyTheme);
})();
darkQuery.addEventListener('change', applyTheme);
document.querySelectorAll('#themeSeg button').forEach((b) => { b.onclick = () => setThemePref(b.dataset.themeOpt); });
if (api.onAccent) api.onAccent((a) => { accentHex = a; applyAccent(); });

/** Native Windows right-click menu. items: [{id,label}|{type:'separator'}], run: {id: fn}. */
async function nativeMenu(e, items, run) {
  e.preventDefault();
  e.stopPropagation();
  if (!api.popupMenu) return;
  const id = await api.popupMenu(items.filter(Boolean));
  if (id && run[id]) run[id]();
}

// ---- navigation ------------------------------------------------------------------
const PAGES = ['home', 'device', 'received', 'activity'];
function showPage(p) {
  const changed = p !== page;
  page = p;
  document.querySelectorAll('.nav-item[data-page]').forEach((b) => b.classList.toggle('sel', b.dataset.page === p));
  for (const k of PAGES) $(`page-${k}`).classList.toggle('hidden', k !== p);
  $('backBtn').classList.toggle('hidden', p !== 'device');
  if (p === 'received') receiveSeenAt = Date.now();
  if (p !== 'device') closeDevText();
  if (p !== 'activity' && historySearch) { $('globalSearch').value = ''; historySearch = ''; }
  if (changed) $('scroller').scrollTop = 0;
  render();
}
document.querySelectorAll('.nav-item[data-page]').forEach((b) => { b.onclick = () => showPage(b.dataset.page); });
function openDevice(name) {
  if (currentDevice !== name) { devCache.forEach((v) => v.el.remove()); devCache.clear(); stackKey = ''; }
  currentDevice = name;
  showPage('device');
  api.refresh();
}
$('backBtn').onclick = () => showPage('home');

// ---- render ----------------------------------------------------------------------
function render() {
  $('selfAvatar').src = avatarSrc(state.self.avatarIndex);
  $('selfName').textContent = state.self.name || 'This PC';
  renderNet();
  renderSide();
  renderHeader();
  renderBadge();
  if (page === 'home') renderHome();
  if (page === 'device') renderDevice();
  if (page === 'received') renderReceived();
  if (page === 'activity') renderHistory();
  renderDock();
  renderSheet();
  renderRequests();
}

function renderNet() {
  const n = state.network || {};
  const ok = n.mode === 'hotspot' || !!n.name;
  $('netDot').className = `dot ${ok ? 'on' : 'warn'}`;
  $('netChipTxt').textContent = n.mode === 'hotspot' ? 'Hotspot · direct link' : (n.name ? `${n.name} · ${n.ip}` : 'No network found');
  $('meBtn').title = `Your device (Ctrl+,)\n${$('netChipTxt').textContent}`;
}

function renderHeader() {
  const online = state.devices.filter((d) => d.online).length;
  let title = 'OnSpot', sub = '';
  if (page === 'home') {
    title = 'Home';
    sub = online ? `${plural(online, 'device')} nearby · visible as “${state.self.name || ''}”` : 'Searching for devices…';
  } else if (page === 'device') {
    const d = deviceByName(currentDevice);
    title = currentDevice || '';
    sub = d && d.online ? `Nearby${d.host ? ` · ${d.host}` : ''}` : 'Offline';
  } else if (page === 'received') {
    title = 'Received';
    sub = `Visible as “${state.self.name || ''}”`;
  } else {
    title = 'Activity';
    sub = historySearch ? `Results for “${historySearch}”` : plural(state.transfers.length, 'item');
  }
  $('pageTitle').textContent = title;
  $('pageSub').textContent = sub;
}

function renderBadge() {
  const n = page === 'received' ? 0 : state.transfers.filter((t) => t.incoming && t.ts > receiveSeenAt && t.state !== 'failed').length;
  $('recvBadge').textContent = n > 99 ? '99+' : String(n);
  $('recvBadge').classList.toggle('hidden', !n);
}

// ---- device activity (ring + ✓/✕ after a run) ------------------------------------------
const outcome = new Map(); // device -> {ok, failed, until}
const runIds = new Map(); // device -> Set of outgoing ids seen while busy

function deviceActivity(name) {
  const mine = state.transfers.filter((t) => t.device === name && !t.incoming && t.kind === 'file');
  const active = mine.filter(isActive);
  let ids = runIds.get(name);
  if (active.length) {
    if (!ids) { ids = new Set(); runIds.set(name, ids); }
    active.forEach((t) => ids.add(t.id));
    const run = mine.filter((t) => ids.has(t.id));
    const total = run.reduce((s, t) => s + (t.size || 0), 0);
    const done = run.reduce((s, t) => s + (t.state === 'done' ? (t.size || 0) : (t.bytes || 0)), 0);
    return { busy: true, queuedOnly: active.every((t) => t.state === 'queued'), progress: total ? done / total : 0, left: active.length };
  }
  if (ids) {
    runIds.delete(name);
    const run = mine.filter((t) => ids.has(t.id));
    const failed = run.filter((t) => t.state === 'failed').length;
    const cancelled = run.filter((t) => t.state === 'cancelled').length;
    if (run.length && cancelled < run.length) {
      outcome.set(name, { ok: !failed, failed, until: Date.now() + 4000 });
      setTimeout(render, 4100);
    }
  }
  return { busy: false };
}
function flashOutcome(name) {
  outcome.set(name, { ok: true, failed: 0, until: Date.now() + 2500 });
  setTimeout(render, 2600);
}

function personStatus(d, act, inSheet) {
  const o = outcome.get(d.name);
  if (!d.online) return { text: d.lastActivity ? `Seen ${fmtDay(d.lastActivity).toLowerCase()}` : 'Offline' };
  if (act.busy) return act.queuedOnly ? { text: 'Waiting…', cls: 'busy' } : { text: `${Math.round(act.progress * 100)}%`, cls: 'busy' };
  if (o && o.until > Date.now()) return o.ok ? { text: 'Sent', cls: 'ok' } : { text: `${o.failed} failed`, cls: 'bad' };
  if (d.conflict) return { text: 'Name used twice', cls: 'bad' };
  return { text: inSheet ? 'Click to send' : 'Nearby' };
}

/** A round avatar with name, status, a progress ring while sending and a ✓/✕ afterwards. */
function makePerson(d0, inSheet) {
  const name = d0.name;
  const img = el('img', { alt: '' });
  const ring = svgRing(72, 34);
  const dot = el('i', { class: 'sd' });
  const badge = el('span', { class: 'badge-ic hidden' });
  const nameEl = el('span', { class: 'pn' });
  const sub = el('span', { class: 'ps' });
  const btn = el('button', { class: 'person' }, el('span', { class: 'pa' }, ring.svg, img, dot, badge), nameEl, sub);
  btn.addEventListener('click', () => (inSheet ? sendTrayTo(name) : openDevice(name)));
  if (!inSheet) btn.addEventListener('contextmenu', (e) => deviceMenu(e, name));
  addDropTarget(btn, name);
  let lastBadge = '';
  return {
    el: btn,
    update(d) {
      const act = deviceActivity(d.name);
      if (img.dataset.idx !== String(d.avatarIndex)) { img.dataset.idx = String(d.avatarIndex); img.src = avatarSrc(d.avatarIndex); }
      nameEl.textContent = d.name;
      btn.title = d.online ? `${d.name}${d.host ? ` · ${d.host}` : ''}` : `${d.name} is offline`;
      btn.classList.toggle('off', !d.online);
      btn.classList.toggle('sending', !!act.busy);
      dot.classList.toggle('on', d.online);
      ring.set(act.busy ? Math.max(0.02, act.progress) : 0);
      const st = personStatus(d, act, inSheet);
      sub.textContent = st.text;
      sub.className = `ps ${st.cls || ''}`;
      const o = outcome.get(d.name);
      const b = !act.busy && o && o.until > Date.now() ? (o.ok ? 'ok' : 'bad') : '';
      if (b !== lastBadge) {
        lastBadge = b;
        badge.className = `badge-ic ${b}${b ? '' : ' hidden'}`;
        badge.replaceChildren(...(b ? [icon(b === 'ok' ? 'check' : 'x', 10)] : []));
      }
      dot.classList.toggle('hidden', !!b);
    },
  };
}

function deviceMenu(e, name) {
  const d = deviceByName(name);
  const online = !!(d && d.online);
  nativeMenu(e, [
    { id: 'files', label: 'Send files…', enabled: online },
    { id: 'folder', label: 'Send a folder…', enabled: online },
    { id: 'clip', label: 'Send clipboard', enabled: online },
    { type: 'separator' },
    { id: 'open', label: `Open ${name}` },
    { id: 'history', label: 'Show activity' },
  ], {
    files: async () => sendPathsTo(name, await api.pickFiles()),
    folder: async () => sendPathsTo(name, await api.pickFolderToSend()),
    clip: async () => { const r = await api.sendClipboardNow(name); toast(r.ok ? 'Clipboard sent' : r.error); },
    open: () => openDevice(name),
    history: () => { historyFilter = `dev:${name}`; showPage('activity'); },
  });
}

// ---- navigation pane: device list ---------------------------------------------------------
const sideCache = new Map();
function makeSideDev(d0) {
  const name = d0.name;
  const img = el('img', { alt: '' });
  const ring = svgRing(38, 17);
  const dot = el('i');
  const nameEl = el('b');
  const sub = el('small');
  const btn = el('button', { class: 'side-dev' }, el('span', { class: 'sa' }, img, ring.svg, dot), el('span', { class: 'st' }, nameEl, sub));
  btn.addEventListener('click', () => openDevice(name));
  btn.addEventListener('contextmenu', (e) => deviceMenu(e, name));
  addDropTarget(btn, name);
  return {
    el: btn,
    update(d) {
      const act = deviceActivity(d.name);
      if (img.dataset.idx !== String(d.avatarIndex)) { img.dataset.idx = String(d.avatarIndex); img.src = avatarSrc(d.avatarIndex); }
      nameEl.textContent = d.name;
      btn.title = d.online ? `${d.name}${d.host ? ` · ${d.host}` : ''}\nDrop files here to send` : `${d.name} is offline`;
      btn.classList.toggle('off', !d.online);
      btn.classList.toggle('sel', page === 'device' && currentDevice === d.name);
      dot.className = d.online ? 'on' : '';
      ring.set(act.busy ? Math.max(0.02, act.progress) : 0);
      const st = personStatus(d, act, false);
      sub.textContent = st.text === 'Nearby' ? (d.clipboardState === 'on' ? 'Clipboard synced' : 'Available') : st.text;
      sub.className = st.cls || '';
    },
  };
}
function renderSide() {
  const devs = sortedDevices();
  reconcile($('sideDevices'), sideCache, devs.map((d) => ({ key: d.name, data: d, make: makeSideDev })));
  $('sideNoDevices').classList.toggle('hidden', devs.some((d) => d.online));
}

function addDropTarget(node, name) {
  node.addEventListener('dragover', (e) => { if (hasFiles(e)) { e.preventDefault(); node.classList.add('drag-over'); } });
  node.addEventListener('dragleave', () => node.classList.remove('drag-over'));
  node.addEventListener('drop', (e) => {
    e.preventDefault(); e.stopPropagation();
    node.classList.remove('drag-over');
    endDrag();
    sendPathsTo(name, [...e.dataTransfer.files].map((f) => api.pathForFile(f)).filter(Boolean));
  });
}

// ---- HOME ----------------------------------------------------------------------------------
const peopleCache = new Map();
const collectionCache = new Map();
function sortedDevices() {
  return [...state.devices].sort((a, b) => (b.online - a.online) || ((b.lastActivity || 0) - (a.lastActivity || 0)) || a.name.localeCompare(b.name));
}
function renderHome() {
  const devs = sortedDevices();
  const online = devs.filter((d) => d.online).length;
  reconcile($('people'), peopleCache, devs.map((d) => ({ key: d.name, data: d, make: (x) => makePerson(x, false) })));
  $('people').classList.toggle('hidden', !devs.length);
  $('nearbyCount').textContent = online ? `${online} online` : '';
  $('nearbyEmpty').classList.toggle('hidden', online > 0);
  $('radarAvatar').src = avatarSrc(state.self.avatarIndex);
  $('nearbyEmptySub').textContent = state.network && state.network.mode === 'hotspot'
    ? 'Join the same mobile hotspot and open OnSpot on the other device.'
    : 'Open OnSpot on your phone and stay on the same Wi‑Fi.';

  const groups = new Map();
  for (const t of state.transfers) {
    if (!groups.has(t.device)) groups.set(t.device, []);
    groups.get(t.device).push(t);
  }
  const cols = [...groups.entries()]
    .map(([name, items]) => ({ name, items: items.slice().sort((a, b) => b.ts - a.ts) }))
    .sort((a, b) => b.items[0].ts - a.items[0].ts);
  reconcile($('collections'), collectionCache, cols.map((c) => ({ key: c.name, data: c, make: makeCollection })));
  $('collectionsHead').classList.toggle('hidden', !cols.length);
  $('homeEmpty').classList.toggle('hidden', cols.length > 0);
}

/** A pastel card per device with the last few things shared, fanned out like photos. */
function makeCollection(c0) {
  const name = c0.name;
  const fan = el('div', { class: 'fan' });
  const add = el('button', {
    class: 'add-tile', title: `Send files to ${name}`,
    onclick: async (e) => { e.stopPropagation(); sendPathsTo(name, await api.pickFiles()); },
  }, icon('add', 20), el('small', { textContent: 'Send' }));
  const title = el('b');
  const meta = el('small');
  const devAv = el('img', { alt: '' });
  const meAv = el('img', { alt: '' });
  const busy = el('span', { class: 'col-busy hidden' });
  const card = el('div', { class: `collection tint-${tintOf(name)}`, tabIndex: 0, role: 'button' },
    busy, fan, el('div', { class: 'meta-row' }, el('div', {}, title, meta), el('div', { class: 'av-pair' }, devAv, meAv)));
  card.addEventListener('click', () => openDevice(name));
  card.addEventListener('contextmenu', (e) => deviceMenu(e, name));
  card.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openDevice(name); } });
  addDropTarget(card, name);
  let fanKey = '';
  return {
    el: card,
    update(c) {
      const d = deviceByName(name);
      const top = c.items.slice(0, 3);
      const k = top.map((t) => `${t.id}:${t.state}`).join('|');
      if (k !== fanKey) {
        fanKey = k;
        const photos = top.slice().reverse().map((t) => fillMedia(el('div', { class: 'photo' }), t));
        fan.replaceChildren(...photos, add);
      }
      add.disabled = !(d && d.online);
      title.textContent = name;
      const n = c.items.length;
      meta.textContent = `${plural(n, 'item')} • ${fmtDay(c.items[0].ts)}`;
      devAv.src = avatarSrc(d ? d.avatarIndex : 1);
      meAv.src = avatarSrc(state.self.avatarIndex);
      const act = deviceActivity(name);
      const incoming = c.items.filter((t) => t.incoming && isActive(t));
      let pill = '';
      if (act.busy) pill = act.queuedOnly ? 'Waiting…' : `Sending ${Math.round(act.progress * 100)}%`;
      else if (incoming.length) pill = 'Receiving…';
      busy.textContent = pill;
      busy.classList.toggle('hidden', !pill);
    },
  };
}
$('firstSendBtn').onclick = () => openSendSheet();

// ---- DEVICE page ------------------------------------------------------------------------------
const devCache = new Map();
let stackKey = '';
const CLIP_SUB = {
  off: 'Copy here, paste there',
  pending: 'Waiting for approval…',
  on: 'On for both devices',
  moved: 'Paused — new address',
  conflict: 'Paused — name used twice',
};
function polaroid(cls, t, d) {
  const pic = el('div', { class: 'pic' });
  let capTitle, capSub;
  if (t) {
    fillMedia(pic, t);
    capTitle = t.kind === 'text' ? 'Message' : t.name;
    capSub = `${t.incoming ? 'Received' : 'Sent'} · ${fmtDay(t.ts)}`;
  } else {
    pic.classList.add('blank');
    pic.append(el('img', { class: 'big-av', src: avatarSrc(d ? d.avatarIndex : 1), alt: '' }));
    capTitle = currentDevice;
    capSub = 'Nothing shared yet';
  }
  const cap = el('div', { class: 'cap' }, el('div', {}, el('b', { textContent: capTitle }), el('small', { textContent: capSub })));
  if (cls === 'front') {
    cap.append(el('div', { class: 'av-pair' }, el('img', { src: avatarSrc(d ? d.avatarIndex : 1), alt: '' }), el('img', { src: avatarSrc(state.self.avatarIndex), alt: '' })));
  }
  return el('div', { class: `polaroid ${cls}` }, pic, cap);
}
function renderDevice() {
  const name = currentDevice;
  const d = deviceByName(name);
  const items = state.transfers.filter((t) => t.device === name).sort((a, b) => b.ts - a.ts);
  if (!d && !items.length) { showPage('home'); return; }
  const online = !!(d && d.online);
  const k = items.slice(0, 3).map((t) => `${t.id}:${t.state}`).join('|') + `|${d ? d.avatarIndex : 0}|${state.self.avatarIndex}`;
  if (k !== stackKey) {
    stackKey = k;
    $('devStack').replaceChildren(polaroid('back2', items[2], d), polaroid('back1', items[1], d), polaroid('front', items[0], d));
  }
  $('devName').textContent = name;
  $('devMeta').textContent = `${plural(items.length, 'item')} • ${online ? 'Nearby now' : 'Offline'}`;
  $('devSendBtn').disabled = !online;
  $('devMoreBtn').disabled = !online;
  $('devTextBtn').disabled = !online;
  $('devClipBtn').disabled = !online;
  const cs = (d && d.clipboardState) || 'off';
  $('clipSyncToggle').checked = cs !== 'off';
  $('clipSyncToggle').disabled = !online && cs === 'off';
  $('clipSyncSub').textContent = CLIP_SUB[cs] || CLIP_SUB.off;

  let text = '', btn = null;
  if (d && d.online && d.conflict) text = `More than one device here is called “${d.name}”. Clipboard sync is paused until one is renamed.`;
  else if (d && d.online && cs === 'moved') {
    text = `${d.name} is at a new address (${d.host}). Clipboard sync is paused in case it’s a different device.`;
    btn = { label: 'It’s my device', run: () => { api.clipboardTrust(d.name); toast('Clipboard sync resumed'); } };
  } else if (!online) text = `${name} is offline. Open OnSpot on it to send things again.`;
  $('devBanner').classList.toggle('hidden', !text);
  $('devBannerText').textContent = text;
  $('devBannerBtn').classList.toggle('hidden', !btn);
  if (btn) { $('devBannerBtn').textContent = btn.label; $('devBannerBtn').onclick = btn.run; }

  $('devListTitle').textContent = `Shared with ${name}`;
  const recent = items.slice(0, 40);
  reconcile($('devItems'), devCache, recent.map((t) => ({ key: t.id, data: t, make: (x) => makeRow(x, { noDevice: true }) })));
  $('devEmpty').classList.toggle('hidden', recent.length > 0);
}
$('devSendBtn').onclick = async () => { const name = currentDevice; sendPathsTo(name, await api.pickFiles()); };
$('devMoreBtn').onclick = async () => { const name = currentDevice; sendPathsTo(name, await api.pickFolderToSend()); };
$('devClipBtn').onclick = async () => {
  const r = await api.sendClipboardNow(currentDevice);
  toast(r.ok ? 'Clipboard sent' : r.error);
};
function closeDevText() { $('devTextBox').classList.add('hidden'); $('devTextInput').value = ''; }
$('devTextBtn').onclick = () => {
  const box = $('devTextBox');
  if (!box.classList.contains('hidden')) { closeDevText(); return; }
  box.classList.remove('hidden');
  $('devTextInput').focus();
};
$('devTextCancel').onclick = closeDevText;
$('devTextSend').onclick = async () => {
  const text = $('devTextInput').value.trim();
  if (!text) return;
  const name = currentDevice;
  const r = await api.sendText(name, text);
  if (r && r.ok === false) { toast(r.error || 'Could not send'); return; }
  closeDevText();
  flashOutcome(name);
  toast(`Sent to ${name}`);
};
$('devTextInput').addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); $('devTextSend').click(); }
});
$('clipSyncToggle').addEventListener('change', async () => {
  const d = deviceByName(currentDevice);
  if (!d) return;
  const on = $('clipSyncToggle').checked;
  const r = await api.clipboardSync(d.name, on);
  if (on) toast(r && r.ok ? `Asked ${d.name} to allow clipboard sync…` : (r && r.error) || 'Could not send the request');
  else toast(d.clipboardState === 'pending' ? 'Request cancelled' : 'Clipboard sync off');
  render();
});

// ---- sending ----------------------------------------------------------------------------------
async function sendPathsTo(name, paths) {
  paths = (paths || []).filter(Boolean);
  const d = deviceByName(name);
  if (!d || !d.online) { toast(`${name} is offline`); return; }
  if (!paths.length) return;
  const r = await api.sendFiles(name, paths);
  if (r && r.ok === false) toast(r.error || 'Could not send');
  else toast(`Sending to ${name}`);
}

async function sendTrayTo(name) {
  const d = deviceByName(name);
  if (!d) return;
  if (!d.online) { toast(`${name} is offline`); return; }
  if (!tray.length) { await sendPathsTo(name, await api.pickFiles()); return; }
  const items = tray;
  const paths = items.filter((t) => t.type !== 'text').map((t) => t.path);
  const texts = items.filter((t) => t.type === 'text');
  let failed = null;
  if (paths.length) {
    const r = await api.sendFiles(name, paths);
    if (r && r.ok === false) failed = r.error || 'Could not send';
  }
  for (const t of texts) {
    const r = await api.sendText(name, t.text);
    if (r && r.ok === false) failed = r.error;
  }
  if (failed) { toast(failed); return; }
  toast(`Sending ${plural(items.length, 'item')} to ${name}`);
  if (!paths.length) flashOutcome(name);
  tray = [];
  closeSendSheet();
  render();
}

// ---- SEND sheet -------------------------------------------------------------------------------
const trayCache = new Map();
const sheetPeopleCache = new Map();
function openSendSheet() {
  $('sendSheet').classList.remove('hidden');
  renderSheet();
  api.refresh();
}
function closeSendSheet() { $('sendSheet').classList.add('hidden'); closeTextEditor(); }
const sheetOpen = () => !$('sendSheet').classList.contains('hidden');
function renderSheet() {
  if (!sheetOpen()) return;
  const files = tray.filter((t) => t.type !== 'text');
  const texts = tray.filter((t) => t.type === 'text');
  const count = files.reduce((s, t) => s + (t.files || 1), 0);
  const size = files.reduce((s, t) => s + (t.size || 0), 0);
  const parts = [];
  if (count) parts.push(`${plural(count, 'file')} · ${fmtSize(size)}`);
  if (texts.length) parts.push(plural(texts.length, 'message'));
  $('sendTitle').textContent = tray.length ? 'Ready to send' : 'Send';
  $('traySummary').textContent = parts.length ? parts.join(' + ') : 'Files, folders, text or your clipboard';
  $('clearTrayBtn').classList.toggle('hidden', !tray.length);
  $('dropHint').classList.toggle('hidden', tray.length > 0);
  reconcile($('trayList'), trayCache, tray.map((t) => ({ key: t.key, data: t, make: makeTrayItem })));
  const devs = sortedDevices().filter((d) => d.online);
  reconcile($('sendPeople'), sheetPeopleCache, devs.map((d) => ({ key: d.name, data: d, make: (x) => makePerson(x, true) })));
  $('sendPeople').classList.toggle('hidden', !devs.length);
  $('sendNoPeople').classList.toggle('hidden', devs.length > 0);
  $('sendToHint').textContent = tray.length ? 'Click a device to send everything' : 'Click a device to pick files for it';
}

function makeTrayItem(t) {
  const remove = () => { tray = tray.filter((x) => x.key !== t.key); renderSheet(); };
  if (t.type === 'text') {
    return {
      el: el('div', { class: 'tray-item', title: t.text },
        fileIcon('', 'text'),
        el('span', { class: 'meta' }, el('b', { textContent: t.text.replace(/\s+/g, ' ').slice(0, 140) }), el('small', { textContent: `${t.label || 'Message'} · ${t.text.length} characters` })),
        el('button', { class: 'x', title: 'Remove', onclick: remove }, icon('x', 10))),
    };
  }
  const ic = fileIcon(t.name, t.type === 'folder' ? 'folder' : 'file');
  if (t.type === 'file' && isImage(t.name)) {
    thumbFor(`p:${t.path}`, () => api.pathThumbnail(t.path)).then((u) => { if (u) ic.append(el('img', { src: u, alt: '' })); });
  }
  return {
    el: el('div', { class: 'tray-item', title: t.path },
      ic,
      el('span', { class: 'meta' }, el('b', { textContent: t.name }),
        el('small', { textContent: t.type === 'folder' ? `${plural(t.files, 'file')} · ${fmtSize(t.size)}` : fmtSize(t.size) })),
      el('button', { class: 'x', title: 'Remove', onclick: remove }, icon('x', 10))),
  };
}

async function addPaths(paths) {
  paths = (paths || []).filter(Boolean);
  if (!paths.length) return;
  const have = new Set(tray.map((t) => t.path));
  const described = await api.describePaths(paths.filter((p) => !have.has(p)));
  for (const d of described) {
    tray.push({ key: `p:${d.path}`, type: d.isDir ? 'folder' : 'file', path: d.path, name: d.name, size: d.size, files: d.files });
  }
  if (!described.length) toast('Nothing new to add');
  openSendSheet();
}
function addText(text, label) {
  text = String(text || '').trim();
  if (!text) return;
  tray.push({ key: `t:${++textSeq}`, type: 'text', name: label || 'Message', text, label });
  openSendSheet();
}

$('sendBtn').onclick = () => openSendSheet();
$('sendClose').onclick = closeSendSheet;
$('sendSheet').addEventListener('mousedown', (e) => { if (e.target === $('sendSheet')) closeSendSheet(); });
$('addFilesBtn').onclick = async () => addPaths(await api.pickFiles());
$('addFolderBtn').onclick = async () => addPaths(await api.pickFolderToSend());
$('addTextBtn').onclick = () => {
  $('textEditor').classList.remove('hidden');
  $('textInput').focus();
  updateTextCount();
};
$('addClipBtn').onclick = async () => {
  const text = await api.readClipboard();
  if (!text || !text.trim()) { toast('The clipboard has no text'); return; }
  addText(text, 'Clipboard');
  toast('Clipboard added');
};
function updateTextCount() {
  const n = $('textInput').value.length;
  $('textCount').textContent = n ? `${n} characters · Ctrl+Enter to add` : '';
  $('addTextConfirm').disabled = !$('textInput').value.trim();
}
function closeTextEditor() { $('textEditor').classList.add('hidden'); $('textInput').value = ''; }
$('textInput').addEventListener('input', updateTextCount);
$('textInput').addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); $('addTextConfirm').click(); }
});
$('addTextConfirm').onclick = () => { addText($('textInput').value); closeTextEditor(); };
$('cancelTextBtn').onclick = closeTextEditor;
$('clearTrayBtn').onclick = () => { tray = []; renderSheet(); };

// ---- RECEIVED -------------------------------------------------------------------------------
const incomingCache = new Map();
const receivedCache = new Map();
const recvChipCache = new Map();
function renderReceived() {
  $('beaconAvatar').src = avatarSrc(state.self.avatarIndex);
  $('recvName').textContent = state.self.name || '';
  const n = state.network || {};
  $('recvSub').textContent = n.mode === 'hotspot'
    ? 'Others on your hotspot can send to this PC.'
    : (n.name ? `Others on ${n.name} can send files and messages here.` : 'Connect to a Wi‑Fi network so others can find you.');
  $('recvFolder').textContent = settings.receiveDir || '';
  $('recvFolderBtn').title = `${settings.receiveDir || ''}\nClick to change`;
  const nearby = settings.receiveFrom !== 'anyone';
  $('recvNearbyOnly').checked = nearby;
  $('acceptLabel').textContent = nearby ? 'Only devices in your Nearby list' : 'Anyone on the network';
  $('acceptState').textContent = nearby ? 'Nearby only' : 'Anyone';

  const incoming = state.transfers.filter((t) => t.incoming && isActive(t));
  $('incomingSection').classList.toggle('hidden', !incoming.length);
  reconcile($('incomingList'), incomingCache, incoming.map((t) => ({ key: t.id, data: t, make: (x) => makeRow(x) })));

  const all = state.transfers.filter((t) => t.incoming && t.state === 'done');
  const counts = new Map();
  for (const t of all) counts.set(t.device, (counts.get(t.device) || 0) + 1);
  if (recvFilter && !counts.has(recvFilter)) recvFilter = null;
  const chips = counts.size > 1
    ? [{ id: 'all', label: 'All', count: all.length, sel: !recvFilter, onPick: () => { recvFilter = null; renderReceived(); } },
      ...[...counts.entries()].map(([name, c]) => ({ id: name, label: name, avatar: avatarOf(name), count: c, sel: recvFilter === name, onPick: () => { recvFilter = name; renderReceived(); } }))]
    : [];
  reconcile($('recvChips'), recvChipCache, chips.map((c) => ({ key: c.id, data: c, make: makeChip })));
  $('recvChips').classList.toggle('hidden', !chips.length);

  const received = all.filter((t) => !recvFilter || t.device === recvFilter).slice(-48).reverse();
  $('receivedEmpty').classList.toggle('hidden', received.length > 0);
  reconcile($('receivedGrid'), receivedCache, received.map((t) => ({ key: t.id, data: t, make: makeReceivedCard })));
}

function makeReceivedCard(t) {
  const av = el('img', { alt: '', title: t.device });
  const pic = fillMedia(el('div', { class: 'pic' }), t);
  const card = el('button', {
    class: 'pcard',
    title: t.kind === 'text' ? 'Click to copy' : `Open ${t.name}\nRight-click to show in folder`,
    onclick: () => {
      if (t.kind === 'text') { api.copyText(t.text); toast('Copied to clipboard'); } else api.openTransfer(t.id);
    },
    oncontextmenu: (e) => transferMenu(e, t),
  }, pic,
  el('div', { class: 'cap' },
    el('div', {}, el('b', { textContent: t.kind === 'text' ? 'Message' : t.name }),
      el('small', { textContent: `${t.kind === 'text' ? fmtTime(t.ts) : fmtSize(t.size)} · ${fmtDay(t.ts)}` })),
    av));
  return {
    el: card,
    update() { // the sender may change their picture after the file arrived
      const i = String(avatarOf(t.device));
      if (av.dataset.idx !== i) { av.dataset.idx = i; av.src = avatarSrc(i); }
    },
  };
}

function makeChip(c0) {
  let cur = c0;
  const img = el('img', { alt: '' });
  const label = el('span');
  const cnt = el('span', { class: 'cnt' });
  const b = el('button', { class: 'chip', onclick: () => cur.onPick() }, img, label, cnt);
  return {
    el: b,
    update(c) {
      cur = c;
      img.classList.toggle('hidden', !c.avatar);
      if (c.avatar && img.dataset.idx !== String(c.avatar)) { img.dataset.idx = String(c.avatar); img.src = avatarSrc(c.avatar); }
      b.classList.toggle('plain', !c.avatar);
      b.classList.toggle('sel', !!c.sel);
      label.textContent = c.label;
      cnt.textContent = c.count != null ? String(c.count) : '';
    },
  };
}

$('recvFolderBtn').onclick = async () => {
  const d = await api.pickDir();
  if (!d) return;
  settings = await api.saveSettings({ receiveDir: d });
  toast('Save folder changed');
  render();
};
$('recvNearbyOnly').addEventListener('change', async () => {
  settings = await api.saveSettings({ receiveFrom: $('recvNearbyOnly').checked ? 'nearby' : 'anyone' });
  toast($('recvNearbyOnly').checked ? 'Only Nearby devices can send to you' : 'Anyone on the network can send to you');
  render();
});
$('openFolderBtn').onclick = () => api.openReceiveDir();

// ---- rows (activity / receiving now / device page) -----------------------------------------
function actionBtn(name, label, onclick) {
  return el('button', { class: label ? '' : 'icon-only', title: label || name, onclick: (e) => { e.stopPropagation(); onclick(); } }, icon(name, 12), label || null);
}

function rowActions(x) {
  const out = [];
  if (isActive(x)) out.push(actionBtn('x', 'Cancel', () => api.cancelTransfer(x.id)));
  else if (x.kind === 'text') out.push(actionBtn('copy', 'Copy', () => { api.copyText(x.text); toast('Copied to clipboard'); }));
  else if (x.state === 'done' && x.path) {
    if (x.incoming) out.push(actionBtn('open', 'Open', () => api.openTransfer(x.id)));
    out.push(actionBtn('folder', x.incoming ? '' : 'Show', () => api.showTransfer(x.id)));
  } else if ((x.state === 'failed' || x.state === 'cancelled') && !x.incoming && x.path) {
    out.push(actionBtn('retry', 'Retry', () => api.retryTransfer(x.id)));
  }
  return out;
}

function transferMenu(e, x) {
  const file = x.kind === 'file' && x.state === 'done' && x.path;
  nativeMenu(e, [
    x.kind === 'text' ? { id: 'copy', label: 'Copy text' } : null,
    file ? { id: 'open', label: 'Open' } : null,
    file ? { id: 'show', label: 'Show in folder' } : null,
    isActive(x) ? { id: 'cancel', label: 'Cancel' } : null,
    (x.state === 'failed' || x.state === 'cancelled') && !x.incoming && x.path ? { id: 'retry', label: 'Try again' } : null,
    { type: 'separator' },
    { id: 'device', label: `Open ${x.device}` },
  ], {
    copy: () => { api.copyText(x.text); toast('Copied to clipboard'); },
    open: () => api.openTransfer(x.id),
    show: () => api.showTransfer(x.id),
    cancel: () => api.cancelTransfer(x.id),
    retry: () => api.retryTransfer(x.id),
    device: () => openDevice(x.device),
  });
}

function makeRow(t0, opts = {}) {
  const ic = fileIcon(t0.name, t0.kind);
  const title = t0.kind === 'text' ? el('b', { class: 'text-body' }, linkify(t0.text || '')) : el('b', { textContent: t0.name, title: t0.name });
  const sub = el('span', { class: 'sub' });
  const barFill = el('i');
  const bar = el('div', { class: 'bar' }, barFill);
  const acts = el('div', { class: 'acts' });
  const row = el('div', { class: 'row' }, ic, el('span', { class: 'meta' }, title, sub, bar), acts);
  let cur = t0;
  row.addEventListener('contextmenu', (e) => transferMenu(e, cur));
  row.addEventListener('dblclick', () => {
    if (cur.kind === 'text') { api.copyText(cur.text); toast('Copied to clipboard'); } else if (cur.state === 'done' && cur.path) api.openTransfer(cur.id);
  });
  let thumbAsked = false;
  let actKey = '';
  return {
    el: row,
    update(x) {
      cur = x;
      if (isImage(x.name) && x.kind === 'file' && x.state === 'done' && !thumbAsked && (x.incoming || x.path)) {
        thumbAsked = true;
        thumbFor(x.id, () => api.thumbnail(x.id)).then((u) => { if (u) ic.append(el('img', { src: u, alt: '' })); });
      }
      const dir = el('span', { class: `dir ${x.incoming ? 'in' : 'out'}` }, icon(x.incoming ? 'down' : 'up', 10), opts.noDevice ? (x.incoming ? 'Received' : 'Sent') : `${x.incoming ? 'From' : 'To'} ${x.device}`);
      const bits = [];
      if (x.state === 'queued') bits.push(`Waiting · ${fmtSize(x.size)}`);
      else if (x.state === 'transferring') {
        if (x.retrying && !x.bytes) bits.push(`Reconnecting (try ${x.retrying + 1})`);
        else {
          const sp = speedOf([x]);
          bits.push(`${Math.round((x.progress || 0) * 100)}% of ${fmtSize(x.size)}`);
          if (sp) bits.push(`${fmtSize(sp)}/s`, fmtEta((x.size - x.bytes) / sp));
        }
      } else if (x.state === 'failed') bits.push(el('span', { class: 'failed', textContent: `Failed${x.error ? ` — ${x.error}` : ''}` }));
      else if (x.state === 'cancelled') bits.push(el('span', { class: 'failed', textContent: 'Cancelled' }));
      else { if (x.kind !== 'text') bits.push(fmtSize(x.size)); bits.push(fmtTime(x.ts)); }
      const parts = [dir];
      for (const b of bits.filter(Boolean)) parts.push(' · ', b);
      sub.replaceChildren(...parts);
      const active = isActive(x);
      bar.classList.toggle('hidden', !active);
      bar.classList.toggle('queued', x.state === 'queued');
      barFill.style.width = `${Math.round((x.progress || 0) * 100)}%`;
      const k = `${x.state}|${!!x.path}`;
      if (k !== actKey) { actKey = k; acts.replaceChildren(...rowActions(x)); }
    },
  };
}

function makeBatch(data) {
  let open = false;
  const ic = el('div', { class: 'file-ic ft-folder' }, icon('stack', 18));
  const title = el('b');
  const sub = el('span', { class: 'sub' });
  const barFill = el('i');
  const bar = el('div', { class: 'bar' }, barFill);
  const acts = el('div', { class: 'acts' });
  const chev = el('span', { class: 'chev' }, icon('chevron', 12));
  const body = el('div', { class: 'batch-body hidden' });
  const head = el('div', { class: 'row' }, ic, el('span', { class: 'meta' }, title, sub, bar), acts, chev);
  const root = el('div', { class: 'batch' }, head, body);
  const cache = new Map();
  let members = data.members;
  const sync = () => { if (open) reconcile(body, cache, members.map((m) => ({ key: m.id, data: m, make: (x) => makeRow(x, { noDevice: true }) }))); };
  head.onclick = () => { open = !open; root.classList.toggle('open', open); body.classList.toggle('hidden', !open); sync(); };
  return {
    el: root,
    cache,
    update(d) {
      members = d.members;
      const first = members[0];
      const total = members.length;
      const done = members.filter((m) => m.state === 'done').length;
      const failed = members.filter((m) => m.state === 'failed').length;
      const active = members.filter(isActive);
      const bytes = members.reduce((s, m) => s + (m.size || 0), 0);
      const doneBytes = members.reduce((s, m) => s + (m.state === 'done' ? (m.size || 0) : (m.bytes || 0)), 0);
      const progress = bytes ? doneBytes / bytes : 0;
      title.textContent = `${total} files · ${fmtSize(bytes)}`;
      const dir = el('span', { class: `dir ${first.incoming ? 'in' : 'out'}` }, icon(first.incoming ? 'down' : 'up', 10), `${first.incoming ? 'From' : 'To'} ${first.device}`);
      const status = active.length
        ? `${done}/${total} done · ${Math.round(progress * 100)}%`
        : (failed ? el('span', { class: 'failed', textContent: `${done} sent, ${failed} failed` }) : `${fmtTime(first.ts)}`);
      sub.replaceChildren(dir, ' · ', status);
      bar.classList.toggle('hidden', !active.length);
      barFill.style.width = `${Math.round(progress * 100)}%`;
      acts.replaceChildren(...(active.length
        ? [actionBtn('x', 'Cancel all', () => active.forEach((m) => api.cancelTransfer(m.id)))]
        : (failed && !first.incoming ? [actionBtn('retry', 'Retry failed', () => members.filter((m) => m.state === 'failed').forEach((m) => api.retryTransfer(m.id)))] : [])));
      sync();
    },
  };
}

// ---- ACTIVITY ----------------------------------------------------------------------------
const historyCache = new Map();
const filterChipCache = new Map();
const BASE_FILTERS = [
  ['all', 'All', () => true],
  ['sent', 'Sent', (t) => !t.incoming],
  ['received', 'Received', (t) => t.incoming],
  ['text', 'Messages', (t) => t.kind === 'text'],
  ['failed', 'Failed', (t) => t.state === 'failed' || t.state === 'cancelled'],
];
function filterTest(id) {
  if (id.startsWith('dev:')) { const n = id.slice(4); return (t) => t.device === n; }
  return (BASE_FILTERS.find((f) => f[0] === id) || BASE_FILTERS[0])[2];
}
function historyMatches(t) {
  if (!filterTest(historyFilter)(t)) return false;
  if (historySearch) {
    const q = historySearch.toLowerCase();
    if (![t.name, t.text, t.device].some((s) => s && String(s).toLowerCase().includes(q))) return false;
  }
  return true;
}
function renderHistory() {
  const all = state.transfers;
  const devNames = [...new Set(all.map((t) => t.device))];
  if (historyFilter.startsWith('dev:') && !devNames.includes(historyFilter.slice(4))) historyFilter = 'all';
  const chips = [
    ...BASE_FILTERS.map(([id, label, test]) => ({ id, label, count: id === 'all' ? null : all.filter(test).length })).filter((c) => c.count !== 0),
    ...devNames.map((n) => ({ id: `dev:${n}`, label: n, avatar: avatarOf(n), count: all.filter((t) => t.device === n).length })),
  ].map((c) => ({ ...c, sel: c.id === historyFilter, onPick: () => { historyFilter = c.id; renderHistory(); } }));
  reconcile($('historyFilters'), filterChipCache, chips.map((c) => ({ key: c.id, data: c, make: makeChip })));

  const items = all.filter(historyMatches).sort((a, b) => b.ts - a.ts);
  const entries = [];
  const batches = new Map();
  let day = '';
  for (const t of items) {
    if (t.batchId && batches.has(t.batchId)) { batches.get(t.batchId).members.unshift(t); continue; }
    const d = fmtDay(t.ts);
    if (d !== day) {
      day = d;
      entries.push({ key: `day:${d}`, data: d, make: (x) => ({ el: el('div', { class: 'day-h', textContent: x }) }) });
    }
    if (t.batchId) {
      const data = { members: [t] };
      batches.set(t.batchId, data);
      entries.push({ key: `batch:${t.batchId}`, data, make: makeBatch });
    } else {
      entries.push({ key: t.id, data: t, make: (x) => makeRow(x) });
    }
  }
  reconcile($('historyList'), historyCache, entries);
  $('historyEmpty').classList.toggle('hidden', items.length > 0);
  $('historyEmpty').querySelector('b').textContent = all.length ? 'Nothing matches' : 'No transfers here yet';
  $('clearHistoryBtn').disabled = !all.length;
}
$('globalSearch').addEventListener('input', () => {
  historySearch = $('globalSearch').value.trim();
  if (historySearch && page !== 'activity') showPage('activity');
  else if (page === 'activity') { renderHistory(); renderHeader(); }
});
$('globalSearch').addEventListener('keydown', (e) => {
  if (e.key === 'Escape') { e.stopPropagation(); clearSearch(); $('globalSearch').blur(); }
});
function clearSearch() {
  if (!historySearch && !$('globalSearch').value) return;
  $('globalSearch').value = '';
  historySearch = '';
  if (page === 'activity') { renderHistory(); renderHeader(); }
}
$('clearHistoryBtn').onclick = async () => {
  if (!confirm('Clear the transfer history? Files that were received stay on disk.')) return;
  await api.clearHistory();
  toast('History cleared');
};

// ---- dock --------------------------------------------------------------------------------
const DOCK_C = 2 * Math.PI * 15;
$('dockArc').style.strokeDasharray = String(DOCK_C);
function renderDock() {
  const active = state.transfers.filter((t) => isActive(t) && t.kind === 'file');
  $('dock').classList.toggle('hidden', !active.length);
  if (!active.length) return;
  const total = active.reduce((s, t) => s + (t.size || 0), 0);
  const done = active.reduce((s, t) => s + (t.bytes || 0), 0);
  const f = total ? done / total : 0;
  $('dockArc').style.strokeDashoffset = String(DOCK_C * (1 - f));
  $('dockPct').textContent = `${Math.round(f * 100)}%`;
  const ins = active.filter((t) => t.incoming), outs = active.filter((t) => !t.incoming);
  const peers = [...new Set(active.map((t) => t.device))];
  const n = active.length;
  let title;
  if (ins.length && outs.length) title = `${n} transfers in progress`;
  else title = `${outs.length ? 'Sending' : 'Receiving'} ${plural(n, 'file')} ${outs.length ? 'to' : 'from'} ${peers.length > 1 ? `${peers.length} devices` : peers[0]}`;
  $('dockTitle').textContent = title;
  const sp = speedOf(active.filter((t) => t.state === 'transferring'));
  $('dockSub').textContent = `${fmtSize(done)} of ${fmtSize(total)}` + (sp ? ` · ${fmtSize(sp)}/s · ${fmtEta((total - done) / sp)}` : '');
}
$('dock').onclick = () => {
  const active = state.transfers.filter(isActive);
  const peers = [...new Set(active.map((t) => t.device))];
  if (active.length && active.every((t) => t.incoming)) showPage('received');
  else if (peers.length === 1) openDevice(peers[0]);
  else showPage('activity');
};

// ---- clipboard requests ----------------------------------------------------------------------
let lastRequestsKey = '';
function renderRequests() {
  const key = state.pendingRequests.map((n) => `${n}:${avatarOf(n)}`).join(',');
  if (key === lastRequestsKey) return;
  lastRequestsKey = key;
  const box = $('requests');
  box.replaceChildren();
  for (const name of state.pendingRequests) {
    box.append(el('div', { class: 'req' },
      el('img', { src: avatarSrc(avatarOf(name)), alt: '' }),
      el('div', {},
        el('p', {}, el('b', { textContent: name }), ' wants to sync clipboards. Text you copy on either device will appear on the other.'),
        el('div', { class: 'btns' },
          el('button', { class: 'btn sm', textContent: 'Decline', onclick: () => api.clipboardRespond(name, false) }),
          el('button', { class: 'btn sm primary', textContent: 'Allow', onclick: () => api.clipboardRespond(name, true) })))));
  }
}

// ---- progress (lightweight updates while bytes flow) ------------------------------------------
let progressFrame = 0;
const pendingDevices = new Set();
function applyProgress(p) {
  const t = state.transfers.find((x) => x.id === p.id);
  if (!t) return;
  Object.assign(t, p);
  for (const cache of [incomingCache, devCache, historyCache]) {
    const v = cache.get(t.id);
    if (v && v.update) v.update(t);
  }
  if (t.batchId) {
    const b = historyCache.get(`batch:${t.batchId}`);
    if (b) b.update({ members: state.transfers.filter((x) => x.batchId === t.batchId && historyMatches(x)).sort((a, c) => a.ts - c.ts) });
  }
  pendingDevices.add(t.device);
  if (!progressFrame) {
    progressFrame = requestAnimationFrame(() => {
      progressFrame = 0;
      renderDock();
      for (const name of pendingDevices) {
        const d = deviceByName(name);
        for (const cache of [peopleCache, sheetPeopleCache, sideCache]) { const v = cache.get(name); if (v && d) v.update(d); }
        const c = collectionCache.get(name);
        if (c) c.update({ name, items: state.transfers.filter((x) => x.device === name).sort((a, b) => b.ts - a.ts) });
      }
      pendingDevices.clear();
    });
  }
}

// ---- drag & drop --------------------------------------------------------------------------
function hasFiles(e) { return e.dataTransfer && [...e.dataTransfer.types].includes('Files'); }
function endDrag() { dragDepth = 0; document.body.classList.remove('dragging'); $('dropOverlay').classList.add('hidden'); }
function dropTargetDevice() {
  if (page !== 'device' || sheetOpen()) return null;
  const d = deviceByName(currentDevice);
  return d && d.online ? d.name : null;
}
window.addEventListener('dragenter', (e) => {
  if (!hasFiles(e)) return;
  e.preventDefault();
  if (dragDepth++ === 0) {
    const target = dropTargetDevice();
    const online = state.devices.some((d) => d.online);
    if (target) {
      $('dropText').textContent = `Drop to send to ${target}`;
      $('dropSub').textContent = 'Sending starts straight away';
    } else {
      $('dropText').textContent = 'Drop to add to your selection';
      $('dropSub').textContent = page === 'home' && online ? 'Or drop on someone to send straight away' : 'Then pick a device to send to';
    }
    document.body.classList.add('dragging');
    $('dropOverlay').classList.remove('hidden');
  }
});
window.addEventListener('dragover', (e) => e.preventDefault());
window.addEventListener('dragleave', (e) => {
  if (!hasFiles(e)) return;
  if (--dragDepth <= 0) endDrag();
});
window.addEventListener('drop', (e) => {
  e.preventDefault();
  endDrag();
  const paths = [...e.dataTransfer.files].map((f) => api.pathForFile(f)).filter(Boolean);
  const target = dropTargetDevice();
  if (target) sendPathsTo(target, paths);
  else addPaths(paths);
});

// ---- toolbar & shortcuts ---------------------------------------------------------------------
$('refreshBtn').onclick = () => {
  api.refresh();
  const b = $('refreshBtn');
  b.classList.remove('spin'); void b.offsetWidth; b.classList.add('spin');
  toast('Scanning…');
};
window.addEventListener('keydown', (e) => {
  const mod = e.ctrlKey || e.metaKey;
  if (e.key === 'Escape') {
    if (!$('modal').classList.contains('hidden')) { if (settings.onboarded) $('modal').classList.add('hidden'); }
    else if (!$('textEditor').classList.contains('hidden')) closeTextEditor();
    else if (sheetOpen()) closeSendSheet();
    else if (!$('devTextBox').classList.contains('hidden')) closeDevText();
    else if (page === 'activity' && historySearch) clearSearch();
    else if (page === 'device') showPage('home');
  }
  if (e.key === 'F5') { e.preventDefault(); $('refreshBtn').click(); }
  if (mod && ['1', '2', '3'].includes(e.key)) { e.preventDefault(); showPage(['home', 'received', 'activity'][Number(e.key) - 1]); }
  if (mod && e.key.toLowerCase() === 'o') { e.preventDefault(); $('addFilesBtn').click(); }
  if (mod && e.key === ',') { e.preventDefault(); openSettings(false); }
  if (mod && e.key.toLowerCase() === 'f') { e.preventDefault(); $('globalSearch').focus(); $('globalSearch').select(); }
});
document.addEventListener('paste', (e) => {
  if (e.target.closest('input, textarea') || !$('modal').classList.contains('hidden')) return;
  const text = e.clipboardData && e.clipboardData.getData('text');
  if (text && text.trim()) { addText(text, 'Pasted text'); toast('Text added'); }
});

// ---- settings / onboarding ------------------------------------------------------------------
function openSettings(first) {
  $('modalTitle').textContent = first ? 'Welcome to OnSpot' : 'Your device';
  $('modalSub').textContent = first ? 'Choose how this PC appears to your phone' : 'How others see this PC';
  $('cancelBtn').classList.toggle('hidden', !!first);
  $('setName').value = settings.deviceName;
  $('setDir').value = settings.receiveDir;
  $('setStart').checked = !!settings.startWithWindows;
  $('setNearbyOnly').checked = settings.receiveFrom !== 'anyone';
  pickedDir = null;
  editAvatar = settings.avatarIndex;
  $('previewAvatar').src = avatarSrc(editAvatar);
  setMode(settings.connectionMode || 'wifi');
  loadNetworks(settings.networkInterface || 'auto');
  const grid = $('avatarGrid');
  grid.replaceChildren();
  for (let i = 1; i <= 11; i++) {
    grid.append(el('img', {
      src: avatarSrc(i), alt: '', class: i === editAvatar ? 'sel' : '',
      onclick: () => {
        editAvatar = i;
        $('previewAvatar').src = avatarSrc(i);
        [...grid.children].forEach((c, j) => c.classList.toggle('sel', j + 1 === i));
      },
    }));
  }
  applyTheme();
  $('modal').classList.remove('hidden');
  $('setName').focus();
}
function setMode(m) {
  editMode = m;
  document.querySelectorAll('.mode').forEach((b) => b.classList.toggle('sel', b.dataset.mode === m));
  $('netRow').classList.toggle('hidden', m !== 'wifi');
}
document.querySelectorAll('.mode').forEach((b) => { b.onclick = () => setMode(b.dataset.mode); });
async function loadNetworks(current) {
  const nets = await api.listNetworks();
  const sel = $('setNet');
  sel.replaceChildren(el('option', { value: 'auto', textContent: 'Automatic (best Wi‑Fi adapter)' }));
  for (const n of nets) sel.append(el('option', { value: n.id, textContent: `${n.name} · ${n.ip}${n.wifi ? ' (Wi‑Fi)' : ''}` }));
  sel.value = [...sel.options].some((o) => o.value === current) ? current : 'auto';
}
$('meBtn').onclick = () => openSettings(false);
$('settingsBtn').onclick = () => openSettings(false);
$('cancelBtn').onclick = () => $('modal').classList.add('hidden');
$('modal').addEventListener('mousedown', (e) => { if (e.target === $('modal') && settings.onboarded) $('modal').classList.add('hidden'); });
$('dirBtn').onclick = async () => { const d = await api.pickDir(); if (d) { pickedDir = d; $('setDir').value = d; } };
$('saveBtn').onclick = async () => {
  const patch = {
    deviceName: $('setName').value, avatarIndex: editAvatar,
    startWithWindows: $('setStart').checked, onboarded: true,
    connectionMode: editMode, networkInterface: $('setNet').value,
    receiveFrom: $('setNearbyOnly').checked ? 'nearby' : 'anyone',
  };
  if (pickedDir) patch.receiveDir = pickedDir;
  settings = await api.saveSettings(patch);
  $('modal').classList.add('hidden');
  toast('Saved');
  render();
};

api.onState((s) => { state = s; render(); });
api.onProgress(applyProgress);
(async () => {
  const init = await api.init();
  state = init.state;
  settings = init.settings;
  document.documentElement.classList.toggle('mica', !!init.mica);
  accentHex = init.accent || null;
  applyAccent();
  $('versionTxt').textContent = `OnSpot ${init.version}`;
  showPage('home');
  if (!settings.onboarded) openSettings(true);
})();
