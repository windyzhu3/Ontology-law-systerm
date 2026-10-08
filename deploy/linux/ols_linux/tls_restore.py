"""TLS reconciliation within an existing checkpoint restore, never a nested rotation."""
from pathlib import Path
from . import journal,runtime,tls_generation,tls_proxy,tls_probe
from .config import digest
from .bundle import sha


def _path(root,opid):return root/'operations'/(opid+'-restore-tls.json')


def _state(root):
    op=journal.current(root);plan=journal._read(root,root/'restore-plan.json')
    if plan['operationId']!=op['operationId'] or op['phase']=='COMPLETE':raise RuntimeError('Original pending restore required')
    path=_path(root,op['operationId'])
    if not path.exists():return None
    state=journal._read(root,path)
    if state['checkpointDigest']!=plan['checkpointDigest']:raise RuntimeError('Original TLS restore checkpoint differs')
    return state


def prepare(root: Path,value: dict):
    with journal.locked(root) as root:
        op=journal.current(root);path=_path(root,op['operationId'])
        if path.exists():
            state=_state(root)
            if state['checkpointDigest']!=digest(value):raise RuntimeError('Original TLS restore differs')
            return
        if not (root/'certs/public.crt').exists():return
        before=tls_generation.checkpoint_binding(root)
        target=value.get('tlsBinding') or {}
        if target.get('proxies') and before.get('proxies'):
            identities=lambda rows:sorted((r['service']['role'],r['service']['transport'],r['service']['name'],r['service']['identity'],r['service']['image']) for r in rows)
            if identities(target['proxies'])!=identities(before['proxies']):raise RuntimeError('Restored proxy registration differs from current registered services')
        journal._write(root,path,{'checkpointDigest':digest(value),'before':before,'target':value.get('tlsBinding')})


def _rows(state):return (state.get('target') or {}).get('proxies',state['before'].get('proxies',[]))


def close(root: Path):
    with journal.locked(root) as root:
        state=_state(root)
        if not state:return
        # Close using the pre-restore identity even before target assets exist.
        rows=state['before'].get('proxies',[]) or _rows(state)
        for row in rows:
            service=row['service'];actual=tls_proxy._service(service)
            if service['role']=='nginx':
                if actual['running']:tls_proxy._action(service,'stop')
                if tls_proxy._service(service)['running']:raise RuntimeError('Restored outer proxy stop unconfirmed')


def _legacy_context(root,generation,state):
    if generation['version']!=0 or not _rows(state):return
    path=root/'tls-restored.json'
    if path.exists():
        context=journal._read(root,path)
        if context['generationId']!=generation['generationId']:raise RuntimeError('Restored legacy probe context differs')
        if sha(Path(context['deployment']['httpHelper']))!=context['helperSha256']:raise RuntimeError('Restored probe helper differs')
        return
    helper=root/'tls-restored/https-json.mjs'
    data=(Path(__file__).parents[1]/'runtime/tls-https-json.mjs').read_bytes()
    if helper.exists() and helper.read_bytes()!=data:raise RuntimeError('Restored helper conflicts')
    if not helper.exists():runtime.private_file(helper,data)
    journal._write(root,path,{'generationId':generation['generationId'],'helperSha256':sha(helper),
        'deployment':{'httpHelper':str(helper),'probeTargets':state['before'].get('probeTargets',[])},
        'proxies':_rows(state)})


def activate(root: Path,*,now: int):
    with journal.locked(root) as root:
        if journal.current(root)['phase']!='ACTIVATION_UNKNOWN':raise RuntimeError('Original restore activation phase required')
        state=_state(root)
        if not state:return
        generation=tls_generation.resolve(root)
        if generation['candidate']['notAfter']<=now:raise RuntimeError('Expired restored certificate cannot activate')
        close(root)
        desired=[]
        for index,row in enumerate(_rows(state)):
            service=row['service'];tls_proxy._service(service)
            path=Path(service['config'])
            if not path.is_relative_to(root/'proxy') or path.resolve()!=path.absolute():raise RuntimeError('Restored proxy path outside registry')
            data=row['configuration']
            if not (state.get('target') or {}).get('proxies'):
                for name,old in state['before']['paths'].items():
                    if name in {'certificate','privateKey','httpTrust'}:data=data.replace(old,generation['paths'][name])
            candidate=root/'operations'/(journal.current(root)['operationId']+'-restore-proxy-'+str(index)+'.conf')
            if candidate.exists() and candidate.read_text()!=data:raise RuntimeError('Original proxy restore bytes differ')
            if not candidate.exists():runtime.private_file(candidate,data.encode())
            tls_proxy._check(service,candidate);desired.append((service,path,data))
        for service,path,data in desired:
            if not path.exists() or path.read_text()!=data:runtime.private_file(path,data.encode())
            if service['role']=='caddy':tls_proxy._action(service,'reload' if tls_proxy._service(service)['running'] else 'start')
        _legacy_context(root,generation,dict(state,target={'proxies':[{'service':service,'configuration':data} for service,path,data in desired]}))


def open_verified(root: Path,*,now: int):
    with journal.locked(root) as root:
        state=_state(root)
        if not state or not _rows(state):return
        generation=tls_generation.resolve(root)
        for scope in ['native','bridge']:
            proof=tls_probe.collect(root,generation,scope=scope,now=now)
            if proof['status']!='PASS':raise RuntimeError('Restored TLS '+scope+' proof unavailable')
        for row in _rows(state):
            service=row['service'];actual=tls_proxy._service(service)
            if service['role']=='nginx' and not actual['running']:tls_proxy._action(service,'start')
        proof=tls_probe.collect(root,generation,scope='all',now=now)
        if proof['status']!='PASS':raise RuntimeError('Restored public TLS proof unavailable')
        journal._write(root,root/'operations'/(journal.current(root)['operationId']+'-restore-tls-proof.json'),proof)
