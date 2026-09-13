import https from 'node:https';
import {createHash} from 'node:crypto';
import {existsSync, lstatSync, readFileSync, readdirSync, statSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const FIXED_SPA_PORT = 29444;
const FIXED_API_PORT = 29445;
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');

export function route(raw) {
  if (typeof raw !== 'string' || !raw.startsWith('/') || raw.startsWith('//') || /[\s#]/.test(raw)) return 'missing';
  if (/%2e|%2f|%5c|\.\.|\\/i.test(raw.split('?')[0])) return 'missing';
  let pathname;
  try { pathname = new URL(raw, 'https://localhost:29444').pathname; }
  catch { return 'missing'; }
  if (pathname.startsWith('/api/')) return 'api';
  if (['/', '/login', '/auth/callback', '/workbench', '/admin/identity/principals',
    '/admin/identity/organizations', '/admin/identity/appointments',
    '/admin/identity/authority-grants'].includes(pathname)) return 'spa';
  if (/^\/assets\/[A-Za-z0-9_.-]+$/.test(pathname)) return 'asset';
  return 'missing';
}

function regular(pathname, label) {
  if (!existsSync(pathname) || lstatSync(pathname).isSymbolicLink() || !statSync(pathname).isFile()) {
    throw new Error(`${label} missing or linked`);
  }
  return pathname;
}

function canonicalFiles(files) {
  return Object.fromEntries(Object.entries(files).sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0));
}

export function validateDist(distArg, expectedTreeDigest, expectedFiles) {
  if (typeof distArg !== 'string' || !path.isAbsolute(distArg) ||
      typeof expectedTreeDigest !== 'string' || !/^[0-9a-f]{64}$/.test(expectedTreeDigest) ||
      expectedFiles === null || typeof expectedFiles !== 'object' || Array.isArray(expectedFiles)) {
    throw new Error('SPA artifact manifest invalid');
  }
  const dist = path.resolve(distArg);
  if (!existsSync(dist) || lstatSync(dist).isSymbolicLink() || !statSync(dist).isDirectory()) {
    throw new Error('SPA artifact mismatch');
  }
  const actual = {};
  const visit = folder => {
    for (const name of readdirSync(folder).sort()) {
      const file = path.join(folder, name), info = lstatSync(file);
      if (info.isSymbolicLink()) throw new Error('SPA artifact mismatch');
      if (info.isDirectory()) visit(file);
      else if (info.isFile()) actual[path.relative(dist, file).split(path.sep).join('/')] = sha256(readFileSync(file));
    }
  };
  visit(dist);
  const expected = canonicalFiles(expectedFiles), found = canonicalFiles(actual);
  if (!Object.hasOwn(found, 'index.html') || JSON.stringify(found) !== JSON.stringify(expected) ||
      sha256(JSON.stringify(expected)) !== expectedTreeDigest) throw new Error('SPA artifact mismatch');
  return {dist, files: found};
}

function cleanHeaders(source, upstreamHost) {
  const headers = {...source, host: upstreamHost};
  for (const name of Object.keys(headers)) {
    const lower = name.toLowerCase();
    if (lower === 'forwarded' || lower.startsWith('x-forwarded-') ||
        ['connection', 'transfer-encoding', 'upgrade', 'proxy-authorization', 'proxy-connection',
          'keep-alive', 'te', 'trailer'].includes(lower)) delete headers[name];
  }
  return headers;
}

function responseHeaders(source) {
  const headers = {...source};
  for (const name of Object.keys(headers)) {
    if (['connection', 'transfer-encoding', 'upgrade', 'proxy-authenticate', 'proxy-authorization',
      'keep-alive', 'te', 'trailer'].includes(name.toLowerCase())) delete headers[name];
  }
  return headers;
}

export function createR1Server(inputs, testPorts = undefined) {
  const listenPort = testPorts?.listenPort ?? FIXED_SPA_PORT;
  const upstreamPort = testPorts?.upstreamPort ?? FIXED_API_PORT;
  if (!Number.isInteger(listenPort) || listenPort < 0 || listenPort > 65535 ||
      !Number.isInteger(upstreamPort) || upstreamPort < 1 || upstreamPort > 65535) {
    throw new Error('invalid bounded test ports');
  }
  const {dist, files} = validateDist(inputs.dist, inputs.treeDigest, inputs.files);
  const tls = inputs.tls, upstreamCa = inputs.upstreamCa;
  if (!tls?.cert || !tls?.key || !upstreamCa) throw new Error('strict TLS inputs required');
  const expectedHost = `localhost:${listenPort || FIXED_SPA_PORT}`;
  return https.createServer({cert: tls.cert, key: tls.key}, (req, res) => {
    if (req.headers.host !== expectedHost && !(listenPort === 0 && /^localhost:\d+$/.test(req.headers.host ?? ''))) {
      res.writeHead(421, {'Cache-Control': 'no-store'}).end(); return;
    }
    const destination = route(req.url);
    if (destination === 'api') {
      const upstreamHost = `localhost:${upstreamPort}`;
      const upstream = https.request({hostname: 'localhost', family: 4, port: upstreamPort,
        servername: 'localhost', path: req.url, method: req.method,
        headers: cleanHeaders(req.headers, upstreamHost), ca: upstreamCa,
        rejectUnauthorized: true, timeout: 10000}, incoming => {
        res.writeHead(incoming.statusCode, responseHeaders(incoming.headers)); incoming.pipe(res);
      });
      upstream.on('timeout', () => upstream.destroy(new Error('upstream timeout')));
      upstream.on('error', () => {
        if (!res.headersSent) res.writeHead(502, {'Cache-Control': 'no-store'});
        res.end();
      });
      req.pipe(upstream); return;
    }
    if (!['GET', 'HEAD'].includes(req.method) || destination === 'missing') {
      res.writeHead(404, {'Cache-Control': 'no-store'}).end(); return;
    }
    const pathname = new URL(req.url, 'https://localhost:29444').pathname;
    const file = destination === 'spa' ? path.join(dist, 'index.html') : path.join(dist, pathname);
    if (!existsSync(file) || lstatSync(file).isSymbolicLink() || !statSync(file).isFile()) {
      res.writeHead(404, {'Cache-Control': 'no-store'}).end(); return;
    }
    const bytes = readFileSync(file), relative = path.relative(dist, file).split(path.sep).join('/');
    if (sha256(bytes) !== files[relative]) {
      res.writeHead(503, {'Cache-Control': 'no-store'}).end(); return;
    }
    const types = {'.html': 'text/html; charset=utf-8', '.js': 'application/javascript',
      '.css': 'text/css', '.svg': 'image/svg+xml'};
    res.writeHead(200, {'Content-Type': types[path.extname(file)] ?? 'application/octet-stream',
      'Cache-Control': destination === 'spa' ? 'no-store' : 'public, max-age=31536000, immutable',
      'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; connect-src 'self' https://localhost:29443; frame-src https://localhost:29443",
      'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer'});
    res.end(req.method === 'HEAD' ? undefined : bytes);
  });
}

function loadProductionInputs(runtimeArg, applicationsArg) {
  if (![runtimeArg, applicationsArg].every(value => typeof value === 'string' && path.isAbsolute(value))) {
    throw new Error('controlled absolute paths required');
  }
  const runtime = path.resolve(runtimeArg), applications = path.resolve(applicationsArg);
  if (applications !== path.join(runtime, 'applications') || lstatSync(runtime).isSymbolicLink() ||
      lstatSync(applications).isSymbolicLink()) throw new Error('uncontrolled application path');
  const manifest = JSON.parse(readFileSync(regular(path.join(applications, 'manifest.json'), 'application manifest')));
  const serverPath = fileURLToPath(import.meta.url);
  if (manifest.profile !== 'R1_E2E_APPLICATIONS_V1' || manifest.ports?.spa !== FIXED_SPA_PORT ||
      manifest.ports?.api !== FIXED_API_PORT || manifest.serverSourceSha256 !== sha256(readFileSync(serverPath))) {
    throw new Error('application manifest mismatch');
  }
  const spa = manifest.artifacts?.spa;
  if (!spa || path.resolve(runtime, spa.path) !== path.join(runtime, 'artifacts', 'workbench-dist')) {
    throw new Error('application SPA binding mismatch');
  }
  return {dist: path.resolve(runtime, spa.path), treeDigest: spa.sha256, files: spa.files,
    tls: {cert: readFileSync(regular(path.join(runtime, 'certs', 'host.crt'), 'host certificate')),
      key: readFileSync(regular(path.join(runtime, 'certs', 'host.key'), 'host key'))},
    upstreamCa: readFileSync(regular(path.join(runtime, 'certs', 'ca.pem'), 'application CA'))};
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const server = createR1Server(loadProductionInputs(process.argv[2], process.argv[3]));
  server.listen(FIXED_SPA_PORT, '127.0.0.1', () =>
    console.log(`R1_SPA_READY pid=${process.pid} host=127.0.0.1 port=${FIXED_SPA_PORT}`));
}
