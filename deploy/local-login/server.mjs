import https from 'node:https';
import {readFileSync, existsSync, statSync, lstatSync, readdirSync} from 'node:fs';
import {createHash} from 'node:crypto';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

export function route(raw) {
  if (typeof raw !== 'string' || !raw.startsWith('/') || raw.startsWith('//') || /[\s#]/.test(raw)) return 'missing';
  if (/%2e|%2f|%5c|\.\.|\\/i.test(raw.split('?')[0])) return 'missing';
  let pathname;
  try {pathname = new URL(raw, 'https://localhost:19444').pathname;}
  catch {return 'missing';}
  if (pathname.startsWith('/api/')) return 'api';
  if (['/', '/login', '/auth/callback', '/workbench', '/admin/identity/principals',
    '/admin/identity/organizations', '/admin/identity/appointments', '/admin/identity/authority-grants'].includes(pathname)) return 'spa';
  if (/^\/assets\/[A-Za-z0-9_.-]+$/.test(pathname)) return 'asset';
  return 'missing';
}

const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');

export function releasePaths(runtimeArg, distArg, serverArg) {
  if (![runtimeArg, distArg, serverArg].every(value => typeof value === 'string' && path.isAbsolute(value))) throw new Error('controlled absolute paths required');
  const runtime = path.resolve(runtimeArg), dist = path.resolve(distArg), host = path.resolve(serverArg);
  const directory = path.dirname(host), id = path.basename(directory);
  if (!/^[0-9a-f]{32}$/.test(id) || path.dirname(directory) !== path.join(runtime, 'releases') ||
    dist !== path.join(directory, 'dist') || host !== path.join(directory, 'server.mjs')) throw new Error('uncontrolled release path');
  for (const entry of [runtime, path.join(runtime, 'releases'), directory, dist, host]) {
    if (lstatSync(entry).isSymbolicLink()) throw new Error('linked release rejected');
  }
  const pointer = JSON.parse(readFileSync(path.join(runtime, 'current-release.json')));
  const descriptorBytes = readFileSync(path.join(directory, 'release.json'));
  const descriptor = JSON.parse(descriptorBytes);
  if (pointer.id !== id || descriptor.id !== id || sha256(descriptorBytes) !== pointer.descriptorHash) throw new Error('release descriptor mismatch');
  const actual = {};
  const visit = folder => {
    for (const name of readdirSync(folder)) {
      const file = path.join(folder, name), info = lstatSync(file);
      if (info.isSymbolicLink()) throw new Error('linked release rejected');
      if (info.isDirectory()) visit(file);
      else if (info.isFile() && file !== path.join(directory, 'release.json')) actual[path.relative(directory, file).split(path.sep).join('/')] = sha256(readFileSync(file));
    }
  };
  visit(directory);
  if (Object.keys(actual).length !== Object.keys(descriptor.files).length ||
    Object.entries(actual).some(([name, hash]) => descriptor.files[name] !== hash) || !actual['dist/index.html']) throw new Error('release artifact mismatch');
  const journalFile = path.join(runtime, 'release-journal.json');
  if (existsSync(journalFile) && JSON.parse(readFileSync(journalFile)).phase !== 'COMPLETE') throw new Error('release recovery pending');
  return {runtime, dist, hashes: descriptor.files};
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const {runtime, dist, hashes} = releasePaths(process.argv[2], process.argv[3], fileURLToPath(import.meta.url));
  const ca = readFileSync(path.join(runtime, 'certs/ca.pem'));
  const server = https.createServer({cert: readFileSync(path.join(runtime, 'certs/server.crt')),
    key: readFileSync(path.join(runtime, 'certs/server.key'))}, (req, res) => {
    if (req.headers.host !== 'localhost:19444') {res.writeHead(421).end(); return;}
    const destination = route(req.url);
    if (destination === 'api') {
      const headers = {...req.headers, host: 'localhost:19445'};
      for (const name of Object.keys(headers)) {
        if (name === 'forwarded' || name.startsWith('x-forwarded-') || ['connection', 'transfer-encoding', 'upgrade', 'proxy-authorization', 'proxy-connection'].includes(name)) delete headers[name];
      }
      const upstream = https.request({hostname: 'localhost', family: 4, port: 19445, servername: 'localhost',
        path: req.url, method: req.method, headers, ca, rejectUnauthorized: true, timeout: 10000}, incoming => {
        if (req.url.split('?')[0] === '/api/v1/session/context') console.log('SELF response status=' + incoming.statusCode);
        res.writeHead(incoming.statusCode, incoming.headers); incoming.pipe(res);
      });
      upstream.on('timeout', () => upstream.destroy());
      upstream.on('error', () => {if (!res.headersSent) res.writeHead(502, {'Cache-Control': 'no-store'}); res.end();});
      req.pipe(upstream); return;
    }
    if (!['GET', 'HEAD'].includes(req.method) || destination === 'missing') {res.writeHead(404).end(); return;}
    const pathname = new URL(req.url, 'https://localhost:19444').pathname;
    const file = destination === 'spa' ? path.join(dist, 'index.html') : path.join(dist, pathname);
    if (!existsSync(file) || !statSync(file).isFile()) {res.writeHead(404).end(); return;}
    const bytes = readFileSync(file);
    const key = 'dist/' + path.relative(dist, file).split(path.sep).join('/');
    if (lstatSync(file).isSymbolicLink() || sha256(bytes) !== hashes[key]) {res.writeHead(503, {'Cache-Control': 'no-store'}).end(); return;}
    const types = {'.html': 'text/html; charset=utf-8', '.js': 'application/javascript', '.css': 'text/css', '.svg': 'image/svg+xml'};
    res.writeHead(200, {'Content-Type': types[path.extname(file)] ?? 'application/octet-stream',
      'Cache-Control': destination === 'spa' ? 'no-store' : 'public, max-age=31536000, immutable',
      'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; connect-src 'self' https://localhost:19443; frame-src https://localhost:19443",
      'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer'});
    res.end(req.method === 'HEAD' ? undefined : bytes);
  });
  server.listen(19444, '127.0.0.1', () => console.log('SPA HTTPS listening on fixed loopback 19444'));
}
