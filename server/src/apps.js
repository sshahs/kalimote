// Popular TV apps: friendly names for package ids, and the catalog offered in
// the app picker on Google TV (whose remote protocol can't list installed
// apps). Package ids are the common Android TV ones; Fire TV variants are
// listed too. The Android app has the same list in Apps.kt.

export const CATALOG = [
  { name: 'YouTube', packages: ['com.google.android.youtube.tv', 'com.amazon.firetv.youtube'] },
  { name: 'Netflix', packages: ['com.netflix.ninja'] },
  { name: 'Prime Video', packages: ['com.amazon.amazonvideo.livingroom', 'com.amazon.avod', 'com.amazon.avod.thirdpartyclient'] },
  { name: 'Disney+', packages: ['com.disney.disneyplus'] },
  { name: 'Max', packages: ['com.wbd.stream', 'com.hbo.hbonow'] },
  { name: 'Hulu', packages: ['com.hulu.livingroomplus'] },
  { name: 'Apple TV', packages: ['com.apple.atve.androidtv.appletv', 'com.apple.atve.amazon.appletv'] },
  { name: 'Paramount+', packages: ['com.cbs.ott'] },
  { name: 'Peacock', packages: ['com.peacocktv.peacockandroid'] },
  { name: 'Spotify', packages: ['com.spotify.tv.android'] },
  { name: 'YouTube Music', packages: ['com.google.android.youtube.tvmusic'] },
  { name: 'Plex', packages: ['com.plexapp.android'] },
  { name: 'Jellyfin', packages: ['org.jellyfin.androidtv'] },
  { name: 'Kodi', packages: ['org.xbmc.kodi'] },
  { name: 'Twitch', packages: ['tv.twitch.android.app', 'tv.twitch.android.viewer'] },
  { name: 'Crunchyroll', packages: ['com.crunchyroll.crunchyroid'] },
  { name: 'VLC', packages: ['org.videolan.vlc'] },
  { name: 'SmartTube', packages: ['com.liskovsoft.smarttubetv.beta', 'com.liskovsoft.smarttubetv'] },
  { name: 'Play Store', packages: ['com.android.vending'] },
  { name: 'Settings', packages: ['com.android.tv.settings', 'com.amazon.tv.settings.v2'] },
];

const NAMES = new Map(CATALOG.flatMap((a) => a.packages.map((p) => [p, a.name])));
NAMES.set('com.google.android.tvlauncher', 'Home');
NAMES.set('com.google.android.apps.tv.launcherx', 'Home');
NAMES.set('com.amazon.tv.launcher', 'Home');
NAMES.set('com.amazon.cloud9', 'Silk Browser');
NAMES.set('org.jellyfin.androidtv.debug', 'Jellyfin (debug)');

const NOISE = new Set(['com', 'org', 'net', 'tv', 'android', 'androidtv', 'app', 'apps', 'firetv', 'amazon', 'google', 'mobile', 'client', 'ott', 'leanback', 'atv', 'beta', 'release', 'free', 'pro']);

/** A readable name for a package id. */
export function appName(pkg) {
  if (!pkg) return '';
  if (NAMES.has(pkg)) return NAMES.get(pkg);
  const debug = /\.debug$/.test(pkg);
  const base = pkg.replace(/\.debug$/, '');
  if (NAMES.has(base)) return `${NAMES.get(base)} (debug)`;
  const parts = base.split('.').filter((p) => !NOISE.has(p.toLowerCase()));
  // The last meaningful segment is usually the app (com.example.coolapp → Coolapp).
  const word = parts.length ? parts[parts.length - 1] : base.split('.').pop();
  const name = word.charAt(0).toUpperCase() + word.slice(1);
  return debug ? `${name} (debug)` : name;
}

export const launchUrl = (pkg) => `market://launch?id=${pkg}`;
