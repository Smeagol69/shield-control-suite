const { contextBridge, ipcRenderer, webUtils } = require('electron');

contextBridge.exposeInMainWorld('shield', {
  getState: () => ipcRenderer.invoke('shield:get-state'),
  reconnect: () => ipcRenderer.invoke('shield:reconnect'),
  getConfig: () => ipcRenderer.invoke('config:get'),

  getStatus: () => ipcRenderer.invoke('status:get'),
  refreshStatus: () => ipcRenderer.invoke('status:refresh'),
  screenshot: () => ipcRenderer.invoke('shield:screenshot'),
  reboot: () => ipcRenderer.invoke('shield:reboot'),

  kodiPullLog: () => ipcRenderer.invoke('kodi:pull-log'),
  kodiStart: () => ipcRenderer.invoke('kodi:start'),
  kodiStop: () => ipcRenderer.invoke('kodi:stop'),

  scrcpyLaunch: () => ipcRenderer.invoke('scrcpy:launch'),
  scrcpyStop: () => ipcRenderer.invoke('scrcpy:stop'),
  scrcpyGet: () => ipcRenderer.invoke('scrcpy:get'),

  listDir: (p) => ipcRenderer.invoke('fs:list', p),
  homeDir: () => ipcRenderer.invoke('fs:home'),
  pull: (remotePath, name, size) => ipcRenderer.invoke('fs:pull', { remotePath, name, size }),
  send: (paths, remoteDir) => ipcRenderer.invoke('fs:send', { paths, remoteDir }),
  reveal: (p) => ipcRenderer.invoke('reveal', p),

  // dropped DOM File -> absolute Windows path (File.path was removed in Electron 32+)
  filePath: (file) => webUtils.getPathForFile(file),

  onState: (cb) => ipcRenderer.on('shield:state', (_e, s) => cb(s)),
  onStatus: (cb) => ipcRenderer.on('status:update', (_e, s) => cb(s)),
  onScrcpy: (cb) => ipcRenderer.on('scrcpy:state', (_e, s) => cb(s)),
  onTransfer: (cb) => ipcRenderer.on('transfer:update', (_e, t) => cb(t)),
});
