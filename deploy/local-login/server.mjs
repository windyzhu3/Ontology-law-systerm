import https from 'node:https';
import {readFileSync, existsSync, statSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

export function route(raw) {
  if (typeof raw !== 'string' || !raw.startsWith('/') || raw.startsWith('//') || /[\s#]/.test(raw)) return 'missing';
  if (/%2e|%2f|%5c|\.\.|\\/i.test(raw.split('?')[0])) return 'missing';
  let pathname;
  try {pathname = new URL(raw, 'https://localhost:19444').pathname;}
  catch {return 'missing';}
  if (pathname.startsWith('/api/')) return 'api';
  if (['/', '/login', '/auth/callback', '/workbench'].includes(pathname)) return 'spa';
  if (/^\/assets\/[A-Za-z0-9_.-]+$/.test(pathname)) return 'asset';
  return 'missing';
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
  const runtime = path.join(root, '.superpowers/sdd/2026-09-08-task9-real-user-access-plan/local-login-runtime');
  const dist = path.join(root, 'apps/workbench/dist');
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
    const types = {'.html': 'text/html; charset=utf-8', '.js': 'application/javascript', '.css': 'text/css', '.svg': 'image/svg+xml'};
    res.writeHead(200, {'Content-Type': types[path.extname(file)] ?? 'application/octet-stream',
      'Cache-Control': destination === 'spa' ? 'no-store' : 'public, max-age=31536000, immutable',
      'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; connect-src 'self' https://localhost:19443; frame-src https://localhost:19443",
      'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer'});
    res.end(req.method === 'HEAD' ? undefined : readFileSync(file));
  });
  server.listen(19444, '127.0.0.1', () => console.log('SPA HTTPS listening on fixed loopback 19444'));
}
