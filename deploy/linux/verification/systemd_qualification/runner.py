#!/usr/bin/env python3
"""Real-systemd proxy-only qualification; no database or business PASS inference."""
import argparse
import hashlib
import http.client
import json
import os
from pathlib import Path
import signal
import socket
import ssl
import subprocess
import sys
import time
sys.path.insert(0,str(Path(__file__).resolve().parents[2]))
from ols_linux import journal,runtime,tls_proxy,tls_systemd as sd,tls_maintenance,tls_original,tls_systemd_qualification as qualification
from ols_linux.config import digest
from fixture import BASE,ROOT,NAMES,SLICE,SCOPE,DROPIN,BINARIES,put,record,prepare,registration,certificate
from guard import Guard,admission,baseline,health,stop_tests


def request(port=29848,path='/',*,method='GET',source='127.0.0.1',host='127.0.0.1',ca='old',tls=True,verify_host=None):
    context=ssl.create_default_context(cafile=str(BASE/'materials'/(ca+'-ca.pem'))) if tls else None
    raw=socket.create_connection(('127.0.0.1',port),timeout=4,source_address=(source,0))
    try:
        stream=context.wrap_socket(raw,server_hostname=verify_host or host) if tls else raw
        try:
            fingerprint=hashlib.sha256(stream.getpeercert(binary_form=True)).hexdigest() if tls else None
            stream.sendall((method+' '+path+' HTTP/1.1\r\nHost: '+host+'\r\nConnection: close\r\nContent-Length: 0\r\n\r\n').encode())
            response=http.client.HTTPResponse(stream);response.begin();body=response.read(65537)
            if len(body)>65536:raise RuntimeError('Fixture response too large')
            return {'status':response.status,'body':body.decode(),'leaf':fingerprint,'source':source,'port':port,'method':method,'path':path}
        finally:stream.close()
    finally:raw.close()


def expect_failure(action):
    try:action()
    except (RuntimeError,ssl.SSLError,OSError,ValueError) as error:return type(error).__name__+': '+str(error)
    raise AssertionError('Required negative control unexpectedly succeeded')


def require(value,message):
    if not value:raise AssertionError(message)


def wait_upstream(ca):
    deadline=time.monotonic()+8
    while True:
        try:
            for port in (29843,29844):require(request(port,ca=ca)['status']==200,'Synthetic upstream not ready')
            return
        except (OSError,AssertionError):
            if time.monotonic()>deadline:raise
            time.sleep(.1)


def native(name,ca):
    runtime.run(['systemctl','stop',NAMES['upstream']])
    for role in ('identity','app'):
        for ext in ('crt','key'):put(BASE/'materials'/(role+'.'+ext),(BASE/'materials'/(name+'.'+ext)).read_bytes())
    runtime.run(['systemctl','start',NAMES['upstream']]);wait_upstream(ca)


def finalize(report,guard):
    # No admissible report exists until every stop and in-flight guard check ends.
    stop_tests();guard.close()
    journal._write(ROOT,ROOT/'verification/systemd-proxy-qualification.json',report)


def main():
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=['prepare','run','child-switch']);parser.add_argument('--inputs-file');parser.add_argument('--operation-id');args=parser.parse_args()
    os.umask(0o077)
    group=Path('/proc/self/cgroup').read_text().strip()
    if not group.endswith('/'+SCOPE) or '/'+SLICE+'/' not in group:raise RuntimeError('All fixture work must run in the authorized capped control scope')
    if Path('/proc/1/comm').read_text().strip()!='systemd' or os.geteuid()!=0:raise RuntimeError('Actual systemd/root required')
    if args.action=='child-switch':
        oid=journal.current(ROOT)['operationId'];require(oid==args.operation_id,'Original operation ID differs')
        original=runtime.run
        def interrupted(argv,*a,**kw):
            result=original(argv,*a,**kw)
            if argv==['systemctl','start',NAMES['caddy']]:
                put(BASE/'evidence/interruption-boundary.json',json.dumps({'operationId':oid,'pid':os.getpid(),'boundary':'committed-caddy-start-before-response','signal':'SIGKILL'}))
                os.kill(os.getpid(),signal.SIGKILL)
            return result
        runtime.run=interrupted
        tls_proxy.apply(ROOT,oid,'switch')
        raise RuntimeError('Declared SIGKILL boundary was not reached')
    inputs=json.loads(Path(args.inputs_file).read_text());initial=admission(inputs)
    guard=Guard(inputs,initial);guard.start()
    original_run=runtime.run
    def guarded_run(argv,*a,**kw):
        stopping=len(argv)==3 and argv[:2]==['systemctl','stop'] and argv[2] in NAMES.values()
        if not stopping:guard.check()
        return original_run(argv,*a,**kw)
    runtime.run=guarded_run
    finalized=False
    try:
        if args.action=='prepare':
            op=prepare();journal._write(ROOT,ROOT/'verification/admission.json',initial)
            print(json.dumps({'status':'PREPARED','operationId':op['operationId']}));return
        op=journal.current(ROOT);oid=op['operationId']
        require(oid==args.operation_id,'Use the exact original prepared operation ID')
        initial=journal._read(ROOT,ROOT/'verification/admission.json')
        require(baseline(inputs)==initial['production'],'Original production drift before run')
        installed=journal._read(ROOT,ROOT/'verification/installed.json');sd.verify_files(list(installed.values()))
        if (ROOT/'verification/started.json').exists():raise RuntimeError('Previous run exists; preserve original evidence for targeted continuation, never prepare a replacement')
        journal._write(ROOT,ROOT/'verification/started.json',{'operationId':oid,'time':time.time()})
        for role in ('upstream','caddy','nginx'):runtime.run(['systemctl','start',NAMES[role]])
        wait_upstream('old');time.sleep(.3)
        reg=registration()
        for service in reg['services']:sd.await_started(service)
        tls_proxy.validate_registration(ROOT,reg)
        old=tls_original.capture({'version':0,'candidate':certificate('old-native')},reg)
        journal._write(ROOT,ROOT/'verification/original.json',old)
        gid=digest({'certificate':certificate('new-public'),'operationId':oid});generation=ROOT/'tls/generations'/gid
        paths={'certificate':str(generation/'public.crt'),'privateKey':str(generation/'public.key'),'httpTrust':str(generation/'http-trust.pem')}
        for name,source in [('public.crt','new-public.crt'),('public.key','new-public.key')]:put(generation/name,(BASE/'materials'/source).read_bytes())
        put(generation/'http-trust.pem',b''.join((BASE/'materials'/(ca+'-ca.pem')).read_bytes() for ca in ('old','new','internal')))
        tls_original.require_newer(old,certificate('new-public'))
        plan=tls_proxy.prepare(ROOT,oid,reg,paths);outer=reg['services'][0];bridge=reg['services'][1]
        cases={};evidence={}
        def passed(case,data):
            guard.check();relative='verification/'+case+'.json';journal._write(ROOT,ROOT/relative,{'operationId':oid,'time':time.time(),'evidence':data})
            evidence[relative]=record(ROOT/relative)['sha256'];cases[case]={'status':'PASS','evidence':relative}
            print(json.dumps({'case':case,'status':'PASS','operationId':oid}),flush=True)
        def phase(name):guard.check();journal.record(ROOT,oid,{'phase':name})
        def close():phase('STOPPING');require(tls_proxy.apply(ROOT,oid,'close')['closed'],'Outer closure unproven')
        def open_gate(rollback=False):
            phase('ROLLBACK_OPENING' if rollback else 'OPENING');require(not tls_proxy.apply(ROOT,oid,'open')['closed'],'Outer did not open')
        def strict(ca,external,native_leaf):
            rows=[request(ca=ca,path='/realms/qualification/protocol/openid-connect/certs'),request(ca=ca,path='/fixture-app')]
            require(all(row['status']==200 and row['leaf']==external for row in rows),'Public strict TLS or forwarding mismatch')
            for port in (29843,29844):require(request(port,ca=ca)['leaf']==native_leaf,'Native fixture leaf differs')
            for port,path in ((29846,'/realms/qualification'),(29847,'/fixture-app')):require(request(port,path,ca='internal',verify_host='localhost')['status']==200,'Strict internal bridge mismatch')
            return rows
        # Q01: actual graph drift, no adoption and no service action.
        before=sd.observe(outer);extra=BASE/'nginx/conf.d/unregistered.conf';put(extra,'# unknown include\n',0o644)
        try:rejected=expect_failure(lambda:tls_proxy.validate_registration(ROOT,reg))
        finally:extra.unlink()
        require(sd.observe(outer)['process']==before['process'],'Registration refusal changed process')
        passed('Q01',{'rejected':rejected,'services':[sd.observe(s) for s in reg['services']]})
        # Q02: original full routing/headers and HTTP challenge behavior.
        rows=strict('old',certificate('old-public')['leafDerSha256'],certificate('old-native')['leafDerSha256'])
        require(json.loads(rows[0]['body'])['host']=='127.0.0.1','Public Host was not preserved')
        challenge=request(29845,'/.well-known/acme-challenge/token',tls=False);require(challenge['status']==200 and challenge['body']=='synthetic-challenge\n','Challenge differs')
        require(request(29845,'/else',tls=False)['status']==308,'HTTP redirect missing')
        require(request(29845,'/',tls=False,host='wrong.invalid')['status']==421,'Unknown Host accepted')
        require(request(path='/admin/master')['status']==404,'Administrative route exposed')
        passed('Q02',rows+[challenge])
        # Q04: each internal TLS leg rejects wrong CA, SAN and real expired peers.
        negative=[]
        for leg in ('native','bridge'):
            for fault in ('wrong-ca','wrong-san','expired'):
                names=['identity','app'] if leg=='native' else ['bridge'];unit=NAMES['upstream' if leg=='native' else 'caddy']
                saved={name+'.'+ext:(BASE/'materials'/(name+'.'+ext)).read_bytes() for name in names for ext in ('crt','key')}
                try:
                    runtime.run(['systemctl','stop',unit])
                    for name in names:
                        for ext in ('crt','key'):put(BASE/'materials'/(name+'.'+ext),(BASE/'materials'/(leg+'-'+fault+'.'+ext)).read_bytes())
                    runtime.run(['systemctl','start',unit]);time.sleep(.25)
                    receipts=[request(path=path) for path in ('/realms/qualification','/fixture-app')]
                    require(all(r['status']==502 for r in receipts),'Invalid upstream TLS was accepted')
                    negative.append({'leg':leg,'fault':fault,'receipts':receipts})
                finally:
                    runtime.run(['systemctl','stop',unit])
                    for name,data in saved.items():put(BASE/'materials'/name,data)
                    runtime.run(['systemctl','start',unit]);time.sleep(.25)
        negative.append({'publicWrongCA':expect_failure(lambda:request(ca='wrong')),'publicWrongSAN':expect_failure(lambda:request(verify_host='wrong.invalid'))})
        strict('old',certificate('old-public')['leafDerSha256'],certificate('old-native')['leafDerSha256'])
        passed('Q04',negative)
        # Q03: real maintenance installation, exact source/method/path policy.
        close();phase('ACTIVATING')
        target=[{'role':'bridgeIdentity','tlsIdentity':'internal','verifyHost':'localhost','connectHost':'127.0.0.1','connectPort':29846}]
        oldpaths={'certificate':str(BASE/'materials/old-public.crt'),'privateKey':str(BASE/'materials/old-public.key')}
        http_before=record(BASE/'nginx/conf.d/http.conf')
        tls_maintenance.start(ROOT,oid,plan['services'],target,oldpaths)
        receipts=[]
        for source in ('127.0.0.1','127.0.0.2'):
            for method,path in [('GET','certs'),('POST','token/introspect')]:
                row=request(path='/realms/qualification/protocol/openid-connect/'+path,method=method,source=source);require(row['status']==200,'Maintenance allowed endpoint failed');receipts.append(row)
        require(request(path='/realms/qualification/protocol/openid-connect/certs',source='127.0.0.3')['status']==403,'Maintenance source ACL broadened')
        for path,status in [('/fixture-app',503),('/admin/master',503),('/realms/qualification/%2e/certs',404),('//realms/qualification',404)]:require(request(path=path)['status']==status,'Maintenance path guard failed')
        require(request(path='/realms/qualification/protocol/openid-connect/certs',method='POST')['status']==405,'Maintenance method guard failed')
        require(record(BASE/'nginx/conf.d/http.conf')==http_before,'HTTP fragment changed')
        require(request(29845,'/.well-known/acme-challenge/token',tls=False)==challenge,'Challenge changed during maintenance')
        passed('Q03',receipts)
        # Q06: actual nginx parser failure and a foreign listener prevent start/closure.
        close();invalid=ROOT/'proxy/invalid.conf';put(invalid,'invalid_nginx_directive;\n')
        failures={'invalidConfiguration':expect_failure(lambda:tls_proxy._check(outer,invalid))}
        with socket.socket() as foreign:
            foreign.bind(('127.0.0.1',29848));foreign.listen(1)
            failures['unconfirmedClosure']=expect_failure(lambda:tls_proxy.observe(ROOT,oid))
        require(tls_proxy.observe(ROOT,oid)['closed'],'Fixture did not remain closed')
        passed('Q06',failures)
        # Q07/Q08: child truly dies after committed systemctl start, retaining intent.
        phase('PROXY_SWITCHING');before_bridge=sd.observe(bridge)
        child=subprocess.Popen([sys.executable,'-B',str(Path(__file__)),'child-switch','--operation-id',oid],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        guard.child=child
        try:
            guard.check();child.communicate(timeout=30)
        finally:
            if child.poll() is None:child.kill();child.communicate(timeout=5)
            guard.child=None
        guard.check()
        require(child.returncode==-signal.SIGKILL,'Controller was not killed at declared real boundary')
        boundary=json.loads((BASE/'evidence/interruption-boundary.json').read_text());require(boundary['operationId']==oid,'Interrupted operation differs')
        after_child=sd.await_started(bridge,root=ROOT,operation_id=oid)
        require(tls_proxy.apply(ROOT,oid,'switch')['closed'],'Resume must keep outer closed')
        resumed=sd.observe(bridge,root=ROOT,operation_id=oid);require(resumed['process']==after_child['process'],'Resume repeated completed Caddy start')
        passed('Q07',{'boundary':boundary,'resumedProcess':resumed['process'],'sameProcessAfterResume':True})
        passed('Q08',{'returncode':child.returncode,'boundary':boundary,'operationIdAfterResume':journal.current(ROOT)['operationId']})
        # Q05: actual loaded trust/PID and converged new leaves.
        sd.verify_restart(before_bridge,resumed);require(resumed['credentials']['issuer-ca.pem']==record(generation/'http-trust.pem')['sha256'],'New credential bytes were not loaded')
        native('new-public','new');open_gate()
        forward=strict('new',certificate('new-public')['leafDerSha256'],certificate('new-public')['leafDerSha256'])
        passed('Q05',{'before':before_bridge,'after':resumed,'strict':forward})
        # Q09/Q12: restore distinct originals while every required original is valid.
        close();tls_original.validate(old,now=int(time.time()));phase('ROLLBACK_SWITCHING');tls_proxy.apply(ROOT,oid,'rollback');native('old-native','old')
        tls_original.validate(old,now=int(time.time()));open_gate(True)
        restored=strict('old',certificate('old-public')['leafDerSha256'],certificate('old-native')['leafDerSha256'])
        require(sd.observe(bridge)['credentials']==plan['services'][1]['beforeCredentials'],'Original credential set not restored')
        passed('Q09',restored)
        require(old['originalPublicTargets']['nativeEntry']!=old['originalPublicTargets']['publicEntry'],'Fixture original identities were not distinct')
        passed('Q12',{'original':old,'restored':restored,'requireNewer':expect_failure(lambda:tls_original.require_newer(old,old['candidate']))})
        # Q10: natural expiry, no clock change; old externally longer-lived leaf cannot mask native expiry.
        close();deadline=old['originalPublicTargets']['nativeEntry']['notAfter']
        while time.time()<=deadline:guard.check();time.sleep(min(5,max(.1,deadline-time.time()+.1)))
        refusal=expect_failure(lambda:tls_original.validate(old,now=int(time.time())))
        expired_peers=[expect_failure(lambda port=port:request(port,ca='old')) for port in (29843,29844)]
        require(tls_proxy.observe(ROOT,oid)['closed'],'Expired rollback reopened outer')
        passed('Q10',{'refusal':refusal,'oldNotAfter':deadline,'observedAt':time.time(),'outerClosed':True,'strictExpiredPeerFailures':expired_peers})
        require(baseline(inputs)==initial['production'],'Production changed');final_health=health(inputs)
        passed('Q11',{'baseline':initial['production'],'final':baseline(inputs),'health':final_health})
        require(set(cases)==qualification.CASES,'Incomplete matrix')
        guard.check();report={'status':'PASS','scope':'systemd proxy adapter only; no native/business acceptance inference','operationId':oid,'implementationDigest':qualification.implementation_digest(),'manager':{'comm':Path('/proc/1/comm').read_text().strip(),'pid':1},'cases':cases,'evidence':evidence,'binaries':{role:'sha256:'+value[1] for role,value in BINARIES.items()}}
        finalize(report,guard);finalized=True
        print(json.dumps({'status':'PASS','operationId':oid,'report':str(ROOT/'verification/systemd-proxy-qualification.json')}),flush=True)
    finally:
        try:
            if not finalized:
                try:
                    if args.action=='run':stop_tests()
                finally:guard.close()
        finally:runtime.run=original_run


if __name__=='__main__':main()
