const fs = require('fs');
const path = require('path');

function sanitizeName(name) {
  return String(name || '').replace(/[<>:"/\\|?*]/g, '_').trim() || 'download';
}

function resolvePullRoot(downloadsDirectory, configuredDirectory) {
  const configured = typeof configuredDirectory === 'string' ? configuredDirectory.trim() : '';
  return configured || path.join(downloadsDirectory, 'KodiDrop');
}

function uniqueLocalPath(directory, name, { isDirectory = false } = {}) {
  const safeName = sanitizeName(name);
  let candidate = path.join(directory, safeName);
  if (!fs.existsSync(candidate)) return candidate;

  const extension = isDirectory ? '' : path.extname(safeName);
  const base = safeName.slice(0, safeName.length - extension.length);
  for (let index = 1; index < 1000; index++) {
    candidate = path.join(directory, `${base} (${index})${extension}`);
    if (!fs.existsSync(candidate)) return candidate;
  }
  return path.join(directory, `${base}-${Date.now()}${extension}`);
}

module.exports = { resolvePullRoot, sanitizeName, uniqueLocalPath };
