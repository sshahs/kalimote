import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { once } from 'node:events';
import WebSocket from 'ws';
import { DeviceManager } from '../src/devices.js';
import { createServer } from '../src/server.js';
import { MockTv } from './mock-tv.js';

test('web API: add, pair, control, token auth', async (t) => {
  const tv = await new MockTv().listen();
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'kalimote-'));
  const manager = new DeviceManager({ dataDir });
  const server = createServer({ manager, token: 'secret' });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `ws://127.0.0.1:${server.address().port}/ws`;
  t.after(() => {
    manager.close();
    server.close();
    tv.close();
    fs.rmSync(dataDir, { recursive: true, force: true });
  });

  const denied = new WebSocket(`${base}?token=wrong`);
  await assert.rejects(once(denied, 'open'));

  const ws = new WebSocket(`${base}?token=secret`);
  const events = [];
  ws.on('message', (raw) => {
    const m = JSON.parse(raw);
    if (m.event) events.push(m);
  });
  await once(ws, 'open');
  let id = 0;
  const call = (op, args = {}) =>
    new Promise((resolve, reject) => {
      const myId = ++id;
      const on = (raw) => {
        const m = JSON.parse(raw);
        if (m.re !== myId) return;
        ws.off('message', on);
        m.ok ? resolve(m.result) : reject(new Error(m.error));
      };
      ws.on('message', on);
      ws.send(JSON.stringify({ id: myId, op, ...args }));
    });

  const device = await call('add', {
    host: '127.0.0.1',
    name: 'Test TV',
    pairingPort: tv.pairingPort,
    remotePort: tv.remotePort,
  });
  await assert.rejects(call('key', { device: device.id, key: 'HOME' }), /not connected/);

  const codeP = once(tv, 'code');
  await call('pair.start', { device: device.id });
  const [code] = await codeP;
  await call('pair.finish', { device: device.id, code });

  const keyP = once(tv, 'key');
  await call('key', { device: device.id, key: 'HOME' });
  assert.deepEqual((await keyP)[0], { code: 3, direction: 3 });

  const list = await call('list');
  assert.equal(list.devices[0].paired, true);
  assert.equal(list.devices[0].state.connected, true);
  assert.ok(events.length > 0);

  // Pairing state survives a restart.
  const reloaded = new DeviceManager({ dataDir });
  assert.equal(reloaded.list().devices[0].paired, true);
  assert.equal(reloaded.identity.cert, manager.identity.cert);
  ws.close();
});
