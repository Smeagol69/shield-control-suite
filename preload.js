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
  deletePath: (remotePath) => ipcRenderer.invoke('fs:delete', remotePath),
  execShell: (cmd, root) => ipcRenderer.invoke('shell:exec', { cmd, root }),
  morpheStatus: () => ipcRenderer.invoke('morphe:status'),
  morpheOpen: () => ipcRenderer.invoke('morphe:open'),
  appsCatalog: () => ipcRenderer.invoke('apps:catalog'),
  appsOpen: (pkg) => ipcRenderer.invoke('apps:open', pkg),
  appsUninstall: (pkg) => ipcRenderer.invoke('apps:uninstall', pkg),
  appsPage: (url) => ipcRenderer.invoke('apps:page', url),
  appsInstallUrl: (url) => ipcRenderer.invoke('apps:install-url', url),
  appsList: (includeSystem) => ipcRenderer.invoke('apps:list', includeSystem),
  appsManage: (pkg, action) => ipcRenderer.invoke('apps:manage', { pkg, action }),
  appsTrim: () => ipcRenderer.invoke('apps:trim'),
  inputKey: (code) => ipcRenderer.invoke('input:key', code),
  inputText: (text) => ipcRenderer.invoke('input:text', text),
  reveal: (p) => ipcRenderer.invoke('reveal', p),

  // dropped DOM File -> absolute Windows path (File.path was removed in Electron 32+)
  filePath: (file) => webUtils.getPathForFile(file),

  onState: (cb) => ipcRenderer.on('shield:state', (_e, s) => cb(s)),
  onStatus: (cb) => ipcRenderer.on('status:update', (_e, s) => cb(s)),
  onScrcpy: (cb) => ipcRenderer.on('scrcpy:state', (_e, s) => cb(s)),
  onTransfer: (cb) => ipcRenderer.on('transfer:update', (_e, t) => cb(t)),
});
