"""Ephemeral real X.509 fixtures. Never production trust."""
import hashlib
import json
from pathlib import Path
import ssl
import subprocess
import time
from ols_linux import journal,runtime


def command(*args):
    p=subprocess.run(['openssl',*map(str,args)],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    if p.returncode:raise AssertionError('Fixture openssl failed: '+p.stderr.decode())
    return p.stdout


def materials(directory: Path, *, expired_old=False):
    directory.mkdir(mode=0o700,parents=True,exist_ok=True)
    result={'directory':directory,'now':int(time.time())}
    for name in ['internal','old','new','untrusted']:
        key=directory/(name+'.key');cert=directory/(name+'.pem')
        command('req','-x509','-newkey','ec','-pkeyopt','ec_paramgen_curve:P-256','-nodes','-keyout',key,'-out',cert,'-days','365','-subj','/CN='+name,'-addext','basicConstraints=critical,CA:TRUE','-addext','keyUsage=critical,keyCertSign,cRLSign')
        result[name]=cert
    for name,ca,days in [('old-leaf','old',-1 if expired_old else 2),('new-leaf','new',30),('rsa-leaf','new',30)]:
        key=directory/(name+'.key');csr=directory/(name+'.csr');cert=directory/(name+'.pem');ext=directory/(name+'.ext')
        ext.write_text('basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=IP:127.0.0.1,DNS:localhost\n')
        algo=['rsa:2048'] if name=='rsa-leaf' else ['ec','-pkeyopt','ec_paramgen_curve:P-256']
        command('req','-new','-newkey',*algo,'-nodes','-keyout',key,'-out',csr,'-subj','/CN=unused')
        command('x509','-req','-in',csr,'-CA',result[ca],'-CAkey',directory/(ca+'.key'),'-set_serial',str(10+days+(1 if name=='rsa-leaf' else 0)),'-days',str(days),'-extfile',ext,'-out',cert)
        result[name]=cert
    for p in directory.iterdir():p.chmod(0o600)
    result['fingerprint']=lambda p:hashlib.sha256(ssl.PEM_cert_to_DER_cert(p.read_text())).hexdigest()
    return result


def instance(directory,fixture):
    root=directory/'runtime';op=journal.begin(root,'initialize','a'*64)
    runtime.save(root,{'verification':True,'publicOrigin':'https://127.0.0.1','identityOrigin':'https://localhost','publicTlsHashes':{},'settingsDigest':'b'*64})
    (root/'certs').mkdir(mode=0o700)
    for source,dest in [('internal','ca.pem'),('old','public-ca.pem'),('old-leaf','public.crt')]:
        runtime.private_file(root/'certs'/dest,fixture[source].read_bytes())
    runtime.private_file(root/'certs/public.key',(fixture['directory']/'old-leaf.key').read_bytes())
    journal._write(root,root/'tls-test-anchors.json',{'fingerprints':[fixture['fingerprint'](fixture['new'])]})
    return root


def inputs(fixture,leaf='new-leaf'):
    return {'certificate':str(fixture[leaf]),'privateKey':str(fixture['directory']/(leaf+'.key')),
            'intermediates':[], 'approvedAnchors':[str(fixture['new'])],
            'origins':['https://127.0.0.1','https://localhost'],'provenance':{'kind':'manual'}}
