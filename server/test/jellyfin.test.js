import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { once } from 'node:events';
import { detectJellyfin, endpointIp, normalizeSession, pickSession, JellyfinClient } from '../src/jellyfin.js';
import { DeviceManager } from '../src/devices.js';
import { createServer } from '../src/server.js';
import { MockTv } from './mock-tv.js';
import { MockJellyfin } from './mock-jellyfin.js';

const fixture = JSON.parse(fs.readFileSync(new URL('./fixtures/jellyfin-sessions.json', import.meta.url), 'utf8'));

test('detectJellyfin recognises release and debug builds', () => {
  assert.deepEqual(detectJellyfin('org.jellyfin.androidtv'), { package: 'org.jellyfin.androidtv', debug: false, flavor: 'androidtv' });
  assert.deepEqual(detectJellyfin('org.jellyfin.androidtv.debug'), { package: 'org.jellyfin.androidtv.debug', debug: true, flavor: 'androidtv' });
  assert.equal(detectJellyfin('org.jellyfin.mobile.debug').flavor, 'mobile');
  assert.equal(detectJellyfin('com.netflix.ninja'), null);
  assert.equal(detectJellyfin(null), null);
});

test('endpointIp normalises Jellyfin RemoteEndPoint values', () => {
  assert.equal(endpointIp('::ffff:192.168.1.5'), '192.168.1.5');
  assert.equal(endpointIp('192.168.1.5:51234'), '192.168.1.5');
  assert.equal(endpointIp('[fe80::1]:8096'), 'fe80::1');
  assert.equal(endpointIp('10.0.0.2'), '10.0.0.2');
});

test('normalizeSession and pickSession', () => {
  const sessions = fixture.map(normalizeSession);
  const tv = sessions[1];
  assert.equal(tv.remoteIp, '127.0.0.1');
  assert.equal(tv.item.name, 'The One Where It Works');
  assert.equal(tv.item.runtimeMs, 2600000);
  assert.equal(tv.positionMs, 600000);
  assert.deepEqual(tv.audio.map((a) => [a.index, a.selected]), [[1, true], [2, false]]);
  assert.deepEqual(tv.subtitles.map((a) => a.index), [3, 4]);
  assert.equal(tv.subtitleIndex, -1);
  assert.equal(pickSession(sessions, ['127.0.0.1']).sessionId, 'tv-session');
  // Unknown IP: falls back to the Android TV client that is playing.
  assert.equal(pickSession(sessions, ['10.9.9.9']).sessionId, 'tv-session');
  assert.equal(pickSession([sessions[0]], ['10.9.9.9']), null);
});

let tv;
let jf;
let manager;
let server;
let base;
let dataDir;
let deviceId;

before(async () => {
  tv = await new MockTv().listen();
  jf = await new MockJellyfin().listen();
  dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'kalimote-jf-'));
  manager = new DeviceManager({ dataDir });
  server = createServer({ manager, token: null });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  base = `http://127.0.0.1:${server.address().port}`;
  const d = manager.add({ host: '127.0.0.1', name: 'TV', pairingPort: tv.pairingPort, remotePort: tv.remotePort });
  deviceId = d.id;
  const codeP = once(tv, 'code');
  await manager.startPairing(d.id);
  await manager.finishPairing(d.id, (await codeP)[0]);
});

after(() => {
  manager.close();
  server.close();
  tv.close();
  jf.close();
  fs.rmSync(dataDir, { recursive: true, force: true });
});

test('JellyfinClient validates settings', async () => {
  assert.throws(() => new JellyfinClient({ url: 'jellyfin.local', apiKey: 'x' }), /http/);
  assert.throws(() => new JellyfinClient({ url: 'http://x', apiKey: '' }), /API key/);
  await assert.rejects(new JellyfinClient({ url: jf.url, apiKey: 'wrong' }).info(), /rejected the API key/);
  await assert.rejects(new JellyfinClient({ url: 'http://127.0.0.1:1', apiKey: 'k' }).info(), /Cannot reach/);
});

test('Jellyfin app on the TV is detected, including the debug build', async () => {
  manager.live(deviceId).launchApp('org.jellyfin.androidtv.debug'); // mock TV reports the link as the app
  const end = Date.now() + 3000;
  while (!manager.list().devices[0].jellyfinApp && Date.now() < end) await new Promise((r) => setTimeout(r, 20));
  assert.deepEqual(manager.list().devices[0].jellyfinApp, { package: 'org.jellyfin.androidtv.debug', debug: true, flavor: 'androidtv' });
});

test('Jellyfin server: configure, status, control, image proxy', async () => {
  const st0 = await manager.jellyfinStatus(deviceId);
  assert.equal(st0.configured, false);
  await assert.rejects(manager.setJellyfin({ url: jf.url, apiKey: 'nope' }), /rejected/);

  const info = await manager.setJellyfin({ url: `${jf.url}/`, apiKey: 'test-key' });
  assert.equal(info.name, 'Mock Jellyfin');
  assert.deepEqual(manager.list().jellyfin, { configured: true, url: jf.url });
  assert.ok(!JSON.stringify(manager.list()).includes('test-key'), 'API key must not reach the browser');
  // Re-saving without a key keeps the stored one.
  await manager.setJellyfin({ url: jf.url, apiKey: '' });

  const st = await manager.jellyfinStatus(deviceId);
  assert.equal(st.session.sessionId, 'tv-session');
  assert.equal(st.session.item.seriesName, 'Kalimote Stories');

  await manager.jellyfinControl(deviceId, 'playpause');
  await manager.jellyfinControl(deviceId, 'seekBy', 30000);
  await manager.jellyfinControl(deviceId, 'subtitle', 4);
  await manager.jellyfinControl(deviceId, 'audio', 2);
  await manager.jellyfinControl(deviceId, 'seek', 99999999999); // clamped to runtime
  await manager.jellyfinControl(deviceId, 'message', 'Dinner is ready');
  assert.deepEqual(
    jf.commands.map((c) => c.path),
    [
      '/Sessions/tv-session/Playing/PlayPause',
      '/Sessions/tv-session/Playing/Seek?seekPositionTicks=6300000000',
      '/Sessions/tv-session/Command',
      '/Sessions/tv-session/Command',
      '/Sessions/tv-session/Playing/Seek?seekPositionTicks=26000000000',
      '/Sessions/tv-session/Command',
    ],
  );
  assert.deepEqual(jf.commands[2].body, { Name: 'SetSubtitleStreamIndex', Arguments: { Index: '4' } });
  assert.equal(jf.commands[5].body.Arguments.Text, 'Dinner is ready');

  const after = await manager.jellyfinStatus(deviceId);
  assert.equal(after.session.paused, true);
  assert.equal(after.session.subtitles.find((s) => s.selected).index, 4);
  await assert.rejects(manager.jellyfinControl(deviceId, 'dance'), /Unknown Jellyfin action/);

  // REST + image proxy
  const rest = await (await fetch(`${base}/api/devices/${deviceId}/jellyfin`)).json();
  assert.equal(rest.session.sessionId, 'tv-session');
  const r = await fetch(`${base}/api/devices/${deviceId}/jellyfin`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ action: 'next' }),
  });
  assert.equal((await r.json()).ok, true);
  assert.equal(jf.commands.at(-1).path, '/Sessions/tv-session/Playing/NextTrack');
  const img = await fetch(`${base}/api/jellyfin/image/item-42?tag=abc123`);
  assert.equal(img.headers.get('content-type'), 'image/svg+xml');
  assert.ok((await img.arrayBuffer()).byteLength > 0);

  // Clearing the settings
  await manager.setJellyfin({ url: '' });
  assert.equal(manager.list().jellyfin.configured, false);
});
