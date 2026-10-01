// Message definitions for the Android TV Remote Protocol v2
// (com.google.android.tv.remote.service). Field numbers follow the
// pairingmessage.proto / remotemessage.proto files used by the official
// Google TV app.

import { Writer, Message } from './proto.js';

export const PROTOCOL_VERSION = 2;

export const Status = {
  OK: 200,
  ERROR: 400,
  BAD_CONFIGURATION: 401,
  BAD_SECRET: 402,
};

export const Encoding = { ALPHANUMERIC: 1, NUMERIC: 2, HEXADECIMAL: 3, QRCODE: 4 };
export const Role = { INPUT: 1, OUTPUT: 2 };

// PairingMessage field numbers
export const Pairing = {
  PROTOCOL_VERSION: 1,
  STATUS: 2,
  REQUEST: 10,
  REQUEST_ACK: 11,
  OPTION: 20,
  CONFIGURATION: 30,
  CONFIGURATION_ACK: 31,
  SECRET: 40,
  SECRET_ACK: 41,
};

function pairingEnvelope(field, body, status = Status.OK) {
  return new Writer()
    .varint(Pairing.PROTOCOL_VERSION, PROTOCOL_VERSION)
    .varint(Pairing.STATUS, status)
    .message(field, body)
    .finish();
}

function encoding(type, symbolLength) {
  return new Writer().varint(1, type).varint(2, symbolLength);
}

export const pairingMsg = {
  request: (clientName, serviceName = 'atvremote') =>
    pairingEnvelope(Pairing.REQUEST, new Writer().string(1, serviceName).string(2, clientName)),
  requestAck: (serverName) =>
    pairingEnvelope(Pairing.REQUEST_ACK, new Writer().string(1, serverName)),
  option: () =>
    pairingEnvelope(
      Pairing.OPTION,
      new Writer().message(1, encoding(Encoding.HEXADECIMAL, 6)).varint(3, Role.INPUT),
    ),
  configuration: () =>
    pairingEnvelope(
      Pairing.CONFIGURATION,
      new Writer().message(1, encoding(Encoding.HEXADECIMAL, 6)).varint(2, Role.INPUT),
    ),
  configurationAck: () => pairingEnvelope(Pairing.CONFIGURATION_ACK, new Writer()),
  secret: (secret) => pairingEnvelope(Pairing.SECRET, new Writer().bytes(1, secret)),
  secretAck: (secret) => pairingEnvelope(Pairing.SECRET_ACK, new Writer().bytes(1, secret)),
  error: (status) =>
    new Writer()
      .varint(Pairing.PROTOCOL_VERSION, PROTOCOL_VERSION)
      .varint(Pairing.STATUS, status)
      .finish(),
};

export function parsePairing(buf) {
  const m = new Message(buf);
  const kinds = Object.entries(Pairing).filter(([, f]) => f >= 10);
  const found = kinds.find(([, f]) => m.has(f));
  return {
    status: m.int(Pairing.STATUS),
    kind: found ? found[0] : null,
    body: found ? m.message(found[1]) : null,
  };
}

// RemoteMessage field numbers
export const Remote = {
  CONFIGURE: 1,
  SET_ACTIVE: 2,
  ERROR: 3,
  PING_REQUEST: 8,
  PING_RESPONSE: 9,
  KEY_INJECT: 10,
  IME_KEY_INJECT: 20,
  IME_BATCH_EDIT: 21,
  IME_SHOW_REQUEST: 22,
  VOICE_BEGIN: 30,
  VOICE_PAYLOAD: 31,
  VOICE_END: 32,
  START: 40,
  SET_VOLUME_LEVEL: 50,
  ADJUST_VOLUME_LEVEL: 51,
  APP_LINK_LAUNCH: 90,
};

export const Direction = { START_LONG: 1, END_LONG: 2, SHORT: 3 };

// Feature bitmask advertised in RemoteConfigure / RemoteSetActive:
// KEY | IME | VOICE | POWER | VOLUME | APP_LINK
export const FEATURES = 622;

const remote = (field, body) => new Writer().message(field, body).finish();

export const remoteMsg = {
  configure: ({ model = 'Kalimote', vendor = 'Kalimote', appVersion = '1.0.0' } = {}) =>
    remote(
      Remote.CONFIGURE,
      new Writer().varint(1, FEATURES).message(
        2,
        new Writer()
          .string(1, model)
          .string(2, vendor)
          .varint(3, 1)
          .string(4, '1')
          .string(5, 'atvremote')
          .string(6, appVersion),
      ),
    ),
  setActive: () => remote(Remote.SET_ACTIVE, new Writer().varint(1, FEATURES)),
  pingResponse: (val1) => remote(Remote.PING_RESPONSE, new Writer().varint(1, val1)),
  key: (keyCode, direction = Direction.SHORT) =>
    remote(Remote.KEY_INJECT, new Writer().varint(1, keyCode).varint(2, direction)),
  appLink: (url) => remote(Remote.APP_LINK_LAUNCH, new Writer().string(1, url)),
  imeBatchEdit: (imeCounter, fieldCounter, text) =>
    remote(
      Remote.IME_BATCH_EDIT,
      new Writer()
        .varint(1, imeCounter)
        .varint(2, fieldCounter)
        .message(
          3,
          new Writer()
            .varint(1, 1)
            .message(
              2,
              new Writer()
                .varint(1, Math.max(text.length - 1, 0))
                .varint(2, Math.max(text.length - 1, 0))
                .string(3, text),
            ),
        ),
    ),
  // Messages sent by the TV (used by the mock TV and tests).
  pingRequest: (val1) => remote(Remote.PING_REQUEST, new Writer().varint(1, val1)),
  start: (started) => remote(Remote.START, new Writer().varint(1, started)),
  volume: ({ level, max, muted, playerModel = '' }) =>
    remote(
      Remote.SET_VOLUME_LEVEL,
      new Writer().string(3, playerModel).varint(6, max).varint(7, level).varint(8, muted),
    ),
  imeKeyInject: (appPackage, imeCounter = 0, fieldCounter = 0) =>
    remote(
      Remote.IME_KEY_INJECT,
      new Writer()
        .message(1, new Writer().varint(1, imeCounter).string(12, appPackage))
        .message(2, new Writer().varint(1, fieldCounter)),
    ),
};

export function parseRemote(buf) {
  const m = new Message(buf);
  const entry = Object.entries(Remote).find(([, f]) => m.has(f));
  return entry ? { kind: entry[0], body: m.message(entry[1]) ?? new Message(Buffer.alloc(0)) } : { kind: null, body: null };
}
