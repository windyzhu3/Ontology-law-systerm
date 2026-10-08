"""Original-operation public TLS transition. No identity or business writes."""
from datetime import datetime,timezone
from pathlib import Path
import uuid
from . import journal,runtime,release,database,tls_material,tls_generation,tls_deployment,tls_proxy,tls_probe,tls_import
from .config import digest


def _path(root,opid):return root/'operations'/(opid+'-tls.json')

def _save(root,opid,data):journal._write(root,_path(root,opid),data)

def _phase(root,opid,phase,**evidence):journal.record(root,opid,dict(phase=phase,**evidence))


def reconcile_pending(root: Path) -> dict | None:
    with journal.locked(root) as root:
        path=root/'tls-pending.json'
        if not path.exists():return None
        value=journal._read(root,path);current=journal.current(root);opid=value['operationId']
        if current['operationId']==opid:
            if current['configDigest']!=value['configDigest']:raise RuntimeError('Pending TLS inputs conflict')
            return current
        if current['operationId']!=value['previousOperationId'] or current['phase']!='COMPLETE':
            if journal.read(root,opid)['phase']=='COMPLETE':return None
            raise RuntimeError('Another operation superseded pending TLS')
        operation=root/'operations'/(opid+'.json')
        if operation.exists():
            op=journal.read(root,opid)
            if op['kind']!='rotate-public-tls' or op['configDigest']!=value['configDigest'] or op['phase']!='CREATED':raise RuntimeError('Orphan TLS registration conflicts')
            journal._write(root,root/'current-operation.json',{'operationId':opid});return op
        return journal.begin(root,'rotate-public-tls',value['configDigest'],operation_id=opid)


def begin(root: Path,inputs: dict,*,now: int) -> dict:
    with journal.locked(root) as root:
        reconcile_pending(root)
        current=journal.current(root)
        if current['phase']!='COMPLETE':raise RuntimeError('Resume the current original operation')
        if not isinstance(inputs,dict) or set(inputs)!={'materials','proxies','probeTargets'}:raise RuntimeError('Exact TLS rotation inputs required')
        materials=tls_import.normalize(root,inputs['materials'],now=now)
        frozen=tls_material.freeze(root,materials)
        selected=tls_generation.resolve(root)
        if selected.get('version')==1 and selected['candidate']['inputDigest']==frozen['candidate']['inputDigest']:
            original=journal.read(root,selected['operationId']);saved=journal._read(root,_path(root,original['operationId']))
            if original['phase']!='COMPLETE' or original['kind']!='rotate-public-tls' or saved['proxies']!=inputs['proxies'] or saved['probeTargets']!=inputs['probeTargets']:raise RuntimeError('Duplicate delivery original binding differs')
            if selected['candidate']['notAfter']<=now or tls_probe.collect(root,selected,scope='all',now=now)['status']!='PASS':raise RuntimeError('Duplicate delivery is not an observed healthy active generation')
            return {'operationId':original['operationId'],'kind':'rotate-public-tls','phase':'COMPLETE','outcome':'UNCHANGED','generationId':selected['generationId']}
        runtime.validate_tls(root);before=database.observe(root)
        if before['gate']['operating_mode']!='ACTIVE':raise RuntimeError('TLS rotation requires original ACTIVE gate')
        release._installed(root,before['gate'])
        candidate=tls_material.stage(root,materials,now=now,frozen=frozen)
        tls_proxy.validate_registration(root,inputs['proxies'])
        old=tls_generation.resolve(root);opid=uuid.uuid4().hex
        data={'candidate':candidate,'previousGeneration':old,'before':before,'proxies':inputs['proxies'],
              'probeTargets':inputs['probeTargets'],'previousResources':runtime.load(root),
              'previousLaunch':journal._read(root,root/'launch.json'),'release':journal._read(root,root/'current-release.json')}
        _save(root,opid,data)
        journal._write(root,root/'tls-pending.json',{'operationId':opid,'previousOperationId':current['operationId'],'configDigest':digest(data)})
        reconcile_pending(root)
        return resume(root,opid,now=now)


def gate(root,opid,name,mode,*,expected_before=None):
    path=root/'operations'/(opid+'-tls-gate-'+name+'.json')
    actual=database.observe(root)['gate']
    if path.exists():intent=journal._read(root,path)
    else:
        if _path(root,opid).exists():
            data=journal._read(root,_path(root,opid))
            allowed=[data['before']['gate']]
            for saved in (root/'operations').glob(opid+'-tls-gate-*.json'):
                allowed.append(journal._read(root,saved)['expected'])
            if not any(release._same_gate(actual,g) for g in allowed):raise RuntimeError('TLS gate was changed by another operation')
        if expected_before is not None and not release._same_gate(actual,expected_before):raise RuntimeError('TLS gate origin conflict')
        intent={'before':actual,'expected':dict(actual,operating_mode=mode,revision=actual['revision']+1,changed_at=datetime.now(timezone.utc).isoformat(timespec='microseconds'))}
        journal._write(root,path,intent)
    if intent['expected']['operating_mode']!=mode:raise RuntimeError('TLS gate intent conflict')
    if release._same_gate(actual,intent['expected']):return actual
    if not release._same_gate(actual,intent['before']):raise RuntimeError('TLS gate was changed by another operation')
    return release._cas_gate(root,actual,intent['expected'])


def _prepare(root,opid,data):
    if 'generationId' in data:return tls_generation.read(root,data['generationId'])
    layout=tls_generation.layout(root,opid,data['candidate'])
    trust=tls_generation.build_trust(root,data['candidate'],Path(layout['paths']['httpTrust']).parent)
    deployment=tls_deployment.prepare(root,opid,data['candidate'],trust)
    deployment['probeTargets']=data['probeTargets']
    generation=tls_generation.seal(root,opid,data['candidate'],trust,deployment)
    proxy=tls_proxy.prepare(root,opid,data['proxies'],generation['paths'])
    from . import tls_maintenance
    tls_maintenance.prepare(root,opid,proxy['services'],data['probeTargets'],generation['paths'])
    data['generationId']=generation['generationId'];_save(root,opid,data)
    return generation


def _stop(root,opid):
    state=tls_proxy.apply(root,opid,'close')
    if not state['closed']:raise RuntimeError('Outer proxy stop unconfirmed')
    runtime.stop_writers(root,opid,phase='FAILING' if journal.current(root)['phase']=='FAILING' else 'STOPPING')


def _fail(root,opid,data):
    _phase(root,opid,'FAILING')
    _stop(root,opid)
    attempt=str(data.get('attempt',0))
    actual=database.observe(root)['gate']
    allowed=[data['before']['gate']]
    for suffix in ['maintenance','active-'+attempt,'retry-'+attempt]:
        p=root/'operations'/(opid+'-tls-gate-'+suffix+'.json')
        if p.exists():allowed.append(journal._read(root,p)['expected'])
    existing=root/'operations'/(opid+'-tls-gate-blocked-'+attempt+'.json')
    if existing.exists():allowed.append(journal._read(root,existing)['expected'])
    if not any(release._same_gate(actual,g) for g in allowed):raise RuntimeError('TLS failure gate conflict; stop observed, gate unknown')
    gate(root,opid,'blocked-'+attempt,'BLOCKED',expected_before=actual)
    _phase(root,opid,'BLOCKED')


def _start(root,opid,generation,data):
    from . import tls_maintenance
    rollback=journal.current(root)['phase']=='ROLLBACK_ACTIVATING'
    tls_proxy.apply(root,opid,'rollback' if rollback else 'switch')
    proxy=journal._read(root,root/'operations'/(opid+'-proxy.json'))
    tls_maintenance.start(root,opid,proxy['services'],data['probeTargets'],generation['paths'])
    runtime.start_internal(root,data['release']['descriptor'])
    launch=journal._read(root,root/'launch.json');entry=launch['ingress']
    actual=runtime.owned(root,'container',entry['name'])
    if actual['Config']['Labels'].get('ols.launch')!=entry['digest']:raise RuntimeError('TLS entry launch differs')
    if not actual['State']['Running']:runtime.run(['docker','start',entry['name']])


def _probe(root,opid,generation,scope,now):
    evidence=tls_probe.collect(root,generation,scope=scope,now=now)
    if evidence['status']!='PASS':raise RuntimeError('TLS consumer or native fingerprint verification failed')
    journal._write(root,root/'operations'/(opid+'-tls-proof-'+scope+'.json'),evidence)
    return evidence


def resume(root: Path,operation_id: str,*,now: int) -> dict:
    with journal.locked(root) as root:
        reconcile_pending(root);op=journal.current(root)
        if op['operationId']!=operation_id or op['kind']!='rotate-public-tls':raise RuntimeError('Explicit current TLS operation required')
        data=journal._read(root,_path(root,operation_id));opid=operation_id
        if op['phase']=='COMPLETE':return {'operationId':opid,'kind':op['kind'],'phase':'COMPLETE','outcome':op['events'][-1]['outcome'],'generationId':tls_generation.resolve(root)['generationId']}
        if op['phase'].startswith('ROLLBACK'):return rollback(root,opid,now=now)
        phase=op['phase']
        if phase=='FAILING':_fail(root,opid,data);phase='BLOCKED'
        if phase=='BLOCKED':
            _phase(root,opid,'RETRYING',retryAttempt=data.get('attempt',0)+1)
            phase='RETRYING'
        if phase=='RETRYING':
            data['attempt']=journal.current(root)['events'][-1]['retryAttempt'];_save(root,opid,data)
            gate(root,opid,'retry-'+str(data['attempt']),'MAINTENANCE')
            _phase(root,opid,'STOPPING');phase='STOPPING'
        try:
            if phase not in {'CREATED','PREPARATION_BLOCKED','PREPARED','MAINTENANCE_REQUESTED','MAINTENANCE','STOPPING','QUIESCED','TRUST_PREPARED','SWITCHING','SWITCHED','ACTIVATING','NATIVE_VERIFIED','PROXY_SWITCHING','READY_TO_OPEN','OPENING'}:
                raise RuntimeError('Unknown original TLS phase')
            tls_material.verify(Path(data['candidate']['directory']),data['candidate'],now=now)
            if phase in {'CREATED','PREPARATION_BLOCKED'}:
                generation=_prepare(root,opid,data);_phase(root,opid,'PREPARED');phase='PREPARED'
            else:generation=tls_generation.read(root,data['generationId'])
            if phase in {'PREPARED','MAINTENANCE_REQUESTED'}:
                _phase(root,opid,'MAINTENANCE_REQUESTED')
                gate(root,opid,'maintenance','MAINTENANCE',expected_before=data['before']['gate'])
                _phase(root,opid,'MAINTENANCE');phase='MAINTENANCE'
            if phase in {'MAINTENANCE','STOPPING'}:
                _phase(root,opid,'STOPPING');_stop(root,opid);_phase(root,opid,'QUIESCED');phase='QUIESCED'
            if phase in {'QUIESCED','TRUST_PREPARED','SWITCHING'}:
                tls_generation.verify_trust(root,generation['trust'])
                _phase(root,opid,'SWITCHING');tls_deployment.switch(root,opid,generation)
                tls_deployment.verify_copies(root,generation)
                _phase(root,opid,'SWITCHED');phase='SWITCHED'
            if phase in {'SWITCHED','ACTIVATING'}:
                _phase(root,opid,'ACTIVATING')
                gate(root,opid,'active-'+str(data.get('attempt',0)),'ACTIVE')
                _start(root,opid,generation,data);_probe(root,opid,generation,'native',now)
                _phase(root,opid,'NATIVE_VERIFIED');phase='NATIVE_VERIFIED'
            if phase in {'NATIVE_VERIFIED','PROXY_SWITCHING'}:
                _phase(root,opid,'PROXY_SWITCHING');tls_proxy.apply(root,opid,'switch')
                _probe(root,opid,generation,'bridge',now)
                _phase(root,opid,'READY_TO_OPEN');phase='READY_TO_OPEN'
            if phase in {'READY_TO_OPEN','OPENING'}:
                _phase(root,opid,'OPENING');tls_proxy.apply(root,opid,'open')
                _probe(root,opid,generation,'all',now)
                from .verify import ingress_ready
                ingress_ready(root,data['release']['descriptor'])
                _phase(root,opid,'COMPLETE',outcome='ROTATED',generationId=generation['generationId'])
                return {'operationId':opid,'kind':'rotate-public-tls','phase':'COMPLETE','outcome':'ROTATED','generationId':generation['generationId']}
            raise RuntimeError('Unknown original TLS phase')
        except Exception:
            current=journal.current(root)['phase']
            if current in {'CREATED','PREPARATION_BLOCKED'}:_phase(root,opid,'PREPARATION_BLOCKED')
            elif current!='COMPLETE':_fail(root,opid,data)
            raise


def _rollback_fail(root,opid,data):
    _phase(root,opid,'ROLLBACK_FAILING')
    if not tls_proxy.apply(root,opid,'close')['closed']:raise RuntimeError('Rollback proxy stop unconfirmed')
    runtime.stop_writers(root,opid,phase='ROLLBACK_FAILING')
    gate(root,opid,'rollback-blocked-'+str(data['rollbackAttempt']),'BLOCKED')
    _phase(root,opid,'ROLLBACK_BLOCKED')


def rollback(root: Path,operation_id: str,*,now: int) -> dict:
    with journal.locked(root) as root:
        op=tls_generation.current(root,operation_id);data=journal._read(root,_path(root,operation_id));old=data['previousGeneration']
        opid=operation_id;phase=op['phase']
        if old['candidate']['notAfter']<=now:
            if phase.startswith('ROLLBACK'):_rollback_fail(root,opid,data)
            raise RuntimeError('Expired old certificate cannot reopen service')
        if 'generationId' not in data:raise RuntimeError('Original prepared rollback unavailable')
        if phase=='ROLLBACK_FAILING':
            _rollback_fail(root,opid,data);phase='ROLLBACK_BLOCKED'
        if not phase.startswith('ROLLBACK') or phase=='ROLLBACK_BLOCKED':
            _phase(root,opid,'ROLLBACK_REQUESTED',rollbackAttempt=data.get('rollbackAttempt',0)+1)
            phase='ROLLBACK_REQUESTED'
        if phase=='ROLLBACK_REQUESTED':
            data['rollbackAttempt']=journal.current(root)['events'][-1]['rollbackAttempt'];_save(root,opid,data)
        attempt=str(data['rollbackAttempt'])
        if old['version']==0:
            old=dict(old,deployment={'httpHelper':tls_generation.read(root,data['generationId'])['deployment']['httpHelper'],'probeTargets':data['probeTargets']})
        try:
            if phase=='ROLLBACK_REQUESTED':
                gate(root,opid,'rollback-maintenance-'+attempt,'MAINTENANCE')
                _phase(root,opid,'ROLLBACK_STOPPING');phase='ROLLBACK_STOPPING'
            if phase=='ROLLBACK_STOPPING':
                if not tls_proxy.apply(root,opid,'close')['closed']:raise RuntimeError('Rollback proxy stop unconfirmed')
                runtime.stop_writers(root,opid,phase='ROLLBACK_STOPPING')
                _phase(root,opid,'ROLLBACK_SWITCHING');phase='ROLLBACK_SWITCHING'
            if phase=='ROLLBACK_SWITCHING':
                for name,h in old['files'].items():
                    from .bundle import sha
                    if sha(root/name)!=h:raise RuntimeError('Original rollback bytes differ')
                resources=runtime.load(root);prior=data['previousResources']
                for role in ['api','worker','identity','scanner','entry']:resources['containers'][role]=prior['containers'][role]
                resources['ingress']=prior['ingress'];runtime.save(root,resources)
                journal._write(root,root/'tls-selection.json',{'generationId':old['generationId'],'operationId':opid})
                if old['version']==0:
                    journal._write(root,root/'tls/active.json',{'legacy':old,'operationId':opid})
                else:journal._write(root,root/'tls/active.json',{'generationId':old['generationId'],'operationId':opid})
                journal._write(root,root/'launch.json',data['previousLaunch'])
                tls_deployment.copy_identity(root,resources['containers']['identity'],old)
                tls_proxy.apply(root,opid,'rollback')
                _phase(root,opid,'ROLLBACK_ACTIVATING');phase='ROLLBACK_ACTIVATING'
            if phase=='ROLLBACK_ACTIVATING':
                gate(root,opid,'rollback-active-'+attempt,'ACTIVE')
                _start(root,opid,old,data);_probe(root,opid,old,'native',now)
                _phase(root,opid,'ROLLBACK_OPENING');phase='ROLLBACK_OPENING'
            if phase=='ROLLBACK_OPENING':
                tls_proxy.apply(root,opid,'open');_probe(root,opid,old,'all',now)
                _phase(root,opid,'COMPLETE',outcome='ROLLED_BACK',generationId=old['generationId'])
                return {'operationId':opid,'kind':'rotate-public-tls','phase':'COMPLETE','outcome':'ROLLED_BACK','generationId':old['generationId']}
            raise RuntimeError('Unknown original TLS rollback phase')
        except Exception:
            _rollback_fail(root,opid,data)
            raise
