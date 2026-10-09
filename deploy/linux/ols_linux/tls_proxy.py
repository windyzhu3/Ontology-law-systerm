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
    from . import tls_systemd
    return tls_systemd.observe_service(service)


def validate_registration(root: Path,registration: dict) -> dict:
    version=registration.get('version')
    expected={'version','services'}|({'qualification'} if version==2 else set())
    if set(registration)!=expected or version not in {1,2} or not isinstance(registration['services'],list):raise RuntimeError('Explicit proxy registration required')
    if version==2:
        from . import tls_systemd_qualification
        tls_systemd_qualification.require(root,registration)
    roles=[]
    for service in registration['services']:
        fields={'role','transport','name','identity','image','config','configSha256','tlsPaths'}|({'systemd'} if version==2 else set())
        if set(service)!=fields:raise RuntimeError('Unexpected proxy registration field')
        if service['role'] not in {'nginx','caddy'} or service['transport'] not in {'docker','systemd'} or not re.fullmatch('[a-zA-Z0-9][a-zA-Z0-9_.@-]{0,127}',service['name']):raise RuntimeError('Unsupported proxy service')
        roles.append(service['role']);path=Path(service['config'])
        if version==2:
            from . import tls_systemd
            if service['transport']!='systemd':raise RuntimeError('Version 2 is the qualified systemd profile')
            tls_systemd.verify_files(service['systemd']['immutableFiles']);tls_systemd.verify_includes(service['systemd']['includes'])
            if sha(path)!=service['configSha256']:raise RuntimeError('Original systemd configuration differs')
            tls_systemd.read_configuration(service)
        else:
            if not path.is_relative_to(root/'proxy') or path.resolve()!=path.absolute() or sha(path)!=service['configSha256']:raise RuntimeError('Proxy configuration must be sealed under the runtime proxy directory')
            tls_material.read_private(path)
        if not set(service['tlsPaths'])<={'certificate','privateKey','httpTrust'}:raise RuntimeError('Unknown proxy TLS path')
        actual=_service(service)
        if service['transport']=='docker' and not any(m.get('Type')=='bind' and m.get('Source')==str(root) and m.get('Destination')==str(root) for m in actual['actual'].get('Mounts',[])):
            raise RuntimeError('Proxy must already mount the registered runtime at its original path')
    if len(set(roles))!=len(roles) or 'nginx' not in roles:raise RuntimeError('One registered outer nginx required')
    return registration


def _read_config(service):
    if service['transport']=='systemd':
        from . import tls_systemd
        return tls_systemd.read_configuration(service)
    return tls_material.read_private(service['config'])


def _write_config(service,data,allowed):
    if service['transport']=='systemd':
        from . import tls_systemd
        tls_systemd.write_configuration(service,data,allowed=allowed)
    else:runtime.private_file(Path(service['config']),data)


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
            before=_read_config(service);after=before
            if service['transport']=='systemd':
                from . import tls_systemd
                after=tls_systemd.replace_public_paths(service,before,generation_paths)
            for name,old in (service['tlsPaths'].items() if service['transport']=='docker' else []):
                new=generation_paths[name]
                if not all(re.fullmatch('/[a-zA-Z0-9_./-]+',p) for p in [old,new]):raise RuntimeError('Unsafe proxy TLS path token')
                if old.encode() not in before:raise RuntimeError('Declared TLS reference missing')
                after=after.replace(old.encode(),new.encode())
            candidate=root/'proxy'/(operation_id+'-'+service['role']+'.conf')
            if candidate.exists() and candidate.read_bytes()!=after:raise RuntimeError('Proxy candidate differs')
            if not candidate.exists():runtime.private_file(candidate,after)
            row={'service':service,'before':before.decode('utf-8'),'after':after.decode('utf-8'),'candidate':str(candidate)}
            if service['transport']=='systemd' and service['role']=='caddy':
                observed=_service(service)
                if not observed['running'] or not observed['credentials']:raise RuntimeError('Original loaded Caddy credentials required')
                row['beforeCredentials']=observed['credentials']
            services.append(row)
        value={'operationId':operation_id,'registration':registration,'paths':generation_paths,'services':services}
        journal._write(root,saved,value);return value


def _check(service,candidate):
    arguments=['nginx','-t','-c',str(candidate)] if service['role']=='nginx' else ['caddy','validate','--config',str(candidate),'--adapter','caddyfile']
    if service['transport']=='docker':
        runtime.run(['docker','run','--rm','--network','none','--volumes-from',service['name'],
                     '--entrypoint',arguments[0],service['image'],*arguments[1:]])
    else:
        from . import tls_systemd
        if service['role']=='nginx':
            complete=tls_systemd.nginx_candidate(service,candidate)
            runtime.run(tls_systemd.start_commands('nginx',str(complete))['check'])
        elif tls_material.read_private(candidate)!=_read_config(service):
            raise RuntimeError('Caddy configuration must remain unchanged; new credentials validate in ExecStartPre')


def _action(service,action):
    if service['transport']=='docker':
        if action in {'start','stop'}:runtime.run(['docker',action,service['name']])
        else:runtime.run(['docker','exec',service['name'],'caddy','reload','--config',service['config'],'--adapter','caddyfile'])
    else:
        if service['role']=='caddy' and action=='reload':
            raise RuntimeError('Systemd Caddy requires original-operation credential control, not reload')
        runtime.run(['systemctl',action,service['name']])


def _allowed_config(root,operation_id,row):
    values=[row['before'].encode(),row['after'].encode()]
    path=root/'operations'/(operation_id+'-issuer-maintenance.json')
    if row['service']['role']=='nginx' and path.exists():
        saved=journal._read(root,path)
        if saved['service']!=row['service']:raise RuntimeError('Original maintenance service differs')
        values.append(saved['configuration'].encode())
    return values


def _state(root,operation_id,service):
    if service['transport']=='systemd':
        from . import tls_systemd
        return _service(tls_systemd.operation_service(root,operation_id,service))
    return _service(service)


def observe(root: Path,operation_id: str) -> dict:
    value=journal._read(root,root/'operations'/(operation_id+'-proxy.json'))
    states={}
    for row in value['services']:
        service=row['service'];state=_state(root,operation_id,service)
        data=_read_config(service)
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
        if action in {'switch','rollback'}:
            from . import tls_systemd
            tls_systemd.reconcile_manager(root,operation_id)
        observed=observe(root,operation_id)
        if action in {'switch','rollback'} and observed['services']['nginx']['configuration']=='maintenance':
            outer=next(row['service'] for row in value['services'] if row['service']['role']=='nginx')
            if _state(root,operation_id,outer)['running']:_action(outer,'stop')
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
                service=row['service'];desired=row['after' if action=='switch' else 'before'].encode()
                _write_config(service,desired,_allowed_config(root,operation_id,row))
                if service['role']=='caddy':
                    if service['transport']=='systemd':
                        from . import tls_systemd
                        generation_id=Path(value['paths']['httpTrust']).parent.name
                        tls_systemd.switch_credential_sources(root,operation_id,service,generation_id,'forward' if action=='switch' else 'rollback')
                        expected=dict(row['beforeCredentials'])
                        if action=='switch':expected['issuer-ca.pem']=sha(Path(value['paths']['httpTrust']))
                        tls_systemd.control(root,operation_id,service,'load',expected)
                    else:_action(service,'reload' if _service(service)['running'] else 'start')
        else:
            outer=next(row['service'] for row in value['services'] if row['service']['role']=='nginx')
            if action=='open' and observed['services']['nginx']['configuration']=='maintenance':
                if _state(root,operation_id,outer)['running']:_action(outer,'stop')
                if _state(root,operation_id,outer)['running']:raise RuntimeError('Maintenance stop unconfirmed')
                row=next(r for r in value['services'] if r['service']['role']=='nginx')
                desired=row['before' if op['phase']=='ROLLBACK_OPENING' else 'after']
                _write_config(outer,desired.encode(),_allowed_config(root,operation_id,row))
            if _state(root,operation_id,outer)['running']!=(action=='open'):
                _action(outer,'start' if action=='open' else 'stop')
                if action=='open' and outer['transport']=='systemd':
                    from . import tls_systemd
                    tls_systemd.await_started(outer,root=root,operation_id=operation_id)
        return observe(root,operation_id)
