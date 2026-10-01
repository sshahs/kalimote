// Android KeyEvent key codes accepted by the remote service.
export const KeyCode = {
  HOME: 3,
  BACK: 4,
  DIGIT_0: 7,
  DIGIT_1: 8,
  DIGIT_2: 9,
  DIGIT_3: 10,
  DIGIT_4: 11,
  DIGIT_5: 12,
  DIGIT_6: 13,
  DIGIT_7: 14,
  DIGIT_8: 15,
  DIGIT_9: 16,
  DPAD_UP: 19,
  DPAD_DOWN: 20,
  DPAD_LEFT: 21,
  DPAD_RIGHT: 22,
  DPAD_CENTER: 23,
  VOLUME_UP: 24,
  VOLUME_DOWN: 25,
  POWER: 26,
  ENTER: 66,
  DEL: 67,
  MENU: 82,
  SEARCH: 84,
  MEDIA_PLAY_PAUSE: 85,
  MEDIA_STOP: 86,
  MEDIA_NEXT: 87,
  MEDIA_PREVIOUS: 88,
  MEDIA_REWIND: 89,
  MEDIA_FAST_FORWARD: 90,
  MUTE: 91,
  MEDIA_PLAY: 126,
  MEDIA_PAUSE: 127,
  VOLUME_MUTE: 164,
  INFO: 165,
  CHANNEL_UP: 166,
  CHANNEL_DOWN: 167,
  GUIDE: 172,
  CAPTIONS: 175,
  SETTINGS: 176,
  TV_INPUT: 178,
  PROG_RED: 183,
  PROG_GREEN: 184,
  PROG_YELLOW: 185,
  PROG_BLUE: 186,
  ASSIST: 219,
  SLEEP: 223,
  WAKEUP: 224,
};

/** Accepts a key name ("DPAD_UP", "KEYCODE_DPAD_UP") or a numeric code. */
export function resolveKey(key) {
  if (typeof key === 'number' && Number.isInteger(key) && key > 0) return key;
  if (typeof key === 'string') {
    if (/^\d+$/.test(key)) return Number(key);
    const name = key.toUpperCase().replace(/^KEYCODE_/, '');
    if (name in KeyCode) return KeyCode[name];
  }
  throw new Error(`Unknown key: ${key}`);
}
