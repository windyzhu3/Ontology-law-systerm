"""Final owned API/Worker and HTTPS entry assembly; no HUMAN identity writes."""
from pathlib import Path
from urllib.parse import parse_qs,urlsplit
import uuid
import hashlib
import json
import re
import ssl
import os
import time
from . import bundle,identity,journal,runtime
from .config import canonical,digest


def api_properties(base,descriptor):
    props=dict(base)
    props.update({'ols.api.database.schema-version':descriptor['schemaVersion'],
        'ols.api.database.release-digest':descriptor['files'][descriptor['jar']],
        'ols.api.database.manifest-hash':descriptor['manifestHash']})
    return props


def worker_properties(root,descriptor,plan,service,api,worker_password,trust_password):
    for key in ('tenantId','principalId','appointmentId'):uuid.UUID(service[key])
    if service['tenantId']!=plan['tenantId']:raise RuntimeError('Original SERVICE tenant differs')
    url=api['ols.api.database.url']
    if not url.startswith('jdbc:postgresql://') or parse_qs(urlsplit(url[5:]).query).get('sslmode')!=['verify-full']:
        raise RuntimeError('Worker requires the original fully verified TLS database')
    file=lambda name:str(Path(root)/name)
    props={'ols.runtime-role':'worker','spring.main.web-application-type':'none','spring.main.banner-mode':'off','logging.level.root':'WARN',
        'logging.level.io.github.windyzhu3.ontologylaw.worker.WorkerRuntimeHealth':'INFO',
        'ols.worker.semantic-baseline':api.get('ols.api.semantic-baseline','MVP-2026-09-08.3'),'ols.worker.node':'LINUX_WORKER',
        'ols.worker.api-origin':plan['apiOrigin'],'ols.worker.database.url':url,'ols.worker.database.username':'law_worker_login',
        'ols.worker.database.password':worker_password,'ols.worker.database.schema-version':descriptor['schemaVersion'],
        'ols.worker.database.release-digest':descriptor['files'][descriptor['jar']],'ols.worker.database.manifest-hash':descriptor['manifestHash'],
        'ols.worker.owner-exception-observation-enabled':True,'ols.worker.opportunity-task-scheduling-enabled':True}
    binding={'tenant-id':service['tenantId'],'principal-id':service['principalId'],'appointment-id':service['appointmentId'],
        'credential-alias':'ols_service','key-store-path':file('certs/service.p12'),'key-store-password':trust_password,
        'key-password':trust_password,'trust-store-path':file('certs/identity-trust.p12'),'trust-store-password':trust_password}
    props.update({'ols.worker.bindings[0].'+key:value for key,value in binding.items()})
    return props


def property_bytes(props):
    rows=[]
    for key,value in props.items():
        if isinstance(value,bool):value='true' if value else 'false'
        if any(c in key+str(value) for c in '\r\n'):raise RuntimeError('Multiline deployment property refused')
        rows.append(key+'='+str(value))
    return ('\n'.join(rows)+'\n').encode('ascii','backslashreplace')


def read_properties(path):
    result={}
    for line in Path(path).read_text(encoding='ascii').splitlines():
        if not line or line.startswith('#'):continue
        key,value=line.split('=',1)
        if key in result:raise RuntimeError('Duplicate original deployment property')
        result[key]=value
    return result


def create_args(args):
    if args[:3]!=['docker','run','-d']:raise RuntimeError('Controlled detached launch required')
    return ['docker','create',*args[3:]]


def scanner_environment():
    # Keep fresh signatures and real scanning. A concurrent reload retains two
    # complete signature databases and can kill clamd within its sealed budget.
    return ['CLAMD_CONF_ConcurrentDatabaseReload=no']


def final_names(prefix,descriptor_digest):
    if not re.fullmatch('[a-f0-9]{64}',descriptor_digest):raise RuntimeError('Exact descriptor required')
    suffix=descriptor_digest[:12]
    return {role:prefix+'-'+role+'-'+suffix for role in ('api','worker','entry','scanner')}


def bind_release(root,descriptor):
    with journal.locked(root) as root:
        if descriptor.get('version')!=2:raise RuntimeError('Full activation requires a sealed runtime payload bundle')
        installed=journal._read(root,root/'installed-candidate.json')
        if installed['descriptor']!=descriptor:raise RuntimeError('Installed release differs from binding target')
        directory=Path(installed['directory']);bundle.verify(descriptor,directory)
        plan=journal._read(root,root/'identity/plan.json');service=journal._read(root,root/'assembly/service.json')
        resources=runtime.load(root);op=journal.current(root)
        if plan['instanceId']!=resources['instanceId'] or plan['tenantId']!=service['tenantId']:raise RuntimeError('Original runtime identity differs')
        if any(runtime.owned(root,'container',name)['State']['Running'] for name in resources['writers'] if runtime.inspect('container',name)):
            raise RuntimeError('Full release binding requires observed stopped writers')
        original=root/'config/business-api.properties'
        if original.exists():
            from . import business_config
            config=journal._read(root,root/'initialization.json')['config']
            business_config.verify_configuration(root,config)
        else:
            original=root/'config/initial-admin.properties'
        api=api_properties(read_properties(original),descriptor)
        if (root/'tls/active.json').exists():
            from .public_runtime import effective_paths
            selected=effective_paths(root)
            for key in ('server.ssl.trust-store','ols.api.identity-trust-store-path'):api[key]=selected['javaTrustStore']
        trust=identity.secret_file(root/'secrets/trust-password.txt')
        worker=worker_properties(root,descriptor,plan,service,api,identity.secret_file(root/'secrets/worker-db.txt'),trust)
        if (root/'tls/active.json').exists():worker['ols.worker.bindings[0].trust-store-path']=selected['javaTrustStore']
        worker['ols.worker.bindings[0].certificate-sha256']=hashlib.sha256(ssl.PEM_cert_to_DER_cert((root/'certs/service.crt').read_text())).hexdigest()
        prefix=resources['name'];suffix=descriptor['descriptorDigest'][:12]
        names=final_names(prefix,descriptor['descriptorDigest'])
        config_dir=root/'deployments'/descriptor['descriptorDigest']
        scanner=json.loads((directory/'deploy/linux/runtime/scanner.lock.json').read_text())
        if scanner['image']!='clamav/clamav@sha256:9cb27d7660bdf66e9878c832cb433dd8aa152cfbe16f3c2c0084c80b04ae22b4' or scanner['servicePort']!=3310 or scanner['hostPort'] is not None:
            raise RuntimeError('Reviewed internal scanner configuration required')
        entry={'dist':str(directory/descriptor['spa']),'hostHeader':urlsplit(plan['origin']).netloc,'apiOrigin':plan['apiOrigin'],
            'identityOrigin':plan['issuer'].split('/realms/')[0],'ca':str(root/'certs/ca.pem'),'certificate':str(root/'certs/server.crt'),
            'privateKey':str(root/'certs/server.key'),'spaFiles':descriptor['spaFiles'],'port':plan['ports']['entry']}
        if resources.get('publicTlsHashes'):
            entry.update(ca=str(root/'certs/http-trust.pem'),certificate=str(root/'certs/public.crt'),privateKey=str(root/'certs/public.key'))
        if (root/'tls/active.json').exists():entry.update(ca=selected['httpTrust'],certificate=selected['certificate'],privateKey=selected['privateKey'])
        files={config_dir/'api.properties':property_bytes(api),config_dir/'worker.properties':property_bytes(worker),config_dir/'entry.json':canonical(entry)}
        file_hashes={str(path.relative_to(root)):hashlib.sha256(data).hexdigest() for path,data in files.items()}
        record={'descriptorDigest':descriptor['descriptorDigest'],'files':file_hashes,'names':names,'originalIdentityPlanDigest':digest(plan),'originalServiceDigest':digest(service)}
        saved=config_dir/'binding.json'
        if saved.exists() and journal._read(root,saved)!=record:raise RuntimeError('Original sealed deployment binding changed')
        if not saved.exists():journal._write(root,saved,record)
        for path,data in files.items():
            if path.exists() and path.read_bytes()!=data:raise RuntimeError('Original deployment configuration changed')
            if not path.exists():runtime.private_file(path,data)
        materials=root/'materials'
        if materials.resolve()!=materials.absolute():raise RuntimeError('Linked material store refused')
        materials.mkdir(mode=0o700,exist_ok=True)
        volume=prefix+'-scanner-data'
        for role,name in names.items():
            if runtime.inspect('container',name) and name not in resources['containers'].values():raise RuntimeError('Unregistered final container is not adopted')
            old=resources['containers'].get(role)
            if old and old!=name:resources['containers']['retained'+role+old[-12:]]=old
            resources['containers'][role]=name
        if runtime.inspect('volume',volume) and volume not in resources['volumes']:raise RuntimeError('Unregistered scanner volume is not adopted')
        if volume not in resources['volumes']:resources['volumes'].append(volume)
        for role in ('scanner','api','worker'):
            if names[role] not in resources['writers']:resources['writers'].append(names[role])
        resources['ingress']=names['entry'];resources['repo']=str(directory);runtime.save(root,resources)
        if not runtime.inspect('volume',volume):runtime.run(['docker','volume','create','--label',runtime.LABEL+'='+resources['instanceId'],'--label','ols.operation='+op['operationId'],volume])
        runtime.owned(root,'volume',volume)
        entries=[{'name':plan['identity'],'role':'identity','digest':digest(plan),'args':[]}]
        def command(role,image,args,network,mounts,env=()):
            sealed=digest({'binding':record,'role':role,'image':image,'mounts':mounts,'env':list(env)})
            base=['docker','run','-d','--name',names[role],'--label',runtime.LABEL+'='+resources['instanceId'],'--label','ols.operation='+op['operationId'],'--label','ols.launch='+sealed,
                '--network',network,'--log-driver','local','--log-opt','max-size=10m','--log-opt','max-file=2']
            base += [arg for mount in mounts for arg in ('--mount',mount)]
            base += [arg for value in env for arg in ('-e',value)]
            if role=='scanner':base+=['--memory','1536m','--cpus','2']
            else:base+=['--memory','1536m' if role=='api' else '768m','--cpus','2']
            if role=='entry':base+=['--entrypoint','node']
            base += [image,*args]
            return {'name':names[role],'role':role,'digest':sealed,'args':base}
        entries.append(command('scanner',scanner['image'],[],resources['network'],['type=volume,source='+volume+',target=/var/lib/clamav'],scanner_environment()))
        mount='type=bind,source='+str(root)+',target='+str(root)+',readonly'
        jar=directory/descriptor['jar'];network='container:'+plan['pod']
        for role in ('api','worker'):
            mounts=[mount]
            env=[]
            if role=='api':
                mounts.append('type=bind,source='+str(materials)+',target='+str(materials))
                env=['OLS_MATERIAL_STORE_PATH='+str(materials),'OLS_CLAMD_HOST='+names['scanner'],'OLS_CLAMD_PORT=3310']
            entries.append(command(role,plan['runtimeImage'],['-Xmx'+('768m' if role=='api' else '384m'),'-jar',str(jar),'--spring.config.location=file:'+str(config_dir/(role+'.properties'))],network,mounts,env))
        ingress=command('entry',plan['runtimeImage'],[str(directory/'deploy/linux/runtime/server.mjs'),str(config_dir/'entry.json')],network,[mount])
        launch={'descriptorDigest':descriptor['descriptorDigest'],'binding':str(saved),'bindingDigest':digest(record),'containers':entries,'ingress':ingress}
        journal._write(root,root/'launch.json',launch)
        if runtime.inspect('container',names['entry']):
            actual=runtime.owned(root,'container',names['entry'])
            if actual['Config']['Labels'].get('ols.launch')!=ingress['digest'] or actual['State']['Running']:raise RuntimeError('Ingress binding differs or was opened prematurely')
        else:runtime.run(create_args(ingress['args']))
        return {'status':'BOUND','descriptorDigest':descriptor['descriptorDigest'],'businessIngress':'CLOSED'}


def worker_ready(log):
    states=re.findall(r'R1_WORKER_(READY|UNAVAILABLE)\b',log)
    return 'R1_WORKER_ASSEMBLY_ISOLATED' in log and bool(states) and states[-1]=='READY'


def verify_ready(root,descriptor):
    if not (root/'launch.json').exists():raise RuntimeError('Full sealed runtime launch unavailable')
    launch=journal._read(root,root/'launch.json');resources=runtime.load(root)
    if launch['descriptorDigest']!=descriptor['descriptorDigest'] or 'binding' not in launch or 'ingress' not in launch:
        raise RuntimeError('Management-only launch cannot qualify business ingress')
    binding_path=Path(launch['binding'])
    if binding_path.resolve()!=binding_path.absolute() or not binding_path.is_relative_to(root/'deployments'):
        raise RuntimeError('Sealed launch binding path differs')
    binding=journal._read(root,binding_path)
    if digest(binding)!=launch['bindingDigest'] or binding['descriptorDigest']!=descriptor['descriptorDigest']:
        raise RuntimeError('Sealed launch binding differs')
    for name,expected in binding['files'].items():
        path=root/name
        if path.resolve()!=path.absolute() or not path.is_relative_to(root) or bundle.sha(path)!=expected:raise RuntimeError('Live runtime configuration changed')
    entries={e['role']:e for e in launch['containers']}
    if len(entries)!=len(launch['containers']) or set(entries)!={'api','worker','identity','scanner'}:
        raise RuntimeError('All four required runtime roles must be registered exactly once')
    plan=journal._read(root,root/'identity/plan.json')
    if digest(plan)!=binding['originalIdentityPlanDigest']:raise RuntimeError('Original identity launch changed')
    materials=root/'materials'
    if materials.resolve()!=materials.absolute() or not materials.is_dir() or os.name!='nt' and materials.stat().st_mode&0o077:
        raise RuntimeError('Private material store unavailable')
    from .assembly import service_ready
    deadline=time.monotonic()+150
    while True:
        actual={}
        for role,entry in entries.items():
            if entry['name']!=resources['containers'][role]:raise RuntimeError('Current role container differs from sealed launch')
            value=runtime.owned(root,'container',entry['name'])
            label='ols.identity-plan' if role=='identity' else 'ols.launch'
            if value['Config']['Labels'].get(label)!=entry['digest']:raise RuntimeError('Current role launch label differs')
            if not value['State']['Running']:raise RuntimeError('Required runtime role stopped; ingress stays closed')
            actual[role]=value
        log=runtime.run(['docker','logs','--since',actual['worker']['State']['StartedAt'],entries['worker']['name']])
        worker=worker_ready((log.stdout+log.stderr).decode('utf-8','replace'))
        ping=r"const net=require('net');let b='';const s=net.connect(3310,process.argv[1],()=>s.write('zPING\0'));s.setTimeout(3000,()=>s.destroy());s.on('data',d=>{b+=d; if(b.includes('PONG')){s.end();process.exit(0);}});s.on('error',()=>process.exit(1));s.on('close',()=>process.exit(b.includes('PONG')?0:1));"
        scanner=runtime.run(['docker','exec',plan['pod'],'node','-e',ping,entries['scanner']['name']],check=False,timeout=6).returncode==0
        try:
            discovery=identity.http(root,plan['issuer']+'/.well-known/openid-configuration')
            idp=discovery['status']==200 and json.loads(discovery['body'])['issuer']==plan['issuer']
            api=idp and service_ready(root,plan)
        except (RuntimeError,ValueError):api=False
        if worker and scanner and api:
            return {'status':'PASS','worker':'CURRENT_BOOT_READY','scanner':'REAL_PONG','identity':'VERIFIED_TLS_DISCOVERY','api':'AUTHENTICATED_MTLS_READY','materialStore':'PRIVATE','descriptorDigest':descriptor['descriptorDigest']}
        if time.monotonic()>deadline:raise RuntimeError('Full runtime readiness unknown; ingress stays closed')
        time.sleep(.5)
