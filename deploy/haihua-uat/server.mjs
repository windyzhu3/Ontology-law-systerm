import https from 'node:https';
import { readFileSync, existsSync } from 'node:fs';
import path from 'node:path';
const runtime = path.resolve(process.argv[2]);
const ca = readFileSync(path.join(runtime, 'certs/ca.pem'));
function handle(req, res) {
  if (!['localhost:20544', '127.0.0.1:20544'].includes(req.headers.host)) { res.writeHead(421).end(); return; }
  const raw = req.url;
  if (!raw?.startsWith('/') || raw.startsWith('//') || /%2e|%2f|%5c|\.\.|\\/i.test(raw.split('?')[0])) { res.writeHead(404).end(); return; }
  const url = new URL(raw, 'https://localhost:20544');
  if (url.pathname.startsWith('/api/')) {
    const headers = { ...req.headers, host: 'localhost:20545' };
    for (const name of Object.keys(headers)) if (name === 'forwarded' || name.startsWith('x-forwarded-') || ['connection', 'transfer-encoding', 'upgrade', 'proxy-authorization', 'proxy-connection'].includes(name)) delete headers[name];
    const upstream = https.request({ hostname: 'localhost', family: 4, port: 20545, path: raw, method: req.method, headers, ca, rejectUnauthorized: true, timeout: 60000 }, incoming => { res.writeHead(incoming.statusCode, incoming.headers); incoming.pipe(res); });
    upstream.on('timeout', () => upstream.destroy());
    upstream.on('error', () => { if (!res.headersSent) res.writeHead(502, { 'Cache-Control': 'no-store' }); res.end(); });
    req.pipe(upstream); return;
  }
  let file;
  if (/^\/assets\/[A-Za-z0-9_.-]+$/.test(url.pathname)) file = path.join(runtime, 'dist', url.pathname);
  else if (['/', '/login', '/auth/callback', '/workbench'].includes(url.pathname) || /^\/(admin\/identity|management|business)\/[a-z/-]+$/.test(url.pathname)) file = path.join(runtime, 'dist/index.html');
  if (!file || !existsSync(file)) { res.writeHead(404).end(); return; }
  const types = { '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.html': 'text/html; charset=utf-8' };
  res.writeHead(200, { 'Content-Type': types[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
  res.end(readFileSync(file));
}
for (const host of ['127.0.0.1', '::1']) https.createServer({ cert: readFileSync(path.join(runtime, 'certs/server.crt')), key: readFileSync(path.join(runtime, 'certs/server.key')) }, handle).listen(20544, host);
