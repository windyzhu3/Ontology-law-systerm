"""Read-only TLS observation; scheduler evidence is signed and stored outside runtime."""
import hashlib
import hmac
import json
import ssl
from pathlib import Path
from . import journal,tls_generation,tls_material,tls_probe,tls_import
from .config import canonical


def evaluate(not_after: int,now: int,last_success: int | None,*,verified: bool | None) -> dict:
    remaining=not_after-now
    expiry='BLOCKED' if remaining<=0 else 'CRITICAL' if remaining<=7*86400 else 'ACTION_REQUIRED' if remaining<=14*86400 else 'WARNING' if remaining<=30*86400 else 'OK'
    monitoring='UNCONFIGURED' if last_success is None else 'STALE' if now-last_success>26*3600 else 'CURRENT'
    status='BLOCKED' if expiry=='BLOCKED' or verified is False else 'UNKNOWN' if verified is None or monitoring!='CURRENT' else expiry
    return {'status':status,'expiryState':expiry,'monitoringState':monitoring,'secondsRemaining':remaining}


def sign(root: Path,payload: dict) -> dict:
    return {'payload':payload,'mac':hmac.new(journal._key(root),canonical(payload),hashlib.sha256).hexdigest()}


def _previous(root,path,now):
    if path is None:return None
    path=Path(path)
    if not path.is_absolute() or path.is_relative_to(root):raise RuntimeError('Prior check must be a private file outside runtime')
    try:
        evidence=json.loads(tls_material.read_private(path))['checkEvidence'];payload=evidence['payload']
        if not hmac.compare_digest(evidence['mac'],sign(root,payload)['mac']):raise RuntimeError('Prior TLS check authentication failed')
        fields={'instanceId','generationId','checkedAt','probeStatus','lastSuccessfulCheckAt'}
        if set(payload)!=fields or payload['instanceId']!=journal._owner(root)['instanceId'] or type(payload['checkedAt'])!=int or payload['checkedAt']>now or payload['probeStatus'] not in {'PASS','BLOCKED','UNKNOWN'}:raise RuntimeError('Prior TLS check context differs')
        success=payload['lastSuccessfulCheckAt']
        if success is not None and (type(success)!=int or success>payload['checkedAt']):raise RuntimeError('Prior TLS success timestamp invalid')
        if payload['probeStatus']=='PASS' and success!=payload['checkedAt']:raise RuntimeError('Prior TLS success proof differs')
        if payload['probeStatus']!='PASS' and success==payload['checkedAt']:raise RuntimeError('Failed check is not success evidence')
        return success
    except (KeyError,TypeError,ValueError):raise RuntimeError('Prior signed TLS check invalid') from None


def status(root: Path,*,now: int,previous_check: Path | None=None) -> dict:
    with journal.locked(root) as root:
        previous=_previous(root,previous_check,now);generation=tls_generation.resolve(root)
        probe=tls_probe.collect(root,generation,scope='all',now=now)
        verified=True if probe['status']=='PASS' else False if probe['status']=='BLOCKED' else None
        result=evaluate(generation['candidate']['notAfter'],now,previous,verified=verified)
        before=int(ssl.cert_time_to_seconds(tls_material.metadata(Path(generation['paths']['certificate']))['notBefore']))
        if now<before:result.update(status='BLOCKED',expiryState='NOT_YET_VALID')
        op=journal.current(root)
        if op['phase']!='COMPLETE':result.update(status='BLOCKED',reasonCode='ORIGINAL_OPERATION_PENDING')
        success=now if probe['status']=='PASS' and now>=before else previous
        payload={'instanceId':op['instanceId'],'generationId':generation['generationId'],'checkedAt':now,'probeStatus':probe['status'],'lastSuccessfulCheckAt':success}
        result.update(operationId=op['operationId'],phase=op['phase'],generationId=generation['generationId'],notAfter=generation['candidate']['notAfter'],
                      probe=probe,lastSuccessfulCheckAt=success,checkEvidence=sign(root,payload),issuance=tls_import.issuance_status(root,now=now))
        return result
