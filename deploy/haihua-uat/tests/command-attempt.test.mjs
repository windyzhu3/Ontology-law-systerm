import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { reserveAttempt } from '../command-attempt.mjs';

const request = { headers: () => ({ 'idempotency-key': 'original-key', authorization: 'MUST-NOT-PERSIST' }),
  method: () => 'POST', url: () => 'https://localhost:20544/api/v1/leads',
  postDataJSON: () => ({ sourceRecordKey: 'original-source', customerName: 'Synthetic' }) };
test('lost response retains original command/body and rejects new-key rerun', t => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'haihua-attempt-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const attempt = reserveAttempt(dir, 'capture', { username: 'sales01' });
  attempt.dispatch(request); // The route can now forward; simulate loss of response.
  const text = fs.readFileSync(path.join(dir, 'capture-attempt.json'), 'utf8');
  const recorded = JSON.parse(text);
  assert.equal(recorded.state, 'DISPATCHED'); assert.equal(recorded.commandId, 'original-key');
  assert.equal(recorded.body.sourceRecordKey, 'original-source');
  assert.ok(!text.includes('MUST-NOT-PERSIST'));
  assert.throws(() => reserveAttempt(dir, 'capture', {}), /EEXIST/);
  assert.throws(() => attempt.dispatch(request), /refuse second submission/);
  assert.equal(fs.readFileSync(path.join(dir, 'capture-attempt.json'), 'utf8'), text);
});
test('reservation without dispatch also refuses automatic retry', t => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'haihua-attempt-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  reserveAttempt(dir, 'admin-setup', {});
  assert.throws(() => reserveAttempt(dir, 'admin-setup', {}), /EEXIST/);
});
