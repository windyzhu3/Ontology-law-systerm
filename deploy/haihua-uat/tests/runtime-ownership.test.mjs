import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { assertRuntimeActive } from '../runtime-ownership.mjs';

function fixture(t, instance = 'haihua-restore-perf_e1') {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'haihua-ownership-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const runtime = path.join(root, instance); fs.mkdirSync(runtime);
  const jar = Buffer.from('verified artifact'); fs.writeFileSync(path.join(runtime, 'app.jar'), jar);
  fs.writeFileSync(path.join(runtime, 'deployment.json'), JSON.stringify({ releaseDigest: crypto.createHash('sha256').update(jar).digest('hex') }));
  const registry = { spa: { pid: 1, args: ['node'] }, api: { pid: 2, args: ['java'] } };
  fs.writeFileSync(path.join(runtime, 'processes.json'), JSON.stringify(registry));
  const process = pid => ({ ExecutablePath: pid === 1 ? 'node' : 'java', CommandLine: pid === 1
    ? `node ${path.join(runtime, 'server.mjs')} ${runtime}`
    : `java -jar ${path.join(runtime, 'app.jar')} ${path.join(runtime, 'application.properties')}` });
  const containers = names => names.map(name => ({ Name: '/' + name, State: { Running: true }, NetworkSettings: {
    Ports: name.endsWith('-business-db') ? { '5432/tcp': [{ HostIp: '127.0.0.1', HostPort: '20546' }] }
      : { '8443/tcp': [{ HostIp: '127.0.0.1', HostPort: '20543' }] } } }));
  return { runtime, process, containers };
}
test('selected active physical instance passes', t => {
  const f = fixture(t); assert.equal(assertRuntimeActive(f.runtime, f.process, f.containers), true);
});
test('same credentials/issuer cannot let a stopped clone reach original ports', t => {
  const f = fixture(t); assert.throws(() => assertRuntimeActive(f.runtime, () => null, f.containers), /refuse requests/);
});
test('PID reuse by original SPA is rejected', t => {
  const f = fixture(t); const wrong = pid => ({ ...f.process(pid), CommandLine: 'node C:/original/server.mjs' });
  assert.throws(() => assertRuntimeActive(f.runtime, wrong, f.containers), /refuse requests/);
});
test('own applications with wrong physical database refuse', t => {
  const f = fixture(t); const wrong = names => f.containers(names).map(c => ({ ...c, Name: '/ontology-law-haihua-uat-business-db' }));
  assert.throws(() => assertRuntimeActive(f.runtime, f.process, wrong), /refuse requests/);
});
test('changed artifact and stopped database refuse', t => {
  const f = fixture(t);
  assert.throws(() => assertRuntimeActive(f.runtime, f.process, names => f.containers(names).map(c => ({ ...c, State: { Running: false } }))), /refuse requests/);
  fs.appendFileSync(path.join(f.runtime, 'app.jar'), 'changed');
  assert.throws(() => assertRuntimeActive(f.runtime, f.process, f.containers), /refuse requests/);
});
