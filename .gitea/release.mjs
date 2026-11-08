// Publishes files as assets of the Gitea release for an existing tag, replacing
// same-named assets, so re-runs are safe. Used by .gitea/workflows/android.yml.
//
// Works on pull mirrors: the mirror sync creates a tag-only entry for a new tag
// and POST /releases turns it into a full release. It never creates the tag: a
// tag made on a pull mirror is deleted by its next sync, so tags come from GitHub.
//
//   node .gitea/release.mjs <tag> <title> <notes.md> <file>...
//
// Env: GITEA_URL (https://gitea.example.com), GITEA_REPO (owner/name), GITEA_TOKEN.
import { readFile } from 'node:fs/promises';
import { basename } from 'node:path';

const [tag, title, notesFile, ...files] = process.argv.slice(2);
const { GITEA_URL, GITEA_REPO, GITEA_TOKEN } = process.env;
if (!tag || !files.length || !GITEA_URL || !GITEA_REPO || !GITEA_TOKEN) {
  console.error('usage: GITEA_URL=… GITEA_REPO=owner/name GITEA_TOKEN=… node release.mjs <tag> <title> <notes> <file>…');
  process.exit(2);
}
const api = `${GITEA_URL.replace(/\/+$/, '')}/api/v1/repos/${GITEA_REPO}`;

const HINTS = {
  401: 'the token cannot write releases: set this repository\'s Actions token permissions to permissive, or add a RELEASE_TOKEN secret (scope write:repository)',
  403: 'the token cannot write releases: set this repository\'s Actions token permissions to permissive, or add a RELEASE_TOKEN secret (scope write:repository)',
  404: `repository ${GITEA_REPO} not found, or its Releases unit is disabled (Settings → Repository → Releases)`,
  413: 'upload too large: raise the reverse proxy body limit (e.g. nginx client_max_body_size 100m) and [repository.release] FILE_MAX_SIZE',
  400: "if this is 'file type is not allowed', add .apk,.txt to [repository.release] ALLOWED_TYPES",
};

async function call(method, path, body, { allow = [] } = {}) {
  const headers = { Authorization: `token ${GITEA_TOKEN}`, Accept: 'application/json' };
  const form = body instanceof FormData;
  if (body && !form) headers['Content-Type'] = 'application/json';
  let res;
  try {
    res = await fetch(api + path, { method, headers, body: form ? body : body && JSON.stringify(body) });
  } catch (e) {
    throw new Error(`${method} ${path}: cannot reach ${GITEA_URL} from the runner (${e.cause?.code ?? e.message}); check ROOT_URL and runner networking`);
  }
  if (allow.includes(res.status)) return { status: res.status, json: null };
  if (!res.ok) {
    const hint = HINTS[res.status] ? ` — ${HINTS[res.status]}` : '';
    throw new Error(`${method} ${path} → HTTP ${res.status}${hint}\n${(await res.text()).slice(0, 800)}`);
  }
  return { status: res.status, json: res.status === 204 ? null : await res.json() };
}

try {
  const t = await call('GET', `/tags/${encodeURIComponent(tag)}`, null, { allow: [404] });
  if (t.status === 404) {
    throw new Error(`Tag ${tag} doesn't exist here. Tags come from GitHub through the mirror; release there first.`);
  }

  const notes = notesFile ? await readFile(notesFile, 'utf8') : '';
  const fields = { name: title, body: notes, draft: false, prerelease: /-/.test(tag) };
  // 404 also covers the tag-only entry a mirror sync creates: POST converts it.
  let { status, json: release } = await call('GET', `/releases/tags/${encodeURIComponent(tag)}`, null, { allow: [404] });
  if (status === 404) {
    const created = await call('POST', '/releases', { tag_name: tag, ...fields }, { allow: [409] });
    release = created.status === 409 // created meanwhile by another run
      ? (await call('GET', `/releases/tags/${encodeURIComponent(tag)}`)).json
      : created.json;
    console.log(`Created release ${tag}`);
  } else {
    release = (await call('PATCH', `/releases/${release.id}`, fields)).json;
    console.log(`Updating release ${tag}`);
  }

  const { json: assets } = await call('GET', `/releases/${release.id}/assets`);
  for (const file of files) {
    const name = basename(file);
    for (const old of assets ?? []) {
      if (old.name === name) await call('DELETE', `/releases/${release.id}/assets/${old.id}`);
    }
    const form = new FormData();
    form.append('attachment', new Blob([await readFile(file)]), name);
    await call('POST', `/releases/${release.id}/assets?name=${encodeURIComponent(name)}`, form);
    console.log(`Uploaded ${name}`);
  }
  console.log(`Release published: ${release.html_url}`);
} catch (e) {
  console.log(`::error::${e.message.split('\n')[0]}`);
  console.error(e.message);
  process.exit(1);
}
