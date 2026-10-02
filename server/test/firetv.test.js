import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import { encodeMessage, MessageReader, CMD, adbPublicKey, parseAdbPublicKey, signToken, verifyToken } from '../src/adb/protocol.js';
import { AdbClient } from '../src/adb/client.js';
import { FireTvConnection, parseCurrentApp, parsePowered, inputText, launchCommand } from '../src/adb/firetv.js';
import { generateClientCertificate } from '../src/protocol/certs.js';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { DeviceManager } from '../src/devices.js';
import { MockFireTv } from './mock-firetv.js';

const identity = generateClientCertificate('adb-test');

const waitFor = async (pred, ms = 4000) => {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (pred()) return;
    await new Promise((r) => setTimeout(r, 20));
  }
  throw new Error('timed out');
};

test('ADB message framing', () => {
  const a = encodeMessage(CMD.OPEN, 1, 0, 'shell:ls\0');
  const b = encodeMessage(CMD.OKAY, 7, 1);
  const r = new MessageReader();
  assert.equal(r.push(a.subarray(0, 10)).length, 0);
  const msgs = r.push(Buffer.concat([a.subarray(10), b]));
  assert.equal(msgs.length, 2);
  assert.equal(msgs[0].command, CMD.OPEN);
  assert.equal(msgs[0].data.toString(), 'shell:ls\0');
  assert.equal(msgs[1].arg0, 7);
  assert.equal(a.readUInt32LE(16), [...Buffer.from('shell:ls\0')].reduce((x, y) => x + y, 0));
});

test('ADB public key format round-trips and signatures verify', () => {
  const text = adbPublicKey(identity.key, 'me@host');
  assert.match(text, / me@host$/);
  assert.equal(Buffer.from(text.split(' ')[0], 'base64').length, 524); // 2048-bit key
  const pub = parseAdbPublicKey(text);
  const original = crypto.createPublicKey(identity.key).export({ format: 'jwk' });
  assert.equal(pub.export({ format: 'jwk' }).n, original.n);
  const token = crypto.randomBytes(20);
  assert.ok(verifyToken(pub, token, signToken(identity.key, token)));
  assert.ok(!verifyToken(pub, crypto.randomBytes(20), signToken(identity.key, token)));
  // n0inv: n[0] * n0inv == -1 (mod 2^32)
  const buf = Buffer.from(text.split(' ')[0], 'base64');
  const n0 = BigInt(buf.readUInt32LE(8));
  const n0inv = BigInt(buf.readUInt32LE(4));
  assert.equal((n0 * n0inv) % (1n << 32n), (1n << 32n) - 1n);
});

test('parsers for dumpsys output, text and launch commands', () => {
  const out = '  mCurrentFocus=Window{9f0 u0 org.jellyfin.androidtv.debug/org.jellyfin.androidtv.ui.MainActivity}\n mWakefulness=Asleep\n';
  assert.equal(parseCurrentApp(out), 'org.jellyfin.androidtv.debug');
  assert.equal(parseCurrentApp('mFocusedApp=ActivityRecord{1 u0 com.netflix.ninja/.MainActivity t5}'), 'com.netflix.ninja');
  assert.equal(parsePowered(out), false);
  assert.equal(parsePowered('Display Power: state=ON'), true);
  assert.equal(parsePowered('nothing'), null);
  assert.equal(inputText("it's 50% off"), "input text 'it'\\''s%s50\\%%soff'");
  assert.equal(launchCommand('market://launch?id=com.netflix.ninja'), 'monkey -p com.netflix.ninja -c android.intent.category.LAUNCHER 1 || monkey -p com.netflix.ninja 1');
  assert.equal(launchCommand("https://youtu.be/x?a=1&b='2'"), "am start -a android.intent.action.VIEW -d 'https://youtu.be/x?a=1&b='\\''2'\\'''");
});

let tv;
before(async () => {
  tv = await new MockFireTv({ autoApprove: false }).listen();
});
after(() => tv.close());

test('unknown key is refused without pairing, accepted after the TV prompt', async () => {
  const plain = new AdbClient({ host: '127.0.0.1', port: tv.port, key: identity.key });
  await assert.rejects(plain.connect(), (e) => e.code === 'unauthorized');

  // Denied on the TV
  const denied = new AdbClient({ host: '127.0.0.1', port: tv.port, key: identity.key, approvalTimeoutMs: 3000 });
  const p1 = denied.connect();
  await new Promise((r) => tv.once('prompt', r));
  tv.deny();
  await assert.rejects(p1, /did not allow/);

  // Allowed on the TV
  const pairing = new AdbClient({ host: '127.0.0.1', port: tv.port, key: identity.key, approvalTimeoutMs: 3000 });
  const p2 = pairing.connect();
  await new Promise((r) => tv.once('prompt', r));
  tv.approve();
  assert.match(await p2, /ro.product.model=AFTMM/);
  assert.match(await pairing.shell('dumpsys window windows'), /com\.amazon\.tv\.launcher/);
  pairing.close();

  // Now trusted: signature auth works with no prompt.
  const again = new AdbClient({ host: '127.0.0.1', port: tv.port, key: identity.key });
  await again.connect();
  again.close();
});

test('FireTvConnection: state, keys, text, launch, power, reconnect, revoke', async () => {
  const conn = new FireTvConnection({ host: '127.0.0.1', port: tv.port, key: identity.key });
  conn.on('error', () => {});
  await conn.connect();
  assert.deepEqual(
    { connected: conn.state.connected, powered: conn.state.powered, app: conn.state.currentApp },
    { connected: true, powered: true, app: 'com.amazon.tv.launcher' },
  );

  tv.keys.length = 0;
  conn.sendKey('DPAD_UP');
  conn.sendKey('DPAD_CENTER', 'START_LONG');
  conn.sendKey('DPAD_CENTER', 'END_LONG');
  conn.sendText("it's 50% off");
  await waitFor(() => tv.texts.length === 1);
  assert.deepEqual(tv.keys, [{ code: 19, long: false }, { code: 23, long: true }]);
  assert.equal(tv.texts[0], "it's 50% off");

  conn.launchApp('org.jellyfin.androidtv.debug'.replace(/^/, 'market://launch?id='));
  await waitFor(() => conn.state.currentApp === 'org.jellyfin.androidtv.debug');

  conn.sendKey('POWER');
  await waitFor(() => conn.state.powered === false);

  conn.retryMs = 50;
  for (const s of tv.sockets) s.destroy();
  await waitFor(() => !conn.state.connected);
  await waitFor(() => conn.state.connected);

  const unpaired = new Promise((r) => conn.once('unpaired', r));
  tv.revokeAll();
  await unpaired;
  conn.disconnect();
  assert.throws(() => conn.sendKey('HOME'), /Not connected/);
});

test('DeviceManager: add, pair (approve on TV), control and Jellyfin detection for a Fire TV', async () => {
  const fire = await new MockFireTv({ autoApprove: false }).listen();
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'kalimote-ftv-'));
  const manager = new DeviceManager({ dataDir });
  try {
    const d = manager.add({ host: '127.0.0.1', name: 'Bedroom Fire TV', type: 'firetv', remotePort: fire.port });
    assert.equal(manager.list().devices[0].type, 'firetv');
    assert.throws(() => manager.add({ host: '10.0.0.9', type: 'roku' }), /Unknown device type/);

    // Cancelled pairing
    const cancelled = manager.startPairing(d.id);
    await new Promise((r) => fire.once('prompt', r));
    manager.cancelPairing(d.id);
    await assert.rejects(cancelled, /cancelled/);

    // Approved pairing
    const pairing = manager.startPairing(d.id);
    await new Promise((r) => fire.once('prompt', r));
    assert.equal(manager.list().devices[0].pairing, true);
    fire.approve();
    await pairing;
    assert.equal(manager.list().devices[0].paired, true);
    assert.equal(manager.list().devices[0].state.connected, true);

    manager.live(d.id).sendKey('HOME');
    await waitFor(() => fire.keys.some((k) => k.code === 3));
    await manager.runMacro(d.id, { script: 'DPAD_DOWN x2\ntext hi there' });
    await waitFor(() => fire.texts.includes('hi there'));
    assert.equal(fire.keys.filter((k) => k.code === 20).length, 2);

    manager.live(d.id).launchApp('market://launch?id=org.jellyfin.androidtv.debug');
    await waitFor(() => manager.list().devices[0].jellyfinApp?.debug === true);

    await manager.power(d.id, 'off');
    await waitFor(() => manager.list().devices[0].state.powered === false);
    await manager.power(d.id, 'off'); // already off: no extra POWER
    await new Promise((r) => setTimeout(r, 300));
    assert.equal(fire.keys.filter((k) => k.code === 26).length, 1);

    // Reload: paired Fire TV reconnects with its stored type.
    manager.close();
    const reloaded = new DeviceManager({ dataDir });
    await reloaded.connect(d.id);
    assert.equal(reloaded.list().devices[0].state.connected, true);
    reloaded.close();
  } finally {
    manager.close();
    fire.close();
    fs.rmSync(dataDir, { recursive: true, force: true });
  }
});

test('appName and parsePackages', async () => {
  const { appName } = await import('../src/apps.js');
  const { parsePackages } = await import('../src/adb/firetv.js');
  assert.equal(appName('com.netflix.ninja'), 'Netflix');
  assert.equal(appName('com.amazon.firetv.youtube'), 'YouTube');
  assert.equal(appName('org.jellyfin.androidtv.debug'), 'Jellyfin (debug)');
  assert.equal(appName('com.example.coolapp'), 'Coolapp');
  assert.equal(appName('org.videolan.vlc'), 'VLC');
  assert.deepEqual(
    parsePackages('priority=0 match=0x1\n  com.netflix.ninja/.MainActivity\n  org.xbmc.kodi/org.xbmc.kodi.Splash\npackage:com.x.y'),
    ['com.netflix.ninja', 'org.xbmc.kodi', 'com.x.y'],
  );
});

test('Fire TV: list apps, screenshot, push + install an APK', async () => {
  const fire = await new MockFireTv().listen();
  const conn = new FireTvConnection({ host: '127.0.0.1', port: fire.port, key: identity.key });
  conn.on('error', () => {});
  try {
    await conn.connect({ approvalTimeoutMs: 3000 });
    const apps = await conn.listApps();
    assert.deepEqual(apps.map((a) => a.name), ['Coolapp', 'Jellyfin', 'Jellyfin (debug)', 'Netflix', 'VLC', 'YouTube']);
    assert.equal(apps.find((a) => a.name === 'Netflix').package, 'com.netflix.ninja');

    const png = await conn.screenshot();
    assert.equal(png.subarray(1, 4).toString(), 'PNG');
    assert.ok(png.length > 500);

    await assert.rejects(conn.installApk(Buffer.from('not an apk')), /not an APK/);
    // ~300 KB fake APK: exercises multiple 64 KB DATA chunks and flow control.
    const apk = Buffer.concat([Buffer.from('PK\x03\x04'), crypto.randomBytes(300 * 1024)]);
    const progress = [];
    const installed = new Promise((r) => fire.once('installed', r));
    const out = await conn.installApk(apk, { onProgress: (phase, sent, total) => progress.push([phase, sent, total]) });
    assert.match(out, /Success/);
    assert.ok((await installed).equals(apk), 'device received the exact bytes');
    assert.ok(progress.filter(([p]) => p === 'upload').length >= 5);
    assert.deepEqual(progress.at(-1), ['install', apk.length, apk.length]);
    await waitFor(() => fire.files.size === 0); // temp file removed
  } finally {
    conn.disconnect();
    fire.close();
  }
});
