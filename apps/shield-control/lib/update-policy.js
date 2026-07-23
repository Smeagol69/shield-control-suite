'use strict';

function detectUpdateMode(isPackaged, portableExecutableFile) {
  if (!isPackaged) return 'development';
  return portableExecutableFile ? 'portable' : 'installed';
}

function clampUpdatePercent(value) {
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return 0;
  return Math.max(0, Math.min(100, Math.round(numeric)));
}

function updateErrorMessage(error) {
  const message = String(error && (error.message || error) || 'Update check failed');
  return message.replace(/\s+/g, ' ').slice(0, 220);
}

module.exports = {
  clampUpdatePercent,
  detectUpdateMode,
  updateErrorMessage,
};
