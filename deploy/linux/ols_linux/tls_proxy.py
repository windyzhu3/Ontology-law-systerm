"""Fixed nginx/Caddy actions for explicitly registered, runtime-mounted services."""
import hashlib
from pathlib import Path
import re
from . import journal,runtime,tls_material
from .config import digest
from .bundle import sha


def _service(service):
    if service['transport']=='docker':
        actual=runtime.inspect('container',service['name'])
        if not actual or actual['Id']!=service['identity'] or actual['Image']!=service['image']:raise RuntimeError('Registered proxy identity differs')
        return {'running':actual['State']['Running'],'actual':actual}
    value=runtime.run(['systemctl','show',service['name'],'--property=FragmentPath,ExecStart,User,Group']).stdout
    if hashlib.sha256(value).hexdigest()!=service['identity']:raise RuntimeError('Registered proxy unit differs')
    binary=Path('/usr/sbin/nginx' if service['role']=='nginx' else '/usr/bin/caddy')
    if sha(binary)!=service['image'].removeprefix('sha256:'):raise RuntimeError('Registered proxy executable differs')
    state=runtime.run(['systemctl','show',service['name'],'--property=ActiveState,SubState,MainPID'],check=False)
    fields=dict(line.split('=',1) for line in state.stdout.decode().splitlines() if '=' in line)
    if state.returncode==0 and fields.get('ActiveState')=='active' and fields.get('SubState')=='running' and fields.get('MainPID','0').isdigit() and int(fields['MainPID'])>0:return {'running':True}
    if state.returncode==0 and fields=={'ActiveState':'inactive','SubState':'dead','MainPID':'0'}:return {'running':False}
    raise RuntimeError('Registered proxy process state unknown')


def validate_registration(root: Path,registration: dict) -> dict:
    if set(registration)!={'version','services'} or registration['version']!=1 or not isinstance(registration['services'],list):raise RuntimeError('Explicit proxy registration required')
    roles=[]
    for service in registration['services']:
        if set(service)!={'role','transport','name','identity','image','config','configSha256','tlsPaths'}:raise RuntimeError('Unexpected proxy registration field')
        if service['role'] not in {'nginx','caddy'} or service['transport'] not in {'docker','systemd'} or not re.fullmatch('[a-zA-Z0-9][a-zA-Z0-9_.@-]{0,127}',service['name']):raise RuntimeError('Unsupported proxy service')
        roles.append(service['role']);path=Path(service['config'])
        if not path.is_relative_to(root/'proxy') or path.resolve()!=path.absolute() or sha(path)!=service['configSha256']:raise RuntimeError('Proxy configuration must be sealed under the runtime proxy directory')
        tls_material.read_private(path)
        if not set(service['tlsPaths'])<={'certificate','privateKey','httpTrust'}:raise RuntimeError('Unknown proxy TLS path')
        actual=_service(service)
        if service['transport']=='docker' and not any(m.get('Type')=='bind' and m.get('Source')==str(root) and m.get('Destination')==str(root) for m in actual['actual'].get('Mounts',[])):
            raise RuntimeError('Proxy must already mount the registered runtime at its original path')
    if len(set(roles))!=len(roles) or 'nginx' not in roles:raise RuntimeError('One registered outer nginx required')
    return registration


def prepare(root: Path,operation_id: str,registration: dict,generation_paths: dict) -> dict:
    with journal.locked(root) as root:
        saved=root/'operations'/(operation_id+'-proxy.json')
        if saved.exists():
            value=journal._read(root,saved)
            if value['registration']!=registration or value['paths']!=generation_paths:raise RuntimeError('Original proxy plan differs')
            return value
        validate_registration(root,registration)
        services=[]
        for service in registration['services']:
            before=tls_material.read_private(service['config']);after=before
            for name,old in service['tlsPaths'].items():
                new=generation_paths[name]
                if not all(re.fullmatch('/[a-zA-Z0-9_./-]+',p) for p in [old,new]):raise RuntimeError('Unsafe proxy TLS path token')
                if old.encode() not in before:raise RuntimeError('Declared TLS reference missing')
                after=after.replace(old.encode(),new.encode())
            candidate=root/'proxy'/(operation_id+'-'+service['role']+'.conf')
            if candidate.exists() and candidate.read_bytes()!=after:raise RuntimeError('Proxy candidate differs')
            if not candidate.exists():runtime.private_file(candidate,after)
            services.append({'service':service,'before':before.decode('utf-8'),'after':after.decode('utf-8'),'candidate':str(candidate)})
        value={'operationId':operation_id,'registration':registration,'paths':generation_paths,'services':services}
        journal._write(root,saved,value);return value


def _check(service,candidate):
    arguments=['nginx','-t','-c',str(candidate)] if service['role']=='nginx' else ['caddy','validate','--config',str(candidate),'--adapter','caddyfile']
    if service['transport']=='docker':
        runtime.run(['docker','run','--rm','--network','none','--volumes-from',service['name'],
                     '--entrypoint',arguments[0],service['image'],*arguments[1:]])
    else:runtime.run([('/usr/sbin/nginx' if service['role']=='nginx' else '/usr/bin/caddy'),*arguments[1:]])


def _action(service,action):
    if service['transport']=='docker':
        if action in {'start','stop'}:runtime.run(['docker',action,service['name']])
        else:runtime.run(['docker','exec',service['name'],'caddy','reload','--config',service['config'],'--adapter','caddyfile'])
    else:runtime.run(['systemctl',action,service['name']])


def observe(root: Path,operation_id: str) -> dict:
    value=journal._read(root,root/'operations'/(operation_id+'-proxy.json'))
    states={}
    for row in value['services']:
        service=row['service'];state=_service(service)
        data=tls_material.read_private(service['config'])
        from . import tls_maintenance
        maintenance=tls_maintenance.configured(root,operation_id,service) if service['role']=='nginx' else False
        if data not in [row['before'].encode(),row['after'].encode()] and not maintenance:raise RuntimeError('Live proxy configuration conflicts')
        states[service['role']]={'running':state['running'],'configuration':'maintenance' if maintenance else 'candidate' if data==row['after'].encode() else 'previous'}
    return {'closed':not states['nginx']['running'],'services':states}


def apply(root: Path,operation_id: str,action: str) -> dict:
    if action not in {'close','switch','open','rollback'}:raise RuntimeError('Unsupported proxy action')
    with journal.locked(root) as root:
        op=journal.current(root)
        if op['operationId']!=operation_id or op['kind']!='rotate-public-tls' or op['phase']=='COMPLETE':raise RuntimeError('Original proxy operation required')
        phases={'close':{'STOPPING','FAILING','ROLLBACK_STOPPING','ROLLBACK_FAILING'},'switch':{'PROXY_SWITCHING','ACTIVATING'},'rollback':{'ROLLBACK_SWITCHING','ROLLBACK_ACTIVATING'},'open':{'OPENING','ROLLBACK_OPENING'}}
        if op['phase'] not in phases[action]:raise RuntimeError('Original proxy phase does not permit action')
        value=journal._read(root,root/'operations'/(operation_id+'-proxy.json'))
        observed=observe(root,operation_id)
        if action in {'switch','rollback'} and observed['services']['nginx']['configuration']=='maintenance':
            outer=next(row['service'] for row in value['services'] if row['service']['role']=='nginx')
            if _service(outer)['running']:_action(outer,'stop')
            observed=observe(root,operation_id)
        if action in {'switch','rollback'} and not observed['closed']:raise RuntimeError('Outer proxy must be observed closed')
        if action in {'switch','rollback'}:
            for row in value['services']:
                service=row['service'];candidate=Path(row['candidate'])
                desired=row['after' if action=='switch' else 'before'].encode()
                if action=='rollback':
                    candidate=candidate.with_suffix('.rollback.conf');runtime.private_file(candidate,desired)
                _check(service,candidate)
            for row in value['services']:
                service=row['service'];runtime.private_file(Path(service['config']),row['after' if action=='switch' else 'before'].encode())
                if service['role']=='caddy':_action(service,'reload' if _service(service)['running'] else 'start')
        else:
            outer=next(row['service'] for row in value['services'] if row['service']['role']=='nginx')
            if action=='open' and observed['services']['nginx']['configuration']=='maintenance':
                if _service(outer)['running']:_action(outer,'stop')
                if _service(outer)['running']:raise RuntimeError('Maintenance stop unconfirmed')
                row=next(r for r in value['services'] if r['service']['role']=='nginx')
                desired=row['before' if op['phase']=='ROLLBACK_OPENING' else 'after']
                runtime.private_file(Path(outer['config']),desired.encode())
            if _service(outer)['running']!=(action=='open'):_action(outer,'start' if action=='open' else 'stop')
        return observe(root,operation_id)
