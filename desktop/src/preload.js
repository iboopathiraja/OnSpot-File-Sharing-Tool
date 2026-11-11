'use strict';
const { contextBridge, ipcRenderer, webUtils } = require('electron');

const invoke = (ch) => (...a) => ipcRenderer.invoke(ch, ...a);

contextBridge.exposeInMainWorld('onspot', {
  init: invoke('init'),
  listNetworks: invoke('list-networks'),
  refresh: invoke('refresh'),
  pickFiles: invoke('pick-files'),
  pickFolderToSend: invoke('pick-folder-to-send'),
  describePaths: invoke('describe-paths'),
  pathThumbnail: invoke('path-thumbnail'),
  readClipboard: invoke('read-clipboard'),
  sendFiles: invoke('send-files'),
  sendText: invoke('send-text'),
  sendClipboardNow: invoke('send-clipboard-now'),
  clipboardSync: invoke('clipboard-sync'),
  clipboardTrust: invoke('clipboard-trust'),
  clipboardRespond: invoke('clipboard-respond'),
  copyText: invoke('copy-text'),
  openTransfer: invoke('open-transfer'),
  showTransfer: invoke('show-transfer'),
  openReceiveDir: invoke('open-receive-dir'),
  thumbnail: invoke('thumbnail'),
  openExternal: invoke('open-external'),
  clearHistory: invoke('clear-history'),
  retryTransfer: invoke('retry-transfer'),
  cancelTransfer: invoke('cancel-transfer'),
  pickDir: invoke('pick-dir'),
  saveSettings: invoke('save-settings'),
  setTheme: invoke('set-theme'),
  popupMenu: invoke('popup-menu'),
  pathForFile: (file) => webUtils.getPathForFile(file),
  onAccent: (cb) => ipcRenderer.on('accent', (_e, a) => cb(a)),
  onState: (cb) => ipcRenderer.on('state', (_e, s) => cb(s)),
  onProgress: (cb) => ipcRenderer.on('progress', (_e, p) => cb(p)),
});
