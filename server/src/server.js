#!/usr/bin/env node
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { WebSocketServer } from 'ws';
import { DeviceManager } from './devices.js';

const PUBLIC_DIR = fileURLToPath(new URL('../public/', import.meta.url));
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json',
  '.webmanifest': 'application/manifest+json',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
};

function tokenMatches(expected, given) {
  if (!expected) return true;
  const a = Buffer.from(String(expected));
  const b = Buffer.from(String(given ?? ''));
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function serveStatic(req, res) {
  const url = new URL(req.url, 'http://localhost');
  let rel = decodeURIComponent(url.pathname);
  if (rel.endsWith('/')) rel += 'index.html';
  const file = path.normalize(path.join(PUBLIC_DIR, rel));
  if (!file.startsWith(PUBLIC_DIR)) {
    res.writeHead(403).end();
    return;
  }
  fs.readFile(file, (err, data) => {
    if (err) {
      res.writeHead(404, { 'content-type': 'text/plain' }).end('Not found');
      return;
    }
    res.writeHead(200, {
      'content-type': MIME[path.extname(file)] ?? 'application/octet-stream',
      'cache-control': 'no-cache',
      'x-content-type-options': 'nosniff',
    });
    res.end(data);
  });
}

/** Handles one request from the browser. Returns the result payload. */
async function handle(manager, msg) {
  const { op, device } = msg;
  switch (op) {
    case 'list':
      return manager.list();
    case 'rescan':
      manager.rescan();
      return manager.list();
    case 'add':
      return manager.add(msg);
    case 'rename':
      return manager.rename(device, msg.name);
    case 'update':
      return manager.update(device, { name: msg.name, mac: msg.mac });
    case 'remove':
      return manager.remove(device);
    case 'pair.start':
      return manager.startPairing(device);
    case 'pair.finish':
      return manager.finishPairing(device, msg.code);
    case 'pair.cancel':
      return manager.cancelPairing(device);
    case 'connect':
      return manager.connect(device);
    case 'key':
      return manager.live(device).sendKey(msg.key, msg.direction ?? 'SHORT');
    case 'text':
      return manager.live(device).sendText(msg.text);
    case 'launch':
      return manager.live(device).launchApp(msg.url);
    case 'power':
      return manager.power(device, msg.state);
    case 'wake':
      return manager.wake(device);
    case 'volume':
      return manager.setVolume(device, msg.level);
    case 'sleep':
      return manager.setSleepTimer(device, msg.minutes);
    case 'macro.save':
      return manager.saveMacro({ id: msg.macroId, name: msg.name, script: msg.script });
    case 'macro.delete':
      return manager.deleteMacro(msg.macro);
    case 'macro.run':
      return manager.runMacro(device, { macro: msg.macro, script: msg.script });
    case 'macro.stop':
      return manager.stopMacro(device);
    case 'jellyfin.set':
      return manager.setJellyfin({ url: msg.url, apiKey: msg.apiKey });
    case 'jellyfin.status':
      return manager.jellyfinStatus(device);
    case 'jellyfin.control':
      return manager.jellyfinControl(device, msg.action, msg.value);
    default:
      throw new Error(`Unknown operation: ${op}`);
  }
}

function readBody(req, limit = 64 * 1024) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > limit) {
        reject(Object.assign(new Error('Body too large'), { status: 413 }));
        req.destroy();
      } else chunks.push(c);
    });
    req.on('end', () => {
      const raw = Buffer.concat(chunks).toString('utf8');
      if (!raw) return resolve({});
      try {
        resolve(JSON.parse(raw));
      } catch {
        reject(Object.assign(new Error('Body must be JSON'), { status: 400 }));
      }
    });
    req.on('error', reject);
  });
}

/**
 * REST API for automation (Home Assistant, iOS Shortcuts, curl):
 *   GET  /api/devices
 *   GET  /api/devices/:device/jellyfin       what Jellyfin is playing on the TV
 *   POST /api/devices/:device/{key,text,open,power,wake,volume,sleep,macro,jellyfin}
 *   GET  /api/jellyfin/image/:itemId?tag=    poster proxy (keeps the API key server-side)
 * :device is the device id or its name. Auth: "Authorization: Bearer <token>"
 * or ?token=, when KALIMOTE_TOKEN is set.
 */
async function handleApi(manager, req, res, url) {
  const send = (status, body) => {
    res.writeHead(status, { 'content-type': 'application/json' }).end(JSON.stringify(body));
  };
  try {
    const parts = url.pathname.split('/').filter(Boolean).map(decodeURIComponent); // ['api', 'devices', id, action]
    if (parts[1] === 'devices' && parts.length === 2 && req.method === 'GET') {
      const { devices, macros } = manager.list();
      return send(200, { devices, macros });
    }
    if (parts[1] === 'jellyfin' && parts[2] === 'image' && parts.length === 4 && req.method === 'GET') {
      const upstream = await manager.jellyfinClient().image(parts[3], url.searchParams.get('tag'));
      res.writeHead(200, {
        'content-type': upstream.headers.get('content-type') ?? 'image/jpeg',
        'cache-control': 'private, max-age=86400',
        // Images only: an SVG from the upstream server must never run script in our origin.
        'content-security-policy': "default-src 'none'; style-src 'unsafe-inline'; sandbox",
        'x-content-type-options': 'nosniff',
      });
      res.end(Buffer.from(await upstream.arrayBuffer()));
      return;
    }
    if (parts[1] === 'devices' && parts.length === 4 && parts[3] === 'jellyfin' && req.method === 'GET') {
      return send(200, await manager.jellyfinStatus(manager.get(parts[2]).id));
    }
    if (parts[1] === 'devices' && parts.length === 3 && req.method === 'GET') {
      const id = manager.get(parts[2]).id;
      return send(200, manager.list().devices.find((d) => d.id === id));
    }
    if (parts[1] === 'devices' && parts.length === 4 && req.method === 'POST') {
      const device = manager.get(parts[2]).id;
      const body = await readBody(req);
      const action = parts[3];
      const ops = {
        key: () => manager.live(device).sendKey(body.key, body.direction ?? 'SHORT'),
        text: () => manager.live(device).sendText(body.text),
        open: () => manager.live(device).launchApp(body.url),
        power: () => manager.power(device, body.state ?? 'toggle'),
        wake: () => manager.wake(device),
        volume: () => manager.setVolume(device, body.level),
        sleep: () => manager.setSleepTimer(device, body.minutes ?? 0),
        macro: () => manager.runMacro(device, { macro: body.name ?? body.macro, script: body.script }),
        jellyfin: () => manager.jellyfinControl(device, body.action, body.value),
      };
      if (!ops[action]) return send(404, { ok: false, error: `Unknown action: ${action}` });
      const result = await ops[action]();
      return send(200, { ok: true, result: result ?? null });
    }
    return send(404, { ok: false, error: 'Not found' });
  } catch (e) {
    return send(e.status ?? 400, { ok: false, error: e.message });
  }
}

export function createServer({ manager, token }) {
  const server = http.createServer((req, res) => {
    if (req.url === '/healthz') {
      res.writeHead(200, { 'content-type': 'text/plain' }).end('ok');
      return;
    }
    const url = new URL(req.url, 'http://localhost');
    if (url.pathname.startsWith('/api/')) {
      const bearer = /^Bearer\s+(.+)$/i.exec(req.headers.authorization ?? '')?.[1];
      if (!tokenMatches(token, bearer ?? url.searchParams.get('token'))) {
        res.writeHead(401, { 'content-type': 'application/json' }).end('{"ok":false,"error":"Unauthorized"}');
        return;
      }
      handleApi(manager, req, res, url);
      return;
    }
    serveStatic(req, res);
  });

  const wss = new WebSocketServer({ noServer: true, maxPayload: 64 * 1024 });
  server.on('upgrade', (req, socket, head) => {
    const url = new URL(req.url, 'http://localhost');
    if (url.pathname !== '/ws' || !tokenMatches(token, url.searchParams.get('token'))) {
      socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
      socket.destroy();
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => wss.emit('connection', ws));
  });

  const broadcast = (payload) => {
    const data = JSON.stringify({ event: 'devices', ...payload });
    for (const ws of wss.clients) if (ws.readyState === ws.OPEN) ws.send(data);
  };
  let pending = null;
  manager.on('change', () => {
    // Coalesce bursts of state updates into one push.
    if (pending) return;
    pending = setImmediate(() => {
      pending = null;
      broadcast(manager.list());
    });
  });

  wss.on('connection', (ws) => {
    ws.send(JSON.stringify({ event: 'devices', ...manager.list() }));
    ws.on('message', async (raw) => {
      let msg;
      try {
        msg = JSON.parse(raw);
      } catch {
        return;
      }
      try {
        const result = await handle(manager, msg);
        ws.send(JSON.stringify({ re: msg.id, ok: true, result: result ?? null }));
      } catch (e) {
        ws.send(JSON.stringify({ re: msg.id, ok: false, error: e.message }));
      }
    });
  });

  return server;
}

if (process.argv[1] && fs.realpathSync(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const port = Number(process.env.PORT ?? 8080);
  const host = process.env.HOST ?? '0.0.0.0';
  const token = process.env.KALIMOTE_TOKEN || null;
  const dataDir = process.env.KALIMOTE_DATA ?? path.join(os.homedir(), '.kalimote');
  const manager = new DeviceManager({ dataDir, clientName: process.env.KALIMOTE_NAME ?? 'Kalimote Web' });
  if (process.env.KALIMOTE_DISCOVERY !== '0') manager.startDiscovery();
  manager.connectAll();
  const server = createServer({ manager, token });
  server.listen(port, host, () => {
    console.log(`Kalimote web remote: http://${host === '0.0.0.0' ? 'localhost' : host}:${port}/`);
    console.log(`Data directory: ${dataDir}${token ? ' (token auth enabled)' : ''}`);
  });
  const shutdown = () => {
    manager.close();
    server.close();
    process.exit(0);
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}
