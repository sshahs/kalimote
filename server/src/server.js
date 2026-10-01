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
    default:
      throw new Error(`Unknown operation: ${op}`);
  }
}

export function createServer({ manager, token }) {
  const server = http.createServer((req, res) => {
    if (req.url === '/healthz') {
      res.writeHead(200, { 'content-type': 'text/plain' }).end('ok');
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
