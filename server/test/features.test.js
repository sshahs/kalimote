import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { once } from 'node:events';
import { parseMacro } from '../src/macros.js';
import { magicPacket, normalizeMac } from '../src/wol.js';
import { DeviceManager } from '../src/devices.js';
import { createServer } from '../src/server.js';
import { MockTv } from './mock-tv.js';

test('parseMacro', () => {
  const steps = parseMacro(`
    # open YouTube and search
    HOME, wait 1s
    DPAD_DOWN x3
    hold DPAD_CENTER 2s
    text hello, world
    open https://www.youtube.com/watch?v=abc,def
    26*2
  `);
  assert.deepEqual(steps, [
    { type: 'key', key: 3, times: 1 },
    { type: 'wait', ms: 1000 },
    { type: 'key', key: 20, times: 3 },
    { type: 'hold', key: 23, ms: 2000 },
    { type: 'text', text: 'hello, world' },
    { type: 'open', url: 'https://www.youtube.com/watch?v=abc,def' },
    { type: 'key', key: 26, times: 2 },
  ]);
  assert.throws(() => parseMacro('NOT_A_KEY'), /Line 1: Unknown key/);
  assert.throws(() => parseMacro('HOME\nwait forever'), /Line 2: Invalid duration/);
  assert.throws(() => parseMacro('open youtube'), /needs a link/);
});

test('wake-on-lan packet', () => {
  assert.equal(normalizeMac('AA-BB-CC-DD-EE-FF'), 'aa:bb:cc:dd:ee:ff');
  assert.equal(normalizeMac('nope'), null);
  const p = magicPacket('aa:bb:cc:dd:ee:ff');
  assert.equal(p.length, 102);
  assert.deepEqual([...p.subarray(0, 6)], [255, 255, 255, 255, 255, 255]);
  assert.deepEqual([...p.subarray(96)], [0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff]);
});

let tv;
let manager;
let server;
let base;
let dataDir;
let deviceId;

before(async () => {
  tv = await new MockTv().listen();
  dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'kalimote-'));
  manager = new DeviceManager({ dataDir });
  server = createServer({ manager, token: 'tok' });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  base = `http://127.0.0.1:${server.address().port}`;
  const d = manager.add({ host: '127.0.0.1', name: 'Den TV', pairingPort: tv.pairingPort, remotePort: tv.remotePort });
  deviceId = d.id;
  const codeP = once(tv, 'code');
  await manager.startPairing(d.id);
  await manager.finishPairing(d.id, (await codeP)[0]);
});

after(() => {
  manager.close();
  server.close();
  tv.close();
  fs.rmSync(dataDir, { recursive: true, force: true });
});

const api = async (method, url, body, token = 'tok') => {
  const res = await fetch(base + url, {
    method,
    headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, body: await res.json() };
};

const waitFor = async (pred, ms = 3000) => {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (pred()) return;
    await new Promise((r) => setTimeout(r, 20));
  }
  throw new Error('timed out');
};

test('REST API: auth, lookup by name, key, text, open', async () => {
  assert.equal((await api('GET', '/api/devices', null, 'wrong')).status, 401);
  const list = await api('GET', '/api/devices');
  assert.equal(list.body.devices[0].name, 'Den TV');

  tv.keys.length = 0;
  assert.equal((await api('POST', '/api/devices/den%20tv/key', { key: 'HOME' })).body.ok, true);
  await waitFor(() => tv.keys.some((k) => k.code === 3));

  await api('POST', `/api/devices/${deviceId}/text`, { text: 'abc' });
  await waitFor(() => tv.texts.includes('abc'));
  await api('POST', `/api/devices/${deviceId}/open`, { url: 'https://www.youtube.com' });
  await waitFor(() => tv.links.includes('https://www.youtube.com'));

  const bad = await api('POST', `/api/devices/${deviceId}/key`, { key: 'BOGUS' });
  assert.equal(bad.status, 400);
  assert.match(bad.body.error, /Unknown key/);
  assert.equal((await api('POST', '/api/devices/nope/key', { key: 'HOME' })).status, 400);
});

test('power on/off only toggles when needed', async () => {
  const conn = manager.live(deviceId);
  await waitFor(() => conn.state.powered === true);
  await manager.power(deviceId, 'on'); // already on: nothing sent
  await manager.power(deviceId, 'off');
  await waitFor(() => conn.state.powered === false);
  await manager.power(deviceId, 'off'); // already off: nothing sent
  await new Promise((r) => setTimeout(r, 100));
  assert.equal(conn.state.powered, false);
  await api('POST', `/api/devices/${deviceId}/power`, { state: 'on' });
  await waitFor(() => conn.state.powered === true);
});

test('volume can be set to an absolute level', async () => {
  const conn = manager.live(deviceId);
  await manager.setVolume(deviceId, 15);
  await waitFor(() => conn.state.volume.level === 15);
  await api('POST', `/api/devices/${deviceId}/volume`, { level: 12 });
  await waitFor(() => conn.state.volume.level === 12);
});

test('macros: save, run by name, list', async () => {
  const saved = manager.saveMacro({ name: 'Up and home', script: 'DPAD_UP x2\nwait 50\nHOME' });
  assert.ok(saved.id);
  assert.throws(() => manager.saveMacro({ name: 'Bad', script: 'FLY' }), /Unknown key/);
  tv.keys.length = 0;
  const res = await api('POST', `/api/devices/${deviceId}/macro`, { name: 'up and home' });
  assert.equal(res.body.ok, true);
  assert.deepEqual(tv.keys.map((k) => k.code), [19, 19, 3]);
  assert.equal(manager.list().macros.length, 1);
  // Persisted
  assert.equal(new DeviceManager({ dataDir }).list().macros[0].name, 'Up and home');
});

test('sleep timer turns the TV off', async () => {
  const conn = manager.live(deviceId);
  await waitFor(() => conn.state.powered === true);
  const at = manager.setSleepTimer(deviceId, 0.002); // ~120ms
  assert.ok(at > Date.now());
  assert.ok(manager.list().devices[0].sleepAt);
  await waitFor(() => conn.state.powered === false);
  assert.equal(manager.list().devices[0].sleepAt, null);
  manager.setSleepTimer(deviceId, 30);
  manager.setSleepTimer(deviceId, 0);
  assert.equal(manager.list().devices[0].sleepAt, null);
});

test('wake requires a MAC address', async () => {
  manager.update(deviceId, { mac: '' });
  await assert.rejects(manager.wake(deviceId), /MAC/);
  assert.throws(() => manager.update(deviceId, { mac: 'zz' }), /MAC/);
  manager.update(deviceId, { mac: 'AA:BB:CC:DD:EE:FF' });
  assert.equal(manager.list().devices[0].mac, 'aa:bb:cc:dd:ee:ff');
});
