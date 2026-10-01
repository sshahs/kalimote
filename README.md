# Kalimote

Remote control for **Android TV / Google TV** from a browser or an Android phone.

Kalimote uses the same protocol as the official Google TV remote app, the
*Android TV Remote Protocol v2*. The TV needs no developer mode, no ADB and no
extra app. You pair once with a code shown on the TV screen.

```
┌──────────────┐  WebSocket  ┌──────────────────┐   TLS :6467 pairing
│ Browser (web │ ──────────▶ │ Kalimote server  │ ─────────────────────┐
│ remote, PWA) │             │ (Node.js)        │   TLS :6466 control  ▼
└──────────────┘             └──────────────────┘ ───────────────▶ ┌────────┐
                                                                   │   TV   │
┌──────────────┐        TLS :6467 / :6466 (direct)                 └────────┘
│ Android app  │ ─────────────────────────────────────────────────────▲
└──────────────┘
```

| Part | Path | Notes |
| --- | --- | --- |
| Web remote + server | [`server/`](server) | Browsers can't open raw TLS sockets, so a small Node.js server on your LAN talks to the TV and serves the UI. |
| Android app | [`android/app`](android/app) | Kotlin + Jetpack Compose. Talks to the TV directly. |
| Protocol library | [`android/atvremote`](android/atvremote) | Pure Kotlin/JVM implementation of pairing and control, used by the app. |

## Features

- Automatic discovery of TVs on the network (mDNS `_androidtvremote2._tcp`), or add one by IP
- Secure pairing with the 6-character code shown on the TV
- D-pad with press-and-hold repeat, or a swipe **touchpad** mode
- Back, Home, Menu, Assistant, Power, Input, Settings
- Volume and channel rockers, with live volume level from the TV
- Media controls, number pad, colour buttons, captions, guide
- Send text to the TV's focused text field (search boxes, etc.)
- One-tap app launch shortcuts (YouTube, Netflix, Prime Video, Disney+, …), customisable
- Shows the app currently in the foreground on the TV
- Several TVs at once; reconnects automatically
- Web: installable PWA, keyboard shortcuts, light/dark theme, optional access token
- Android: the phone's hardware volume buttons control the TV

## Web remote

Requirements: Node.js 18+, running on a machine on the same network as the TV
(a PC, Raspberry Pi, NAS, …).

```bash
cd server
npm install
npm start          # http://localhost:8080
```

Open the page on any device on your network (`http://<server-ip>:8080`), click
the TV icon, then pick your TV or enter its IP address and type the code shown
on the TV.

Configuration (environment variables):

| Variable | Default | |
| --- | --- | --- |
| `PORT` | `8080` | HTTP port |
| `HOST` | `0.0.0.0` | Bind address |
| `KALIMOTE_DATA` | `~/.kalimote` | Where the client certificate and paired TVs are stored |
| `KALIMOTE_TOKEN` | *(none)* | If set, the UI must be opened once with `?token=<value>` (it is then remembered by the browser) |
| `KALIMOTE_NAME` | `Kalimote Web` | Name shown on the TV during pairing |
| `KALIMOTE_DISCOVERY` | `1` | Set to `0` to disable mDNS discovery |

Anyone who can reach the web server can control your paired TVs. Keep it on
your LAN, or set `KALIMOTE_TOKEN`.

Keyboard shortcuts: arrows = D-pad, Enter = OK, Backspace/Esc = Back, `H` =
Home, `M` = Menu, Space = Play/Pause, `+`/`-` = Volume, digits.

### Docker

```bash
cd server
docker build -t kalimote .
docker run -d --name kalimote --network host -v kalimote-data:/data kalimote
```

`--network host` is needed for mDNS discovery. Without it, add TVs by IP
address and publish the port with `-p 8080:8080`.

## Android app

Download the APK from the latest [CI run](../../actions/workflows/ci.yml)
(`kalimote-apk` artifact), or build it:

```bash
cd android
./gradlew :app:assembleDebug      # needs the Android SDK (ANDROID_HOME)
adb install app/build/outputs/apk/debug/app-debug.apk
```

Minimum Android version: 8.0 (API 26). The phone must be on the same Wi-Fi
network as the TV.

The protocol library builds and tests without the Android SDK:

```bash
cd android
./gradlew -Pkalimote.jvmOnly=true :atvremote:test
```

## Development

`server/test/mock-tv.js` is a fake TV that implements the TV side of the
protocol. Use it to work on either client without a real TV:

```bash
cd server
npm run mock-tv     # prints the pairing code, logs every key received
npm start           # in another terminal; add TV "127.0.0.1"
```

Tests:

```bash
cd server && npm test                                       # protocol + web API, against the mock TV
cd android && ./gradlew -Pkalimote.jvmOnly=true :atvremote:test   # Kotlin client, also against the Node mock TV
```

## How the protocol works

1. **Pairing** (TLS, port 6467). The client presents a self-signed RSA
   certificate. The two sides exchange protobuf messages (request → options →
   configuration), and the TV then shows a 6-hex-digit code. The client sends
   `SHA-256(client modulus, client exponent, server modulus, server exponent,
   code[1..2])`. The first byte of the code is a checksum of that hash. Once the
   TV accepts it, it remembers the client certificate.
2. **Control** (TLS, port 6466, same certificate). The TV sends a configure
   message, the client replies with its features, and the TV marks the session
   active. Then the client sends key presses (Android `KEYCODE_*` values), IME
   text edits and app links. The TV sends power state, volume, the current app
   and keep-alive pings, which the client must answer.

All messages are length-prefixed (varint) protobuf.

## Troubleshooting

- **TV not found:** mDNS can be blocked by routers, VLANs, or Docker without
  host networking. Add the TV by IP instead (TV: Settings → Network & Internet).
- **"TV rejected the connection; pairing required":** the TV forgot this
  remote, for example after a factory reset or after you removed it from the
  TV's list. Pair again.
- **Pairing fails immediately:** make sure the TV is awake. Some TVs only
  allow pairing while the screen is on.
- **Text input does nothing:** focus a text field on the TV first, such as a
  search box.

## License

MIT
