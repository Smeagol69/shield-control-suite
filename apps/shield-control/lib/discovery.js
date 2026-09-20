'use strict';

/**
 * Finding the Shield again after its address changes.
 *
 * A remembered IP is a guess about DHCP, and DHCP eventually disagrees. When the lease moves,
 * every adb call fails in a way that looks exactly like the device being switched off - and
 * worse, the old address is often reissued to something else, so the app sits there collecting
 * connection-refused from a printer. Both discovery paths below exist to answer "where did it
 * go?" without asking the person to go and look.
 */

/** `adb mdns services` lines look like: `adb-01234\t_adb._tcp\t10.0.0.11:5555`. */
const SERVICE_LINE = /^(\S+)\s+(_adb[\w-]*\._tcp)\.?\s+(\d{1,3}(?:\.\d{1,3}){3}):(\d+)\s*$/;
const ARP_LINE = /^(\d{1,3}(?:\.\d{1,3}){3})\s+([0-9a-f]{2}(?:-[0-9a-f]{2}){5})\s+(\w+)/i;
const LINE_SPLIT = /\r?\n/;

const subnetOf = (ip) => String(ip || '').split('.').slice(0, 3).join('.');

/**
 * Parses `adb mdns services` output into connectable candidates.
 *
 * Both the classic `_adb._tcp` and Android 11's `_adb-tls-connect._tcp` are accepted; which one a
 * device advertises depends on whether its debugging is the legacy fixed-port kind or the newer
 * wireless flavour, and either is worth trying before giving up.
 */
function parseMdnsServices(stdout) {
  const seen = new Set();
  const found = [];
  for (const raw of String(stdout || '').split(LINE_SPLIT)) {
    const line = raw.trim();
    if (!line || /^list of/i.test(line)) continue;
    const m = line.match(SERVICE_LINE);
    if (!m) continue;
    const port = Number(m[4]);
    if (!Number.isInteger(port) || port <= 0 || port > 65535) continue;
    const serial = `${m[3]}:${port}`;
    if (seen.has(serial)) continue;
    seen.add(serial);
    found.push({ name: m[1], service: m[2], ip: m[3], port, serial });
  }
  return found;
}

/**
 * Neighbours the machine has already talked to, as connectable candidates.
 *
 * mDNS is the right mechanism but it is not dependable: the Shield advertises periodically, the
 * responder resets whenever the adb server restarts, and a query moments later can come back
 * empty for a device that is plainly reachable - observed exactly that while building this. The
 * ARP table needs no cooperation from the device and no scanning, since the OS has been keeping
 * it all along, which makes it a good second opinion.
 *
 * Only same-subnet hosts are offered, and broadcast/multicast entries are dropped. Every
 * candidate still has to answer adb and identify as a Shield before it is adopted.
 */
function parseArpNeighbours(stdout, options) {
  const opts = options || {};
  const port = opts.port || 5555;
  const want = subnetOf(opts.subnetOf);
  const seen = new Set();
  const found = [];
  for (const raw of String(stdout || '').split(LINE_SPLIT)) {
    const m = raw.trim().match(ARP_LINE);
    if (!m) continue;
    const ip = m[1];
    const mac = m[2].toLowerCase();
    if (mac.startsWith('ff-') || mac.startsWith('01-00-5e')) continue;
    if (ip.endsWith('.255') || ip.endsWith('.0')) continue;
    if (want && subnetOf(ip) !== want) continue;
    if (seen.has(ip)) continue;
    seen.add(ip);
    found.push({ name: null, service: 'arp', ip, port, serial: `${ip}:${port}` });
  }
  return found;
}

/** mDNS first where it works, then the ARP table, without offering the same endpoint twice. */
function mergeCandidates(primary, fallback) {
  const seen = new Set(primary.map((c) => c.serial));
  return [...primary, ...fallback.filter((c) => !seen.has(c.serial))];
}

/**
 * Orders candidates so the one most likely to be *this* Shield is tried first.
 *
 * The mDNS instance name is stable across address changes, so a device we have successfully
 * talked to before is recognisable even though its IP moved - which is the whole point. Failing
 * that, sharing a subnet with the last known address is a decent hint, since a DHCP pool usually
 * reassigns within the same range.
 */
function rankCandidates(candidates, options) {
  const opts = options || {};
  const knownName = opts.knownName || null;
  const knownIp = opts.knownIp || null;
  const knownSubnet = subnetOf(knownIp);

  const score = (c) => {
    let s = 0;
    if (knownName && c.name === knownName) s += 100;
    if (knownIp && c.ip === knownIp) s += 50;
    if (knownSubnet && subnetOf(c.ip) === knownSubnet) s += 10;
    // Prefer an mDNS hit over a bare ARP guess, and the legacy service over the paired one.
    if (c.service === '_adb._tcp') s += 5;
    else if (c.service !== 'arp') s += 3;
    return s;
  };

  return [...candidates].sort((a, b) => score(b) - score(a));
}

/** Whether a device's model string looks like an NVIDIA Shield. */
function looksLikeShield(model) {
  return /shield/i.test(String(model || ''));
}

module.exports = {
  parseMdnsServices,
  parseArpNeighbours,
  mergeCandidates,
  rankCandidates,
  looksLikeShield,
  SERVICE_LINE,
};
