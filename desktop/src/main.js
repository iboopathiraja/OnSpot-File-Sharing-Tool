'use strict';
const path = require('path');
const {
  app, BrowserWindow, Tray, Menu, Notification, ipcMain, dialog, shell, clipboard, nativeImage, powerMonitor,
  nativeTheme, systemPreferences,
} = require('electron');
const os = require('os');
const { Settings, defaultsFor } = require('./core/settings');
const { Engine } = require('./core/engine');
const { listNetworks } = require('./core/lan');
const fs = require('fs');
const { isDangerousFile, expandPaths } = require('./core/files');

const ICON = path.join(__dirname, '..', 'build', 'icon.png');
let win = null;
let tray = null;
let engine = null;
let settings = null;
let quitting = false;
let lastPickedDir = null; // the only new receive folder save-settings will accept
const selectionPaths = new Set(); // files/folders the user picked or dropped into the send tray
const liveNotifications = new Set(); // keeps notifications alive so their click handler still fires

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => showWindow());
  // Any quit (tray, Windows shutdown/log-off, updater) must get past the hide-on-close handler.
  app.on('before-quit', () => { quitting = true; });
  app.on('will-quit', () => { if (engine) engine.stop(); });
  app.whenReady().then(boot);
}

async function boot() {
  app.setAppUserModelId('com.beyondzero.onspot.desktop');
  settings = new Settings(app.getPath('userData'), defaultsFor(app.getPath('downloads')));
  engine = new Engine({
    settings,
    clipboard: { readText: () => clipboard.readText(), writeText: (t) => clipboard.writeText(t) },
    historyFile: path.join(app.getPath('userData'), 'history.json'),
  });
  engine.on('state', (s) => send('state', s));
  engine.on('progress', (p) => send('progress', p));
  engine.on('notify', notify);
  engine.on('batch-status', (text) => tray && tray.setToolTip(text || 'OnSpot'));
  registerIpc();
  createTray();
  createWindow();
  powerMonitor.on('resume', () => { engine.checkNetwork(); engine.refresh(); });
  // Follow Windows when the theme or accent colour changes while OnSpot is open.
  nativeTheme.on('updated', () => {
    if (win && !win.isDestroyed()) { try { win.setTitleBarOverlay(titleBarOverlay()); } catch { /* ignore */ } }
  });
  systemPreferences.on('accent-color-changed', () => send('accent', accentColor()));
  await startEngine();
  applyLoginItem();
}

/** Starting can fail (e.g. the firewall or another program holding the network); offer a retry. */
async function startEngine() {
  for (;;) {
    try {
      await engine.start();
      return;
    } catch (e) {
      engine.stop();
      const choice = dialog.showMessageBoxSync({
        type: 'error',
        title: 'OnSpot',
        message: 'OnSpot could not start',
        detail: `${e && e.message}\n\nCheck that your network is connected and that OnSpot is allowed through Windows Firewall.`,
        buttons: ['Try again', 'Quit'],
        defaultId: 0,
        cancelId: 1,
      });
      if (choice === 1) { app.quit(); return; }
    }
  }
}

function send(channel, payload) {
  if (win && !win.isDestroyed()) win.webContents.send(channel, payload);
}

/** Mica (the translucent Windows 11 backdrop) needs Windows 11 22H2, build 22621 or newer. */
const MICA = process.platform === 'win32' && Number(os.release().split('.')[2] || 0) >= 22621;
const TITLE_BAR_HEIGHT = 48;

function titleBarOverlay() {
  return { color: '#00000000', symbolColor: nativeTheme.shouldUseDarkColors ? '#ffffff' : '#1a1a1a', height: TITLE_BAR_HEIGHT };
}

function accentColor() {
  try {
    const a = process.platform === 'win32' ? systemPreferences.getAccentColor() : '';
    return /^[0-9a-f]{6}/i.test(a) ? `#${a.slice(0, 6)}` : null;
  } catch { return null; }
}

function createWindow() {
  win = new BrowserWindow({
    width: 1120, height: 740, minWidth: 820, minHeight: 560,
    show: !(settings.get('onboarded') && settings.get('startMinimized') && process.argv.includes('--hidden')),
    // Windows 11 look: our own title bar (Windows still draws the min/max/close buttons) over
    // a Mica backdrop. Older Windows gets a plain solid background instead.
    titleBarStyle: 'hidden',
    titleBarOverlay: titleBarOverlay(),
    backgroundMaterial: MICA ? 'mica' : undefined,
    backgroundColor: MICA ? '#00000000' : (nativeTheme.shouldUseDarkColors ? '#202020' : '#f3f3f3'),
    title: 'OnSpot',
    icon: nativeImage.createFromPath(ICON),
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });
  win.setMenu(null);
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  win.webContents.on('will-navigate', (e) => e.preventDefault());
  win.on('close', (e) => {
    if (!quitting) { e.preventDefault(); win.hide(); }
  });
  // Probe again immediately whenever the window comes back into view — discovery keeps
  // running the whole time regardless, but after being minimized/backgrounded for a while
  // this gets the list fresh right away instead of waiting for the next scheduled scan.
  win.on('focus', () => engine && engine.refresh());
  win.on('show', () => engine && engine.refresh());
}

function showWindow() {
  if (!win) return;
  if (win.isMinimized()) win.restore();
  win.show();
  win.focus();
}

function createTray() {
  tray = new Tray(nativeImage.createFromPath(ICON).resize({ width: 32, height: 32 }));
  tray.setToolTip('OnSpot');
  tray.on('click', showWindow);
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: 'Open OnSpot', click: showWindow },
    { label: 'Open received files', click: openReceiveDir },
    { type: 'separator' },
    { label: 'Quit', click: () => app.quit() },
  ]));
}

function notify({ title, body, transferId }) {
  if (!Notification.isSupported()) return;
  if (win && win.isVisible() && win.isFocused()) return;
  const n = new Notification({ title, body, icon: nativeImage.createFromPath(ICON) });
  liveNotifications.add(n);
  const forget = () => liveNotifications.delete(n);
  n.on('click', () => {
    forget();
    const rec = transferId && engine.getTransfer(transferId);
    if (rec && rec.path) shell.showItemInFolder(rec.path); else showWindow();
  });
  n.on('close', forget);
  n.on('failed', forget);
  n.show();
  setTimeout(forget, 10 * 60 * 1000).unref();
}

function applyLoginItem() {
  if (!app.isPackaged) return;
  app.setLoginItemSettings({ openAtLogin: !!settings.get('startWithWindows'), args: ['--hidden'] });
}

function openReceiveDir() {
  const dir = settings.get('receiveDir');
  fs.promises.mkdir(dir, { recursive: true }).catch(() => {}).then(() => shell.openPath(dir));
}

/** A completed transfer that has a file on disk, looked up by id (the UI never passes paths). */
function transferFile(id) {
  const rec = engine.getTransfer(String(id));
  return rec && rec.kind === 'file' && rec.state === 'done' && rec.path ? rec : null;
}

/** Received programs/scripts ask first, so one stray click can't run something from the network. */
async function openTransfer(id) {
  const rec = transferFile(id);
  if (!rec) return;
  if (rec.incoming && isDangerousFile(rec.path)) {
    const { response } = await dialog.showMessageBox(win, {
      type: 'warning',
      title: 'Open this file?',
      message: `"${path.basename(rec.path)}" can run programs on this PC.`,
      detail: `It was received from ${rec.device}. Only open it if you trust where it came from.`,
      buttons: ['Show in folder', 'Open anyway', 'Cancel'],
      defaultId: 0,
      cancelId: 2,
      noLink: true,
    });
    if (response === 0) shell.showItemInFolder(rec.path);
    if (response !== 1) return;
  }
  await shell.openPath(rec.path);
}

function thumbnailOf(p) {
  try {
    if (!/\.(png|jpe?g|gif|bmp|webp)$/i.test(p)) return null;
    const img = nativeImage.createFromPath(p);
    if (img.isEmpty()) return null;
    const { width } = img.getSize();
    return img.resize({ width: Math.min(width, 240) }).toDataURL();
  } catch { return null; }
}

/** Folders that must never receive files: anything under Start Menu (incl. Startup) or Windows. */
function isForbiddenReceiveDir(dir) {
  const norm = path.resolve(dir).toLowerCase() + path.sep;
  const startMenu = path.join(app.getPath('appData'), 'Microsoft', 'Windows', 'Start Menu').toLowerCase() + path.sep;
  const windir = (process.env.WINDIR || 'C:\\Windows').toLowerCase() + path.sep;
  const programData = (process.env.PROGRAMDATA || 'C:\\ProgramData').toLowerCase() + path.sep;
  return norm.startsWith(startMenu) || norm.startsWith(windir) || norm.startsWith(programData);
}

function registerIpc() {
  ipcMain.handle('init', () => ({
    state: engine.snapshot(), settings: settings.all(), version: app.getVersion(),
    mica: MICA, accent: accentColor(), titleBarHeight: TITLE_BAR_HEIGHT,
  }));
  // 'system' | 'light' | 'dark' — also recolours Mica and the window buttons to match.
  ipcMain.handle('set-theme', (_e, theme) => {
    nativeTheme.themeSource = ['light', 'dark'].includes(theme) ? theme : 'system';
    if (win && !win.isDestroyed()) {
      try { win.setTitleBarOverlay(titleBarOverlay()); } catch { /* not supported */ }
      if (!MICA) win.setBackgroundColor(nativeTheme.shouldUseDarkColors ? '#202020' : '#f3f3f3');
    }
    return nativeTheme.shouldUseDarkColors;
  });
  // Native right-click menu. Items: {id, label} or {type:'separator'}; resolves to the picked id.
  ipcMain.handle('popup-menu', (_e, items) => new Promise((resolve) => {
    if (!win || !Array.isArray(items)) { resolve(null); return; }
    let picked = null;
    const template = items.slice(0, 20).map((it) => (it && it.type === 'separator'
      ? { type: 'separator' }
      : { label: String((it && it.label) || ''), enabled: !(it && it.enabled === false), click: () => { picked = String(it.id); } }));
    Menu.buildFromTemplate(template).popup({ window: win, callback: () => setImmediate(() => resolve(picked)) });
  }));
  ipcMain.handle('list-networks', () => listNetworks().filter((n) => !n.virtual || n.wifi).map(({ id, name, ip, wifi }) => ({ id, name, ip, wifi })));
  ipcMain.handle('refresh', () => engine.refresh());

  ipcMain.handle('pick-files', async () => {
    const r = await dialog.showOpenDialog(win, { properties: ['openFile', 'multiSelections'] });
    return r.canceled ? [] : r.filePaths;
  });
  ipcMain.handle('pick-folder-to-send', async () => {
    const r = await dialog.showOpenDialog(win, { properties: ['openDirectory'] });
    return r.canceled ? [] : r.filePaths;
  });
  // Names/sizes for the "what to send" tray. Paths described here (picked or dropped by the
  // user) are also the only non-transfer paths the tray may request thumbnails for.
  ipcMain.handle('describe-paths', async (_e, paths) => {
    if (!Array.isArray(paths)) return [];
    const out = [];
    for (const p of paths.filter((x) => typeof x === 'string').slice(0, 500)) {
      try {
        const st = await fs.promises.stat(p);
        if (st.isDirectory()) {
          const files = await expandPaths([p]);
          out.push({ path: p, name: path.basename(p), isDir: true, files: files.length, size: files.reduce((s, f) => s + f.size, 0) });
        } else if (st.isFile()) {
          out.push({ path: p, name: path.basename(p), isDir: false, files: 1, size: st.size });
        } else continue;
        selectionPaths.add(p);
      } catch { /* vanished or unreadable: leave it out */ }
    }
    return out;
  });
  ipcMain.handle('path-thumbnail', (_e, p) => (typeof p === 'string' && selectionPaths.has(p) ? thumbnailOf(p) : null));
  ipcMain.handle('read-clipboard', () => clipboard.readText());
  ipcMain.handle('send-files', async (_e, name, paths) => {
    if (typeof name !== 'string' || !Array.isArray(paths)) return { ok: false };
    try {
      await engine.sendFiles(name, paths.filter((p) => typeof p === 'string'));
      return { ok: true };
    } catch (e) { return { ok: false, error: e.message }; }
  });
  ipcMain.handle('send-text', async (_e, name, text) => {
    try { await engine.sendText(String(name), String(text)); return { ok: true }; } catch (e) { return { ok: false, error: e.message }; }
  });
  ipcMain.handle('clipboard-sync', (_e, name, on) => engine.setClipboardSync(String(name), !!on));
  ipcMain.handle('clipboard-trust', (_e, name) => engine.trustClipboardHost(String(name)));
  ipcMain.handle('clipboard-respond', (_e, name, approved) => engine.respondClipboardRequest(String(name), !!approved));
  ipcMain.handle('copy-text', (_e, text) => clipboard.writeText(String(text)));
  ipcMain.handle('send-clipboard-now', async (_e, name) => {
    const text = clipboard.readText();
    if (!text) return { ok: false, error: 'The clipboard has no text' };
    try { await engine.sendText(String(name), text); return { ok: true }; } catch (e) { return { ok: false, error: e.message }; }
  });
  ipcMain.handle('open-transfer', (_e, id) => openTransfer(id));
  ipcMain.handle('show-transfer', (_e, id) => { const rec = transferFile(id); if (rec) shell.showItemInFolder(rec.path); });
  ipcMain.handle('open-receive-dir', () => openReceiveDir());
  ipcMain.handle('thumbnail', (_e, id) => {
    const rec = transferFile(id);
    return rec ? thumbnailOf(rec.path) : null;
  });
  ipcMain.handle('open-external', (_e, u) => { try { const x = new URL(String(u)); if (x.protocol === 'http:' || x.protocol === 'https:') return shell.openExternal(x.href); } catch { /* ignore */ } });
  ipcMain.handle('clear-history', () => engine.clearHistory());
  ipcMain.handle('retry-transfer', (_e, id) => engine.retryTransfer(String(id)));
  ipcMain.handle('cancel-transfer', (_e, id) => engine.cancelTransfer(String(id)));

  ipcMain.handle('pick-dir', async () => {
    const r = await dialog.showOpenDialog(win, { properties: ['openDirectory', 'createDirectory'] });
    if (r.canceled) return null;
    const dir = r.filePaths[0];
    if (isForbiddenReceiveDir(dir)) {
      dialog.showMessageBox(win, { type: 'warning', message: 'Pick a different folder', detail: 'Received files can’t be saved into system or Start Menu folders.' });
      return null;
    }
    lastPickedDir = dir;
    return dir;
  });
  ipcMain.handle('save-settings', (_e, patch) => {
    if (!patch || typeof patch !== 'object') return settings.all();
    const allowed = {};
    if (typeof patch.deviceName === 'string' && patch.deviceName.trim()) allowed.deviceName = patch.deviceName.trim().slice(0, 40);
    if (Number.isInteger(patch.avatarIndex) && patch.avatarIndex >= 1 && patch.avatarIndex <= 11) allowed.avatarIndex = patch.avatarIndex;
    // Only a folder the user just picked in the native dialog (never an arbitrary string).
    if (typeof patch.receiveDir === 'string' && patch.receiveDir === lastPickedDir) allowed.receiveDir = patch.receiveDir;
    if (patch.connectionMode === 'wifi' || patch.connectionMode === 'hotspot') allowed.connectionMode = patch.connectionMode;
    if (typeof patch.networkInterface === 'string' && patch.networkInterface.length < 100) allowed.networkInterface = patch.networkInterface;
    if (patch.receiveFrom === 'nearby' || patch.receiveFrom === 'anyone') allowed.receiveFrom = patch.receiveFrom;
    for (const k of ['onboarded', 'startWithWindows', 'startMinimized']) if (typeof patch[k] === 'boolean') allowed[k] = patch[k];
    const identityChanged = ('deviceName' in allowed && allowed.deviceName !== settings.get('deviceName'))
      || ('avatarIndex' in allowed && allowed.avatarIndex !== settings.get('avatarIndex'))
      || ('connectionMode' in allowed && allowed.connectionMode !== settings.get('connectionMode'))
      || ('networkInterface' in allowed && allowed.networkInterface !== settings.get('networkInterface'));
    settings.set(allowed);
    if (identityChanged) engine.restartDiscovery();
    applyLoginItem();
    return settings.all();
  });
}
