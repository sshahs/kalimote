// Kalimote web remote. Talks to the Kalimote server over a WebSocket; the
// server speaks the Android TV remote protocol to the TV.

const $ = (sel) => document.querySelector(sel);

const store = {
  get(key, fallback) {
    try {
      const v = localStorage.getItem(`kalimote.${key}`);
      return v === null ? fallback : JSON.parse(v);
    } catch {
      return fallback;
    }
  },
  set(key, value) {
    try {
      localStorage.setItem(`kalimote.${key}`, JSON.stringify(value));
    } catch {
      /* storage unavailable */
    }
  },
};

const DEFAULT_APPS = [
  { name: 'YouTube', url: 'https://www.youtube.com' },
  { name: 'Netflix', url: 'market://launch?id=com.netflix.ninja' },
  { name: 'Prime Video', url: 'market://launch?id=com.amazon.amazonvideo.livingroom' },
  { name: 'Disney+', url: 'market://launch?id=com.disney.disneyplus' },
  { name: 'Spotify', url: 'market://launch?id=com.spotify.tv.android' },
  { name: 'Plex', url: 'market://launch?id=com.plexapp.android' },
  { name: 'Jellyfin', url: 'market://launch?id=org.jellyfin.androidtv' },
];

const state = {
  devices: [],
  discovered: [],
  selected: store.get('selected', null),
  connected: false,
  touchpad: store.get('touchpad', false),
  apps: store.get('apps', DEFAULT_APPS),
  macros: [],
  jellyfin: { configured: false, url: '' },
  jf: null, // { session, fetchedAt } for the selected TV
  pairingDevice: null,
};

// ---------------------------------------------------------------- socket

const urlToken = new URLSearchParams(location.search).get('token');
if (urlToken) {
  store.set('token', urlToken);
  history.replaceState(null, '', location.pathname);
}

let ws;
let nextId = 1;
const pending = new Map();
let socketUp = false;

function connectSocket() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const token = store.get('token', '');
  ws = new WebSocket(`${proto}://${location.host}/ws${token ? `?token=${encodeURIComponent(token)}` : ''}`);
  ws.onopen = () => {
    socketUp = true;
    render();
  };
  ws.onmessage = (e) => {
    const msg = JSON.parse(e.data);
    if (msg.event === 'devices') {
      state.devices = msg.devices;
      state.discovered = msg.discovered;
      state.jellyfin = msg.jellyfin ?? state.jellyfin;
      if (JSON.stringify(msg.macros ?? []) !== JSON.stringify(state.macros)) {
        state.macros = msg.macros ?? [];
        renderMacros();
      }
      render();
    } else if (msg.re && pending.has(msg.re)) {
      const { resolve, reject } = pending.get(msg.re);
      pending.delete(msg.re);
      msg.ok ? resolve(msg.result) : reject(new Error(msg.error));
    }
  };
  ws.onclose = () => {
    socketUp = false;
    for (const { reject } of pending.values()) reject(new Error('Lost connection to server'));
    pending.clear();
    render();
    setTimeout(connectSocket, 1500);
  };
}

function call(op, args = {}) {
  return new Promise((resolve, reject) => {
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      reject(new Error('Not connected to the Kalimote server'));
      return;
    }
    const id = nextId++;
    pending.set(id, { resolve, reject });
    ws.send(JSON.stringify({ id, op, ...args }));
  });
}

let toastTimer;
function toast(text) {
  const el = $('#toast');
  el.textContent = text;
  el.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('show'), 2600);
}

const report = (e) => toast(e.message ?? String(e));

// ---------------------------------------------------------------- remote

function current() {
  return state.devices.find((d) => d.id === state.selected) ?? null;
}

function sendKey(key, direction = 'SHORT') {
  const d = current();
  if (!d) {
    toast('Add a TV first');
    return;
  }
  if (!d.state?.connected) {
    // The power button can wake a TV that dropped off the network.
    if (key === 'POWER' && d.paired && d.mac) {
      call('wake', { device: d.id }).then(() => toast('Wake-on-LAN sent'), report);
      return;
    }
    toast(d.paired ? 'TV is not connected' : 'Pair this TV first');
    return;
  }
  if (navigator.vibrate) navigator.vibrate(direction === 'END_LONG' ? 0 : 12);
  call('key', { device: d.id, key, direction }).catch(report);
}

// Press-and-hold repeat for D-pad and volume buttons.
const REPEAT_DELAY = 400;
const REPEAT_RATE = 110;
document.addEventListener('pointerdown', (e) => {
  const btn = e.target.closest('[data-key]');
  if (!btn || e.button > 0) return;
  e.preventDefault();
  const key = btn.dataset.key;
  btn.classList.add('pressed');
  sendKey(key);
  let delay;
  let interval;
  if (btn.hasAttribute('data-repeat')) {
    delay = setTimeout(() => {
      interval = setInterval(() => sendKey(key), REPEAT_RATE);
    }, REPEAT_DELAY);
  }
  const release = () => {
    clearTimeout(delay);
    clearInterval(interval);
    btn.classList.remove('pressed');
    window.removeEventListener('pointerup', release);
    window.removeEventListener('pointercancel', release);
  };
  window.addEventListener('pointerup', release);
  window.addEventListener('pointercancel', release);
});
// Keep keyboard activation (Enter/Space on a focused button) working.
document.addEventListener('click', (e) => {
  const btn = e.target.closest('[data-key]');
  if (btn && e.detail === 0) sendKey(btn.dataset.key);
});

// Touchpad: swipe to move, tap to select, long-press for long OK.
(() => {
  const pad = $('#touchpad');
  const STEP = 42;
  let origin = null;
  let moved = false;
  let longTimer = null;
  let longActive = false;
  pad.addEventListener('pointerdown', (e) => {
    pad.setPointerCapture(e.pointerId);
    origin = { x: e.clientX, y: e.clientY };
    moved = false;
    longActive = false;
    longTimer = setTimeout(() => {
      longActive = true;
      sendKey('DPAD_CENTER', 'START_LONG');
    }, 600);
  });
  pad.addEventListener('pointermove', (e) => {
    if (!origin) return;
    const dx = e.clientX - origin.x;
    const dy = e.clientY - origin.y;
    if (Math.max(Math.abs(dx), Math.abs(dy)) < STEP) return;
    clearTimeout(longTimer);
    moved = true;
    if (Math.abs(dx) > Math.abs(dy)) sendKey(dx > 0 ? 'DPAD_RIGHT' : 'DPAD_LEFT');
    else sendKey(dy > 0 ? 'DPAD_DOWN' : 'DPAD_UP');
    origin = { x: e.clientX, y: e.clientY };
  });
  const end = () => {
    if (!origin) return;
    clearTimeout(longTimer);
    if (longActive) sendKey('DPAD_CENTER', 'END_LONG');
    else if (!moved) sendKey('DPAD_CENTER');
    origin = null;
  };
  pad.addEventListener('pointerup', end);
  pad.addEventListener('pointercancel', end);
})();

$('#touchpad-toggle').addEventListener('click', () => {
  state.touchpad = !state.touchpad;
  store.set('touchpad', state.touchpad);
  render();
});

// Desktop keyboard shortcuts.
const SHORTCUTS = {
  ArrowUp: 'DPAD_UP',
  ArrowDown: 'DPAD_DOWN',
  ArrowLeft: 'DPAD_LEFT',
  ArrowRight: 'DPAD_RIGHT',
  Enter: 'DPAD_CENTER',
  Backspace: 'BACK',
  Escape: 'BACK',
  h: 'HOME',
  m: 'MENU',
  ' ': 'MEDIA_PLAY_PAUSE',
  '+': 'VOLUME_UP',
  '=': 'VOLUME_UP',
  '-': 'VOLUME_DOWN',
  '0': 'DIGIT_0', '1': 'DIGIT_1', '2': 'DIGIT_2', '3': 'DIGIT_3', '4': 'DIGIT_4',
  '5': 'DIGIT_5', '6': 'DIGIT_6', '7': 'DIGIT_7', '8': 'DIGIT_8', '9': 'DIGIT_9',
};
document.addEventListener('keydown', (e) => {
  if (document.querySelector('dialog[open]')) return;
  if (e.target.closest('input, select, textarea') || e.ctrlKey || e.metaKey || e.altKey) return;
  // Let Enter/Space activate a focused button as usual.
  if (e.target.closest('button') && (e.key === 'Enter' || e.key === ' ')) return;
  const key = SHORTCUTS[e.key];
  if (!key) return;
  e.preventDefault();
  sendKey(key);
});

$('#device-select').addEventListener('change', (e) => {
  state.selected = e.target.value || null;
  store.set('selected', state.selected);
  render();
});

// ---------------------------------------------------------------- apps

function renderApps() {
  const grid = $('#app-grid');
  grid.replaceChildren(
    ...state.apps.map((app, i) => {
      const b = document.createElement('button');
      b.textContent = app.name;
      b.title = `${app.url}\n(right-click or long-press to remove)`;
      b.addEventListener('click', () => {
        const d = current();
        if (!d?.state?.connected) return toast('TV is not connected');
        call('launch', { device: d.id, url: app.url }).catch(report);
      });
      b.addEventListener('contextmenu', (e) => {
        e.preventDefault();
        if (confirm(`Remove "${app.name}" shortcut?`)) {
          state.apps.splice(i, 1);
          store.set('apps', state.apps);
          renderApps();
        }
      });
      return b;
    }),
  );
  const add = document.createElement('button');
  add.className = 'add';
  add.textContent = '+ Add app';
  add.addEventListener('click', openAppPicker);
  grid.append(add);
}

// ---------------------------------------------------------------- manage

$('#manage-btn').addEventListener('click', () => $('#manage-dialog').showModal());
$('#rescan-btn').addEventListener('click', () => call('rescan').catch(report));

$('#add-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const form = e.target;
  try {
    const d = await call('add', { host: form.host.value, name: form.name.value, type: form.type.value });
    form.reset();
    selectDevice(d.id);
    startPairing(d);
  } catch (err) {
    report(err);
  }
});

function selectDevice(id) {
  state.selected = id;
  store.set('selected', id);
  render();
}

function li(title, sub, err, ...buttons) {
  const item = document.createElement('li');
  const meta = document.createElement('div');
  meta.className = 'meta';
  const strong = document.createElement('strong');
  strong.textContent = title;
  const small = document.createElement('small');
  small.textContent = sub;
  meta.append(strong, small);
  if (err) {
    const e = document.createElement('small');
    e.className = 'err';
    e.textContent = err;
    meta.append(e);
  }
  item.append(meta, ...buttons);
  return item;
}

function button(label, onClick, cls) {
  const b = document.createElement('button');
  b.type = 'button';
  b.textContent = label;
  if (cls) b.className = cls;
  b.addEventListener('click', onClick);
  return b;
}

function deviceStatus(d) {
  if (d.pairing) return 'Pairing…';
  if (!d.paired) return 'Not paired';
  return d.state?.connected ? 'Connected' : 'Offline';
}

function renderManage() {
  const list = $('#device-list');
  if (!state.devices.length) {
    const empty = document.createElement('li');
    empty.className = 'empty';
    empty.textContent = 'No TVs yet. Pick one found on your network or add its IP address.';
    list.replaceChildren(empty);
  } else {
    list.replaceChildren(
      ...state.devices.map((d) =>
        li(
          d.name,
          `${d.type === 'firetv' ? 'Fire TV · ' : ''}${d.host} · ${deviceStatus(d)}`,
          d.error,
          d.paired
            ? d.state?.connected
              ? button('Use', () => {
                  selectDevice(d.id);
                  $('#manage-dialog').close();
                }, 'primary')
              : button('Connect', () => call('connect', { device: d.id }).catch(report))
            : button('Pair', () => startPairing(d), 'primary'),
          button('Edit', () => openEdit(d)),
          button('✕', () => {
            if (confirm(`Remove ${d.name}?`)) call('remove', { device: d.id }).catch(report);
          }),
        ),
      ),
    );
  }

  const disc = $('#discovered-list');
  if (!state.discovered.length) {
    const empty = document.createElement('li');
    empty.className = 'empty';
    empty.textContent = 'Searching… (requires the server to be on the same network as the TV)';
    disc.replaceChildren(empty);
  } else {
    disc.replaceChildren(
      ...state.discovered.map((d) =>
        li(
          d.name,
          `${d.type === 'firetv' ? 'Amazon Fire TV · ' : ''}${d.host}`,
          null,
          button('Add', async () => {
            try {
              const added = await call('add', { host: d.host, name: d.name, type: d.type });
              selectDevice(added.id);
              startPairing(added);
            } catch (err) {
              report(err);
            }
          }, 'primary'),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------- pairing

async function startPairing(d) {
  state.pairingDevice = d;
  const adb = d.type === 'firetv';
  $('#pair-name').textContent = d.name;
  $('#pair-code-mode').hidden = adb;
  $('#pair-adb-mode').hidden = !adb;
  $('#pair-code').required = !adb;
  $('#pair-submit').hidden = adb;
  $('#pair-retry').hidden = true;
  $('#pair-error').textContent = adb ? 'Waiting for you to allow Kalimote on the TV…' : 'Connecting to TV…';
  $('#pair-error').classList.add('pending');
  $('#pair-code').value = '';
  if (!$('#pair-dialog').open) $('#pair-dialog').showModal();
  try {
    await call('pair.start', { device: d.id });
    if (adb) {
      $('#pair-dialog').close();
      $('#manage-dialog').close();
      selectDevice(d.id);
      toast(`Paired with ${d.name}`);
      return;
    }
    $('#pair-error').textContent = '';
    $('#pair-error').classList.remove('pending');
    $('#pair-code').focus();
  } catch (e) {
    if (state.pairingDevice?.id !== d.id || /cancelled/.test(e.message)) return;
    $('#pair-error').classList.remove('pending');
    $('#pair-error').textContent = e.message;
    $('#pair-retry').hidden = !adb;
  }
}

$('#pair-retry').addEventListener('click', () => state.pairingDevice && startPairing(state.pairingDevice));

$('#pair-code').addEventListener('input', (e) => {
  e.target.value = e.target.value.toUpperCase().replace(/[^0-9A-F]/g, '');
});

$('#pair-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const d = state.pairingDevice;
  if (!d) return;
  $('#pair-error').textContent = 'Pairing…';
  try {
    await call('pair.finish', { device: d.id, code: $('#pair-code').value });
    $('#pair-dialog').close();
    $('#manage-dialog').close();
    selectDevice(d.id);
    toast(`Paired with ${d.name}`);
  } catch (err) {
    $('#pair-error').textContent = err.message;
    // If the TV closed the session, start over so a new code is shown.
    const latest = await call('list').catch(() => null);
    const fresh = latest?.devices.find((x) => x.id === d.id);
    if (fresh && !fresh.pairing) {
      $('#pair-error').textContent = `${err.message}. A new code was requested.`;
      call('pair.start', { device: d.id }).catch(report);
    }
  }
});

$('#pair-cancel').addEventListener('click', () => {
  if (state.pairingDevice) call('pair.cancel', { device: state.pairingDevice.id }).catch(() => {});
  state.pairingDevice = null;
  $('#pair-dialog').close();
});

// ---------------------------------------------------------------- text

$('#keyboard-btn').addEventListener('click', () => {
  $('#text-input').value = '';
  $('#text-dialog').showModal();
  $('#text-input').focus();
});

$('#text-form').addEventListener('submit', (e) => {
  e.preventDefault();
  const d = current();
  const text = $('#text-input').value;
  if (!d?.state?.connected || !text) return;
  call('text', { device: d.id, text })
    .then(() => {
      $('#text-input').value = '';
      toast('Text sent');
    })
    .catch(report);
});

$('#text-dialog').addEventListener('click', (e) => {
  if (e.target === e.currentTarget) e.currentTarget.close();
});
$('#manage-dialog').addEventListener('click', (e) => {
  if (e.target === e.currentTarget) e.currentTarget.close();
});

// ---------------------------------------------------------------- render

function prettyApp(pkg) {
  if (!pkg) return '';
  const known = {
    'com.google.android.tvlauncher': 'Home',
    'com.google.android.apps.tv.launcherx': 'Home',
    'com.amazon.tv.launcher': 'Home',
    'com.amazon.firetv.youtube': 'YouTube',
    'com.amazon.cloud9': 'Silk Browser',
    'com.amazon.avod': 'Prime Video',
    'com.amazon.avod.thirdpartyclient': 'Prime Video',
    'com.amazon.tv.settings.v2': 'Settings',
    'com.google.android.youtube.tv': 'YouTube',
    'com.netflix.ninja': 'Netflix',
    'com.amazon.amazonvideo.livingroom': 'Prime Video',
    'com.disney.disneyplus': 'Disney+',
    'com.spotify.tv.android': 'Spotify',
    'com.plexapp.android': 'Plex',
  };
  if (known[pkg]) return known[pkg];
  if (/^org\.jellyfin\./.test(pkg)) return /\.debug$/.test(pkg) ? 'Jellyfin (debug)' : 'Jellyfin';
  return pkg;
}

function render() {
  if (!state.devices.some((d) => d.id === state.selected)) {
    state.selected = state.devices[0]?.id ?? null;
  }
  const d = current();

  const select = $('#device-select');
  select.replaceChildren(
    ...(state.devices.length
      ? state.devices.map((x) => new Option(x.name, x.id, false, x.id === state.selected))
      : [new Option('No TV added', '')]),
  );

  const dot = $('#status-dot');
  const connected = !!d?.state?.connected;
  dot.className = `dot ${!socketUp ? 'off' : connected ? 'on' : d?.paired ? 'wait' : ''}`;
  dot.title = !socketUp ? 'Server unreachable' : d ? deviceStatus(d) : 'No TV';

  $('#remote').classList.toggle('disabled', !connected);

  const banner = $('#banner');
  let bannerContent = null;
  if (!socketUp) bannerContent = ['Connecting to Kalimote server…'];
  else if (!d) bannerContent = ['Add your TV to get started.', button('Add TV', () => $('#manage-dialog').showModal(), 'primary')];
  else if (!d.paired && !d.pairing) bannerContent = [`${d.name} needs to be paired.`, button('Pair', () => startPairing(d), 'primary')];
  else if (d.paired && !connected) {
    bannerContent = [
      d.error ? `${d.name}: ${d.error}` : `Connecting to ${d.name}…`,
      ...(d.mac
        ? [button('Wake TV', () => call('wake', { device: d.id }).then(() => toast('Wake-on-LAN sent'), report), 'primary')]
        : []),
      button('Retry', () => call('connect', { device: d.id }).catch(report)),
    ];
  }
  banner.hidden = !bannerContent;
  if (bannerContent) {
    const span = document.createElement('span');
    span.textContent = bannerContent[0];
    banner.replaceChildren(span, ...bannerContent.slice(1));
  }

  const vol = d?.state?.volume;
  $('#volume-label').textContent = vol ? (vol.muted ? 'MUTE' : String(vol.level)) : 'VOL';
  const powerState = d?.state?.powered === false ? ' · Off' : '';
  $('#current-app').textContent = connected ? prettyApp(d.state.currentApp) + powerState : '';

  const sleepLeft = d?.sleepAt ? Math.max(0, Math.ceil((d.sleepAt - Date.now()) / 60000)) : null;
  $('#sleep-badge').hidden = sleepLeft === null;
  $('#sleep-badge').textContent = sleepLeft === null ? '' : `${sleepLeft}m`;
  $('#sleep-status').textContent = sleepLeft === null ? 'Turn the TV off after…' : `TV turns off in ${sleepLeft} min. Change it:`;
  $('#sleep-cancel').hidden = sleepLeft === null;
  $('#macro-status').textContent = d?.runningMacro ? `Running “${d.runningMacro}”…` : '';
  for (const b of document.querySelectorAll('#macro-grid [data-macro]')) {
    b.classList.toggle('macro-running', state.macros.find((m) => m.id === b.dataset.macro)?.name === d?.runningMacro);
  }

  $('#touchpad-toggle').setAttribute('aria-pressed', String(state.touchpad));
  $('#dpad').hidden = state.touchpad;
  $('#touchpad').hidden = !state.touchpad;

  $('#firetv-tools').hidden = !(connected && d?.type === 'firetv');
  for (const b of document.querySelectorAll('[data-jf-browse]')) {
    b.hidden = !(connected && state.jellyfin.configured);
  }

  renderManage();
  renderJellyfin();
}

// ---------------------------------------------------------------- open link

$('#link-form').addEventListener('submit', (e) => {
  e.preventDefault();
  const d = current();
  if (!d?.state?.connected) return toast('TV is not connected');
  const url = e.target.url.value.trim();
  call('launch', { device: d.id, url })
    .then(() => {
      e.target.reset();
      toast('Opening on TV…');
    })
    .catch(report);
});

// ---------------------------------------------------------------- sleep timer

$('#sleep-btn').addEventListener('click', () => {
  if (!current()?.paired) return toast('Pair a TV first');
  $('#sleep-dialog').returnValue = ''; // a backdrop click must not repeat the last choice
  $('#sleep-dialog').showModal();
});
$('#sleep-dialog').addEventListener('close', () => {
  const v = $('#sleep-dialog').returnValue;
  const d = current();
  if (v === '' || !d) return;
  call('sleep', { device: d.id, minutes: Number(v) })
    .then(() => toast(Number(v) ? `TV will turn off in ${v} minutes` : 'Sleep timer off'))
    .catch(report);
});
setInterval(render, 30000); // keep the countdown fresh

// ---------------------------------------------------------------- volume

$('#volume-label').addEventListener('click', () => {
  const d = current();
  const vol = d?.state?.volume;
  if (!d?.state?.connected || !vol) return toast('Volume not available yet');
  const range = $('#volume-range');
  range.max = vol.max || 100;
  range.value = vol.level;
  $('#volume-value').textContent = vol.level;
  $('#volume-dialog').showModal();
});
$('#volume-range').addEventListener('input', (e) => {
  $('#volume-value').textContent = e.target.value;
});
$('#volume-range').addEventListener('change', (e) => {
  const d = current();
  if (d) call('volume', { device: d.id, level: Number(e.target.value) }).catch(report);
});

// ---------------------------------------------------------------- macros

let editingMacro = null;

function renderMacros() {
  const grid = $('#macro-grid');
  grid.replaceChildren(
    ...state.macros.map((m) => {
      const b = document.createElement('button');
      b.textContent = m.name;
      b.dataset.macro = m.id;
      b.title = `${m.script}\n(right-click or long-press to edit)`;
      b.addEventListener('click', () => {
        const d = current();
        if (!d?.state?.connected) return toast('TV is not connected');
        if (d.runningMacro === m.name) {
          call('macro.stop', { device: d.id }).catch(() => {});
          return;
        }
        call('macro.run', { device: d.id, macro: m.id }).catch((err) => {
          if (!/cancelled/.test(err.message)) report(err);
        });
      });
      b.addEventListener('contextmenu', (e) => {
        e.preventDefault();
        openMacro(m);
      });
      return b;
    }),
  );
  const add = document.createElement('button');
  add.className = 'add';
  add.textContent = '+ New macro';
  add.addEventListener('click', () => openMacro(null));
  grid.append(add);
}

function openMacro(m) {
  editingMacro = m;
  const f = $('#macro-form');
  f.name.value = m?.name ?? '';
  f.script.value = m?.script ?? '';
  $('#macro-title').textContent = m ? 'Edit macro' : 'New macro';
  $('#macro-delete').hidden = !m;
  $('#macro-error').textContent = '';
  $('#macro-dialog').showModal();
}

$('#macro-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = e.target;
  try {
    await call('macro.save', { macroId: editingMacro?.id, name: f.name.value, script: f.script.value });
    $('#macro-dialog').close();
  } catch (err) {
    $('#macro-error').textContent = err.message;
  }
});
$('#macro-test').addEventListener('click', () => {
  const d = current();
  if (!d?.state?.connected) return ($('#macro-error').textContent = 'TV is not connected');
  $('#macro-error').textContent = '';
  call('macro.run', { device: d.id, script: $('#macro-form').script.value }).catch((err) => {
    $('#macro-error').textContent = err.message;
  });
});
$('#macro-cancel').addEventListener('click', () => $('#macro-dialog').close());
$('#macro-delete').addEventListener('click', () => {
  if (editingMacro && confirm(`Delete “${editingMacro.name}”?`)) {
    call('macro.delete', { macro: editingMacro.id }).catch(report);
    $('#macro-dialog').close();
  }
});

// ---------------------------------------------------------------- edit device

let editingDevice = null;

function openEdit(d) {
  editingDevice = d;
  const f = $('#edit-form');
  f.name.value = d.name;
  f.mac.value = d.mac ?? '';
  $('#edit-error').textContent = '';
  $('#edit-dialog').showModal();
}

$('#edit-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  try {
    await call('update', { device: editingDevice.id, name: e.target.name.value, mac: e.target.mac.value.trim() });
    $('#edit-dialog').close();
  } catch (err) {
    $('#edit-error').textContent = err.message;
  }
});
$('#edit-cancel').addEventListener('click', () => $('#edit-dialog').close());

for (const id of ['#sleep-dialog', '#volume-dialog', '#macro-dialog', '#edit-dialog', '#jellyfin-dialog']) {
  $(id).addEventListener('click', (e) => {
    if (e.target === e.currentTarget) e.currentTarget.close();
  });
}

// ---------------------------------------------------------------- Jellyfin

const fmtTime = (ms) => {
  const t = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(t / 3600);
  const m = Math.floor((t % 3600) / 60);
  const sec = String(t % 60).padStart(2, '0');
  return h ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`;
};

let jfSeeking = false;
let jfPosterFor = null;

function jfVisible() {
  const d = current();
  return !!(d?.state?.connected && d.jellyfinApp);
}

/** Current position, advanced locally between polls while playing. */
function jfPosition(sess) {
  if (!sess) return 0;
  const elapsed = sess.paused || !state.jf ? 0 : Date.now() - state.jf.fetchedAt;
  return Math.min(sess.positionMs + elapsed, sess.item?.runtimeMs || Infinity);
}

function fillSelect(select, items, withOff, selectedIndex) {
  if (document.activeElement === select) return;
  const opts = [
    ...(withOff ? [new Option('Off', '-1', false, selectedIndex === -1)] : []),
    ...items.map((t) => new Option(t.label, String(t.index), false, t.selected)),
  ];
  if (!opts.length) opts.push(new Option('—', ''));
  select.replaceChildren(...opts);
  select.disabled = items.length === 0;
}

function renderJellyfin() {
  const d = current();
  const show = jfVisible();
  $('#jellyfin').hidden = !show;
  $('#jf-manage-status').textContent = state.jellyfin.configured
    ? `Connected to ${state.jellyfin.url}`
    : "Optional: shows what's playing in Jellyfin and lets you seek and change tracks.";
  $('#jf-manage-btn').textContent = state.jellyfin.configured ? 'Change' : 'Set up';
  if (!show) return;
  $('#jf-debug').hidden = !d.jellyfinApp.debug;
  $('#jf-setup').hidden = state.jellyfin.configured;
  const sess = state.jf?.deviceId === d.id ? state.jf.session : null;
  const item = sess?.item;
  $('#jf-now').hidden = !item;
  $('#jf-nosession').hidden = !state.jellyfin.configured || !state.jf || !!item;
  if (!item) return;

  if (jfPosterFor !== item.id) {
    jfPosterFor = item.id;
    const token = store.get('token', '');
    const q = new URLSearchParams({ ...(item.imageTag ? { tag: item.imageTag } : {}), ...(token ? { token } : {}) });
    $('#jf-poster').src = `/api/jellyfin/image/${encodeURIComponent(item.id)}?${q}`;
  }
  $('#jf-title').textContent = item.name;
  const sub = [];
  if (item.seriesName) sub.push(item.seriesName);
  if (item.season != null && item.episode != null) sub.push(`S${item.season} · E${item.episode}`);
  else if (item.year) sub.push(String(item.year));
  if (sess.paused) sub.push('Paused');
  $('#jf-sub').textContent = sub.join(' · ');
  renderJellyfinTime();
  fillSelect($('#jf-audio'), sess.audio, false);
  fillSelect($('#jf-subs'), sess.subtitles, true, sess.subtitleIndex);
}

function renderJellyfinTime() {
  const sess = state.jf?.session;
  if (!sess?.item || $('#jf-now').hidden) return;
  const pos = jfPosition(sess);
  const seek = $('#jf-seek');
  seek.max = String(sess.item.runtimeMs || 1);
  if (!jfSeeking) seek.value = String(pos);
  $('#jf-pos').textContent = fmtTime(jfSeeking ? Number(seek.value) : pos);
  $('#jf-dur').textContent = fmtTime(sess.item.runtimeMs);
}

let jfPolling = false;
async function pollJellyfin() {
  const d = current();
  if (jfPolling || !jfVisible() || !state.jellyfin.configured || !socketUp) return;
  jfPolling = true;
  try {
    const st = await call('jellyfin.status', { device: d.id });
    state.jf = { deviceId: d.id, session: st.session, fetchedAt: Date.now() };
  } catch (e) {
    state.jf = { deviceId: d.id, session: null, fetchedAt: Date.now(), error: e.message };
  } finally {
    jfPolling = false;
    renderJellyfin();
  }
}
setInterval(pollJellyfin, 2000);
setInterval(renderJellyfinTime, 1000);

function jfControl(action, value) {
  const d = current();
  if (!d) return;
  call('jellyfin.control', { device: d.id, action, value })
    .then(() => setTimeout(pollJellyfin, 300))
    .catch(report);
}

document.addEventListener('click', (e) => {
  const b = e.target.closest('[data-jf]');
  if (b) jfControl(b.dataset.jf, b.dataset.value !== undefined ? Number(b.dataset.value) : undefined);
});
$('#jf-seek').addEventListener('input', () => {
  jfSeeking = true;
  renderJellyfinTime();
});
$('#jf-seek').addEventListener('change', (e) => {
  jfSeeking = false;
  jfControl('seek', Number(e.target.value));
});
$('#jf-audio').addEventListener('change', (e) => jfControl('audio', Number(e.target.value)));
$('#jf-subs').addEventListener('change', (e) => jfControl('subtitle', Number(e.target.value)));
$('#jf-message').addEventListener('click', () => {
  const text = prompt('Message to show on the TV');
  if (text) jfControl('message', text);
});

function openJellyfinSettings() {
  const f = $('#jellyfin-form');
  f.url.value = state.jellyfin.url || '';
  f.apiKey.value = '';
  f.apiKey.placeholder = state.jellyfin.configured ? 'Saved (leave blank to keep)' : 'Paste API key';
  $('#jellyfin-remove').hidden = !state.jellyfin.configured;
  $('#jellyfin-error').textContent = '';
  $('#jellyfin-dialog').showModal();
}
for (const id of ['#jf-settings', '#jf-setup-btn', '#jf-manage-btn']) $(id).addEventListener('click', openJellyfinSettings);
$('#jellyfin-cancel').addEventListener('click', () => $('#jellyfin-dialog').close());
$('#jellyfin-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  $('#jellyfin-error').textContent = 'Connecting…';
  try {
    const info = await call('jellyfin.set', { url: e.target.url.value, apiKey: e.target.apiKey.value });
    $('#jellyfin-dialog').close();
    toast(`Connected to ${info.name} (Jellyfin ${info.version})`);
    pollJellyfin();
  } catch (err) {
    $('#jellyfin-error').textContent = err.message;
  }
});
$('#jellyfin-remove').addEventListener('click', async () => {
  await call('jellyfin.set', { url: '' }).catch(report);
  state.jf = null;
  $('#jellyfin-dialog').close();
  toast('Jellyfin server removed');
});

// ---------------------------------------------------------------- app picker

function tokenQuery(extra = {}) {
  const token = store.get('token', '');
  return new URLSearchParams({ ...extra, ...(token ? { token } : {}) }).toString();
}

function addCustomApp() {
  const name = prompt('Shortcut name');
  if (!name) return;
  const url = prompt(
    'App link to open.\nUse market://launch?id=<package.name> for any installed app, or a deep link URL.',
    'market://launch?id=',
  );
  if (!url) return;
  state.apps.push({ name, url });
  store.set('apps', state.apps);
  renderApps();
}

let pickerApps = [];
async function openAppPicker() {
  const d = current();
  $('#apps-list').replaceChildren();
  $('#apps-filter').value = '';
  $('#apps-title').textContent = 'Apps';
  $('#apps-hint').textContent = 'Loading…';
  $('#apps-dialog').showModal();
  if (!d?.state?.connected) {
    $('#apps-hint').textContent = 'Connect to a TV to see its apps.';
    return;
  }
  try {
    const res = await call('apps.list', { device: d.id });
    pickerApps = res.apps;
    $('#apps-title').textContent = res.installed ? `Apps on ${d.name}` : 'Popular apps';
    $('#apps-hint').textContent = res.installed
      ? 'Installed apps. Open one now, or add it to your shortcuts.'
      : "Google TV can't list installed apps, so here are popular ones. Opening one that isn't installed does nothing.";
    renderPicker();
  } catch (e) {
    $('#apps-hint').textContent = e.message;
  }
}

function renderPicker() {
  const q = $('#apps-filter').value.trim().toLowerCase();
  const have = new Set(state.apps.map((a) => a.url));
  $('#apps-list').replaceChildren(
    ...pickerApps
      .filter((a) => !q || a.name.toLowerCase().includes(q) || a.package.includes(q))
      .map((a) => {
        const url = `market://launch?id=${a.package}`;
        const added = have.has(url);
        return li(
          a.name,
          a.package,
          null,
          button('Open', () => {
            const d = current();
            call('launch', { device: d.id, url })
              .then(() => toast(`Opening ${a.name}…`))
              .catch(report);
          }),
          button(added ? 'Added' : 'Add', (e) => {
            if (added) return;
            state.apps.push({ name: a.name, url });
            store.set('apps', state.apps);
            renderApps();
            renderPicker();
          }, added ? '' : 'primary'),
        );
      }),
  );
}
$('#apps-filter').addEventListener('input', renderPicker);
$('#apps-close').addEventListener('click', () => $('#apps-dialog').close());
$('#apps-custom').addEventListener('click', () => {
  $('#apps-dialog').close();
  addCustomApp();
});

// ---------------------------------------------------------------- Fire TV: screenshot

let shotTimer = null;
function loadShot() {
  const d = current();
  if (!d) return;
  const src = `/api/devices/${encodeURIComponent(d.id)}/screenshot?${tokenQuery({ t: Date.now() })}`;
  $('#shot-status').textContent = $('#shot-img').getAttribute('src') ? '' : 'Capturing…';
  const img = new Image();
  img.onload = () => {
    $('#shot-img').src = src;
    $('#shot-save').href = src;
    $('#shot-status').textContent = '';
  };
  img.onerror = () => {
    $('#shot-status').textContent = 'Could not capture the screen.';
  };
  img.src = src;
}
$('#screenshot-btn').addEventListener('click', () => {
  $('#shot-img').removeAttribute('src');
  $('#shot-live').checked = false;
  $('#shot-dialog').showModal();
  loadShot();
});
$('#shot-refresh').addEventListener('click', loadShot);
$('#shot-live').addEventListener('change', (e) => {
  clearInterval(shotTimer);
  if (e.target.checked) shotTimer = setInterval(loadShot, 2000);
});
$('#shot-close').addEventListener('click', () => $('#shot-dialog').close());
$('#shot-dialog').addEventListener('close', () => {
  clearInterval(shotTimer);
  $('#shot-live').checked = false;
});

// ---------------------------------------------------------------- Fire TV: install APK

$('#install-btn').addEventListener('click', () => $('#apk-file').click());
$('#apk-file').addEventListener('change', (e) => {
  const file = e.target.files[0];
  e.target.value = '';
  const d = current();
  if (!file || !d) return;
  $('#install-tv').textContent = d.name;
  $('#install-file').textContent = `${file.name} · ${(file.size / 1048576).toFixed(1)} MB`;
  $('#install-progress').value = 0;
  $('#install-status').textContent = 'Uploading…';
  $('#install-close').textContent = 'Close';
  $('#install-dialog').showModal();
  const xhr = new XMLHttpRequest();
  xhr.open('POST', `/api/devices/${encodeURIComponent(d.id)}/install?${tokenQuery()}`);
  xhr.setRequestHeader('content-type', 'application/vnd.android.package-archive');
  xhr.upload.onprogress = (ev) => {
    if (!ev.lengthComputable) return;
    const pct = Math.round((ev.loaded / ev.total) * 100);
    $('#install-progress').value = pct * 0.5;
    $('#install-status').textContent = pct < 100 ? `Uploading… ${pct}%` : 'Sending to the TV and installing… (this can take a minute)';
  };
  xhr.upload.onload = () => {
    $('#install-progress').removeAttribute('value'); // indeterminate while the TV installs
    $('#install-status').textContent = 'Sending to the TV and installing… (this can take a minute)';
  };
  xhr.onload = () => {
    let res = {};
    try {
      res = JSON.parse(xhr.responseText);
    } catch {}
    $('#install-progress').value = res.ok ? 100 : 0;
    $('#install-status').textContent = res.ok ? `Installed ${file.name} ✓` : `Install failed: ${res.error ?? xhr.status}`;
    if (res.ok) toast('App installed');
  };
  xhr.onerror = () => {
    $('#install-status').textContent = 'Upload failed (lost connection to the Kalimote server).';
  };
  xhr.send(file);
});
$('#install-close').addEventListener('click', () => $('#install-dialog').close());

// ---------------------------------------------------------------- Jellyfin library

const browseStack = []; // [{ title, request }]

function fmtRuntime(ms) {
  if (!ms) return '';
  const m = Math.round(ms / 60000);
  return m >= 60 ? `${Math.floor(m / 60)}h ${m % 60}m` : `${m}m`;
}

function card(item) {
  const b = document.createElement('button');
  b.type = 'button';
  b.className = `card${item.type === 'Episode' || item.type === 'CollectionFolder' ? ' wide-art' : ''}`;
  const poster = document.createElement('div');
  poster.className = 'poster';
  if (item.imageTag) {
    const img = document.createElement('img');
    img.loading = 'lazy';
    img.alt = '';
    img.src = `/api/jellyfin/image/${encodeURIComponent(item.id)}?${tokenQuery({ tag: item.imageTag })}`;
    img.onerror = () => img.remove();
    poster.append(img);
  }
  if (item.progress) {
    const bar = document.createElement('div');
    bar.className = 'bar';
    bar.style.width = `${Math.min(100, item.progress)}%`;
    poster.append(bar);
  }
  if (!item.browsable) {
    const play = document.createElement('span');
    play.className = 'play';
    play.textContent = '▶';
    poster.append(play);
  }
  const t = document.createElement('div');
  t.className = 't';
  t.textContent = item.type === 'Episode' ? `${item.episode ?? ''}. ${item.name}` : item.name;
  const sub = document.createElement('div');
  sub.className = 's';
  sub.textContent =
    item.type === 'Episode'
      ? `${item.seriesName ?? ''} · S${item.season ?? '?'}`
      : [item.year, fmtRuntime(item.runtimeMs)].filter(Boolean).join(' · ');
  b.append(poster, t, sub);
  b.addEventListener('click', () => (item.browsable ? openBrowse({ view: 'open', item }, item.name) : playOnTv(item)));
  return b;
}

async function playOnTv(item) {
  const d = current();
  toast(`Starting ${item.name} on ${d.name}…`);
  try {
    await call('jellyfin.play', { device: d.id, itemId: item.id });
    $('#browse-dialog').close();
    toast(`Playing ${item.name}`);
    setTimeout(pollJellyfin, 800);
  } catch (e) {
    report(e);
  }
}

async function openBrowse(request, title, push = true) {
  const d = current();
  if (!d) return;
  if (push) browseStack.push({ request, title });
  $('#browse-title').textContent = title;
  $('#browse-back').hidden = browseStack.length <= 1;
  const body = $('#browse-body');
  body.replaceChildren(Object.assign(document.createElement('p'), { className: 'hint', textContent: 'Loading…' }));
  if (!$('#browse-dialog').open) $('#browse-dialog').showModal();
  try {
    const res = await call('jellyfin.browse', { device: d.id, ...request });
    if (browseStack.at(-1)?.request !== request) return; // navigated away meanwhile
    const sections = res.sections.filter((sec) => sec.items.length);
    body.replaceChildren(
      ...(sections.length
        ? sections.map((sec) => {
            const wrap = document.createElement('section');
            const h = document.createElement('h3');
            h.textContent = sec.title;
            const grid = document.createElement('div');
            grid.className = 'cards';
            grid.append(...sec.items.map(card));
            wrap.append(h, grid);
            return wrap;
          })
        : [Object.assign(document.createElement('p'), { className: 'hint', textContent: 'Nothing here.' })]),
    );
  } catch (e) {
    body.replaceChildren(Object.assign(document.createElement('p'), { className: 'error', textContent: e.message }));
  }
}

document.addEventListener('click', (e) => {
  if (!e.target.closest('[data-jf-browse]')) return;
  browseStack.length = 0;
  $('#browse-search').q.value = '';
  openBrowse({ view: 'home' }, 'Jellyfin');
});
$('#browse-back').addEventListener('click', () => {
  browseStack.pop();
  const prev = browseStack.at(-1);
  if (prev) openBrowse(prev.request, prev.title, false);
});
$('#browse-close').addEventListener('click', () => $('#browse-dialog').close());
$('#browse-search').addEventListener('submit', (e) => {
  e.preventDefault();
  const q = e.target.q.value.trim();
  if (q) openBrowse({ view: 'search', query: q }, `Search: ${q}`);
});

for (const id of ['#apps-dialog', '#shot-dialog', '#browse-dialog']) {
  $(id).addEventListener('click', (e) => {
    if (e.target === e.currentTarget) e.currentTarget.close();
  });
}

renderApps();
renderMacros();
render();
connectSocket();
