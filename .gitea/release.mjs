// Creates (or updates) a Gitea release for a tag and uploads files to it,
// replacing assets with the same name. Used by .gitea/workflows/android.yml.
//
//   node .gitea/release.mjs <tag> <commit> <title> <notes.md> <file>...
//
// Env: GITEA_URL (https://gitea.example.com), GITEA_REPO (owner/name), GITEA_TOKEN.
import { readFile } from 'node:fs/promises';
import { basename } from 'node:path';

const [tag, target, title, notesFile, ...files] = process.argv.slice(2);
const { GITEA_URL, GITEA_REPO, GITEA_TOKEN } = process.env;
if (!tag || !GITEA_URL || !GITEA_REPO || !GITEA_TOKEN) {
  console.error('usage: GITEA_URL=… GITEA_REPO=owner/name GITEA_TOKEN=… node release.mjs <tag> <commit> <title> <notes> <file>…');
  process.exit(2);
}
const api = `${GITEA_URL.replace(/\/+$/, '')}/api/v1/repos/${GITEA_REPO}`;

async function call(method, path, body) {
  const headers = { Authorization: `token ${GITEA_TOKEN}`, Accept: 'application/json' };
  const form = body instanceof FormData;
  if (body && !form) headers['Content-Type'] = 'application/json';
  const res = await fetch(api + path, { method, headers, body: form ? body : body && JSON.stringify(body) });
  if (method === 'GET' && res.status === 404) return null;
  if (!res.ok) throw new Error(`${method} ${path} → ${res.status} ${await res.text()}`);
  return res.status === 204 ? null : res.json();
}

const notes = notesFile ? await readFile(notesFile, 'utf8') : '';
let release = await call('GET', `/releases/tags/${encodeURIComponent(tag)}`);
if (release) {
  release = await call('PATCH', `/releases/${release.id}`, { name: title, body: notes, draft: false });
  console.log(`Updating release ${tag}`);
} else {
  // Creates the tag at <commit> if it doesn't exist yet (e.g. a manual run).
  release = await call('POST', '/releases', {
    tag_name: tag,
    target_commitish: target,
    name: title,
    body: notes,
    draft: false,
    prerelease: false,
  });
  console.log(`Created release ${tag}`);
}

for (const file of files) {
  const name = basename(file);
  for (const asset of release.assets ?? []) {
    if (asset.name === name) await call('DELETE', `/releases/${release.id}/assets/${asset.id}`);
  }
  const form = new FormData();
  form.append('attachment', new Blob([await readFile(file)]), name);
  await call('POST', `/releases/${release.id}/assets?name=${encodeURIComponent(name)}`, form);
  console.log(`Uploaded ${name}`);
}
console.log(release.html_url);
