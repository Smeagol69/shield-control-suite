const $ = (s) => document.querySelector(s);

const els = {
  statusText: $('#statusText'),
  deviceName: $('#deviceName'),
  deviceMeta: $('#deviceMeta'),
  controlBtn: $('#controlBtn'),
  controlLabel: $('#controlLabel'),
  reconnectBtn: $('#reconnectBtn'),
  screenshotBtn: $('#screenshotBtn'),
  rebootBtn: $('#rebootBtn'),
  statusBody: $('#statusBody'),
  statusRefresh: $('#statusRefresh'),
  crumbs: $('#crumbs'),
  rootChip: $('#rootChip'),
  navchips: $('#navchips'),
  listing: $('#listing'),
  upBtn: $('#upBtn'),
  refreshBtn: $('#refreshBtn'),
  veil: $('#veil'),
  vtDownload: $('#vtDownload'),
  vtKodi: $('#vtKodi'),
  vtHere: $('#vtHere'),
  vtDownloadPath: $('#vtDownloadPath'),
  vtKodiPath: $('#vtKodiPath'),
  vtHerePath: $('#vtHerePath'),
  transfers: $('#transfers'),
  toasts: $('#toasts'),
  logOverlay: $('#logOverlay'),
  logLines: $('#logLines'),
  logClose: $('#logClose'),
  logPull: $('#logPull'),
};

const SVG_FOLDER =
  '<svg viewBox="0 0 24 24" width="17" height="17"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7z" fill="currentColor"/></svg>';
const SVG_FILE =
  '<svg viewBox="0 0 24 24" width="17" height="17"><path d="M6 2h8l4 4v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z" fill="none" stroke="currentColor" stroke-width="1.6"/><path d="M14 2v4h4" fill="none" stroke="currentColor" stroke-width="1.6"/></svg>';
const SVG_DOWNLOAD =
  '<svg viewBox="0 0 24 24" width="15" height="15"><path d="M12 4v10m0 0 4-4m-4 4-4-4M4 19h16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>';

let state = { status: 'connecting', binaries: { adb: true, scrcpy: true } };
let deviceStatus = { online: false };
let cfg = { pushDir: '/sdcard/Download', kodiDropDir: '/sdcard/Download/KodiDrop' };
let scrcpyRunning = false;
let scrcpyStarting = false;
let currentDir = '/sdcard/Download';
let listingFresh = false;
let loadingDir = false;

const KIND_LABEL = { push: 'To Shield', pull: 'To PC', install: 'Install APK' };

function fmtSize(n) {
  if (n == null || Number.isNaN(n)) return '';
  const u = ['B', 'KB', 'MB', 'GB', 'TB'];
  let v = n;
  let i = 0;
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
  return i === 0 ? `${v} B` : `${v.toFixed(1)} ${u[i]}`;
}

function fmtUptime(sec) {
  if (sec == null) return '—';
  const d = Math.floor(sec / 86400);
  const h = Math.floor((sec % 86400) / 3600);
  const m = Math.floor((sec % 3600) / 60);
  if (d > 0) return `${d}d ${h}h ${m}m`;
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}

function toast(msg, kind = 'error') {
  const d = document.createElement('div');
  d.className = 'toast' + (kind === 'info' ? ' info' : '');
  d.textContent = msg;
  els.toasts.append(d);
  setTimeout(() => d.remove(), 7000);
}

// ---------------- connection state ----------------
function applyState(s) {
  state = s;
  document.body.dataset.status = s.status;

  const labels = {
    connected: s.transport === 'usb' ? 'Connected · USB' : `Connected · ${s.serial || ''}`,
    connecting: 'Connecting…',
    unauthorized: 'Authorize on TV',
    disconnected: 'Disconnected',
  };
  els.statusText.textContent = labels[s.status] || s.status;

  els.deviceName.textContent = s.model || 'NVIDIA Shield';
  if (s.status === 'connected') {
    els.deviceMeta.textContent =
      `Android ${s.android || '?'} · ${s.transport === 'wifi' ? 'WiFi' : 'USB'} · ${s.serial}` +
      (s.detail ? ` · ${s.detail}` : '');
  } else {
    els.deviceMeta.textContent = s.detail || (s.status === 'connecting' ? `Looking for ${s.ip}…` : '—');
  }

  const off = s.status !== 'connected';
  els.screenshotBtn.disabled = off;
  els.rebootBtn.disabled = off;

  updateControlBtn();
  renderStatus();

  if (s.status === 'connected' && !listingFresh && !loadingDir) refresh(currentDir);
  if (s.status !== 'connected') listingFresh = false;
}

function updateControlBtn() {
  const b = els.controlBtn;
  b.classList.toggle('running', scrcpyRunning);
  if (scrcpyRunning) {
    b.disabled = false;
    els.controlLabel.textContent = 'Stop Controlling';
    b.title = 'Close the scrcpy window';
  } else if (scrcpyStarting) {
    b.disabled = true;
    els.controlLabel.textContent = 'Starting…';
    b.title = '';
  } else {
    b.disabled = state.status !== 'connected' || !(state.binaries && state.binaries.scrcpy);
    els.controlLabel.textContent = 'Control Shield';
    b.title = b.disabled ? 'Waiting for the Shield connection' : 'Open live view (scrcpy)';
  }
}

els.controlBtn.addEventListener('click', async () => {
  if (scrcpyRunning) {
    await window.shield.scrcpyStop();
    return;
  }
  scrcpyStarting = true;
  updateControlBtn();
  const r = await window.shield.scrcpyLaunch();
  if (!r.ok) {
    scrcpyStarting = false;
    updateControlBtn();
    toast(r.error || 'Could not start scrcpy');
  }
});

window.shield.onScrcpy((s) => {
  scrcpyRunning = !!s.running;
  scrcpyStarting = false;
  updateControlBtn();
  if (s.error) toast(s.error);
});

els.reconnectBtn.addEventListener('click', () => window.shield.reconnect());
els.screenshotBtn.addEventListener('click', async () => {
  const r = await window.shield.screenshot();
  if (!r.ok) toast(r.error || 'Screenshot failed');
});

// reboot needs a second click within 4 s — no accidental reboots
let rebootArmed = null;
els.rebootBtn.addEventListener('click', async () => {
  if (rebootArmed) {
    clearTimeout(rebootArmed);
    rebootArmed = null;
    els.rebootBtn.classList.remove('arm');
    els.rebootBtn.title = 'Reboot the Shield';
    const r = await window.shield.reboot();
    if (r.ok) toast('Rebooting the Shield — it will reconnect automatically', 'info');
    else toast(r.error || 'Reboot failed');
    return;
  }
  els.rebootBtn.classList.add('arm');
  els.rebootBtn.title = 'Click again to confirm reboot';
  rebootArmed = setTimeout(() => {
    rebootArmed = null;
    els.rebootBtn.classList.remove('arm');
    els.rebootBtn.title = 'Reboot the Shield';
  }, 4000);
});

// ---------------- status card ----------------
els.statusRefresh.addEventListener('click', () => {
  window.shield.refreshStatus();
  toast('Refreshing status…', 'info');
});

window.shield.onStatus((s) => {
  deviceStatus = s;
  renderStatus();
});

function sRow(label, text, { tone, dot, bar, barTone, title, action } = {}) {
  const row = document.createElement('div');
  row.className = 'srow';
  const l = document.createElement('span');
  l.className = 'slabel';
  l.textContent = label;
  const v = document.createElement('span');
  v.className = 'svalue' + (tone ? ` ${tone}` : '');
  if (dot) {
    const d = document.createElement('span');
    d.className = `sdot ${dot}`;
    v.append(d);
  }
  const t = document.createElement('span');
  t.textContent = text;
  if (title) t.title = title;
  v.append(t);
  row.append(l, v);
  if (action) {
    const b = document.createElement('button');
    b.className = 'ghost saction';
    b.textContent = action.label;
    b.addEventListener('click', action.onClick);
    row.append(b);
  } else {
    row.append(document.createElement('span'));
  }
  const frag = document.createDocumentFragment();
  frag.append(row);
  if (bar != null) {
    const wrap = document.createElement('div');
    wrap.className = 'srow';
    wrap.style.minHeight = '0';
    const spacer = document.createElement('span');
    const b = document.createElement('div');
    b.className = 'sbar' + (barTone ? ` ${barTone}` : '');
    const fill = document.createElement('i');
    fill.style.width = `${Math.max(0, Math.min(100, bar))}%`;
    b.append(fill);
    wrap.append(spacer, b);
    frag.append(wrap);
  }
  return frag;
}

function sSection(label) {
  const el = document.createElement('div');
  el.className = 'ssection';
  el.textContent = label;
  return el;
}

function renderStatus() {
  const st = deviceStatus;
  const body = els.statusBody;
  body.replaceChildren();
  body.classList.toggle('stale', !st.online);

  if (!st.ts) {
    const empty = document.createElement('div');
    empty.className = 'status-empty';
    empty.textContent = state.status === 'connected' ? 'Reading device status…' : 'Waiting for the Shield…';
    body.append(empty);
    return;
  }

  body.append(sSection('Device'));

  body.append(sRow('Shield',
    st.online ? `Online · ${st.transport === 'usb' ? 'USB' : 'WiFi'}` : 'Offline',
    { dot: st.online ? 'ok' : 'bad', tone: st.online ? 'good' : 'bad' }));

  body.append(sRow('IP address', st.ip ? `${st.ip}${st.iface ? ` (${st.iface})` : ''}` : '—',
    { tone: st.ip ? 'good' : 'dim' }));

  if (st.storage) {
    const used = st.storage.totalKb - st.storage.availKb;
    const pct = Math.round((used / st.storage.totalKb) * 100);
    body.append(sRow('Storage',
      `${fmtSize(st.storage.availKb * 1024)} free of ${fmtSize(st.storage.totalKb * 1024)}`,
      { bar: pct, barTone: pct > 90 ? 'bad' : pct > 75 ? 'warn' : '', title: `${pct}% used` }));
  } else {
    body.append(sRow('Storage', '—', { tone: 'dim' }));
  }

  if (st.mem) {
    const usedKb = st.mem.totalKb - st.mem.availKb;
    const pct = Math.round((usedKb / st.mem.totalKb) * 100);
    body.append(sRow('Memory',
      `${fmtSize(usedKb * 1024)} of ${fmtSize(st.mem.totalKb * 1024)} used`,
      { bar: pct, barTone: pct > 92 ? 'bad' : pct > 80 ? 'warn' : '', title: `${pct}%` }));
  } else {
    body.append(sRow('Memory', '—', { tone: 'dim' }));
  }

  const t = st.cpuTempC;
  body.append(sRow('CPU temp',
    t != null ? `${Math.round(t)} °C${st.thermalStatus > 0 ? ' · throttling' : ''}` : '—',
    {
      tone: t == null ? 'dim' : t >= 85 || st.thermalStatus > 0 ? 'bad' : t >= 70 ? 'warn' : 'good',
      title: st.gpuTempC != null ? `GPU: ${Math.round(st.gpuTempC)} °C` : undefined,
    }));

  body.append(sRow('Uptime', fmtUptime(st.uptimeSec), {
    tone: st.uptimeSec != null ? 'good' : 'dim',
    title: st.uptimeSec != null ? `Booted ${new Date(Date.now() - st.uptimeSec * 1000).toLocaleString()}` : undefined,
  }));

  body.append(sSection('Kodi'));

  const k = st.kodi || {};
  body.append(sRow('Kodi',
    !k.installed ? 'Not installed' : k.running ? `Running · pid ${k.pid}` : 'Stopped',
    {
      dot: !k.installed ? undefined : k.running ? 'ok' : 'idle',
      tone: !k.installed ? 'dim' : k.running ? 'good' : undefined,
      action: k.installed && st.online
        ? {
            label: k.running ? 'Stop' : 'Start',
            onClick: async () => {
              const r = k.running ? await window.shield.kodiStop() : await window.shield.kodiStart();
              if (!r.ok) toast(r.error || 'Kodi action failed');
            },
          }
        : undefined,
    }));

  const p = st.playback;
  body.append(sRow('Playback',
    p ? `${p.playing ? '▶' : '⏸'} ${p.title || '(no title)'} · ${p.app}` : 'Nothing playing',
    { tone: p ? 'good' : 'dim', title: p && p.title ? p.title : undefined }));

  const kl = st.kodiLog;
  if (!k.installed || !kl || kl.state === 'nokodi') {
    body.append(sRow('Log errors', '—', { tone: 'dim' }));
  } else if (kl.state === 'nolog') {
    body.append(sRow('Log errors', 'no log file yet', { tone: 'dim' }));
  } else {
    body.append(sRow('Log errors',
      kl.count === 0 ? 'none in recent log' : `${kl.count} in recent log`,
      {
        tone: kl.count === 0 ? 'good' : 'warn',
        dot: kl.count === 0 ? 'ok' : 'warn',
        action: kl.count > 0 ? { label: 'View', onClick: () => openLogOverlay(kl) } : undefined,
      }));
  }

  const b = st.backup;
  if (!b || b.state === 'nodir') {
    body.append(sRow('Last backup', `none yet · ${b && b.dir ? b.dir : '/sdcard/backup'}`,
      { tone: 'dim', title: 'Set "backupDir" in config.json if your backups live elsewhere' }));
  } else if (b.state === 'empty') {
    body.append(sRow('Last backup', `folder is empty · ${b.dir}`, { tone: 'dim' }));
  } else {
    const age = Date.now() - b.mtime;
    const stale = age > 14 * 86400000;
    body.append(sRow('Last backup',
      `${new Date(b.mtime).toLocaleDateString()} · ${b.name}`,
      { tone: stale ? 'warn' : 'good', title: new Date(b.mtime).toLocaleString() }));
  }

  body.append(sSection('Services'));

  body.append(sRow('ADB (this PC)',
    st.hostAdbVersion ? `server running · v${st.hostAdbVersion}` : 'server running',
    { dot: 'ok', tone: 'good' }));

  const port = st.adbPort || {};
  body.append(sRow('WiFi bridge',
    port.service || port.persist
      ? `adbd listening on ${port.service || port.persist}${port.persist ? ' · persistent' : ''}`
      : 'TCP port not set',
    {
      dot: port.service || port.persist ? 'ok' : 'warn',
      tone: port.service || port.persist ? 'good' : 'warn',
      title: 'service.adb.tcp.port / persist.adb.tcp.port',
    }));

  body.append(sRow('Root (su)',
    st.root === true ? 'available · Magisk' : st.root === false ? 'not available' : 'checking…',
    { dot: st.root === true ? 'ok' : st.root === false ? 'idle' : undefined, tone: st.root === true ? 'good' : 'dim' }));
}

// ---------------- kodi log overlay ----------------
function openLogOverlay(kl) {
  els.logLines.textContent = (kl.lines && kl.lines.length)
    ? kl.lines.join('\n\n')
    : 'No error lines captured.';
  els.logOverlay.classList.add('show');
}
els.logClose.addEventListener('click', () => els.logOverlay.classList.remove('show'));
els.logOverlay.addEventListener('click', (e) => {
  if (e.target === els.logOverlay) els.logOverlay.classList.remove('show');
});
els.logPull.addEventListener('click', async () => {
  const r = await window.shield.kodiPullLog();
  if (!r.ok) toast(r.error || 'Could not pull kodi.log');
  else els.logOverlay.classList.remove('show');
});

// ---------------- file browser ----------------
async function refresh(dir) {
  if (loadingDir) return;
  loadingDir = true;
  els.listing.classList.add('loading');
  const res = await window.shield.listDir(dir);
  loadingDir = false;
  els.listing.classList.remove('loading');

  if (!res.ok) {
    renderListError(res.error || 'Could not list folder', dir);
    return;
  }
  currentDir = res.path;
  listingFresh = true;
  els.rootChip.hidden = !res.viaRoot;
  renderCrumbs();
  renderNavChips();
  renderEntries(res.entries);
}

function renderCrumbs() {
  els.crumbs.replaceChildren();
  const mk = (label, p) => {
    const b = document.createElement('button');
    b.textContent = label;
    b.title = p;
    b.addEventListener('click', () => refresh(p));
    return b;
  };
  els.crumbs.append(mk('Shield', '/'));
  let acc = '';
  for (const seg of currentDir.split('/').filter(Boolean)) {
    const sep = document.createElement('span');
    sep.className = 'sep';
    sep.textContent = '›';
    els.crumbs.append(sep);
    acc += '/' + seg;
    els.crumbs.append(mk(seg, acc));
  }
  els.crumbs.scrollLeft = els.crumbs.scrollWidth;
}

function navChipDefs() {
  const chips = [
    ['Home', '/sdcard'],
    ['Download', cfg.pushDir],
    ['KodiDrop', cfg.kodiDropDir],
    ['Movies', '/sdcard/Movies'],
  ];
  const seen = new Set();
  return chips.filter(([, p]) => p && !seen.has(p) && seen.add(p));
}

function renderNavChips() {
  els.navchips.replaceChildren();
  for (const [label, p] of navChipDefs()) {
    const b = document.createElement('button');
    b.textContent = label;
    b.title = p;
    if (p === currentDir) b.classList.add('active');
    b.addEventListener('click', () => refresh(p));
    els.navchips.append(b);
  }
}

function renderListError(msg, dir) {
  els.listing.replaceChildren();
  const box = document.createElement('div');
  box.className = 'list-error';
  const t = document.createElement('span');
  t.textContent = msg;
  const retry = document.createElement('button');
  retry.className = 'ghost';
  retry.textContent = 'Retry';
  retry.addEventListener('click', () => refresh(dir));
  const home = document.createElement('button');
  home.className = 'ghost';
  home.textContent = 'Go to Download';
  home.addEventListener('click', () => refresh(cfg.pushDir));
  box.append(t, retry, home);
  els.listing.append(box);
}

function renderEntries(entries) {
  els.listing.replaceChildren();
  if (!entries.length) {
    const d = document.createElement('div');
    d.className = 'empty';
    d.textContent = 'Empty folder';
    els.listing.append(d);
    return;
  }
  for (const e of entries) {
    const row = document.createElement('div');
    row.className = 'row ' + e.type;

    const icon = document.createElement('span');
    icon.className = 'icon';
    icon.innerHTML = e.type === 'dir' ? SVG_FOLDER : SVG_FILE;

    const name = document.createElement('span');
    name.className = 'name';
    name.textContent = e.name;
    name.title = e.path;

    const size = document.createElement('span');
    size.className = 'size';
    size.textContent = e.type === 'dir' ? '' : fmtSize(e.size);

    const date = document.createElement('span');
    date.className = 'date';
    date.textContent = e.mtime || '';

    row.append(icon, name, size, date);

    if (e.type === 'dir') {
      row.addEventListener('click', () => refresh(e.path));
      row.append(document.createElement('span'));
    } else {
      const pull = document.createElement('button');
      pull.className = 'pull';
      pull.title = 'Download to this PC';
      pull.innerHTML = SVG_DOWNLOAD;
      pull.addEventListener('click', (ev) => {
        ev.stopPropagation();
        window.shield.pull(e.path, e.name, e.size);
      });
      row.append(pull);
    }
    els.listing.append(row);
  }
}

els.upBtn.addEventListener('click', () => {
  if (currentDir !== '/') refresh(currentDir.split('/').slice(0, -1).join('/') || '/');
});
els.refreshBtn.addEventListener('click', () => refresh(currentDir));

// ---------------- drag & drop ----------------
let dragDepth = 0;

function hideVeil() {
  dragDepth = 0;
  els.veil.classList.remove('show');
  document.body.classList.remove('dragging');
  for (const t of [els.vtDownload, els.vtKodi, els.vtHere]) t.classList.remove('hover');
}

function showVeil() {
  els.vtDownload.dataset.dir = cfg.pushDir;
  els.vtDownloadPath.textContent = cfg.pushDir;
  els.vtKodi.dataset.dir = cfg.kodiDropDir;
  els.vtKodiPath.textContent = cfg.kodiDropDir;
  const hereIsDistinct = currentDir !== cfg.pushDir && currentDir !== cfg.kodiDropDir;
  els.vtHere.hidden = !hereIsDistinct;
  els.vtHere.dataset.dir = currentDir;
  els.vtHerePath.textContent = currentDir;
  els.veil.classList.add('show');
  document.body.classList.add('dragging');
}

window.addEventListener('dragenter', (e) => {
  e.preventDefault();
  dragDepth++;
  if (dragDepth === 1) showVeil();
});
window.addEventListener('dragover', (e) => e.preventDefault());
window.addEventListener('dragleave', (e) => {
  e.preventDefault();
  dragDepth = Math.max(0, dragDepth - 1);
  if (!dragDepth) hideVeil();
});

function dropPaths(e) {
  const files = [...((e.dataTransfer && e.dataTransfer.files) || [])];
  return files
    .map((f) => { try { return window.shield.filePath(f); } catch { return null; } })
    .filter(Boolean);
}

async function sendTo(e, dir) {
  e.preventDefault();
  e.stopPropagation();
  const paths = dropPaths(e);
  hideVeil();
  if (!paths.length) return;
  if (state.status !== 'connected') {
    toast('The Shield is not connected — drop ignored');
    return;
  }
  const r = await window.shield.send(paths, dir);
  if (r && !r.ok && r.error) toast(r.error);
}

for (const t of [els.vtDownload, els.vtKodi, els.vtHere]) {
  t.addEventListener('dragover', (e) => {
    e.preventDefault();
    e.stopPropagation();
    t.classList.add('hover');
  });
  t.addEventListener('dragleave', () => t.classList.remove('hover'));
  t.addEventListener('drop', (e) => sendTo(e, t.dataset.dir));
}

// drop on the veil backdrop (not on a target) → default Download folder
window.addEventListener('drop', (e) => sendTo(e, cfg.pushDir));

// ---------------- transfers ----------------
const transfers = new Map();

window.shield.onTransfer((t) => {
  transfers.set(t.id, t);
  if (transfers.size > 40) {
    const oldest = [...transfers.values()].filter((x) => x.status === 'done')[0];
    if (oldest) transfers.delete(oldest.id);
  }
  renderTransfers();
  if (t.status === 'done') {
    if (t.kind === 'push') refresh(currentDir);
    setTimeout(() => {
      if (transfers.get(t.id) === t) {
        transfers.delete(t.id);
        renderTransfers();
      }
    }, 9000);
  }
});

function renderTransfers() {
  els.transfers.replaceChildren();
  for (const t of [...transfers.values()].reverse()) {
    const card = document.createElement('div');
    card.className = `transfer ${t.status}` + (t.pct < 0 && t.status === 'active' ? ' indeterminate' : '');

    const top = document.createElement('div');
    top.className = 't-top';
    const kind = document.createElement('span');
    kind.className = 't-kind';
    kind.textContent = KIND_LABEL[t.kind] || t.kind;
    const name = document.createElement('span');
    name.className = 't-name';
    name.textContent = t.name;
    name.title = t.name;
    top.append(kind, name);

    if (t.status === 'done' || t.status === 'error') {
      const x = document.createElement('button');
      x.className = 't-x';
      x.textContent = '✕';
      x.title = 'Dismiss';
      x.addEventListener('click', (ev) => {
        ev.stopPropagation();
        transfers.delete(t.id);
        renderTransfers();
      });
      top.append(x);
    }

    const bar = document.createElement('div');
    bar.className = 'bar';
    const fill = document.createElement('i');
    if (!(t.pct < 0 && t.status === 'active')) fill.style.width = `${Math.max(0, t.pct)}%`;
    bar.append(fill);

    const detail = document.createElement('div');
    detail.className = 't-detail';
    detail.textContent =
      t.status === 'queued' ? 'Waiting…' :
      t.status === 'active' ? (t.pct >= 0 ? `${t.pct}%` : 'Working…') :
      t.detail || (t.status === 'done' ? 'Done' : 'Failed');

    card.append(top, bar, detail);

    if (t.status === 'done' && t.localPath) {
      card.dataset.reveal = '1';
      card.title = 'Show in folder';
      card.addEventListener('click', () => window.shield.reveal(t.localPath));
    }
    els.transfers.append(card);
  }
}

// ---------------- init ----------------
(async () => {
  window.shield.onState(applyState);
  cfg = await window.shield.getConfig();
  currentDir = (await window.shield.homeDir()) || cfg.pushDir;
  renderNavChips();
  applyState(await window.shield.getState());
  deviceStatus = await window.shield.getStatus();
  renderStatus();
  const s = await window.shield.scrcpyGet();
  scrcpyRunning = !!s.running;
  updateControlBtn();
})();
