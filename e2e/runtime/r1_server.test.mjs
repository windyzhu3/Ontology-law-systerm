import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync} from 'node:fs';
import https from 'node:https';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {test} from 'node:test';

import {createR1Server, route, validateDist} from './r1_server.mjs';

const OPENSSL = process.platform === 'win32'
  ? 'C:/Program Files/Git/usr/bin/openssl.exe'
  : 'openssl';
const hash = value => createHash('sha256').update(value).digest('hex');

function generateTls(root, name, caName = name) {
  const caKey = path.join(root, `${caName}-ca.key`), ca = path.join(root, `${caName}-ca.pem`);
  if (caName === name) execFileSync(OPENSSL, ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
    '-subj', `/CN=${name} CA`, '-keyout', caKey, '-out', ca], {stdio: 'ignore'});
  const key = path.join(root, `${name}.key`), csr = path.join(root, `${name}.csr`);
  const cert = path.join(root, `${name}.crt`), ext = path.join(root, `${name}.ext`);
  writeFileSync(ext, 'subjectAltName=DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth\n');
  execFileSync(OPENSSL, ['req', '-new', '-newkey', 'rsa:2048', '-nodes', '-subj', `/CN=localhost`,
    '-keyout', key, '-out', csr], {stdio: 'ignore'});
  execFileSync(OPENSSL, ['x509', '-req', '-days', '1', '-in', csr, '-CA', ca, '-CAkey', caKey,
    '-set_serial', String(Math.floor(Math.random() * 100000) + 1), '-extfile', ext, '-out', cert], {stdio: 'ignore'});
  return {key: readFileSync(key), cert: readFileSync(cert), ca: readFileSync(ca)};
}

function request(port, ca, url = '/', host = `localhost:${port}`) {
  return new Promise((resolve, reject) => {
    const call = https.request({hostname: 'localhost', family: 4, port, path: url, method: 'GET',
      servername: 'localhost', ca, rejectUnauthorized: true, headers: {host}}, response => {
      const chunks = [];
      response.on('data', chunk => chunks.push(chunk));
      response.on('end', () => resolve({status: response.statusCode, headers: response.headers,
        body: Buffer.concat(chunks).toString()}));
    });
    call.on('error', reject); call.end();
  });
}

async function listen(server) {
  await new Promise((resolve, reject) => {server.once('error', reject); server.listen(0, '127.0.0.1', resolve);});
  return server.address().port;
}

test('route whitelist and original dist bytes fail closed', () => {
  for (const page of ['/', '/login', '/auth/callback', '/workbench',
    '/admin/identity/principals', '/admin/identity/organizations',
    '/admin/identity/appointments', '/admin/identity/authority-grants']) assert.equal(route(page), 'spa');
  assert.equal(route('/api/v1/session/context'), 'api');
  assert.equal(route('/assets/app-1.js'), 'asset');
  for (const bad of ['//localhost/login', '/api', '/unknown', '/assets/../secret',
    '/admin%2fidentity/principals', '/login#fragment', 'login']) assert.equal(route(bad), 'missing');

  const root = mkdtempSync(path.join(tmpdir(), 'r1-server-dist-'));
  try {
    mkdirSync(path.join(root, 'assets'));
    writeFileSync(path.join(root, 'index.html'), 'same spa');
    writeFileSync(path.join(root, 'assets/app.js'), 'same js');
    const files = {'assets/app.js': hash('same js'), 'index.html': hash('same spa')};
    assert.equal(validateDist(root, hash(JSON.stringify(files)), files).files['index.html'], files['index.html']);
    writeFileSync(path.join(root, 'index.html'), 'drifted');
    assert.throws(() => validateDist(root, hash(JSON.stringify(files)), files), /SPA artifact mismatch/);
  } finally {rmSync(root, {recursive: true, force: true});}
});

test('real TLS proxy rejects wrong upstream CA and preserves strict 204 boundary with correct CA', async () => {
  const root = mkdtempSync(path.join(tmpdir(), 'r1-server-tls-'));
  let upstream, wrongFront, correctFront;
  try {
    const upstreamTls = generateTls(root, 'upstream');
    const frontTls = generateTls(root, 'front');
    const wrongTls = generateTls(root, 'wrong');
    let received;
    upstream = https.createServer(upstreamTls, (req, res) => {
      received = req.headers;
      res.writeHead(204, {'Cache-Control': 'private, no-store'}).end();
    });
    const upstreamPort = await listen(upstream);
    const dist = path.join(root, 'dist'); mkdirSync(dist); writeFileSync(path.join(dist, 'index.html'), 'spa');
    const files = {'index.html': hash('spa')};
    const inputs = {dist, files, treeDigest: hash(JSON.stringify(files)), tls: frontTls};

    wrongFront = createR1Server({...inputs, upstreamCa: wrongTls.ca}, {listenPort: 0, upstreamPort});
    const wrongPort = await listen(wrongFront);
    let response = await request(wrongPort, frontTls.ca, '/api/internal', `localhost:${wrongPort}`);
    assert.equal(response.status, 502);
    assert.match(response.headers['cache-control'], /no-store/);
    await new Promise(resolve => wrongFront.close(resolve)); wrongFront = undefined;

    correctFront = createR1Server({...inputs, upstreamCa: upstreamTls.ca}, {listenPort: 0, upstreamPort});
    const correctPort = await listen(correctFront);
    await new Promise((resolve, reject) => {
      const call = https.request({hostname: 'localhost', family: 4, port: correctPort, path: '/api/internal',
        method: 'GET', ca: frontTls.ca, rejectUnauthorized: true, servername: 'localhost',
        headers: {host: `localhost:${correctPort}`, forwarded: 'for=unsafe', 'x-forwarded-for': 'unsafe',
          connection: 'keep-alive'}}, response => {
        const chunks = []; response.on('data', chunk => chunks.push(chunk));
        response.on('end', () => {try {
          assert.equal(response.statusCode, 204); assert.equal(Buffer.concat(chunks).length, 0);
          assert.equal(response.headers.etag, undefined); assert.match(response.headers['cache-control'], /no-store/);
          resolve();
        } catch (error) {reject(error);}});
      }); call.on('error', reject); call.end();
    });
    assert.equal(received.host, `localhost:${upstreamPort}`);
    assert.equal(received.forwarded, undefined); assert.equal(received['x-forwarded-for'], undefined);
    response = await request(correctPort, frontTls.ca, '/', 'wrong.example');
    assert.equal(response.status, 421);
  } finally {
    for (const server of [correctFront, wrongFront, upstream]) if (server?.listening) await new Promise(resolve => server.close(resolve));
    rmSync(root, {recursive: true, force: true});
  }
});
