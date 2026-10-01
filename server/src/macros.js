// Macros: small scripts of remote actions, one step per line (or comma
// separated). The same syntax is implemented by the Android app.
//
//   HOME                 press a key (name from keycodes.js, or a number)
//   DPAD_DOWN x3         press a key several times
//   hold DPAD_CENTER 1s  long-press a key
//   wait 500             pause (ms, or "1.5s")
//   text hello world     type into the focused text field
//   open https://...     open an app link / deep link
//   # comment

import { resolveKey } from './protocol/keycodes.js';

const KEY_DELAY_MS = 150;
const MAX_STEPS = 500;
const MAX_WAIT_MS = 10 * 60 * 1000;

function parseDuration(s) {
  const m = /^(\d+(?:\.\d+)?)\s*(ms|s|m)?$/i.exec(String(s ?? '').trim());
  if (!m) throw new Error(`Invalid duration: ${s}`);
  const n = Number(m[1]);
  const unit = (m[2] ?? 'ms').toLowerCase();
  const ms = Math.round(unit === 'm' ? n * 60000 : unit === 's' ? n * 1000 : n);
  if (ms > MAX_WAIT_MS) throw new Error('Waits are limited to 10 minutes');
  return ms;
}

export function parseMacro(script) {
  const steps = [];
  const lines = String(script ?? '').split(/\r?\n/);
  for (const [i, raw] of lines.entries()) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    // Text and links may contain commas, so only split other lines.
    const parts = /^(text|type|open|launch)\b/i.test(line) ? [line] : line.split(',');
    for (const part of parts) {
      const p = part.trim();
      if (!p) continue;
      try {
        steps.push(parseStep(p));
      } catch (e) {
        throw new Error(`Line ${i + 1}: ${e.message}`);
      }
    }
  }
  if (steps.length > MAX_STEPS) throw new Error(`Macros are limited to ${MAX_STEPS} steps`);
  return steps;
}

function parseStep(p) {
  const [word, ...rest] = p.split(/\s+/);
  const arg = p.slice(word.length).trim();
  switch (word.toLowerCase()) {
    case 'wait':
    case 'sleep':
    case 'delay':
      return { type: 'wait', ms: parseDuration(arg) };
    case 'text':
    case 'type':
      if (!arg) throw new Error('text needs a value');
      return { type: 'text', text: arg.replace(/^"(.*)"$/, '$1') };
    case 'open':
    case 'launch':
      if (!/^[a-z][\w+.-]*:/i.test(arg)) throw new Error('open needs a link such as https://… or market://…');
      return { type: 'open', url: arg };
    case 'hold':
      return { type: 'hold', key: resolveKey(rest[0]), ms: rest[1] ? parseDuration(rest[1]) : 1000 };
    default: {
      const m = /^(\S+?)(?:\s*[x*]\s*(\d+))?$/i.exec(p);
      if (!m) throw new Error(`Cannot understand "${p}"`);
      const times = m[2] ? Number(m[2]) : 1;
      if (times < 1 || times > 100) throw new Error('Repeat count must be 1-100');
      return { type: 'key', key: resolveKey(m[1]), times };
    }
  }
}

const sleep = (ms, signal) =>
  new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(new Error('Macro cancelled'));
    const t = setTimeout(resolve, ms);
    signal?.addEventListener('abort', () => {
      clearTimeout(t);
      reject(new Error('Macro cancelled'));
    }, { once: true });
  });

/** Runs parsed steps against a RemoteConnection. */
export async function runMacro(conn, steps, { signal } = {}) {
  for (const step of steps) {
    if (signal?.aborted) throw new Error('Macro cancelled');
    switch (step.type) {
      case 'key':
        for (let i = 0; i < step.times; i++) {
          conn.sendKey(step.key);
          await sleep(KEY_DELAY_MS, signal);
        }
        break;
      case 'hold':
        conn.sendKey(step.key, 'START_LONG');
        await sleep(step.ms, signal);
        conn.sendKey(step.key, 'END_LONG');
        await sleep(KEY_DELAY_MS, signal);
        break;
      case 'wait':
        await sleep(step.ms, signal);
        break;
      case 'text':
        conn.sendText(step.text);
        await sleep(KEY_DELAY_MS, signal);
        break;
      case 'open':
        conn.launchApp(step.url);
        await sleep(KEY_DELAY_MS, signal);
        break;
      default:
        break;
    }
  }
}
