import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { Writer, Message, FrameReader, frame } from '../src/protocol/proto.js';
import { generateClientCertificate, computePairingSecret } from '../src/protocol/certs.js';
import { PairingSession } from '../src/protocol/pairing.js';
import { RemoteConnection } from '../src/protocol/remote.js';
import { resolveKey } from '../src/protocol/keycodes.js';
import { MockTv } from './mock-tv.js';

test('protobuf round trip', () => {
  const buf = new Writer()
    .varint(1, 300)
    .string(2, 'héllo')
    .message(3, new Writer().varint(1, 7))
    .varint(4, -1)
    .finish();
  const m = new Message(buf);
  assert.equal(m.uint(1), 300);
  assert.equal(m.string(2), 'héllo');
  assert.equal(m.message(3).uint(1), 7);
  assert.equal(m.int(4), -1);
});

test('frame reader handles split and merged frames', () => {
  const a = frame(Buffer.alloc(200, 1));
  const b = frame(Buffer.from([1, 2, 3]));
  const all = Buffer.concat([a, b]);
  const r = new FrameReader();
  assert.equal(r.push(all.subarray(0, 1)).length, 0);
  assert.equal(r.push(all.subarray(1, 150)).length, 0);
  const frames = r.push(all.subarray(150));
  assert.equal(frames.length, 2);
  assert.equal(frames[0].length, 200);
  assert.deepEqual([...frames[1]], [1, 2, 3]);
});

test('resolveKey', () => {
  assert.equal(resolveKey('DPAD_UP'), 19);
  assert.equal(resolveKey('KEYCODE_HOME'), 3);
  assert.equal(resolveKey(26), 26);
  assert.throws(() => resolveKey('NOPE'));
});

let tv;
let identity;

before(async () => {
  tv = await new MockTv().listen();
  identity = generateClientCertificate('test-client');
});

after(() => tv.close());

test('computePairingSecret rejects bad checksum', () => {
  assert.throws(() => computePairingSecret(identity.cert, tv.identity.cert, 'zz'), /hexadecimal/);
});

test('unpaired client is rejected', async () => {
  const remote = new RemoteConnection({ host: '127.0.0.1', port: tv.remotePort, ...identity });
  await assert.rejects(remote.connect(), (e) => e.unpaired === true);
  remote.disconnect();
});

test('wrong pairing code fails', async () => {
  const p = new PairingSession({ host: '127.0.0.1', port: tv.pairingPort, ...identity });
  const codeP = once(tv, 'code');
  await p.start();
  const [code] = await codeP;
  // Flip the nonce so the checksum (almost certainly) no longer matches.
  const bad = code.slice(0, 2) + ((parseInt(code.slice(2), 16) + 1) % 0x10000).toString(16).padStart(4, '0');
  assert.throws(() => computePairingSecret(identity.cert, tv.identity.cert, bad));
  p.close();
});

test('pair, connect, and control', async () => {
  const p = new PairingSession({ host: '127.0.0.1', port: tv.pairingPort, ...identity });
  const codeP = once(tv, 'code');
  await p.start();
  const [code] = await codeP;
  await p.finish(code.toLowerCase());

  const remote = new RemoteConnection({ host: '127.0.0.1', port: tv.remotePort, ...identity });
  const errors = [];
  remote.on('error', (e) => errors.push(e));
  await remote.connect();
  assert.equal(remote.state.connected, true);

  const waitFor = (pred) =>
    new Promise((resolve) => {
      if (pred(remote.state)) return resolve();
      const on = (s) => pred(s) && (remote.off('state', on), resolve());
      remote.on('state', on);
    });
  await waitFor((s) => s.powered === true && s.volume?.level === 10 && s.currentApp);

  remote.sendKey('DPAD_UP');
  remote.sendKey('VOLUME_UP');
  await waitFor((s) => s.volume.level === 11);
  remote.sendKey('POWER');
  await waitFor((s) => s.powered === false);
  assert.deepEqual(tv.keys.slice(0, 1), [{ code: 19, direction: 3 }]);

  remote.sendText('hello');
  const [text] = await once(tv, 'text');
  assert.equal(text, 'hello');

  remote.launchApp('https://www.netflix.com/title');
  await waitFor((s) => s.currentApp === 'https://www.netflix.com/title');

  // Reconnects after the connection drops.
  remote.retryMs = 50;
  for (const s of tv.remoteSockets) s.destroy();
  await waitFor((s) => !s.connected);
  await waitFor((s) => s.connected);

  // Revoking pairing on the TV surfaces an 'unpaired' event.
  const unpaired = new Promise((resolve) => remote.once('unpaired', resolve));
  tv.forgetAll();
  await unpaired;
  remote.disconnect();
});
