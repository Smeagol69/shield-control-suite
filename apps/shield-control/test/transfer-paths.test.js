const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

const { resolvePullRoot, sanitizeName, uniqueLocalPath } = require('../lib/transfer-paths');

test('defaults downloads to the KodiDrop folder', () => {
  assert.equal(
    resolvePullRoot('C:\\Users\\viewer\\Downloads', null),
    path.join('C:\\Users\\viewer\\Downloads', 'KodiDrop'),
  );
  assert.equal(resolvePullRoot('ignored', 'D:\\Shield'), 'D:\\Shield');
});

test('sanitizes Windows-reserved filename characters', () => {
  assert.equal(sanitizeName('Kodi: log?.txt'), 'Kodi_ log_.txt');
  assert.equal(sanitizeName('   '), 'download');
});

test('creates collision-safe file and folder paths', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'shield-control-paths-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));

  fs.writeFileSync(path.join(root, 'kodi.log'), 'existing');
  fs.mkdirSync(path.join(root, 'Backups'));

  assert.equal(uniqueLocalPath(root, 'kodi.log'), path.join(root, 'kodi (1).log'));
  assert.equal(
    uniqueLocalPath(root, 'Backups', { isDirectory: true }),
    path.join(root, 'Backups (1)'),
  );
});
