'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {
  clampUpdatePercent,
  detectUpdateMode,
  updateErrorMessage,
} = require('../lib/update-policy');

test('detects development, portable, and installed update modes', () => {
  assert.equal(detectUpdateMode(false), 'development');
  assert.equal(detectUpdateMode(true, 'C:\\ShieldControl.exe'), 'portable');
  assert.equal(detectUpdateMode(true), 'installed');
});

test('normalizes update progress for display', () => {
  assert.equal(clampUpdatePercent(-2), 0);
  assert.equal(clampUpdatePercent(48.7), 49);
  assert.equal(clampUpdatePercent(104), 100);
  assert.equal(clampUpdatePercent('not-a-number'), 0);
});

test('turns updater errors into a bounded single-line message', () => {
  assert.equal(updateErrorMessage(new Error('feed\n unavailable')), 'feed unavailable');
  assert.equal(updateErrorMessage({}), '[object Object]');
  assert.equal(updateErrorMessage(new Error('x'.repeat(300))).length, 220);
});
