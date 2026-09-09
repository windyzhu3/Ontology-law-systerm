import assert from 'node:assert/strict';
import {test} from 'node:test';
import {existsSync} from 'node:fs';

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
