// Sealed controller helper: connect locally while verifying the original origin.
import https from 'node:https';
import tls from 'node:tls';
import net from 'node:net';
import fs from 'node:fs';
let input=''; for await(const chunk of process.stdin){input+=chunk;if(input.length>2*1024*1024)process.exit(1);}
try {
  const v=JSON.parse(input), url=new URL(v.url);
  if(url.protocol!=='https:'||url.username||url.password||!v.allowedOrigins?.includes(url.origin))throw Error();
  if(v.connectHost!=='127.0.0.1'||!Number.isInteger(v.connectPort)||v.connectPort<1024||v.connectPort>65535)throw Error();
  if(Boolean(v.certificate)!==Boolean(v.privateKey))throw Error();
  const client=v.certificate?{cert:fs.readFileSync(v.certificate),key:fs.readFileSync(v.privateKey)}:{};
  const result=await new Promise((resolve,reject)=>{
    const req=https.request({hostname:v.connectHost,port:v.connectPort,servername:net.isIP(url.hostname)?'':url.hostname,
      checkServerIdentity:(_host,cert)=>tls.checkServerIdentity(url.hostname,cert),
      path:url.pathname+url.search,method:v.method??'GET',headers:{...v.headers,host:url.host},
      ca:fs.readFileSync(v.ca),...client,rejectUnauthorized:true,minVersion:'TLSv1.3',timeout:15000},res=>{
        const chunks=[];let n=0;res.on('data',d=>{n+=d.length;if(n>1024*1024)req.destroy(Error());else chunks.push(d);});
        res.on('end',()=>resolve({status:res.statusCode,body:Buffer.concat(chunks).toString('utf8')}));
      });req.on('error',reject);req.on('timeout',()=>req.destroy(Error()));req.end(v.body??undefined);
  });process.stdout.write(JSON.stringify(result));
}catch{process.stderr.write('CONTROLLED_NATIVE_HTTPS_FAILED_OR_UNKNOWN\n');process.exitCode=1;}
