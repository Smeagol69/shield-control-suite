const { app, BrowserWindow, ipcMain, shell, screen } = require('electron');
const path = require('path');
const fs = require('fs');
const https = require('https');
const http = require('http');
const { spawn, execFile, execFileSync } = require('child_process');
const { autoUpdater } = require('electron-updater');
const { resolvePullRoot, sanitizeName, uniqueLocalPath } = require('./lib/transfer-paths');
const {
  clampUpdatePercent,
  detectUpdateMode,
  updateErrorMessage,
} = require('./lib/update-policy');

// ---------------------------------------------------------------------------
// Bundled binaries (vendor/ ships next to the app via electron-builder
// extraResources; in dev it sits in the project root)
// ---------------------------------------------------------------------------
const VENDOR_DIR = app.isPackaged
  ? path.join(process.resourcesPath, 'vendor')
  : path.join(__dirname, 'vendor');

const ADB_EXE = path.join(VENDOR_DIR, 'platform-tools', 'adb.exe');
const SCRCPY_DIR = path.join(VENDOR_DIR, 'scrcpy');
const SCRCPY_EXE = path.join(SCRCPY_DIR, 'scrcpy.exe');

const binaries = {
  adb: fs.existsSync(ADB_EXE),
  scrcpy: fs.existsSync(SCRCPY_EXE),
};

// ---------------------------------------------------------------------------
// Tiny persistent config (zero-config: sane defaults, remembers as you go)
// ---------------------------------------------------------------------------
const DEFAULTS = {
  ip: '10.0.0.6',
  port: 5555,
  lastDir: '/sdcard/Download',
  pushDir: '/sdcard/Download',            // default landing folder for dropped files
  kodiDropDir: '/sdcard/Download/KodiDrop',
  pullDir: null,                          // null → this PC's Downloads\KodiDrop folder
  backupDir: '/sdcard/backup',            // scanned for "last backup" status
  scrcpyBitrate: '8M',                    // lower = lower latency over WiFi (e.g. '4M'); higher = sharper
  bounds: null,
};
let config = { ...DEFAULTS };
const configFile = () => path.join(app.getPath('userData'), 'config.json');

function loadConfig() {
  try {
    config = { ...DEFAULTS, ...JSON.parse(fs.readFileSync(configFile(), 'utf8')) };
  } catch {
    // first run — defaults are fine
  }
}

// persistence is debounced+async so the hot path (a config write per folder
// navigation) never blocks on synchronous disk I/O; flushConfigSync() drains it
// on shutdown so nothing is lost.
let saveTimer = null;
let savePending = false;

function writeConfig(sync) {
  savePending = false;
  const json = JSON.stringify(config, null, 2);
  try {
    fs.mkdirSync(path.dirname(configFile()), { recursive: true });
    if (sync) fs.writeFileSync(configFile(), json);
    else fs.writeFile(configFile(), json, () => {});
  } catch {}
}

function saveConfig(patch) {
  const before = JSON.stringify(config);
  Object.assign(config, patch);
  if (JSON.stringify(config) === before) return; // e.g. re-listing the same folder
  savePending = true;
  if (!saveTimer) saveTimer = setTimeout(() => { saveTimer = null; if (savePending) writeConfig(false); }, 400);
}

function flushConfigSync() {
  if (saveTimer) { clearTimeout(saveTimer); saveTimer = null; }
  if (savePending) writeConfig(true);
}

loadConfig();

const wifiSerial = () => `${config.ip}:${config.port}`;
// quoting for paths that pass through the on-device shell (adb shell ...)
const shq = (p) => `'${String(p).replace(/'/g, `'\\''`)}'`;
const lastLine = (s) =>
  String(s || '').trim().split(/\r?\n/).map((l) => l.trim()).filter(Boolean).pop() || '';

// ---------------------------------------------------------------------------
// adb plumbing
// ---------------------------------------------------------------------------
function runAdb(args, { timeoutMs = 15000 } = {}) {
  return new Promise((resolve) => {
    if (!binaries.adb) return resolve({ ok: false, out: '', err: 'bundled adb.exe not found' });
    execFile(
      ADB_EXE,
      args,
      { timeout: timeoutMs, windowsHide: true, maxBuffer: 16 * 1024 * 1024 },
      (error, stdout, stderr) => {
        resolve({
          ok: !error,
          out: String(stdout || ''),
          err: String(stderr || '') || (error ? String(error.message || error) : ''),
        });
      }
    );
  });
}

const DEVICE_STATES = new Set([
  'device', 'offline', 'unauthorized', 'authorizing', 'connecting',
  'recovery', 'rescue', 'sideload', 'unknown',
]);

async function adbDevices() {
  const r = await runAdb(['devices']);
  const map = new Map();
  for (const line of r.out.split(/\r?\n/)) {
    const m = line.trim().match(/^(\S+)\s+(\S+)$/);
    if (m && DEVICE_STATES.has(m[2])) map.set(m[1], m[2]);
  }
  return map;
}

const propsCache = new Map();
async function fetchProps(serial) {
  if (propsCache.has(serial)) return propsCache.get(serial);
  const r = await runAdb([
    '-s', serial, 'shell',
    'getprop ro.product.model; getprop ro.build.version.release',
  ]);
  const [model, android] = r.out.split(/\r?\n/).map((s) => s.trim());
  const props = { model: model || 'Android device', android: android || '' };
  if (r.ok && model) propsCache.set(serial, props);
  return props;
}

// ---------------------------------------------------------------------------
// Connection state machine
// ---------------------------------------------------------------------------
let win = null;
const state = {
  status: 'connecting', // connecting | connected | unauthorized | disconnected
  transport: null,      // wifi | usb
  serial: null,
  model: null,
  android: null,
  ip: wifiSerial(),
  detail: '',
  binaries,
};

// ---------------------------------------------------------------------------
// GitHub Release updates
// Installed NSIS builds download and apply automatically. Portable builds
// check the same feed and link to the new release because replacing a running
// portable executable in place is unsafe on Windows.
// ---------------------------------------------------------------------------
const RELEASES_URL = 'https://github.com/Smeagol69/shield-control-suite/releases/latest';
const UPDATE_CHECK_INTERVAL_MS = 15 * 60 * 1000;
const updateMode = detectUpdateMode(app.isPackaged, process.env.PORTABLE_EXECUTABLE_FILE);
const updateState = {
  mode: updateMode,
  phase: updateMode === 'development' ? 'disabled' : 'idle',
  currentVersion: app.getVersion(),
  availableVersion: null,
  percent: null,
  message: updateMode === 'development'
    ? 'Updates are enabled in packaged builds.'
    : 'Checking GitHub Releases automatically.',
};
let updateCheckBusy = false;
let updateTimer = null;
let firstUpdateTimer = null;

function pushUpdateState(patch = {}) {
  Object.assign(updateState, patch);
  if (win && !win.isDestroyed()) {
    win.webContents.send('update:state', { ...updateState });
  }
}

async function checkForUpdates() {
  if (updateMode === 'development' || updateCheckBusy || process.env.SHIELD_SMOKE) {
    return { ...updateState };
  }
  updateCheckBusy = true;
  pushUpdateState({
    phase: 'checking',
    percent: null,
    message: 'Checking GitHub Releases…',
  });
  try {
    await autoUpdater.checkForUpdates();
  } catch (error) {
    pushUpdateState({
      phase: 'error',
      message: updateErrorMessage(error),
    });
  } finally {
    updateCheckBusy = false;
  }
  return { ...updateState };
}

function configureAutoUpdates() {
  if (updateMode === 'development' || process.env.SHIELD_SMOKE) return;

  autoUpdater.autoDownload = updateMode === 'installed';
  autoUpdater.autoInstallOnAppQuit = updateMode === 'installed';
  autoUpdater.allowPrerelease = false;

  autoUpdater.on('checking-for-update', () => {
    pushUpdateState({ phase: 'checking', percent: null, message: 'Checking GitHub Releases…' });
  });
  autoUpdater.on('update-available', (info) => {
    const version = info && info.version ? info.version : null;
    pushUpdateState({
      phase: updateMode === 'installed' ? 'downloading' : 'available',
      availableVersion: version,
      percent: updateMode === 'installed' ? 0 : null,
      message: updateMode === 'installed'
        ? `Downloading Shield Control ${version || 'update'}…`
        : `Shield Control ${version || 'update'} is available.`,
    });
  });
  autoUpdater.on('update-not-available', () => {
    pushUpdateState({
      phase: 'current',
      availableVersion: null,
      percent: null,
      message: `Shield Control ${app.getVersion()} is current.`,
    });
  });
  autoUpdater.on('download-progress', (progress) => {
    const percent = clampUpdatePercent(progress.percent);
    pushUpdateState({
      phase: 'downloading',
      percent,
      message: `Downloading update · ${percent}%`,
    });
  });
  autoUpdater.on('update-downloaded', (info) => {
    const version = info && info.version ? info.version : updateState.availableVersion;
    pushUpdateState({
      phase: 'downloaded',
      availableVersion: version,
      percent: 100,
      message: `Shield Control ${version || 'update'} is ready. Restart to install.`,
    });
  });
  autoUpdater.on('error', (error) => {
    pushUpdateState({
      phase: 'error',
      percent: null,
      message: updateErrorMessage(error),
    });
  });

  firstUpdateTimer = setTimeout(checkForUpdates, 8000);
  updateTimer = setInterval(checkForUpdates, UPDATE_CHECK_INTERVAL_MS);
}

async function handleUpdateAction() {
  if (updateMode === 'development') return { ok: false, error: updateState.message };
  if (updateMode === 'portable' && updateState.phase === 'available') {
    await shell.openExternal(RELEASES_URL);
    return { ok: true, opened: true };
  }
  if (updateMode === 'installed' && updateState.phase === 'downloaded') {
    setImmediate(() => autoUpdater.quitAndInstall(false, true));
    return { ok: true, installing: true };
  }
  await checkForUpdates();
  return { ok: true, checking: true };
}

function setState(patch) {
  const before = JSON.stringify(state);
  Object.assign(state, patch, { ip: wifiSerial(), binaries });
  if (JSON.stringify(state) !== before) {
    console.log(
      `[shield] state: ${state.status}` +
        (state.transport ? ` via ${state.transport}` : '') +
        (state.serial ? ` (${state.serial})` : '') +
        (state.detail ? ` — ${state.detail}` : '')
    );
    if (win && !win.isDestroyed()) win.webContents.send('shield:state', { ...state });
    if (state.status !== 'connected') {
      killRemoteShell(); // its serial may be stale; next keypress reopens against the live one
      if (status.online) { status.online = false; pushStatus(); }
    }
  }
}

let connectBusy = false;
let triedTcpip = false; // only restart adbd-in-tcp-mode once per disconnect episode
let lastAttempt = 0;

async function settleConnected(serial, transport, detail) {
  const props = await fetchProps(serial);
  if (transport === 'wifi') triedTcpip = false;
  console.log(`[shield] device: ${props.model} (Android ${props.android}) on ${serial}`);
  setState({ status: 'connected', transport, serial, model: props.model, android: props.android, detail });
  if (rootCheckedFor !== serial) {
    rootAvailable = null;
    rootCheckedFor = serial;
  }
  collectFast();
  collectSlow();
}

async function connectFlow(trigger) {
  if (connectBusy) return;
  if (!binaries.adb) {
    setState({ status: 'disconnected', detail: 'bundled adb.exe missing — reinstall the app' });
    return;
  }
  connectBusy = true;
  lastAttempt = Date.now();
  if (trigger === 'manual') triedTcpip = false;
  try {
    if (state.status !== 'connected') {
      setState({ status: 'connecting', detail: `Reaching ${wifiSerial()}…` });
    }

    // 1) WiFi first
    const con = await runAdb(['connect', wifiSerial()], { timeoutMs: 8000 });
    let devices = await adbDevices();

    // stale socket after device sleep shows as "offline" — reset it once
    if (devices.get(wifiSerial()) === 'offline') {
      await runAdb(['disconnect', wifiSerial()]);
      await runAdb(['connect', wifiSerial()], { timeoutMs: 8000 });
      devices = await adbDevices();
    }

    if (devices.get(wifiSerial()) === 'device') {
      await settleConnected(wifiSerial(), 'wifi', '');
      return;
    }
    if (devices.get(wifiSerial()) === 'unauthorized') {
      setState({
        status: 'unauthorized', transport: null, serial: null,
        detail: 'On the Shield: allow debugging for this computer (check "Always allow")',
      });
      return;
    }

    // 2) USB fallback (+ re-enable WiFi adb over it once)
    const usb = [...devices.entries()].find(([s]) => !s.includes(':'));
    if (usb) {
      const [usbSerial, usbState] = usb;
      if (usbState === 'device') {
        if (!triedTcpip) {
          triedTcpip = true;
          setState({ status: 'connecting', detail: 'USB found — re-enabling WiFi adb (tcpip 5555)…' });
          await runAdb(['-s', usbSerial, 'tcpip', String(config.port)], { timeoutMs: 10000 });
          await new Promise((r) => setTimeout(r, 2500));
          await runAdb(['connect', wifiSerial()], { timeoutMs: 8000 });
          const again = await adbDevices();
          if (again.get(wifiSerial()) === 'device') {
            await settleConnected(wifiSerial(), 'wifi', 'WiFi adb re-enabled via USB');
            return;
          }
        }
        await settleConnected(usbSerial, 'usb', `WiFi (${wifiSerial()}) unreachable — using USB`);
        return;
      }
      if (usbState === 'unauthorized') {
        setState({
          status: 'unauthorized', transport: null, serial: null,
          detail: 'On the Shield: allow debugging for this computer (check "Always allow")',
        });
        return;
      }
    }

    const blob = con.out + '\n' + con.err;
    const hint = /cannot connect|failed to connect|unable to connect/i.test(blob)
      ? lastLine(blob)
      : `Can't reach ${wifiSerial()}`;
    setState({ status: 'disconnected', transport: null, serial: null, detail: `${hint} — retrying automatically` });
  } finally {
    connectBusy = false;
  }
}

let healthBusy = false;
let lastHealthTs = 0;

async function healthCheck() {
  if (healthBusy || connectBusy || !binaries.adb) return;
  healthBusy = true;
  lastHealthTs = Date.now();
  try {
    const devices = await adbDevices();
    if (state.status === 'connected' && state.serial && devices.get(state.serial) === 'device') {
      // opportunistic upgrade: USB session but WiFi came back
      if (state.transport === 'usb' && devices.get(wifiSerial()) === 'device') {
        await settleConnected(wifiSerial(), 'wifi', 'WiFi restored');
      }
      return;
    }
    if (Date.now() - lastAttempt > 10000) {
      connectFlow('auto');
    } else if (state.status === 'connected') {
      setState({ status: 'connecting', transport: null, serial: null, detail: 'Connection lost — reconnecting…' });
    }
  } finally {
    healthBusy = false;
  }
}

// `adb track-devices` streams a frame whenever the device list changes —
// instant connect/disconnect reactions with far fewer adb invocations.
// The periodic check stays as a safety net (stretched while the tracker lives).
let trackProc = null;
let trackKick = null;
let quitting = false;

function startDeviceTracker() {
  if (!binaries.adb || trackProc || quitting) return;
  const child = spawn(ADB_EXE, ['track-devices'], { windowsHide: true });
  child.stdout.on('data', () => {
    if (trackKick) return;
    trackKick = setTimeout(() => { trackKick = null; healthCheck(); }, 250);
  });
  child.stderr.on('data', () => {});
  child.on('error', () => { trackProc = null; });
  child.on('close', () => {
    trackProc = null;
    if (!quitting) setTimeout(startDeviceTracker, 5000); // adb server bounced — reattach
  });
  trackProc = child;
}

function startPolling() {
  startDeviceTracker();
  setInterval(() => {
    const interval = trackProc ? 15000 : 4000;
    if (Date.now() - lastHealthTs < interval) return;
    // telemetry that just succeeded already proves the device is alive, and the
    // track-devices stream surfaces drops — skip the redundant `adb devices` spawn
    if (state.status === 'connected' && trackProc && Date.now() - (status.ts || 0) < 12000) {
      lastHealthTs = Date.now();
      return;
    }
    healthCheck();
  }, 2000);
}

// ---------------------------------------------------------------------------
// Device status (dashboard telemetry)
// ---------------------------------------------------------------------------
let hostAdbVersion = '';
const status = { online: false, ts: 0 };
let fastBusy = false;
let slowBusy = false;
const slowCache = { kodiLog: null, backup: null };

let rootAvailable = null; // Magisk `su` from the adb shell — probed once per connection
let rootCheckedFor = null;
async function checkRoot() {
  if (state.status !== 'connected' || !state.serial) return false;
  const r = await runAdb(['-s', state.serial, 'shell', 'timeout 3 su -c id 2>/dev/null'], { timeoutMs: 9000 });
  rootAvailable = /uid=0\(root\)/.test(r.out);
  console.log('[shield] root access:', rootAvailable ? 'available (su)' : 'not available');
  return rootAvailable;
}

// Android's FUSE layer denies /storage/emulated even to root — root reads the
// raw backing store at /data/media instead; presented paths stay unchanged
function rootPath(p) {
  if (p === '/sdcard' || p.startsWith('/sdcard/')) return '/data/media/0' + p.slice('/sdcard'.length);
  const m = p.match(/^\/storage\/emulated(\/.*)?$/);
  if (m) return '/data/media' + (m[1] || '');
  return p;
}

// one combined on-device script per refresh — cheap, single adb round-trip
const FAST_SCRIPT = [
  'echo ":::ip"',
  'ip route get 1.1.1.1 2>/dev/null | head -1',
  'echo ":::df"',
  'df -k /data 2>/dev/null | tail -1',
  'echo ":::mem"',
  'grep -E "MemTotal|MemAvailable" /proc/meminfo 2>/dev/null',
  'echo ":::up"',
  'cat /proc/uptime 2>/dev/null',
  'echo ":::temp"',
  // sysfs thermal is not shell-readable on the Shield; thermalservice is
  "dumpsys thermalservice 2>/dev/null | sed -n '/Current temperatures from HAL/,$p' | grep 'Temperature{' | head -12",
  'echo ":::adb"',
  'echo "service=$(getprop service.adb.tcp.port) persist=$(getprop persist.adb.tcp.port)"',
  'PKGS=$(pm list packages org.xbmc 2>/dev/null | sed "s/^package://")',
  'echo ":::kodi"',
  'echo "$PKGS" | head -5',
  'echo ":::kodipid"',
  'K=$(echo "$PKGS" | head -1)',
  'if [ -n "$K" ]; then pidof "$K" 2>/dev/null || echo none; else echo none; fi',
  'echo ":::media"',
  'dumpsys media_session 2>/dev/null | grep -E "package=|active=|state=PlaybackState|description=" | head -60',
].join('\n');

function slowScript() {
  return [
    'K=$(pm list packages org.xbmc 2>/dev/null | head -1 | sed "s/^package://")',
    'echo ":::kodilog"',
    'if [ -n "$K" ]; then',
    '  L="/sdcard/Android/data/$K/files/.kodi/temp/kodi.log"',
    '  T=$(tail -n 400 "$L" 2>/dev/null)',
    // scoped storage hides the log from the shell user on Android 11, and FUSE
    // blocks root too — root reads the raw store at /data/media instead
    '  L2="/data/media/0/Android/data/$K/files/.kodi/temp/kodi.log"',
    `  [ -z "$T" ] && T=$(su -c "tail -n 400 '$L2'" 2>/dev/null)`,
    '  if [ -n "$T" ]; then',
    '    echo "count=$(echo "$T" | grep -c " ERROR ")"',
    '    echo "$T" | grep " ERROR " | tail -n 6',
    '  else echo "count=-1"; fi',
    'else echo "count=-2"; fi',
    'echo ":::backup"',
    `D=${shq(config.backupDir || '/sdcard/backup')}`,
    'if [ -d "$D" ]; then B=$(stat -c "%Y %n" "$D"/* 2>/dev/null | sort -rn | head -1); echo "${B:-empty}"; else echo "nodir"; fi',
  ].join('\n');
}

function splitSections(out) {
  const sec = {};
  let cur = null;
  for (const line of out.split(/\r?\n/)) {
    const m = line.match(/^:::(\w+)\s*$/);
    if (m) { cur = m[1]; sec[cur] = []; continue; }
    if (cur) sec[cur].push(line);
  }
  for (const k of Object.keys(sec)) sec[k] = sec[k].join('\n').trim();
  return sec;
}

const APP_NAMES = {
  'org.xbmc.kodi': 'Kodi',
  'com.netflix.ninja': 'Netflix',
  'com.google.android.youtube.tv': 'YouTube',
  'com.plexapp.android': 'Plex',
  'com.amazon.amazonvideo.livingroom': 'Prime Video',
  'com.disney.disneyplus': 'Disney+',
  'com.spotify.tv.android': 'Spotify',
  'app.revanced.android.youtube': 'YouTube (ReVanced)',
  'app.revanced.android.apps.youtube.music': 'YT Music (ReVanced)',
  'app.revanced.android.gms': 'GmsCore',
  'app.rvx.android.youtube': 'YouTube (RVX)',
  'com.revanced.net.revancedmanager': 'ReVanced Manager',
};
function appName(pkg) {
  if (APP_NAMES[pkg]) return APP_NAMES[pkg];
  const seg = String(pkg || '').split('.').filter(Boolean).pop() || pkg;
  return seg ? seg.charAt(0).toUpperCase() + seg.slice(1) : '';
}

function parseMedia(text) {
  const sessions = [];
  let cur = null;
  for (const raw of String(text || '').split(/\r?\n/)) {
    const line = raw.trim();
    const pkg = line.match(/^package=(\S+)/);
    if (pkg) { cur = { pkg: pkg[1] }; sessions.push(cur); continue; }
    if (!cur) continue;
    if (/^active=true/.test(line)) cur.active = true;
    const st = line.match(/state=PlaybackState \{state=(\d+)/);
    if (st) cur.state = Number(st[1]);
    const d = line.match(/description=(.*)$/);
    if (d) cur.desc = d[1];
  }
  // PlaybackState: 3 = playing, 2 = paused
  const pick = sessions.find((s) => s.active && s.state === 3) ||
               sessions.find((s) => s.active && s.state === 2);
  if (!pick) return null;
  const title = (pick.desc || '')
    .split(',').map((s) => s.trim())
    .filter((s) => s && s !== 'null')
    .slice(0, 2).join(' — ');
  return { playing: pick.state === 3, title: title || null, app: appName(pick.pkg) };
}

function parseFast(out) {
  const sec = splitSections(out);
  const s = {};

  const ipm = (sec.ip || '').match(/\bsrc (\S+)/);
  s.ip = ipm ? ipm[1] : null;
  const ifm = (sec.ip || '').match(/\bdev (\S+)/);
  s.iface = ifm ? ifm[1] : null;

  const df = (sec.df || '').trim().split(/\s+/);
  s.storage = df.length >= 4 ? { totalKb: Number(df[1]), availKb: Number(df[3]) } : null;

  const mt = (sec.mem || '').match(/MemTotal:\s+(\d+)/);
  const ma = (sec.mem || '').match(/MemAvailable:\s+(\d+)/);
  s.mem = mt && ma ? { totalKb: Number(mt[1]), availKb: Number(ma[1]) } : null;

  const up = parseFloat((sec.up || '').trim());
  s.uptimeSec = Number.isFinite(up) ? Math.floor(up) : null;

  let cpu = null, gpu = null, thermal = 0;
  for (const m of (sec.temp || '').matchAll(/Temperature\{mValue=([\d.]+), mType=(\d+), mName=([^,}]+), mStatus=(\d+)/g)) {
    const v = parseFloat(m[1]);
    const type = Number(m[2]);
    if (type === 0 || /^CPU/i.test(m[3])) cpu = Math.max(cpu ?? -1, v);
    if (type === 1 || /^GPU/i.test(m[3])) gpu = Math.max(gpu ?? -1, v);
    thermal = Math.max(thermal, Number(m[4]));
  }
  s.cpuTempC = cpu;
  s.gpuTempC = gpu;
  s.thermalStatus = thermal;

  const svc = (sec.adb || '').match(/service=(\d*)/);
  const per = (sec.adb || '').match(/persist=(\d*)/);
  s.adbPort = { service: svc && svc[1] ? svc[1] : null, persist: per && per[1] ? per[1] : null };

  const pkgs = (sec.kodi || '').split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
  const kodiPkg = pkgs.find((p) => p === 'org.xbmc.kodi') || pkgs[0] || null;
  const pid = (sec.kodipid || '').trim();
  s.kodi = {
    installed: !!kodiPkg,
    package: kodiPkg,
    running: /^\d+/.test(pid),
    pid: /^\d+/.test(pid) ? pid.split(/\s+/)[0] : null,
  };

  s.playback = parseMedia(sec.media);
  return s;
}

function pushStatus() {
  if (win && !win.isDestroyed()) win.webContents.send('status:update', { ...status });
}

// telemetry only feeds the UI — don't poll the device while nobody's looking
const winActive = () => !!win && !win.isDestroyed() && win.isVisible() && !win.isMinimized();

let loggedFirstStatus = false;
async function collectFast() {
  if (fastBusy || state.status !== 'connected' || !state.serial || !winActive()) return;
  fastBusy = true;
  try {
    if (rootAvailable === null) await checkRoot();
    const r = await runAdb(['-s', state.serial, 'shell', FAST_SCRIPT], { timeoutMs: 25000 });
    if (!r.out.includes(':::ip')) return;
    const s = parseFast(r.out);
    Object.assign(status, s, {
      online: true,
      root: rootAvailable,
      hostAdbVersion,
      transport: state.transport,
      wifiSerial: wifiSerial(),
      kodiLog: slowCache.kodiLog,
      backup: slowCache.backup,
      ts: Date.now(),
    });
    if (!loggedFirstStatus) {
      loggedFirstStatus = true;
      console.log(
        `[shield] status: ip=${status.ip} (${status.iface}) cpu=${status.cpuTempC}°C ` +
        `mem=${status.mem ? Math.round((status.mem.totalKb - status.mem.availKb) / 1024) : '?'}MB used ` +
        `disk=${status.storage ? Math.round(status.storage.availKb / 1048576 * 10) / 10 : '?'}GB free ` +
        `up=${status.uptimeSec}s kodi=${status.kodi.installed ? (status.kodi.running ? 'running' : 'stopped') : 'not installed'} ` +
        `playback=${status.playback ? status.playback.app : 'none'} adbtcp=${status.adbPort.service || '-'}`
      );
    }
    pushStatus();
  } finally {
    fastBusy = false;
  }
}

async function collectSlow() {
  if (slowBusy || state.status !== 'connected' || !state.serial || !winActive()) return;
  slowBusy = true;
  try {
    const r = await runAdb(['-s', state.serial, 'shell', slowScript()], { timeoutMs: 30000 });
    if (!r.out.includes(':::kodilog')) return;
    const sec = splitSections(r.out);

    const logLines = (sec.kodilog || '').split(/\r?\n/);
    const cm = (logLines[0] || '').match(/count=(-?\d+)/);
    const count = cm ? Number(cm[1]) : -1;
    slowCache.kodiLog = {
      state: count === -2 ? 'nokodi' : count === -1 ? 'nolog' : 'ok',
      count: count >= 0 ? count : 0,
      lines: count > 0 ? logLines.slice(1).filter(Boolean) : [],
    };

    const b = (sec.backup || '').trim();
    if (b === 'nodir' || b === 'empty' || b === '') {
      slowCache.backup = { state: b === 'nodir' ? 'nodir' : 'empty', dir: config.backupDir };
    } else {
      const bm = b.match(/^(\d+)\s+(.+)$/);
      slowCache.backup = bm
        ? { state: 'ok', mtime: Number(bm[1]) * 1000, name: path.posix.basename(bm[2]), dir: config.backupDir }
        : { state: 'empty', dir: config.backupDir };
    }

    status.kodiLog = slowCache.kodiLog;
    status.backup = slowCache.backup;
    status.ts = Date.now();
    pushStatus();
  } finally {
    slowBusy = false;
  }
}

function startStatusPolling() {
  setInterval(collectFast, 8000);
  setInterval(collectSlow, 60000);
}

// ---------------------------------------------------------------------------
// Patching (Morphe on-device patcher + De-Vanced patch bundle)
// ---------------------------------------------------------------------------
const MORPHE_PKG = 'app.morphe.manager';

// Morphe is a phone-oriented app with no D-pad UI — this reads its state so the
// desktop can present it, and morphe:open drives it over scrcpy.
async function collectMorphe() {
  if (state.status !== 'connected' || !state.serial) return { ok: false, error: 'Not connected' };
  const script = [
    'echo ":::ver"',
    `dumpsys package ${MORPHE_PKG} 2>/dev/null | grep -m1 versionName | cut -d= -f2`,
    'echo ":::act"',
    `cmd package resolve-activity --brief ${MORPHE_PKG} 2>/dev/null | tail -1`,
    'echo ":::patched"',
    // ReVanced/RVX patched packages carry their own ids — list them with versions.
    // single-quote the sed/grep so an alternation pipe can't be mis-split by any quoting layer
    "for p in $(pm list packages 2>/dev/null | sed 's/^package://' | grep -E 'revanced|rvx'); do",
    '  echo "$p=$(dumpsys package $p 2>/dev/null | grep -m1 versionName | cut -d= -f2)"',
    'done',
  ].join('\n');
  const r = await runAdb(['-s', state.serial, 'shell', script], { timeoutMs: 25000 });
  const sec = splitSections(r.out);

  const version = (sec.ver || '').trim();
  const installed = !!version;
  const activity = (sec.act || '').trim() || `${MORPHE_PKG}/.MainActivity_Default`;

  const patched = [];
  for (const line of (sec.patched || '').split(/\r?\n/)) {
    const m = line.trim().match(/^(\S+?)=(.*)$/);
    if (m && m[1].includes('.')) patched.push({ pkg: m[1], name: appName(m[1]), version: m[2] || '?' });
  }

  // patch bundle (De-Vanced patches.jar) lives in app-private storage — needs root
  let bundle = null;
  if (installed) {
    if (rootAvailable === null) await checkRoot();
    if (rootAvailable) {
      const b = await runAdb(
        ['-s', state.serial, 'shell', `su -c ${shq(`ls -la /data/data/${MORPHE_PKG}/app_patch_bundles/*/patches.jar 2>/dev/null`)}`],
        { timeoutMs: 15000 }
      );
      const bm = b.out.trim().match(/\s(\d+)\s+(\d{4}-\d{2}-\d{2} \d{2}:\d{2})\s/);
      const count = b.out.split(/\r?\n/).filter((l) => l.includes('patches.jar')).length;
      if (bm) bundle = { sizeBytes: Number(bm[1]), mtime: bm[2], count };
    }
  }

  console.log(
    `[shield] morphe: ${installed ? 'v' + version : 'not installed'}` +
    (bundle ? ` · bundle ${Math.round((bundle.sizeBytes / 1048576) * 10) / 10}MB` : '') +
    ` · patched=${patched.map((a) => a.pkg).join(',') || 'none'}`
  );
  return {
    ok: true,
    installed,
    version,
    activity,
    package: MORPHE_PKG,
    bundle,
    patched,
    rootKnown: rootAvailable === true,
  };
}

function openMorphe() {
  if (state.status !== 'connected' || !state.serial) return { ok: false, error: 'Shield is not connected' };
  // launch Morphe on the device, then mirror it so the phone-style UI is mouse-drivable
  runAdb(['-s', state.serial, 'shell', `monkey --pct-syskeys 0 -p ${MORPHE_PKG} 1`], { timeoutMs: 15000 })
    .then(() => setTimeout(() => launchScrcpy(), 600));
  return { ok: true };
}

// ---------------------------------------------------------------------------
// TV App Store — sideload catalog. This Shield is rooted, so Play Integrity
// fails and the Play Store hides apps like Disney+/Max; they install by sideload
// (the Android-TV APK from APKMirror + drag-drop, or a direct .apk link).
// ---------------------------------------------------------------------------
const TV_APPS = [
  { pkg: 'com.disney.disneyplus', name: 'Disney+', cat: 'Streaming' },
  { pkg: 'com.hulu.plus', name: 'Hulu', cat: 'Streaming' },
  { pkg: 'com.netflix.ninja', name: 'Netflix', cat: 'Streaming' },
  { pkg: 'com.amazon.amazonvideo.livingroom', name: 'Prime Video', cat: 'Streaming' },
  { pkg: 'com.wbd.stream', name: 'Max', cat: 'Streaming' },
  { pkg: 'com.apple.atve.androidtv.appletv', name: 'Apple TV', cat: 'Streaming' },
  { pkg: 'com.peacocktv.peacockandroid', name: 'Peacock', cat: 'Streaming' },
  { pkg: 'com.cbs.ott', name: 'Paramount+', cat: 'Streaming' },
  { pkg: 'com.google.android.youtube.tv', name: 'YouTube', cat: 'Streaming' },
  { pkg: 'com.crunchyroll.crunchyroid', name: 'Crunchyroll', cat: 'Streaming' },
  { pkg: 'com.tubitv', name: 'Tubi', cat: 'Streaming' },
  { pkg: 'tv.pluto.android', name: 'Pluto TV', cat: 'Streaming' },
  { pkg: 'tv.twitch.android.app', name: 'Twitch', cat: 'Streaming' },
  { pkg: 'com.plexapp.android', name: 'Plex', cat: 'Media' },
  { pkg: 'org.jellyfin.androidtv', name: 'Jellyfin', cat: 'Media' },
  { pkg: 'org.xbmc.kodi', name: 'Kodi', cat: 'Media' },
  { pkg: 'org.videolan.vlc', name: 'VLC', cat: 'Media' },
  { pkg: 'com.spotify.tv.android', name: 'Spotify', cat: 'Media' },
  { pkg: 'com.valvesoftware.steamlink', name: 'Steam Link', cat: 'Tools' },
  { pkg: 'com.esaba.downloader', name: 'Downloader', cat: 'Tools' },
];

const apkmirrorSearch = (name) =>
  `https://www.apkmirror.com/?post_type=app_release&searchtype=apk&s=${encodeURIComponent(name + ' Android TV')}`;

async function collectApps() {
  const base = TV_APPS.map((a) => ({ ...a, installed: false, source: apkmirrorSearch(a.name) }));
  if (state.status !== 'connected' || !state.serial) return { ok: true, connected: false, apps: base };
  const r = await runAdb(['-s', state.serial, 'shell', 'pm list packages'], { timeoutMs: 20000 });
  const installed = new Set(
    r.out.split(/\r?\n/).map((l) => l.trim().replace(/^package:/, '')).filter(Boolean)
  );
  return { ok: true, connected: true, apps: base.map((a) => ({ ...a, installed: installed.has(a.pkg) })) };
}

const validPkg = (p) => typeof p === 'string' && /^[A-Za-z0-9_.]+$/.test(p);

async function openApp(pkg) {
  if (state.status !== 'connected' || !state.serial) return { ok: false, error: 'Not connected' };
  if (!validPkg(pkg)) return { ok: false, error: 'bad package name' };
  await runAdb(['-s', state.serial, 'shell', `monkey --pct-syskeys 0 -p ${pkg} 1`], { timeoutMs: 15000 });
  return { ok: true };
}

async function uninstallApp(pkg) {
  if (state.status !== 'connected' || !state.serial) return { ok: false, error: 'Not connected' };
  if (!validPkg(pkg)) return { ok: false, error: 'bad package name' };
  let r = await runAdb(['-s', state.serial, 'uninstall', pkg], { timeoutMs: 30000 });
  if (!/Success/i.test(r.out)) {
    // preinstalled/system app — remove for the current user instead of a full uninstall
    r = await runAdb(['-s', state.serial, 'shell', `pm uninstall --user 0 ${pkg}`], { timeoutMs: 30000 });
  }
  if (/Success/i.test(r.out)) { console.log(`[shield] uninstalled ${pkg}`); return { ok: true }; }
  return { ok: false, error: lastLine(r.err) || lastLine(r.out) || 'uninstall failed' };
}

function openSourcePage(url) {
  if (typeof url === 'string' && /^https:\/\//.test(url)) { shell.openExternal(url); return { ok: true }; }
  return { ok: false, error: 'bad url' };
}

// stream a user-provided URL to a local file, following redirects
function fetchToFile(url, dest, onPct, redirects = 0) {
  return new Promise((resolve) => {
    if (redirects > 5) return resolve({ ok: false, error: 'too many redirects' });
    let u;
    try { u = new URL(url); } catch { return resolve({ ok: false, error: 'invalid URL' }); }
    if (u.protocol !== 'https:' && u.protocol !== 'http:') return resolve({ ok: false, error: 'only http(s) links' });
    const mod = u.protocol === 'http:' ? http : https;
    const req = mod.get(u, { headers: { 'User-Agent': 'ShieldControl' } }, (res) => {
      if ([301, 302, 303, 307, 308].includes(res.statusCode) && res.headers.location) {
        res.resume();
        return resolve(fetchToFile(new URL(res.headers.location, u).toString(), dest, onPct, redirects + 1));
      }
      if (res.statusCode !== 200) { res.resume(); return resolve({ ok: false, error: `HTTP ${res.statusCode}` }); }
      const total = Number(res.headers['content-length']) || 0;
      const out = fs.createWriteStream(dest);
      let got = 0;
      res.on('data', (d) => { got += d.length; if (total && onPct) onPct(Math.min(99, Math.floor((got / total) * 100))); });
      res.pipe(out);
      out.on('finish', () => out.close(() => resolve({ ok: true, bytes: got })));
      out.on('error', (e) => resolve({ ok: false, error: String(e.message || e) }));
    });
    req.on('error', (e) => resolve({ ok: false, error: String(e.message || e) }));
    req.setTimeout(60000, () => { req.destroy(); resolve({ ok: false, error: 'timed out' }); });
  });
}

// download a direct .apk link the user supplied, then install it (progress via the queue)
function installFromUrl(url) {
  if (state.status !== 'connected' || !state.serial) return { ok: false, error: 'Shield is not connected' };
  let parsed;
  try { parsed = new URL(url); } catch { return { ok: false, error: 'Enter a valid http(s) link' }; }
  const serial = state.serial;
  let base = 'download.apk';
  try { base = decodeURIComponent(parsed.pathname.split('/').pop() || '') || 'download.apk'; } catch {}
  if (!/\.apk$/i.test(base)) base += '.apk';
  enqueue({
    kind: 'install',
    name: `${base} · ${parsed.host}`,
    run: async (report) => {
      const tmp = path.join(app.getPath('temp'), `shieldctl-${Date.now()}.apk`);
      const dl = await fetchToFile(url, tmp, report);
      if (!dl.ok) { try { fs.unlinkSync(tmp); } catch {} return { ok: false, detail: `download failed: ${dl.error}` }; }
      // an APK is a ZIP — verify the PK magic before trusting the link
      let magic = Buffer.alloc(2);
      try { const fd = fs.openSync(tmp, 'r'); fs.readSync(fd, magic, 0, 2, 0); fs.closeSync(fd); } catch {}
      if (magic[0] !== 0x50 || magic[1] !== 0x4b) { try { fs.unlinkSync(tmp); } catch {} return { ok: false, detail: 'that link is not an APK' }; }
      const r = await runAdb(['-s', serial, 'install', '-r', tmp], { timeoutMs: 300000 });
      try { fs.unlinkSync(tmp); } catch {}
      return /Success/i.test(r.out)
        ? { ok: true, detail: 'Installed on Shield' }
        : { ok: false, detail: lastLine(r.err) || lastLine(r.out) || 'install failed' };
    },
  });
  return { ok: true };
}

// ---------------------------------------------------------------------------
// App manager — every installed app (superset of the curated store) with
// launch / force-stop / clear-cache / uninstall.
// ---------------------------------------------------------------------------
async function listInstalledApps(includeSystem) {
  const serial = requireDevice();
  if (!serial) return { ok: false, error: 'Not connected' };
  const args = ['-s', serial, 'shell', includeSystem ? 'pm list packages' : 'pm list packages -3'];
  const r = await runAdb(args, { timeoutMs: 20000 });
  const catalog = new Set(TV_APPS.map((a) => a.pkg));
  const apps = r.out.split(/\r?\n/)
    .map((l) => l.trim().replace(/^package:/, ''))
    .filter(Boolean)
    .map((pkg) => ({ pkg, name: appName(pkg), known: APP_NAMES[pkg] != null || catalog.has(pkg) }))
    .sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }));
  return { ok: true, count: apps.length, apps };
}

async function appManage(pkg, action) {
  const serial = requireDevice();
  if (!serial) return { ok: false, error: 'Not connected' };
  if (!validPkg(pkg)) return { ok: false, error: 'bad package name' };
  if (action === 'stop') {
    const r = await runAdb(['-s', serial, 'shell', `am force-stop ${pkg}`], { timeoutMs: 15000 });
    return r.ok ? { ok: true } : { ok: false, error: lastLine(r.err) || 'force-stop failed' };
  }
  if (action === 'clear-cache') {
    // deliberately NOT `pm clear` (that wipes logins/data) — only remove cache dirs, via root
    if (rootAvailable === null) await checkRoot();
    if (!rootAvailable) return { ok: false, error: 'clearing cache needs root' };
    const inner =
      `rm -rf /data/data/${pkg}/cache/* /data/data/${pkg}/code_cache/* 2>/dev/null; ` +
      `rm -rf /data/media/0/Android/data/${pkg}/cache/* 2>/dev/null; echo cleared`;
    const r = await runAdb(['-s', serial, 'shell', `su -c ${shq(inner)}`], { timeoutMs: 20000 });
    return /cleared/.test(r.out) ? { ok: true } : { ok: false, error: lastLine(r.err) || 'clear-cache failed' };
  }
  return { ok: false, error: 'unknown action' };
}

async function trimCaches() {
  const serial = requireDevice();
  if (!serial) return { ok: false, error: 'Not connected' };
  // ask the framework to purge every app's cache to reclaim space
  await runAdb(['-s', serial, 'shell', 'pm trim-caches 999999999999'], { timeoutMs: 30000 });
  setTimeout(collectFast, 1500);
  return { ok: true };
}

// ---------------------------------------------------------------------------
// scrcpy (screen control)
// ---------------------------------------------------------------------------
let scrcpyProc = null;

function emitScrcpy(extra = {}) {
  if (win && !win.isDestroyed()) {
    win.webContents.send('scrcpy:state', { running: !!scrcpyProc, ...extra });
  }
}

function launchScrcpy() {
  if (!binaries.scrcpy) return { ok: false, error: 'bundled scrcpy.exe not found' };
  if (scrcpyProc) return { ok: true };
  if (state.status !== 'connected' || !state.serial) return { ok: false, error: 'Shield is not connected yet' };

  const title = `${state.model || 'NVIDIA Shield'} — Shield Control`;
  const child = spawn(
    SCRCPY_EXE,
    // aac: the Shield (Android 11) has no Opus encoder, and that failure kills the stream.
    // max-fps + tunable bitrate keep mirrored control responsive over WiFi (scrcpy's
    // video buffer already defaults to 0, so no buffering flag is needed).
    ['-s', state.serial, '--window-title', title, '--stay-awake', '--audio-codec=aac',
     '--max-fps=60', `--video-bit-rate=${config.scrcpyBitrate || '8M'}`],
    {
      cwd: SCRCPY_DIR,
      env: { ...process.env, ADB: ADB_EXE }, // make scrcpy use our adb → one adb server
      windowsHide: true,
    }
  );

  let errTail = '';
  const started = Date.now();
  child.stdout.on('data', () => {});
  child.stderr.on('data', (d) => { errTail = (errTail + d).slice(-3000); });
  child.on('error', (e) => {
    scrcpyProc = null;
    emitScrcpy({ error: `scrcpy failed to start: ${e.message}` });
  });
  child.on('exit', (code) => {
    scrcpyProc = null;
    const failedFast = code !== 0 && code != null && Date.now() - started < 8000;
    if (failedFast) {
      const errLine =
        errTail.split(/\r?\n/).reverse().find((l) => l.includes('ERROR')) ||
        lastLine(errTail) || `scrcpy exited (${code})`;
      console.log('[shield] scrcpy error:', errLine);
      emitScrcpy({ error: errLine });
    } else {
      emitScrcpy();
    }
  });

  scrcpyProc = child;
  emitScrcpy();
  return { ok: true };
}

function stopScrcpy() {
  try { if (scrcpyProc) scrcpyProc.kill(); } catch {}
  return { ok: true };
}

// ---------------------------------------------------------------------------
// Device input — virtual remote. Keypresses route through ONE persistent
// `adb shell` so each press is just a stdin write (no ~100ms process spawn per
// key), which is what makes the remote feel responsive.
// ---------------------------------------------------------------------------
const requireDevice = () => (state.status === 'connected' && state.serial ? state.serial : null);

let remoteShell = null;
let remoteShellSerial = null;

function killRemoteShell() {
  if (remoteShell) {
    try { remoteShell.stdin.end(); remoteShell.kill(); } catch {}
    remoteShell = null;
  }
  remoteShellSerial = null;
}

function ensureRemoteShell() {
  const serial = requireDevice();
  if (!serial) return null;
  if (remoteShell && remoteShellSerial === serial && remoteShell.stdin && remoteShell.stdin.writable) {
    return remoteShell;
  }
  killRemoteShell();
  remoteShellSerial = serial;
  const sh = spawn(ADB_EXE, ['-s', serial, 'shell'], { windowsHide: true });
  sh.stdout.on('data', () => {}); // drain both pipes so the shell never blocks on a full buffer
  sh.stderr.on('data', () => {});
  sh.on('error', () => { if (remoteShell === sh) remoteShell = null; });
  sh.on('close', () => { if (remoteShell === sh) remoteShell = null; });
  remoteShell = sh;
  return sh;
}

// only these keycodes may be sent — never forward an arbitrary string to the shell
const ALLOWED_KEYS = new Set([
  3, 4, 19, 20, 21, 22, 23, 24, 25, 26, 66, 67, 82, 84, 85, 86, 87, 88, 89, 90, 164, 187,
]);

function sendKey(code) {
  const n = Number(code);
  if (!ALLOWED_KEYS.has(n)) return { ok: false, error: 'key not allowed' };
  const sh = ensureRemoteShell();
  if (!sh) return { ok: false, error: 'Not connected' };
  try { sh.stdin.write(`input keyevent ${n}\n`); return { ok: true }; }
  catch { killRemoteShell(); return { ok: false, error: 'remote link dropped — try again' }; }
}

async function sendText(text) {
  const serial = requireDevice();
  if (!serial) return { ok: false, error: 'Not connected' };
  if (typeof text !== 'string' || !text) return { ok: false, error: 'no text' };
  // `input text` maps %s to space; single-quote so the device shell passes it verbatim
  const esc = text.replace(/'/g, `'\\''`).replace(/ /g, '%s');
  const r = await runAdb(['-s', serial, 'shell', `input text '${esc}'`], { timeoutMs: 10000 });
  return r.ok ? { ok: true } : { ok: false, error: lastLine(r.err) || 'input failed' };
}

// ---------------------------------------------------------------------------
// Transfers (push / pull / install) — sequential queue with progress events
// ---------------------------------------------------------------------------
let transferSeq = 0;
const queue = [];
let queueBusy = false;

function sendTransfer(t) {
  if (win && !win.isDestroyed()) win.webContents.send('transfer:update', { ...t });
}

function enqueue(job) {
  const id = ++transferSeq;
  const t = {
    id,
    kind: job.kind,
    name: job.name,
    pct: job.kind === 'install' || job.indeterminate ? -1 : 0, // -1 → indeterminate bar
    status: 'queued',
    detail: '',
  };
  sendTransfer(t);
  queue.push({ ...job, t });
  pump();
  return id;
}

async function pump() {
  if (queueBusy) return;
  const job = queue.shift();
  if (!job) return;
  queueBusy = true;
  const { t } = job;
  t.status = 'active';
  sendTransfer(t);
  try {
    // adb repeats the same integer % across chunks — only emit when it moves,
    // so a large push doesn't fire a full IPC + renderer rebuild per chunk
    let lastPct = -2;
    const res = await job.run((pct) => {
      if (pct === lastPct) return;
      lastPct = pct;
      t.pct = pct;
      sendTransfer(t);
    });
    t.status = res.ok ? 'done' : 'error';
    if (res.ok) t.pct = 100;
    t.detail = res.detail || '';
    if (res.localPath) t.localPath = res.localPath;
  } catch (e) {
    t.status = 'error';
    t.detail = String((e && e.message) || e);
  }
  console.log(`[shield] transfer ${t.kind} "${t.name}": ${t.status}${t.detail ? ' — ' + t.detail : ''}`);
  sendTransfer(t);
  queueBusy = false;
  pump();
}

// adb push/pull print "[ 42%] path" progress lines (with \r) — parse both streams
function spawnAdbProgress(args, onPct) {
  return new Promise((resolve) => {
    const child = spawn(ADB_EXE, args, { windowsHide: true });
    let tail = '';
    const onChunk = (d) => {
      const s = d.toString();
      tail = (tail + s).slice(-4000);
      let m;
      let last = null;
      const re = /\[\s*(\d+)%\]/g;
      while ((m = re.exec(s))) last = Number(m[1]);
      if (last != null) onPct(Math.min(last, 100));
    };
    child.stdout.on('data', onChunk);
    child.stderr.on('data', onChunk);
    child.on('error', (e) => resolve({ code: -1, tail: String(e.message || e) }));
    child.on('close', (code) => resolve({ code, tail }));
  });
}

function enqueuePush(localPath, remoteDir, serial) {
  enqueue({
    kind: 'push',
    name: path.basename(localPath),
    run: async (report) => {
      const { code, tail } = await spawnAdbProgress(['-s', serial, 'push', localPath, remoteDir], report);
      return code === 0
        ? { ok: true, detail: `Copied to ${remoteDir}` }
        : { ok: false, detail: lastLine(tail) || 'push failed' };
    },
  });
}

function enqueueInstall(localPath, serial) {
  enqueue({
    kind: 'install',
    name: path.basename(localPath),
    run: async () => {
      const r = await runAdb(['-s', serial, 'install', '-r', localPath], { timeoutMs: 240000 });
      return /Success/i.test(r.out)
        ? { ok: true, detail: 'Installed on Shield' }
        : { ok: false, detail: lastLine(r.err) || lastLine(r.out) || 'install failed' };
    },
  });
}

// Split/bundle APKs (.apkm/.apks/.xapk are ZIP containers of base + config splits)
// must go in via a single install-multiple session, not `install`. Merging them into
// one APK breaks the signature — the mistake that made the manual Disney+ attempt fail.
const BUNDLE_EXTS = new Set(['.apkm', '.apks', '.xapk']);
const isBundle = (p) => BUNDLE_EXTS.has(path.extname(p).toLowerCase());
const isApkLike = (p) => p.toLowerCase().endsWith('.apk') || isBundle(p);

let deviceAbiCache = null;
async function deviceAbi(serial) {
  if (deviceAbiCache) return deviceAbiCache;
  const r = await runAdb(['-s', serial, 'shell', 'getprop ro.product.cpu.abi'], { timeoutMs: 8000 });
  deviceAbiCache = ((r.out || '').trim() || 'arm64-v8a').replace(/-/g, '_'); // arm64-v8a → arm64_v8a
  return deviceAbiCache;
}

const ARCH_TOKENS = ['arm64_v8a', 'armeabi_v7a', 'armeabi', 'x86_64', 'x86', 'mips64', 'mips'];
// arch fallbacks: an arm64 device also runs 32-bit, so keep the best *compatible*
// arch split present (don't drop an armv7a-only bundle just because it isn't arm64)
const ABI_COMPAT = {
  arm64_v8a: ['arm64_v8a', 'armeabi_v7a', 'armeabi'],
  armeabi_v7a: ['armeabi_v7a', 'armeabi'],
  armeabi: ['armeabi'],
  x86_64: ['x86_64', 'x86', 'arm64_v8a', 'armeabi_v7a', 'armeabi'],
  x86: ['x86', 'armeabi_v7a', 'armeabi'],
};
const archOf = (base) => ARCH_TOKENS.find((t) => base.includes('config.' + t) || base.includes('.' + t + '.apk'));

// keep base + all language/density splits + the single best-compatible arch split
function pickSplits(apkFiles, abi) {
  const present = new Set();
  for (const f of apkFiles) { const t = archOf(path.basename(f).toLowerCase()); if (t) present.add(t); }
  const order = ABI_COMPAT[abi] || [abi];
  const chosen = order.find((a) => present.has(a)) || null; // null → no arch splits (base carries libs)
  return apkFiles.filter((f) => {
    const t = archOf(path.basename(f).toLowerCase());
    return !t || t === chosen; // base/lang/dpi always kept; among arch splits keep only the chosen one
  });
}

function extractBundle(bundlePath, outDir) {
  return new Promise((resolve) => {
    try { fs.mkdirSync(outDir, { recursive: true }); } catch {}
    // Windows' bundled tar (bsdtar/libarchive) reads ZIP containers directly
    execFile('tar', ['-xf', bundlePath, '-C', outDir], { windowsHide: true, timeout: 180000 }, (err) => {
      if (err) return resolve({ ok: false, error: 'could not unpack bundle (tar)' });
      const apks = [];
      const walk = (d) => {
        let entries = [];
        try { entries = fs.readdirSync(d, { withFileTypes: true }); } catch { return; }
        for (const e of entries) {
          const full = path.join(d, e.name);
          if (e.isDirectory()) walk(full);
          else if (e.name.toLowerCase().endsWith('.apk')) apks.push(full);
        }
      };
      walk(outDir);
      resolve(apks.length ? { ok: true, apks } : { ok: false, error: 'no APKs inside bundle' });
    });
  });
}

function enqueueBundleInstall(bundlePath, serial) {
  enqueue({
    kind: 'install',
    name: path.basename(bundlePath),
    indeterminate: true,
    run: async () => {
      const outDir = path.join(app.getPath('temp'), `shieldctl-bundle-${Date.now()}`);
      try {
        const ex = await extractBundle(bundlePath, outDir);
        if (!ex.ok) return { ok: false, detail: ex.error };
        const abi = await deviceAbi(serial);
        const splits = pickSplits(ex.apks, abi);
        if (!splits.length) return { ok: false, detail: 'no installable splits for ' + abi };
        const r = await runAdb(['-s', serial, 'install-multiple', '-r', ...splits], { timeoutMs: 600000 });
        return /Success/i.test(r.out)
          ? { ok: true, detail: `Installed — ${splits.length} splits (${abi})` }
          : { ok: false, detail: lastLine(r.err) || lastLine(r.out) || 'install failed' };
      } finally {
        try { fs.rmSync(outDir, { recursive: true, force: true }); } catch {}
      }
    },
  });
}

// ---------------------------------------------------------------------------
// Pulls — no dialogs: everything lands in the PC's Downloads\KodiDrop folder
// ---------------------------------------------------------------------------
function resolvePullDir() {
  const d = resolvePullRoot(app.getPath('downloads'), config.pullDir);
  try { fs.mkdirSync(d, { recursive: true }); } catch {}
  return d;
}

// stream `adb exec-out <cmd>` straight into a local file (binary-safe),
// reporting % when the expected size is known
function execOutToFile(serial, cmdArgs, local, size, report) {
  return new Promise((resolve) => {
    const out = fs.createWriteStream(local);
    const child = spawn(ADB_EXE, ['-s', serial, 'exec-out', ...cmdArgs], { windowsHide: true });
    let err = '';
    let bytes = 0;
    child.stdout.on('data', (d) => {
      bytes += d.length;
      if (!out.write(d)) {
        child.stdout.pause();
        out.once('drain', () => child.stdout.resume());
      }
      if (size > 0 && report) report(Math.min(99, Math.floor((bytes / size) * 100)));
    });
    child.stderr.on('data', (d) => { err = (err + d).slice(-2000); });
    child.on('error', (e) => resolve({ ok: false, detail: String(e.message || e) }));
    child.on('close', (code) => {
      out.end(() => {
        if (code === 0 && bytes > 0) {
          resolve({ ok: true, detail: `Saved to ${local}`, localPath: local });
        } else {
          try { fs.unlinkSync(local); } catch {}
          resolve({ ok: false, detail: lastLine(err) || 'transfer produced no data' });
        }
      });
    });
  });
}

function handlePull({ remotePath, name, size, type }) {
  if (state.status !== 'connected' || !state.serial) return { ok: false, error: 'Not connected' };
  if (typeof remotePath !== 'string' || !remotePath.startsWith('/')) return { ok: false, error: 'bad path' };
  const serial = state.serial;
  const expected = Number(size) > 0 ? Number(size) : 0;
  const isDirectory = type === 'dir';
  enqueue({
    kind: 'pull',
    name: path.posix.basename(remotePath),
    indeterminate: isDirectory,
    run: async (report) => {
      // resolve the local name at run time — queued same-name pulls each get a fresh slot
      const local = uniqueLocalPath(
        resolvePullDir(),
        sanitizeName(name || path.posix.basename(remotePath)),
        { isDirectory },
      );
      const { code, tail } = await spawnAdbProgress(['-s', serial, 'pull', remotePath, local], report);
      if (code === 0) {
        return {
          ok: true,
          detail: `${isDirectory ? 'Folder saved' : 'Saved'} to ${local}`,
          localPath: local,
        };
      }
      // scoped-storage paths deny adb's sync service — root cat gets through
      if (!isDirectory && /permission denied|failed to stat/i.test(tail)) {
        if (rootAvailable === null) await checkRoot();
        if (rootAvailable) {
          const r2 = await execOutToFile(serial, ['su', '-c', `cat ${shq(rootPath(remotePath))}`], local, expected, report);
          if (r2.ok) r2.detail += ' (via root)';
          return r2;
        }
      }
      try { fs.rmSync(local, { recursive: true, force: true }); } catch {}
      return { ok: false, detail: lastLine(tail) || 'pull failed' };
    },
  });
  return { ok: true };
}

// Kodi's log hides behind scoped storage — stream it out via root cat
function pullKodiLog() {
  if (state.status !== 'connected' || !state.serial) return { ok: false, error: 'Not connected' };
  const pkg = status.kodi && status.kodi.package;
  if (!pkg) return { ok: false, error: 'Kodi is not installed' };
  const remote = `/sdcard/Android/data/${pkg}/files/.kodi/temp/kodi.log`;
  const stamp = new Date().toISOString().slice(0, 16).replace(/[T:]/g, '-');
  const local = uniqueLocalPath(resolvePullDir(), `kodi-${stamp}.log`);
  const serial = state.serial;
  enqueue({
    kind: 'pull',
    name: 'kodi.log',
    indeterminate: true,
    run: async () => {
      const r = await execOutToFile(serial, ['su', '-c', `cat ${shq(rootPath(remote))}`], local, 0, null);
      if (!r.ok && r.detail === 'transfer produced no data') r.detail = 'log unreadable (no root?)';
      return r;
    },
  });
  return { ok: true };
}

function takeScreenshot() {
  if (state.status !== 'connected' || !state.serial) return { ok: false, error: 'Not connected' };
  const stamp = new Date().toISOString().slice(0, 19).replace(/[T:]/g, '-');
  const local = uniqueLocalPath(resolvePullDir(), `shield-${stamp}.png`);
  const serial = state.serial;
  enqueue({
    kind: 'pull',
    name: 'screenshot.png',
    indeterminate: true,
    run: () => execOutToFile(serial, ['screencap', '-p'], local, 0, null),
  });
  return { ok: true };
}

// ---------------------------------------------------------------------------
// Device file browser
// ---------------------------------------------------------------------------
const LS_RE = /^([\-dlcbps])[rwxsStT\-]{9}\+?\s+\d+\s+\S+\s+\S+\s+(\d+)(?:,\s*\d+)?\s+(\d{4}-\d{2}-\d{2} \d{2}:\d{2})\s+(.+)$/;

async function listDir(dirPath) {
  if (state.status !== 'connected' || !state.serial) return { ok: false, error: 'Not connected' };
  const p = path.posix.normalize(String(dirPath || config.pushDir));
  let r = await runAdb(['-s', state.serial, 'shell', `ls -al ${shq(p)}`], { timeoutMs: 20000 });
  let viaRoot = false;
  // scoped storage / system dirs deny the shell user — retry with root when we have it
  if ((!r.ok || !r.out.trim()) && /permission denied/i.test(r.err)) {
    if (rootAvailable === null) await checkRoot();
    if (rootAvailable) {
      r = await runAdb(['-s', state.serial, 'shell', `su -c ${shq(`ls -al ${shq(rootPath(p))}`)}`], { timeoutMs: 20000 });
      viaRoot = true;
    }
  }
  if (!r.ok && !r.out.trim()) return { ok: false, error: lastLine(r.err) || 'Could not list folder' };

  const entries = [];
  for (const raw of r.out.split(/\r?\n/)) {
    const m = raw.trim().match(LS_RE);
    if (!m) continue;
    let [, type, size, mtime, name] = m;
    if (type === 'l') {
      const i = name.indexOf(' -> ');
      if (i !== -1) name = name.slice(0, i);
    }
    if (name === '.' || name === '..') continue;
    entries.push({
      name,
      type: type === 'd' || type === 'l' ? 'dir' : 'file', // symlinks navigate like dirs
      size: Number(size),
      mtime,
      path: path.posix.join(p, name),
    });
  }
  entries.sort((a, b) =>
    a.type === b.type
      ? a.name.localeCompare(b.name, undefined, { sensitivity: 'base' })
      : a.type === 'dir' ? -1 : 1
  );
  saveConfig({ lastDir: p });
  return { ok: true, path: p, entries, viaRoot };
}

// refusing to rm these even with confirmation — one fat-finger shouldn't wipe the device
const PROTECTED_PATHS = new Set([
  '/', '/system', '/vendor', '/data', '/data/data', '/data/media', '/data/media/0',
  '/sdcard', '/storage', '/storage/emulated', '/storage/emulated/0',
]);

async function deletePath(remotePath) {
  if (state.status !== 'connected' || !state.serial) return { ok: false, error: 'Not connected' };
  if (typeof remotePath !== 'string' || !remotePath.startsWith('/')) return { ok: false, error: 'bad path' };
  const p = path.posix.normalize(remotePath);
  if (PROTECTED_PATHS.has(p) || p.length < 2) return { ok: false, error: 'refusing to delete a protected path' };

  let r = await runAdb(['-s', state.serial, 'shell', `rm -rf ${shq(p)}`], { timeoutMs: 30000 });
  if (!r.ok || /permission denied|read-only/i.test(r.err)) {
    if (rootAvailable === null) await checkRoot();
    if (rootAvailable) {
      r = await runAdb(['-s', state.serial, 'shell', `su -c ${shq(`rm -rf ${shq(rootPath(p))}`)}`], { timeoutMs: 30000 });
    }
  }
  // trust nothing — confirm it's actually gone (checks both the FUSE and raw views)
  const chk = await runAdb(
    ['-s', state.serial, 'shell', `ls -d ${shq(p)} 2>/dev/null; su -c ${shq(`ls -d ${shq(rootPath(p))}`)} 2>/dev/null`],
    { timeoutMs: 15000 }
  );
  if (chk.out.trim()) return { ok: false, error: lastLine(r.err) || 'delete failed — still present' };
  console.log(`[shield] deleted ${p}`);
  return { ok: true };
}

// arbitrary device command for the in-app console (optionally as root)
async function runShell(cmd, asRoot) {
  if (state.status !== 'connected' || !state.serial) return { ok: false, error: 'Not connected' };
  if (typeof cmd !== 'string' || !cmd.trim()) return { ok: false, error: 'empty command' };
  let args;
  if (asRoot) {
    if (rootAvailable === null) await checkRoot();
    if (!rootAvailable) return { ok: false, error: 'root (su) not available on this device' };
    args = ['-s', state.serial, 'shell', `su -c ${shq(cmd)}`];
  } else {
    args = ['-s', state.serial, 'shell', cmd];
  }
  const r = await runAdb(args, { timeoutMs: 45000 });
  return { ok: true, out: r.out, err: r.ok ? r.err : (r.err || `exit ${1}`), asRoot: !!asRoot };
}

// ---------------------------------------------------------------------------
// IPC
// ---------------------------------------------------------------------------
function registerIpc() {
  ipcMain.handle('shield:get-state', () => ({ ...state }));
  ipcMain.handle('update:get-state', () => ({ ...updateState }));
  ipcMain.handle('update:check', () => checkForUpdates());
  ipcMain.handle('update:action', () => handleUpdateAction());
  ipcMain.handle('shield:reconnect', () => {
    propsCache.clear();
    deviceAbiCache = null; // re-probe arch in case a different device/transport settles
    connectFlow('manual');
    return true;
  });
  ipcMain.handle('config:get', () => ({
    pushDir: config.pushDir,
    kodiDropDir: config.kodiDropDir,
    pullDir: resolvePullDir(),
    backupDir: config.backupDir,
  }));
  ipcMain.handle('status:get', () => ({ ...status }));
  ipcMain.handle('status:refresh', () => {
    collectFast();
    collectSlow();
    return true;
  });
  ipcMain.handle('kodi:pull-log', () => pullKodiLog());
  ipcMain.handle('kodi:start', async () => {
    const pkg = status.kodi && status.kodi.package;
    if (!pkg) return { ok: false, error: 'Kodi is not installed' };
    if (state.status !== 'connected') return { ok: false, error: 'Not connected' };
    await runAdb(['-s', state.serial, 'shell', `monkey --pct-syskeys 0 -p ${pkg} 1`], { timeoutMs: 15000 });
    setTimeout(collectFast, 2500);
    return { ok: true };
  });
  ipcMain.handle('kodi:stop', async () => {
    const pkg = status.kodi && status.kodi.package;
    if (!pkg) return { ok: false, error: 'Kodi is not installed' };
    if (state.status !== 'connected') return { ok: false, error: 'Not connected' };
    await runAdb(['-s', state.serial, 'shell', `am force-stop ${pkg}`], { timeoutMs: 15000 });
    setTimeout(collectFast, 1500);
    return { ok: true };
  });
  ipcMain.handle('shield:screenshot', () => takeScreenshot());
  ipcMain.handle('shield:reboot', async () => {
    if (state.status !== 'connected' || !state.serial) return { ok: false, error: 'Not connected' };
    await runAdb(['-s', state.serial, 'reboot'], { timeoutMs: 8000 });
    setState({
      status: 'connecting', transport: null, serial: null,
      detail: 'Shield is rebooting — reconnecting automatically…',
    });
    return { ok: true };
  });
  ipcMain.handle('scrcpy:launch', () => launchScrcpy());
  ipcMain.handle('scrcpy:stop', () => stopScrcpy());
  ipcMain.handle('scrcpy:get', () => ({ running: !!scrcpyProc }));
  ipcMain.handle('fs:list', (_e, p) => listDir(p));
  ipcMain.handle('fs:home', () => config.lastDir || config.pushDir);
  ipcMain.handle('fs:pull', (_e, payload) => handlePull(payload || {}));
  ipcMain.handle('downloads:open', async () => {
    const error = await shell.openPath(resolvePullDir());
    return error ? { ok: false, error } : { ok: true };
  });
  ipcMain.handle('fs:send', async (_e, { paths, remoteDir } = {}) => {
    if (state.status !== 'connected' || !state.serial) return { ok: false, error: 'Shield is not connected' };
    const serial = state.serial;
    const dir = typeof remoteDir === 'string' && remoteDir.startsWith('/')
      ? path.posix.normalize(remoteDir)
      : config.pushDir;
    const files = (Array.isArray(paths) ? paths : [])
      .filter((p) => typeof p === 'string' && p && fs.existsSync(p));
    if (!files.length) return { ok: false, error: 'Nothing to send' };
    if (files.some((p) => !isApkLike(p))) {
      await runAdb(['-s', serial, 'shell', `mkdir -p ${shq(dir)}`]);
    }
    for (const p of files) {
      if (isBundle(p)) enqueueBundleInstall(p, serial);
      else if (p.toLowerCase().endsWith('.apk')) enqueueInstall(p, serial);
      else enqueuePush(p, dir, serial);
    }
    return { ok: true, queued: files.length, dir };
  });
  ipcMain.handle('fs:delete', (_e, p) => deletePath(p));
  ipcMain.handle('shell:exec', (_e, { cmd, root } = {}) => runShell(cmd, !!root));
  ipcMain.handle('morphe:status', () => collectMorphe());
  ipcMain.handle('morphe:open', () => openMorphe());
  ipcMain.handle('apps:catalog', () => collectApps());
  ipcMain.handle('apps:open', (_e, pkg) => openApp(pkg));
  ipcMain.handle('apps:uninstall', (_e, pkg) => uninstallApp(pkg));
  ipcMain.handle('apps:page', (_e, url) => openSourcePage(url));
  ipcMain.handle('apps:install-url', (_e, url) => installFromUrl(url));
  ipcMain.handle('apps:list', (_e, includeSystem) => listInstalledApps(!!includeSystem));
  ipcMain.handle('apps:manage', (_e, { pkg, action } = {}) => appManage(pkg, action));
  ipcMain.handle('apps:trim', () => trimCaches());
  ipcMain.handle('input:key', (_e, code) => sendKey(code));
  ipcMain.handle('input:text', (_e, text) => sendText(text));
  ipcMain.handle('reveal', (_e, p) => {
    if (typeof p === 'string' && p && fs.existsSync(p)) shell.showItemInFolder(p);
  });
}

// ---------------------------------------------------------------------------
// Window + app lifecycle
// ---------------------------------------------------------------------------
// ignore remembered bounds that would restore off-screen (monitor unplugged)
function safeBounds() {
  const b = config.bounds;
  if (!b || typeof b.x !== 'number' || typeof b.width !== 'number') return {};
  const visible = screen.getAllDisplays().some((d) => {
    const a = d.workArea;
    return b.x < a.x + a.width - 40 && b.x + b.width > a.x + 40 &&
           b.y < a.y + a.height - 40 && b.y >= a.y - 20;
  });
  return visible ? b : {};
}

function createWindow() {
  const b = safeBounds();
  win = new BrowserWindow({
    width: b.width || 1180,
    height: b.height || 780,
    x: b.x,
    y: b.y,
    minWidth: 960,
    minHeight: 640,
    show: false,
    backgroundColor: '#0c0e11',
    autoHideMenuBar: true,
    title: 'Shield Control',
    icon: path.join(__dirname, 'build', 'icon.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      spellcheck: false,
    },
  });
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  win.once('ready-to-show', () => win.show());
  // catch up instantly when the user comes back instead of waiting a poll tick
  const wake = () => { if (Date.now() - (status.ts || 0) > 4000) collectFast(); };
  win.on('focus', wake);
  win.on('restore', wake);
  win.on('close', () => { try { saveConfig({ bounds: win.getBounds() }); flushConfigSync(); } catch {} });
  win.on('closed', () => { win = null; });
}

// smoke mode: run alongside a real instance and never touch its adb server
const gotLock = process.env.SHIELD_SMOKE ? true : app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (win) {
      if (win.isMinimized()) win.restore();
      win.focus();
    }
  });

  app.whenReady().then(async () => {
    app.setAppUserModelId('dev.roesler.shieldcontrol');
    console.log('[shield] adb:', ADB_EXE, '— found =', binaries.adb);
    console.log('[shield] scrcpy:', SCRCPY_EXE, '— found =', binaries.scrcpy);
    registerIpc();
    createWindow();
    configureAutoUpdates();
    runAdb(['version']).then((r) => {
      const m = r.out.match(/Version ([\d.]+)/);
      hostAdbVersion = m ? m[1] : '';
    });
    connectFlow('launch');
    startPolling();
    startStatusPolling();
    if (process.env.SHIELD_SMOKE) setTimeout(() => app.quit(), 25000); // test-harness auto-exit
  });

  app.on('window-all-closed', () => app.quit());

  app.on('will-quit', () => {
    quitting = true;
    if (firstUpdateTimer) clearTimeout(firstUpdateTimer);
    if (updateTimer) clearInterval(updateTimer);
    flushConfigSync();
    killRemoteShell();
    try { if (trackProc) trackProc.kill(); } catch {}
    try { if (scrcpyProc) scrcpyProc.kill(); } catch {}
    // leave no orphan adb.exe behind — reconnect on next launch takes ~1s
    try {
      if (binaries.adb && !process.env.SHIELD_SMOKE) {
        execFileSync(ADB_EXE, ['kill-server'], { timeout: 4000, windowsHide: true });
      }
    } catch {}
  });
}
