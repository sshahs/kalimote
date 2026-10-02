#!/usr/bin/env node
// A fake Jellyfin server exposing just the API Kalimote uses. Serves the
// sessions in fixtures/jellyfin-sessions.json and applies commands to them.
//   node test/mock-jellyfin.js        (port from PORT, default 8096; API key "test-key")
// Each received command is printed as "command <path> <json body>".

import http from 'node:http';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';

const FIXTURE = fileURLToPath(new URL('./fixtures/jellyfin-sessions.json', import.meta.url));
const LIBRARY = fileURLToPath(new URL('./fixtures/jellyfin-library.json', import.meta.url));
// 1x1 PNG
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
  'base64',
);

const POSTER = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 300">
<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#aa5cc3"/><stop offset="1" stop-color="#00a4dc"/></linearGradient></defs>
<rect width="200" height="300" fill="url(#g)"/><circle cx="150" cy="70" r="40" fill="#fff" opacity=".18"/>
<text x="16" y="230" font-family="sans-serif" font-weight="700" font-size="26" fill="#fff">Kalimote</text>
<text x="16" y="262" font-family="sans-serif" font-size="18" fill="#fff" opacity=".85">Stories · S2</text></svg>`;

export class MockJellyfin {
  constructor({ apiKey = 'test-key' } = {}) {
    this.apiKey = apiKey;
    this.sessions = JSON.parse(fs.readFileSync(FIXTURE, 'utf8'));
    this.library = JSON.parse(fs.readFileSync(LIBRARY, 'utf8'));
    this.commands = [];
    this.server = http.createServer((req, res) => this.handle(req, res));
  }

  listen(port = 0, host = '127.0.0.1') {
    return new Promise((resolve) => this.server.listen(port, host, () => resolve(this)));
  }

  get url() {
    return `http://127.0.0.1:${this.server.address().port}`;
  }

  close() {
    this.server.close();
  }

  session(id) {
    return this.sessions.find((s) => s.Id === id);
  }

  async handle(req, res) {
    const chunks = [];
    for await (const c of req) chunks.push(c);
    const body = chunks.length ? JSON.parse(Buffer.concat(chunks).toString()) : null;
    const url = new URL(req.url, 'http://x');
    const json = (status, value) => res.writeHead(status, { 'content-type': 'application/json' }).end(JSON.stringify(value));

    const token = /Token="([^"]*)"/.exec(req.headers.authorization ?? '')?.[1];
    if (token !== this.apiKey) return json(401, { error: 'unauthorized' });

    if (req.method === 'GET' && url.pathname === '/System/Info') {
      return json(200, { ServerName: 'Mock Jellyfin', Version: '10.10.3' });
    }
    if (req.method === 'GET' && url.pathname === '/Sessions') return json(200, this.sessions);

    // Library browsing (enough of the API for Kalimote's browser)
    const items = this.library.items;
    const pub = (list) => list.map(({ Resume, Latest, ...i }) => i);
    if (req.method === 'GET' && url.pathname === '/Users') return json(200, [{ Id: 'user-1', Name: 'sunny' }]);
    let u;
    if (req.method === 'GET' && (u = /^\/Users\/([^/]+)\/(Views|Items\/Resume|Items\/Latest|Items)$/.exec(url.pathname))) {
      if (u[1] !== 'user-1') return json(404, { error: 'no user' });
      if (u[2] === 'Views') return json(200, { Items: this.library.views });
      if (u[2] === 'Items/Resume') return json(200, { Items: pub(items.filter((i) => i.Resume)) });
      if (u[2] === 'Items/Latest') return json(200, pub(items.filter((i) => i.Latest)));
      const q = (url.searchParams.get('searchTerm') ?? url.searchParams.get('SearchTerm') ?? '').toLowerCase();
      const parent = url.searchParams.get('ParentId') ?? url.searchParams.get('parentId');
      const types = (url.searchParams.get('IncludeItemTypes') ?? '').split(',').filter(Boolean);
      const found = items.filter(
        (i) => (!q || i.Name.toLowerCase().includes(q)) && (!parent || i.ParentId === parent) && (!types.length || types.includes(i.Type)),
      );
      return json(200, { Items: pub(found), TotalRecordCount: found.length });
    }
    if (req.method === 'GET' && (u = /^\/Shows\/([^/]+)\/Episodes$/.exec(url.pathname))) {
      return json(200, { Items: pub(items.filter((i) => i.SeriesId === u[1])) });
    }
    const img = /^\/Items\/([^/]+)\/Images\/Primary$/.exec(url.pathname);
    if (req.method === 'GET' && img) {
      // A poster-like gradient so screenshots look real; ?format=png for a raster image.
      if (url.searchParams.get('format') === 'png') return res.writeHead(200, { 'content-type': 'image/png' }).end(PNG);
      return res.writeHead(200, { 'content-type': 'image/svg+xml' }).end(POSTER);
    }

    const play = /^\/Sessions\/([^/]+)\/Playing$/.exec(url.pathname);
    if (req.method === 'POST' && play) {
      const s = this.session(decodeURIComponent(play[1]));
      if (!s) return json(404, { error: 'no session' });
      const item = this.library.items.find((i) => i.Id === url.searchParams.get('itemIds'));
      this.commands.push({ session: s.Id, path: url.pathname + url.search, body });
      console.log(`command ${url.pathname + url.search} null`);
      if (item && url.searchParams.get('playCommand') === 'PlayNow') {
        const { Resume, Latest, ...pubItem } = item;
        s.NowPlayingItem = { ...pubItem, MediaStreams: [] };
        s.PlayState = { PositionTicks: 0, IsPaused: false };
      }
      return res.writeHead(204).end();
    }

    const m = /^\/Sessions\/([^/]+)\/(Playing\/(\w+)|Command)$/.exec(url.pathname);
    if (req.method === 'POST' && m) {
      const s = this.session(decodeURIComponent(m[1]));
      if (!s) return json(404, { error: 'no session' });
      const entry = { session: s.Id, path: url.pathname + url.search, body };
      this.commands.push(entry);
      console.log(`command ${entry.path} ${JSON.stringify(body)}`);
      const ps = s.PlayState;
      switch (m[3] ?? body?.Name) {
        case 'PlayPause':
          ps.IsPaused = !ps.IsPaused;
          break;
        case 'Pause':
          ps.IsPaused = true;
          break;
        case 'Unpause':
          ps.IsPaused = false;
          break;
        case 'Seek':
          ps.PositionTicks = Number(url.searchParams.get('seekPositionTicks'));
          break;
        case 'Stop':
          delete s.NowPlayingItem;
          break;
        case 'SetSubtitleStreamIndex':
          ps.SubtitleStreamIndex = Number(body.Arguments.Index);
          break;
        case 'SetAudioStreamIndex':
          ps.AudioStreamIndex = Number(body.Arguments.Index);
          break;
        default:
          break;
      }
      return res.writeHead(204).end();
    }
    return json(404, { error: 'not found' });
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const mock = await new MockJellyfin({ apiKey: process.env.API_KEY ?? 'test-key' }).listen(
    Number(process.env.PORT ?? 8096),
    process.env.HOST ?? '127.0.0.1',
  );
  console.log(`Mock Jellyfin listening on ${mock.url}`);
}
