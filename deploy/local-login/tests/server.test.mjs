import assert from 'node:assert/strict';
import {test} from 'node:test';
import {existsSync, mkdtempSync, mkdirSync, writeFileSync, rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {createHash} from 'node:crypto';

test('fixed host refuses uncontrolled dist and digest drift before serving', async () => {
  const server = await import('../server.mjs');
  assert.equal(typeof server.releasePaths, 'function', 'fixed release host missing');
  const runtime = mkdtempSync(path.join(tmpdir(), 'local-release-host-'));
  try {
    const id = 'a'.repeat(32), directory = path.join(runtime, 'releases', id);
    mkdirSync(path.join(directory, 'dist'), {recursive: true});
    writeFileSync(path.join(directory, 'dist/index.html'), 'old saved spa');
    writeFileSync(path.join(directory, 'server.mjs'), 'pinned host');
    const hash = value => createHash('sha256').update(value).digest('hex');
    const descriptor = {id, files: {'dist/index.html': hash('old saved spa'), 'server.mjs': hash('pinned host')}};
    const text = JSON.stringify(descriptor);
    writeFileSync(path.join(directory, 'release.json'), text);
    writeFileSync(path.join(runtime, 'current-release.json'), JSON.stringify({id, descriptorHash: hash(text)}));
    const args = [runtime, path.join(directory, 'dist'), path.join(directory, 'server.mjs')];
    assert.equal(server.releasePaths(...args).dist, path.join(directory, 'dist'));
    assert.throws(() => server.releasePaths(runtime, path.join(runtime, 'other'), args[2]));
    writeFileSync(path.join(directory, 'dist/index.html'), 'candidate bytes accidentally copied over old');
    assert.throws(() => server.releasePaths(...args));
  } finally {rmSync(runtime, {recursive: true, force: true});}
});

test('the four exact administration entry points survive direct navigation', async () => {
  const {route} = await import('../server.mjs');
  for (const page of ['principals', 'organizations', 'appointments', 'authority-grants']) {
    assert.equal(route('/admin/identity/' + page), 'spa');
  }
  for (const target of ['/admin/identity/unknown', '/admin/identity/principals/extra',
    '/admin/identity/%2e%2e/login', '/admin%2fidentity/principals', '/api']) {
    assert.equal(route(target), 'missing');
  }
  assert.equal(route('/api/admin/identity/principals'), 'api');
});

test('SPA fallback never captures API or unknown routes', async () => {
  assert.ok(existsSync(new URL('../server.mjs', import.meta.url)), 'HTTPS server helper missing');
  const {route} = await import('../server.mjs');
  assert.equal(route('/auth/callback?state=example'), 'spa');
  assert.equal(route('/auth/callback?state=example&iss=https%3A%2F%2Flocalhost%3A19443%2Frealms%2Flocal-r1&code=synthetic'), 'spa');
  assert.equal(route('/api/v1/session/context'), 'api');
  assert.equal(route('/api/no-such-endpoint'), 'api');
  assert.equal(route('/unrecognized'), 'missing');
  assert.equal(route('/assets/../private.txt'), 'missing');
});

test('malformed and non-origin-relative targets are rejected without throwing', async () => {
  const {route} = await import('../server.mjs');
  for (const target of ['//[', '//localhost/login', 'https://localhost:19444/login', 'login', '', '/login#fragment', '/login\n']) {
    assert.equal(route(target), 'missing', target);
  }
  assert.equal(route('/auth/callback?iss=https%3A%2F%2Flocalhost%3A19443%2Frealms%2Flocal-r1'), 'spa');
  assert.equal(route('/api/v1/session/context'), 'api');
});
