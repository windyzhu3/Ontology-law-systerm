"""Exact public roster resolved through sequential, cross-administrator commands."""
from datetime import datetime,timedelta,timezone
import base64
import hashlib
import hmac
import json
from pathlib import Path
import uuid
from urllib.parse import urlencode
from . import admin,assembly,database,identity,journal
from .config import canonical,digest


def steps(config: dict) -> list[dict]:
    result=[]
    for role in config['roles']:
        if role['code'] in {'DIRECTOR','FINANCE_SUPERVISOR','CASE_SUPERVISOR'}:result.append({'key':'role:'+role['code'],'kind':'role','role':role,'actor':'dingqiming'})
    for organization in config['organizations']:
        if organization['code']!='ROOT':result.append({'key':'org:'+organization['code'],'kind':'organization','organization':organization,'actor':'dingqiming'})
    # Establish the independent second administrator before any Ding business grant.
    ordered=sorted(config['people'],key=lambda p:(p['username']!='huangxuexue',p['username']=='dingqiming',p['username']))
    for person in ordered:
        username=person['username'];actor='huangxuexue' if username=='dingqiming' else 'dingqiming'
        if username!='dingqiming':result.append({'key':'principal:'+username,'kind':'principal','username':username,'name':person['name'],'actor':actor})
        for appointment in person['appointments']:
            if appointment['role']=='IDENTITY_ADMIN' and username=='dingqiming':continue
            result.append({'key':'appointment:'+appointment['key'],'kind':'appointment','username':username,'appointment':appointment,'actor':actor})
            grants=sorted(appointment['grants'],key=lambda g:(not g['authority'].startswith('IDENTITY_'),g['authority'],g['scope']))
            for grant in grants:result.append({'key':'grant:'+appointment['key']+':'+grant['authority']+':'+grant['scope'],'kind':'grant','username':username,'appointment':appointment,'grant':grant,'actor':actor})
    return result


ENDPOINT={'role':'roles','organization':'organizations','principal':'principals','appointment':'appointments','grant':'authority-grants'}


def created_id(tenant,state,actor,kind,rows,before,receipt):
    types={'role':('identity.appointment_role','APPOINTMENT_ROLE'),'organization':('identity.organization_unit','ORGANIZATION_UNIT'),'principal':('identity.principal','IDENTITY_PRINCIPAL'),'appointment':('identity.appointment','APPOINTMENT'),'grant':('identity.authority_grant','AUTHORITY_GRANT')}
    candidates=[]
    for row in rows:
        if row['id'] in before:continue
        scope={'profile':'R1_PUBLIC_FACT_REF_V1','tenant':tenant,'principal':state['principals'][actor['username']],'appointment':actor['appointmentId'],'onBehalfPrincipal':None,'onBehalfAppointment':None,'kind':'HUMAN','type':types[kind][0],'id':row['id']}
        reference=base64.urlsafe_b64encode(hashlib.sha256(canonical(scope)).digest()).decode().rstrip('=')
        if receipt.get('resultFact',{}).get('factRef')==reference and receipt['resultFact'].get('factType')==types[kind][1]:candidates.append(row['id'])
    if len(candidates)!=1:raise RuntimeError('Original receipt does not identify exactly one newly created UUID')
    return candidates[0]


def _list(root,actor,file,kind):
    rows=[];cursor=None;seen=set()
    while True:
        query={'limit':50}
        if cursor:query['cursor']=cursor
        page=admin.read(root,actor,file,'/api/v1/admin/identity/'+ENDPOINT[kind]+'?'+urlencode(query));rows+=page['items'];cursor=page.get('nextCursor')
        if not cursor:break
        if cursor in seen:raise RuntimeError('Administration cursor did not advance')
        seen.add(cursor)
    if len({r['id'] for r in rows})!=len(rows):raise RuntimeError('Duplicate administration UUID')
    return rows


def _new_state(root,config):
    plan=journal._read(root,root/'identity/plan.json');tenant=plan['tenantId']
    key=base64.b64decode(identity.secret_file(root/'secrets/subject-key.txt'))
    subject=hmac.new(key,plan['subjects']['dingqiming'].encode(),hashlib.sha256).hexdigest()
    rows=json.loads(database.sql(root,"SELECT coalesce(json_agg(json_build_object('principal',p.principal_id,'appointment',a.appointment_id,'organization',o.organization_unit_id)),'[]'::json) FROM identity.principal p JOIN identity.appointment a ON a.tenant_id=p.tenant_id AND a.principal_id=p.principal_id JOIN identity.organization_unit o ON o.tenant_id=a.tenant_id AND o.organization_unit_id=a.organization_unit_id WHERE p.tenant_id='"+tenant+"' AND p.principal_kind='HUMAN' AND p.external_subject_hmac=decode('"+subject+"','hex') AND a.role_code='IDENTITY_ADMIN' AND o.unit_code='ROOT'"))
    if len(rows)!=1:raise RuntimeError('Exact original bootstrap references unavailable')
    row=rows[0]
    return {'operationId':plan['operationId'],'configDigest':digest(config),'config':config,'effectiveFrom':(datetime.now(timezone.utc)-timedelta(seconds=1)).isoformat().replace('+00:00','Z'),
        'organizations':{'ROOT':row['organization']},'principals':{'dingqiming':row['principal']},'appointments':{'dingqiming_bootstrap':row['appointment']},'steps':{}}


def _actor(state,username):
    key='dingqiming_bootstrap' if username=='dingqiming' else 'huangxuexue_director'
    return {'username':username,'appointmentId':state['appointments'][key]}


def _body(root,state,step,actor,file):
    kind=step['kind'];effective=state['effectiveFrom']
    if kind=='role':return {'code':step['role']['code'],'displayName':step['role']['name']}
    if kind=='organization':return {'code':step['organization']['code'],'displayName':step['organization']['name'],'parentOrganizationId':state['organizations']['ROOT']}
    if kind=='principal':
        page=admin.read(root,actor,file,'/api/v1/admin/identity/provider-users?'+urlencode({'search':step['username']}))
        if len(page['items'])!=1 or page['items'][0]['label']!=step['username']:raise RuntimeError('Exact enabled original provider candidate unavailable')
        return {'providerUserSelector':page['items'][0]['selector'],'displayName':step['name']}
    if kind=='appointment':
        appointment=step['appointment']
        return {'principalId':state['principals'][step['username']],'organizationId':state['organizations'][appointment['organization']],'roleCode':appointment['role'],'effectiveFrom':effective,'effectiveUntil':None}
    if kind=='grant':return {'appointmentId':state['appointments'][step['appointment']['key']],'authorityCode':step['grant']['authority'],'scopeOrganizationId':state['organizations'][step['grant']['scope']],'validFrom':effective,'validUntil':None}
    raise ValueError('Unknown initialization step')


def run(root: Path, config: dict, sessions: dict[str,Path]) -> dict:
    with journal.locked(root) as root:
        op=journal.current(root)
        if op['kind']!='initialize' or op['configDigest']!=digest(config) or set(sessions)!=admin.ADMINS:raise RuntimeError('Original initialization and both HUMAN session files required')
        if journal._read(root,root/'identity/bootstrap.json')['state']!='VERIFIED':raise RuntimeError('Original offline bootstrap must be verified first')
        identity.verify(root)
        path=root/'initialization.json'
        state=journal._read(root,path) if path.exists() else _new_state(root,config)
        if state['operationId']!=op['operationId'] or state['configDigest']!=digest(config):raise RuntimeError('Original roster/configuration changed')
        if not path.exists():journal._write(root,path,state)
        for username,file in sessions.items():admin.session(root,file,username)
        for step in steps(config):
            key=step['key'];actor=_actor(state,step['actor']);file=sessions[step['actor']]
            record=state['steps'].get(key)
            if record and record['state']=='CONFIRMED':continue
            if not record:
                before=[r['id'] for r in _list(root,actor,file,step['kind'])]
                command={'commandId':str(uuid.uuid4()),'path':'/api/v1/admin/identity/'+ENDPOINT[step['kind']],'body':_body(root,state,step,actor,file),'precondition':None,'actor':actor}
                record={'state':'DISPATCH_UNKNOWN','command':command,'beforeIds':before}
                state['steps'][key]=record;journal._write(root,path,state)
            receipt=admin.execute(root,op['operationId'],record['command'],file)
            rows=_list(root,actor,file,step['kind'])
            # A name/code is never used to adopt an external existing object. This
            # is the unique new UUID after the saved original successful command.
            id=created_id(journal._read(root,root/'identity/plan.json')['tenantId'],state,actor,step['kind'],rows,record['beforeIds'],receipt)
            record.update(state='CONFIRMED',createdId=id,receipt=receipt)
            if step['kind']=='organization':state['organizations'][step['organization']['code']]=id
            if step['kind']=='principal':state['principals'][step['username']]=id
            if step['kind']=='appointment':state['appointments'][step['appointment']['key']]=id
            journal._write(root,path,state)
        from . import verify
        result=verify.initialization(root,config)
        journal.record(root,op['operationId'],{'phase':'IDENTITY_STRUCTURE_VERIFIED','structureDigest':digest(result)})
        return result


def resume(root: Path, operation_id: str, sessions: dict[str,Path]) -> dict:
    with journal.locked(root) as root:
        op=journal.read(root,operation_id)
        if op['kind']!='initialize' or journal.current(root)['operationId']!=operation_id:raise RuntimeError('Original initialization only')
        state=journal._read(root,root/'initialization.json')
        return run(root,state['config'],sessions)
