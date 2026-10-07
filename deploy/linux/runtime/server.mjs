import https from 'node:https';
import {readFileSync,lstatSync} from 'node:fs';
import {createHash} from 'node:crypto';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

export function route(raw) {
  if(typeof raw!=='string'||!raw.startsWith('/')||raw.startsWith('//')||/[\s#]/.test(raw))return 'missing';
  const pathname=raw.split('?')[0];
  if(/%2e|%2f|%5c|\.\.|\\/i.test(pathname))return 'missing';
  if(pathname.startsWith('/api/'))return 'api';
  if(['/','/login','/auth/callback','/workbench'].includes(pathname)||/^\/(business|management|admin)\/[A-Za-z0-9_/-]+$/.test(pathname))return 'spa';
  return /^\/assets\/[A-Za-z0-9_.-]+$/.test(pathname)?'asset':'missing';
}

export function serve(configuration) {
  const c=configuration;
  if(!path.isAbsolute(c.dist)||!c.hostHeader||!/^[A-Za-z0-9.-]+(?::[0-9]+)?$/.test(c.hostHeader))throw new Error('Verified absolute release and host required');
  const api=new URL(c.apiOrigin),identity=new URL(c.identityOrigin);
  if(api.protocol!=='https:'||identity.protocol!=='https:'||api.origin!==c.apiOrigin||identity.origin!==c.identityOrigin)throw new Error('Verified HTTPS origins required');
  const ca=readFileSync(c.ca),cert=readFileSync(c.certificate),key=readFileSync(c.privateKey);
  const server=https.createServer({ca,cert,key,minVersion:'TLSv1.3'},(req,res)=>{
    if(req.headers.host!==c.hostHeader){res.writeHead(421,{'Cache-Control':'no-store'}).end();return;}
    const destination=route(req.url);
    if(destination==='api'){
      const headers={...req.headers,host:api.host};
      for(const name of Object.keys(headers))if(name==='forwarded'||name.startsWith('x-forwarded-')||['connection','transfer-encoding','upgrade','proxy-authorization','proxy-connection'].includes(name))delete headers[name];
      const upstream=https.request({hostname:api.hostname,port:api.port||443,servername:api.hostname,method:req.method,path:req.url,headers,ca,rejectUnauthorized:true,minVersion:'TLSv1.3',timeout:10000},response=>{
        res.writeHead(response.statusCode,response.headers);response.pipe(res);
      });
      upstream.on('timeout',()=>upstream.destroy());
      upstream.on('error',()=>{if(!res.headersSent)res.writeHead(502,{'Cache-Control':'no-store'});res.end();});
      req.pipe(upstream);return;
    }
    if(!['GET','HEAD'].includes(req.method)||destination==='missing'){res.writeHead(404).end();return;}
    const name=destination==='spa'?'index.html':req.url.split('?')[0].slice(1);
    let bytes;
    try{
      const file=path.join(c.dist,name);
      if(lstatSync(file).isSymbolicLink()||!lstatSync(file).isFile())throw new Error();
      bytes=readFileSync(file);
      if(createHash('sha256').update(bytes).digest('hex')!==c.spaFiles[name])throw new Error();
    }catch{res.writeHead(503,{'Cache-Control':'no-store'}).end();return;}
    const types={'.html':'text/html; charset=utf-8','.js':'application/javascript','.css':'text/css','.svg':'image/svg+xml'};
    res.writeHead(200,{'Content-Type':types[path.extname(name)]??'application/octet-stream',
      'Cache-Control':destination==='spa'?'no-store':'public, max-age=31536000, immutable',
      'Content-Security-Policy':`default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; connect-src 'self' ${identity.origin}; frame-src ${identity.origin}`,
      'X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer'});
    res.end(req.method==='HEAD'?undefined:bytes);
  });
  server.listen(c.port??8444,c.listen??'0.0.0.0');return server;
}

if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  const configPath=process.argv[2];
  if(!configPath||!path.isAbsolute(configPath)||lstatSync(configPath).isSymbolicLink())throw new Error('Controlled configuration file required');
  serve(JSON.parse(readFileSync(configPath,'utf8')));
}
