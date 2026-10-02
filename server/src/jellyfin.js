// Jellyfin integration. Detects Jellyfin running on the TV (from the
// foreground app the TV reports) and, optionally, talks to the Jellyfin
// server's API to show what is playing and control it precisely.
// The Android app implements the same logic in JellyfinClient.kt.

import dns from 'node:dns/promises';

/** Recognises Jellyfin apps, including debug builds (package suffix ".debug"). */
export function detectJellyfin(pkg) {
  if (!pkg || !/^org\.jellyfin\./.test(pkg)) return null;
  return {
    package: pkg,
    debug: /\.debug$/.test(pkg),
    flavor: pkg.startsWith('org.jellyfin.androidtv') ? 'androidtv' : 'mobile',
  };
}

const TICKS_PER_MS = 10000;

/** Strips IPv4-mapped prefixes and ports from a Jellyfin RemoteEndPoint. */
export function endpointIp(endpoint) {
  let ip = String(endpoint ?? '').trim();
  if (ip.startsWith('[')) ip = ip.slice(1, ip.indexOf(']')); // [ipv6]:port
  ip = ip.replace(/^::ffff:/i, '');
  if (/^\d+\.\d+\.\d+\.\d+:\d+$/.test(ip)) ip = ip.replace(/:\d+$/, '');
  return ip;
}

/** Converts a raw Jellyfin session to the shape the UIs use. */
export function normalizeSession(s) {
  const item = s.NowPlayingItem;
  const ps = s.PlayState ?? {};
  const streams = item?.MediaStreams ?? [];
  const track = (type, selected) =>
    streams
      .filter((m) => m.Type === type)
      .map((m) => ({
        index: m.Index,
        label: m.DisplayTitle || m.Language || `${type} ${m.Index}`,
        selected: m.Index === selected,
      }));
  return {
    sessionId: s.Id,
    userId: s.UserId ?? null,
    client: s.Client,
    deviceName: s.DeviceName,
    appVersion: s.ApplicationVersion,
    remoteIp: endpointIp(s.RemoteEndPoint),
    supportsRemoteControl: s.SupportsRemoteControl !== false,
    item: item
      ? {
          id: item.Id,
          name: item.Name,
          seriesName: item.SeriesName ?? null,
          season: item.ParentIndexNumber ?? null,
          episode: item.IndexNumber ?? null,
          type: item.Type,
          year: item.ProductionYear ?? null,
          runtimeMs: Math.round((item.RunTimeTicks ?? 0) / TICKS_PER_MS),
          imageTag: item.ImageTags?.Primary ?? null,
        }
      : null,
    positionMs: Math.round((ps.PositionTicks ?? 0) / TICKS_PER_MS),
    paused: !!ps.IsPaused,
    muted: !!ps.IsMuted,
    audio: track('Audio', ps.AudioStreamIndex),
    subtitles: track('Subtitle', ps.SubtitleStreamIndex),
    subtitleIndex: ps.SubtitleStreamIndex ?? -1,
  };
}

/** Converts a library item for the browser UI. */
export function normalizeItem(i) {
  return {
    id: i.Id,
    name: i.Name,
    type: i.Type,
    seriesName: i.SeriesName ?? null,
    season: i.ParentIndexNumber ?? null,
    episode: i.IndexNumber ?? null,
    year: i.ProductionYear ?? null,
    runtimeMs: Math.round((i.RunTimeTicks ?? 0) / TICKS_PER_MS),
    imageTag: i.ImageTags?.Primary ?? null,
    progress: i.UserData?.PlayedPercentage ?? null,
    // Folders and series open a listing; everything else plays.
    browsable: ['Series', 'Season', 'Folder', 'CollectionFolder', 'BoxSet', 'UserView'].includes(i.Type),
  };
}

/** Strict match for starting playback: the TV's own session only. */
export function pickPlaybackSession(sessions, tvIps) {
  return (
    sessions.find((s) => tvIps.includes(s.remoteIp) && s.supportsRemoteControl) ??
    sessions.find((s) => tvIps.includes(s.remoteIp)) ??
    null
  );
}

/**
 * Picks the session belonging to the TV: same IP address first, then an
 * Android TV client that is playing something, then anything playing.
 */
export function pickSession(sessions, tvIps) {
  const playing = sessions.filter((s) => s.item);
  return (
    sessions.find((s) => tvIps.includes(s.remoteIp) && s.item) ??
    sessions.find((s) => tvIps.includes(s.remoteIp)) ??
    playing.find((s) => /android ?tv/i.test(s.client ?? '')) ??
    playing[0] ??
    null
  );
}

export class JellyfinClient {
  constructor({ url, apiKey, version = '1.0.0', fetchImpl = fetch }) {
    this.url = String(url ?? '').trim().replace(/\/+$/, '');
    this.apiKey = String(apiKey ?? '').trim();
    this.version = version;
    this.fetch = fetchImpl;
    if (!/^https?:\/\//i.test(this.url)) throw new Error('Jellyfin URL must start with http:// or https://');
    if (!this.apiKey) throw new Error('Jellyfin API key is required');
  }

  get authHeader() {
    return `MediaBrowser Client="Kalimote", Device="Kalimote", DeviceId="kalimote-remote", Version="${this.version}", Token="${this.apiKey}"`;
  }

  async request(method, path, body) {
    let res;
    try {
      res = await this.fetch(`${this.url}${path}`, {
        method,
        headers: {
          authorization: this.authHeader,
          ...(body ? { 'content-type': 'application/json' } : {}),
        },
        body: body ? JSON.stringify(body) : undefined,
        signal: AbortSignal.timeout(5000),
      });
    } catch (e) {
      throw new Error(`Cannot reach Jellyfin at ${this.url} (${e.cause?.code ?? e.message})`);
    }
    if (res.status === 401 || res.status === 403) throw new Error('Jellyfin rejected the API key');
    if (!res.ok) throw new Error(`Jellyfin returned HTTP ${res.status}`);
    return res;
  }

  /** Returns server name and version; used by "Test connection". */
  async info() {
    const res = await this.request('GET', '/System/Info');
    const j = await res.json();
    return { name: j.ServerName, version: j.Version };
  }

  async sessions() {
    const res = await this.request('GET', '/Sessions?activeWithinSeconds=960');
    return (await res.json()).map(normalizeSession);
  }

  async ipsFor(tvHost) {
    const ips = [tvHost];
    try {
      ips.push(...(await dns.lookup(tvHost, { all: true })).map((a) => a.address));
    } catch {
      /* unresolvable: rely on the other heuristics */
    }
    return ips;
  }

  /** Finds the session for a TV given its host name or IP. */
  async sessionFor(tvHost, { strict = false } = {}) {
    const ips = await this.ipsFor(tvHost);
    const sessions = await this.sessions();
    return strict ? pickPlaybackSession(sessions, ips) : pickSession(sessions, ips);
  }

  async json(path) {
    return (await this.request('GET', path)).json();
  }

  /** The user to browse as: the TV session's user, else the first user. */
  async userId(session) {
    if (session?.userId) return session.userId;
    const users = await this.json('/Users');
    const user = users.find((u) => !u.Policy?.IsDisabled) ?? users[0];
    if (!user) throw new Error('No Jellyfin users found');
    return user.Id;
  }

  /** Continue watching, latest additions and libraries. */
  async home(userId) {
    const u = encodeURIComponent(userId);
    const [resume, latest, views] = await Promise.all([
      this.json(`/Users/${u}/Items/Resume?Limit=12&MediaTypes=Video&Fields=Overview`),
      this.json(`/Users/${u}/Items/Latest?Limit=16&Fields=Overview`),
      this.json(`/Users/${u}/Views`),
    ]);
    return [
      { title: 'Continue watching', items: (resume.Items ?? []).map(normalizeItem) },
      { title: 'Latest', items: (Array.isArray(latest) ? latest : latest.Items ?? []).map(normalizeItem) },
      { title: 'Libraries', items: (views.Items ?? []).map((v) => ({ ...normalizeItem(v), browsable: true })) },
    ].filter((sec) => sec.items.length);
  }

  async search(userId, query) {
    const q = new URLSearchParams({
      searchTerm: query,
      Recursive: 'true',
      IncludeItemTypes: 'Movie,Series,Episode,Video,MusicVideo',
      Limit: '40',
    });
    const res = await this.json(`/Users/${encodeURIComponent(userId)}/Items?${q}`);
    return [{ title: `Results for “${query}”`, items: (res.Items ?? []).map(normalizeItem) }];
  }

  /** Contents of a library/folder, or the episodes of a series. */
  async children(userId, item) {
    if (item.type === 'Series') {
      const res = await this.json(`/Shows/${encodeURIComponent(item.id)}/Episodes?userId=${encodeURIComponent(userId)}&Fields=Overview`);
      return [{ title: item.name, items: (res.Items ?? []).map(normalizeItem) }];
    }
    const q = new URLSearchParams({
      ParentId: item.id,
      Recursive: 'true',
      IncludeItemTypes: 'Movie,Series,Video,MusicVideo',
      SortBy: 'SortName',
      Limit: '200',
    });
    const res = await this.json(`/Users/${encodeURIComponent(userId)}/Items?${q}`);
    return [{ title: item.name, items: (res.Items ?? []).map(normalizeItem) }];
  }

  /** Starts playing an item on a session ("cast" it to the TV). */
  async play(session, itemId) {
    const q = new URLSearchParams({ playCommand: 'PlayNow', itemIds: itemId });
    await this.request('POST', `/Sessions/${encodeURIComponent(session.sessionId)}/Playing?${q}`);
  }

  /**
   * action: playpause | pause | unpause | stop | next | previous |
   *         seek (value = ms) | seekBy (value = ±ms, needs session) |
   *         subtitle (value = stream index, -1 = off) | audio (value = index) |
   *         message (value = text)
   */
  async control(session, action, value) {
    const id = encodeURIComponent(session.sessionId);
    const playing = (cmd, query = '') => this.request('POST', `/Sessions/${id}/Playing/${cmd}${query}`);
    const general = (Name, Arguments) => this.request('POST', `/Sessions/${id}/Command`, { Name, Arguments });
    switch (action) {
      case 'playpause':
        return playing('PlayPause');
      case 'pause':
        return playing('Pause');
      case 'unpause':
        return playing('Unpause');
      case 'stop':
        return playing('Stop');
      case 'next':
        return playing('NextTrack');
      case 'previous':
        return playing('PreviousTrack');
      case 'seek':
      case 'seekBy': {
        let ms = Number(value);
        if (!Number.isFinite(ms)) throw new Error('Seek needs a number of milliseconds');
        if (action === 'seekBy') ms += session.positionMs;
        const max = session.item?.runtimeMs || Infinity;
        ms = Math.max(0, Math.min(ms, max));
        return playing('Seek', `?seekPositionTicks=${Math.round(ms) * TICKS_PER_MS}`);
      }
      case 'subtitle':
        return general('SetSubtitleStreamIndex', { Index: String(Number(value)) });
      case 'audio':
        return general('SetAudioStreamIndex', { Index: String(Number(value)) });
      case 'message':
        return general('DisplayMessage', { Header: 'Kalimote', Text: String(value ?? ''), TimeoutMs: '5000' });
      default:
        throw new Error(`Unknown Jellyfin action: ${action}`);
    }
  }

  /** Fetches an item's primary image (for the now-playing poster). */
  async image(itemId, tag) {
    const q = new URLSearchParams({ maxHeight: '360', quality: '85', ...(tag ? { tag } : {}) });
    return this.request('GET', `/Items/${encodeURIComponent(itemId)}/Images/Primary?${q}`);
  }
}
