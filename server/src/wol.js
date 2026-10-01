import dgram from 'node:dgram';
import fs from 'node:fs';
import os from 'node:os';

export function normalizeMac(mac) {
  const hex = String(mac ?? '').replace(/[^0-9a-f]/gi, '');
  if (hex.length !== 12) return null;
  return hex.toLowerCase().match(/../g).join(':');
}

/** Wake-on-LAN magic packet: 6 x 0xFF followed by the MAC repeated 16 times. */
export function magicPacket(mac) {
  const norm = normalizeMac(mac);
  if (!norm) throw new Error('Invalid MAC address');
  const bytes = Buffer.from(norm.replace(/:/g, ''), 'hex');
  return Buffer.concat([Buffer.alloc(6, 0xff), ...Array(16).fill(bytes)]);
}

/** Directed broadcast addresses of this machine's IPv4 networks. */
function broadcastAddresses() {
  const out = new Set(['255.255.255.255']);
  for (const addrs of Object.values(os.networkInterfaces())) {
    for (const a of addrs ?? []) {
      if (a.family !== 'IPv4' || a.internal || !a.netmask) continue;
      const ip = a.address.split('.').map(Number);
      const mask = a.netmask.split('.').map(Number);
      out.add(ip.map((o, i) => (o & mask[i]) | (~mask[i] & 255)).join('.'));
    }
  }
  return [...out];
}

export async function wake(mac, { addresses = broadcastAddresses(), port = 9 } = {}) {
  const packet = magicPacket(mac);
  const socket = dgram.createSocket('udp4');
  try {
    await new Promise((resolve, reject) => {
      socket.once('error', reject);
      socket.bind(() => {
        socket.setBroadcast(true);
        resolve();
      });
    });
    for (const address of addresses) {
      for (const p of new Set([port, 7])) {
        await new Promise((resolve) => socket.send(packet, p, address, () => resolve()));
      }
    }
  } finally {
    socket.close();
  }
}

/** Looks up a host's MAC in the kernel ARP cache (Linux only; null elsewhere). */
export function lookupMac(ip) {
  try {
    const table = fs.readFileSync('/proc/net/arp', 'utf8');
    for (const line of table.split('\n').slice(1)) {
      const cols = line.trim().split(/\s+/);
      if (cols[0] === ip && cols[3] && cols[3] !== '00:00:00:00:00:00') return normalizeMac(cols[3]);
    }
  } catch {
    /* not Linux, or no access */
  }
  return null;
}
