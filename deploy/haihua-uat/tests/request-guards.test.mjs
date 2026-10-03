import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium, request } from '@playwright/test';
import { createRequestGuards } from '../request-guards.mjs';

test('actual route.fetch and direct API POST refuse after physical ownership changes', async t => {
  let posts = 0, guardChecks = 0;
  const server = http.createServer((req, res) => {
    if (req.method === 'POST') { posts++; res.end('ok'); return; }
    res.setHeader('Content-Type', 'text/html');
    res.end('<button onclick="fetch(\'/api/write\',{method:\'POST\'}).catch(()=>{})">Write</button>');
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const origin = `http://127.0.0.1:${server.address().port}`;
  const guard = createRequestGuards(() => { guardChecks++; throw Error('physical ownership changed'); });
  const api = await request.newContext(); t.after(() => api.dispose());
  await assert.rejects(() => guard.guardedApiPost(api, origin + '/api/write'), /ownership changed/);
  assert.equal(posts, 0);
  const browser = await chromium.launch({ channel: 'chrome', headless: true }); t.after(() => browser.close());
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto(origin);
  let routeChecked;
  const checked = new Promise(resolve => { routeChecked = resolve; });
  await page.route('**/api/write', async route => {
    try { await guard.guardedRouteFetch(route); assert.fail('must not dispatch'); }
    catch (error) { assert.match(error.message, /ownership changed/); await route.abort(); routeChecked(); }
  });
  await page.getByRole('button', { name: 'Write' }).click();
  await checked;
  assert.equal(guardChecks, 2); assert.equal(posts, 0);
  // The transport itself works, so zero writes did not come from a dead server.
  const allowed = createRequestGuards(() => {});
  assert.equal((await allowed.guardedApiPost(api, origin + '/api/write')).status(), 200);
  assert.equal(posts, 1);
});

test('helper sources use guarded routes and direct mutation transports', () => {
  const dir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  for (const file of fs.readdirSync(dir).filter(name => name.endsWith('.mjs') && name !== 'request-guards.mjs')) {
    const text = fs.readFileSync(path.join(dir, file), 'utf8');
    assert.ok(!/\broute\.(fetch|continue)\s*\(/.test(text), file + ': unguarded route forwarding');
    assert.ok(!/\.request\.(post|put|patch|delete)\s*\(/.test(text), file + ': unguarded direct mutation');
  }
});
