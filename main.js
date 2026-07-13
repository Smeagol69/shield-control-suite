const { app, BrowserWindow, ipcMain, shell } = require('electron');
const path = require('path');
const fs = require('fs');
const { spawn, execFile, execFileSync } = require('child_process');

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
  pullDir: null,                          // null → this PC's Downloads folder
  backupDir: '/sdcard/backup',            // scanned for "last backup" status
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

function saveConfig(patch) {
  Object.assign(config, patch);
  try {
    fs.mkdirSync(path.dirname(configFile()), { recursive: true });
    fs.writeFileSync(configFile(), JSON.stringify(config, null, 2));
  } catch {}
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
    if (state.status !== 'connected' && status.online) {
      status.online = false;
      pushStatus();
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

function startPolling() {
  setInterval(async () => {
    if (connectBusy || !binaries.adb) return;
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
  }, 4000);
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
  'echo ":::kodi"',
  'pm list packages org.xbmc 2>/dev/null | sed "s/^package://" | head -5',
  'echo ":::kodipid"',
  'K=$(pm list packages org.xbmc 2>/dev/null | head -1 | sed "s/^package://")',
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

let loggedFirstStatus = false;
async function collectFast() {
  if (fastBusy || state.status !== 'connected' || !state.serial) return;
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
  if (slowBusy || state.status !== 'connected' || !state.serial) return;
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
    // aac: the Shield (Android 11) has no Opus encoder, and that failure kills the stream
    ['-s', state.serial, '--window-title', title, '--stay-awake', '--audio-codec=aac'],
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
    const res = await job.run((pct) => { t.pct = pct; sendTransfer(t); });
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

// ---------------------------------------------------------------------------
// Pulls — no dialogs: everything lands in the PC's Downloads folder
// ---------------------------------------------------------------------------
const sanitizeName = (n) => String(n).replace(/[<>:"/\\|?*]/g, '_');

function resolvePullDir() {
  const d = config.pullDir || app.getPath('downloads');
  try { fs.mkdirSync(d, { recursive: true }); } catch {}
  return d;
}

function uniqueLocalPath(dir, name) {
  let p = path.join(dir, name);
  if (!fs.existsSync(p)) return p;
  const ext = path.extname(name);
  const base = name.slice(0, name.length - ext.length);
  for (let i = 1; i < 1000; i++) {
    p = path.join(dir, `${base} (${i})${ext}`);
    if (!fs.existsSync(p)) return p;
  }
  return path.join(dir, `${base}-${Date.now()}${ext}`);
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

function handlePull({ remotePath, name, size }) {
  if (state.status !== 'connected' || !state.serial) return { ok: false, error: 'Not connected' };
  if (typeof remotePath !== 'string' || !remotePath.startsWith('/')) return { ok: false, error: 'bad path' };
  const serial = state.serial;
  const local = uniqueLocalPath(resolvePullDir(), sanitizeName(name || path.posix.basename(remotePath)));
  const expected = Number(size) > 0 ? Number(size) : 0;
  enqueue({
    kind: 'pull',
    name: path.posix.basename(remotePath),
    run: async (report) => {
      const { code, tail } = await spawnAdbProgress(['-s', serial, 'pull', remotePath, local], report);
      if (code === 0) return { ok: true, detail: `Saved to ${local}`, localPath: local };
      // scoped-storage paths deny adb's sync service — root cat gets through
      if (/permission denied|failed to stat/i.test(tail)) {
        if (rootAvailable === null) await checkRoot();
        if (rootAvailable) {
          const r2 = await execOutToFile(serial, ['su', '-c', `cat ${shq(rootPath(remotePath))}`], local, expected, report);
          if (r2.ok) r2.detail += ' (via root)';
          return r2;
        }
      }
      return { ok: false, detail: lastLine(tail) || 'pull failed' };
    },
  });
  return { ok: true, dest: local };
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

// ---------------------------------------------------------------------------
// IPC
// ---------------------------------------------------------------------------
function registerIpc() {
  ipcMain.handle('shield:get-state', () => ({ ...state }));
  ipcMain.handle('shield:reconnect', () => {
    propsCache.clear();
    connectFlow('manual');
    return true;
  });
  ipcMain.handle('config:get', () => ({
    pushDir: config.pushDir,
    kodiDropDir: config.kodiDropDir,
    pullDir: config.pullDir || app.getPath('downloads'),
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
  ipcMain.handle('fs:send', async (_e, { paths, remoteDir } = {}) => {
    if (state.status !== 'connected' || !state.serial) return { ok: false, error: 'Shield is not connected' };
    const serial = state.serial;
    const dir = typeof remoteDir === 'string' && remoteDir.startsWith('/')
      ? path.posix.normalize(remoteDir)
      : config.pushDir;
    const files = (Array.isArray(paths) ? paths : [])
      .filter((p) => typeof p === 'string' && p && fs.existsSync(p));
    if (!files.length) return { ok: false, error: 'Nothing to send' };
    if (files.some((p) => !p.toLowerCase().endsWith('.apk'))) {
      await runAdb(['-s', serial, 'shell', `mkdir -p ${shq(dir)}`]);
    }
    for (const p of files) {
      if (p.toLowerCase().endsWith('.apk')) enqueueInstall(p, serial);
      else enqueuePush(p, dir, serial);
    }
    return { ok: true, queued: files.length, dir };
  });
  ipcMain.handle('reveal', (_e, p) => {
    if (typeof p === 'string' && p && fs.existsSync(p)) shell.showItemInFolder(p);
  });
}

// ---------------------------------------------------------------------------
// Window + app lifecycle
// ---------------------------------------------------------------------------
function createWindow() {
  const b = config.bounds || {};
  win = new BrowserWindow({
    width: b.width || 1180,
    height: b.height || 780,
    x: b.x,
    y: b.y,
    minWidth: 960,
    minHeight: 640,
    backgroundColor: '#0c0e11',
    autoHideMenuBar: true,
    title: 'Shield Control',
    icon: path.join(__dirname, 'build', 'icon.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      spellcheck: false,
    },
  });
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  win.on('close', () => { try { saveConfig({ bounds: win.getBounds() }); } catch {} });
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
    try { if (scrcpyProc) scrcpyProc.kill(); } catch {}
    // leave no orphan adb.exe behind — reconnect on next launch takes ~1s
    try {
      if (binaries.adb && !process.env.SHIELD_SMOKE) {
        execFileSync(ADB_EXE, ['kill-server'], { timeout: 4000, windowsHide: true });
      }
    } catch {}
  });
}
