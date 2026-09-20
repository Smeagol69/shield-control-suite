const assert = require('node:assert/strict');
const test = require('node:test');

const {
  parseMdnsServices, parseArpNeighbours, mergeCandidates, rankCandidates, looksLikeShield,
} = require('../lib/discovery');

// Captured verbatim from `adb mdns services` on the real network, the day the Shield's DHCP
// lease moved from .6 to .11 and every connection attempt started failing.
const REAL_OUTPUT = [
  'List of discovered mdns services',
  'adb-1320722056308\t_adb._tcp\t10.0.0.11:5555',
].join('\n');

test('parses the real adb mdns output', () => {
  assert.deepEqual(parseMdnsServices(REAL_OUTPUT), [
    {
      name: 'adb-1320722056308',
      service: '_adb._tcp',
      ip: '10.0.0.11',
      port: 5555,
      serial: '10.0.0.11:5555',
    },
  ]);
});

test('accepts the Android 11 wireless-debugging service too', () => {
  const found = parseMdnsServices(
    'adb-XYZ\t_adb-tls-connect._tcp\t192.168.1.42:37175'
  );
  assert.equal(found.length, 1);
  assert.equal(found[0].port, 37175);
  assert.equal(found[0].serial, '192.168.1.42:37175');
});

test('ignores the header and any noise', () => {
  assert.deepEqual(parseMdnsServices('List of discovered mdns services\n\n  \nnonsense'), []);
  assert.deepEqual(parseMdnsServices(''), []);
  assert.deepEqual(parseMdnsServices(undefined), []);
});

test('rejects an out-of-range port rather than trying to connect to it', () => {
  assert.deepEqual(parseMdnsServices('adb-X\t_adb._tcp\t10.0.0.5:70000'), []);
});

test('de-duplicates the same endpoint advertised twice', () => {
  const found = parseMdnsServices(
    'adb-A\t_adb._tcp\t10.0.0.11:5555\nadb-A\t_adb-tls-connect._tcp\t10.0.0.11:5555'
  );
  assert.equal(found.length, 1);
});

test('prefers the device we have talked to before, even at a new address', () => {
  const candidates = parseMdnsServices(
    [
      'adb-stranger\t_adb._tcp\t10.0.0.50:5555',
      'adb-ours\t_adb._tcp\t10.0.0.11:5555',
    ].join('\n')
  );
  // The remembered IP is stale - this is exactly the DHCP-move case - so the instance name is
  // the only thing that still identifies our Shield.
  const ranked = rankCandidates(candidates, { knownName: 'adb-ours', knownIp: '10.0.0.6' });
  assert.equal(ranked[0].name, 'adb-ours');
});

test('falls back to the same subnet when the name is unknown', () => {
  const candidates = parseMdnsServices(
    [
      'adb-far\t_adb._tcp\t192.168.4.20:5555',
      'adb-near\t_adb._tcp\t10.0.0.11:5555',
    ].join('\n')
  );
  const ranked = rankCandidates(candidates, { knownName: null, knownIp: '10.0.0.6' });
  assert.equal(ranked[0].name, 'adb-near');
});

test('ranking never loses or invents a candidate', () => {
  const candidates = parseMdnsServices(
    [
      'adb-a\t_adb._tcp\t10.0.0.11:5555',
      'adb-b\t_adb._tcp\t10.0.0.12:5555',
      'adb-c\t_adb-tls-connect._tcp\t10.0.0.13:40000',
    ].join('\n')
  );
  const ranked = rankCandidates(candidates, { knownIp: '10.0.0.6' });
  assert.equal(ranked.length, candidates.length);
  assert.deepEqual(
    ranked.map((c) => c.serial).sort(),
    candidates.map((c) => c.serial).sort()
  );
});

test('only adopts a device that identifies as a Shield', () => {
  assert.ok(looksLikeShield('SHIELD Android TV'));
  assert.ok(looksLikeShield('shield android tv'));
  // Adopting a phone that happens to have adb on would be worse than failing to connect.
  assert.ok(!looksLikeShield('Pixel 8'));
  assert.ok(!looksLikeShield(''));
  assert.ok(!looksLikeShield(null));
});

// --- ARP fallback -----------------------------------------------------------
// mDNS returned nothing for a plainly-reachable Shield during development, right after the adb
// server restarted. These cover the second opinion that keeps discovery working anyway.
const REAL_ARP = [
  'Interface: 10.0.0.5 --- 0xd',
  '  10.0.0.1              c8-9e-43-e2-54-f9     dynamic',
  '  10.0.0.11             48-b0-2d-65-1e-74     dynamic',
  '  10.0.0.255            ff-ff-ff-ff-ff-ff     static',
].join('\n');

test('reads neighbours out of the real arp table', () => {
  const found = parseArpNeighbours(REAL_ARP, { subnetOf: '10.0.0.6', port: 5555 });
  assert.deepEqual(found.map((c) => c.serial), ['10.0.0.1:5555', '10.0.0.11:5555']);
});

test('drops broadcast and network addresses', () => {
  const found = parseArpNeighbours(REAL_ARP, { subnetOf: '10.0.0.6' });
  assert.ok(!found.some((c) => c.ip.endsWith('.255')));
});

test('ignores neighbours on another subnet', () => {
  const found = parseArpNeighbours(
    '  192.168.1.9              aa-bb-cc-dd-ee-ff     dynamic',
    { subnetOf: '10.0.0.6' }
  );
  assert.deepEqual(found, []);
});

test('merging prefers mDNS and never repeats an endpoint', () => {
  const announced = parseMdnsServices('adb-ours\t_adb._tcp\t10.0.0.11:5555');
  const neighbours = parseArpNeighbours(REAL_ARP, { subnetOf: '10.0.0.6' });
  const merged = mergeCandidates(announced, neighbours);
  assert.equal(merged.filter((c) => c.serial === '10.0.0.11:5555').length, 1);
  assert.equal(merged[0].service, '_adb._tcp', 'the announced one should come first');
});

test('an mDNS hit outranks a bare ARP guess at the same address', () => {
  const announced = parseMdnsServices('adb-ours\t_adb._tcp\t10.0.0.50:5555');
  const neighbours = parseArpNeighbours(REAL_ARP, { subnetOf: '10.0.0.6' });
  const ranked = rankCandidates(mergeCandidates(announced, neighbours), { knownIp: null });
  assert.equal(ranked[0].service, '_adb._tcp');
});
