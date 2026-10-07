import https from 'node:https';
import fs from 'node:fs';
// Private stdin/stdout protocol. Callers capture output; never log request/token/error bodies.
let input='';for await(const chunk of process.stdin)input+=chunk;
try {
  const value=JSON.parse(input);const url=new URL(value.url);
  if(url.protocol!=='https:'||url.hostname!=='localhost')throw Error('Controlled localhost HTTPS only');
  const response=await new Promise((resolve,reject)=>{
    if(Boolean(value.certificate)!==Boolean(value.privateKey))throw Error('Paired client TLS files required');
    const client=value.certificate?{cert:fs.readFileSync(value.certificate),key:fs.readFileSync(value.privateKey)}:{};
    const request=https.request(url,{method:value.method??'GET',headers:value.headers??{},
      ca:fs.readFileSync(value.ca),...client,minVersion:'TLSv1.3',rejectUnauthorized:true,timeout:15000},res=>{
      const chunks=[];let size=0;res.on('data',chunk=>{size+=chunk.length;if(size>1024*1024)request.destroy(Error('Response limit'));else chunks.push(chunk);});
      res.on('end',()=>resolve({status:res.statusCode,body:Buffer.concat(chunks).toString('utf8')}));
    });request.on('error',reject);request.on('timeout',()=>request.destroy(Error('Timeout')));
    request.end(value.body??undefined);
  });process.stdout.write(JSON.stringify(response));
}catch{process.stderr.write('CONTROLLED_HTTPS_FAILED_OR_UNKNOWN\n');process.exitCode=2;}
