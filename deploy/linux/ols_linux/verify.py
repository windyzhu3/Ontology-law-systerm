"""Read-only exact initialization and runtime checks; never repair or grant."""
import base64
import hashlib
import hmac
import json
import re
from pathlib import Path
import uuid
from . import database,identity,journal,runtime
from .config import digest


def _rows(root,query):return json.loads(database.sql(root,"SELECT coalesce(json_agg(v),'[]'::json) FROM ("+query+") v"))


def tenant(root,tenant_id,name):
    uuid.UUID(tenant_id)
    if _rows(root,'SELECT tenant_id AS id,tenant_code AS code,display_name AS name,state FROM identity.tenant')!=[{'id':tenant_id,'code':'HAIHUA','name':name,'state':'ACTIVE'}]:raise RuntimeError('Exact original active tenant differs')


def technical(root,plan,state):
    value=journal._read(root,root/'assembly/service.json')
    for field in ('tenantId','principalId','appointmentId','roleId'):uuid.UUID(value[field])
    if value['tenantId']!=plan['tenantId']:raise RuntimeError('Original SERVICE tenant differs')
    roles=_rows(root,'SELECT tenant_id AS tenant,appointment_role_id AS id,role_code AS code,state FROM identity.appointment_role')
    defaults={'IDENTITY_ADMIN','INTAKE_OPERATOR','ROUTING_SUPERVISOR','CONTACT_OPERATOR','SALES_REPRESENTATIVE','SALES_MANAGER','FINANCE_OPERATOR','CASE_ADMINISTRATOR'}
    if len(roles)!=12 or {r['code'] for r in roles}!=defaults|{'DIRECTOR','FINANCE_SUPERVISOR','CASE_SUPERVISOR','SERVICE'} or any(r['tenant']!=plan['tenantId'] or r['state']!='ACTIVE' for r in roles):raise RuntimeError('Exact active role directory differs')
    expected_ids={'SERVICE':value['roleId'],**{code:state['steps']['role:'+code]['createdId'] for code in ('DIRECTOR','FINANCE_SUPERVISOR','CASE_SUPERVISOR')}}
    if any(r['id']!=expected_ids[r['code']] for r in roles if r['code'] in expected_ids):raise RuntimeError('Original configurable role UUID differs')
    key=base64.b64decode(identity.secret_file(root/'secrets/subject-key.txt'))
    subject=hmac.new(key,b'linux-infrastructure',hashlib.sha256).hexdigest()
    principals=_rows(root,"SELECT tenant_id AS tenant,principal_id AS id,identity_provider_code AS provider,state,encode(external_subject_hmac,'hex') AS subject FROM identity.principal WHERE principal_kind='SERVICE'")
    if principals!=[{'tenant':plan['tenantId'],'id':value['principalId'],'provider':'LINUX_SERVICE','state':'ACTIVE','subject':subject}]:raise RuntimeError('Exact active technical principal differs')
    appointments=_rows(root,"SELECT a.tenant_id AS tenant,a.appointment_id AS id,a.principal_id AS principal,a.organization_unit_id AS organization,a.role_code AS role,a.state,(a.effective_from<=clock_timestamp() AND a.effective_until IS NULL) AS valid FROM identity.appointment a JOIN identity.principal p ON p.tenant_id=a.tenant_id AND p.principal_id=a.principal_id WHERE p.principal_kind='SERVICE'")
    if appointments!=[{'tenant':plan['tenantId'],'id':value['appointmentId'],'principal':value['principalId'],'organization':state['organizations']['ROOT'],'role':'SERVICE','state':'ACTIVE','valid':True}]:raise RuntimeError('Exact active technical appointment differs')
    grants=_rows(root,"SELECT g.tenant_id AS tenant,g.authority_grant_id AS id,g.grantee_appointment_id AS appointment,g.granted_by_appointment_id AS granted_by,g.scope_organization_unit_id AS scope,g.authority_code AS authority,g.state,(g.valid_from<=clock_timestamp() AND g.valid_until IS NULL) AS valid FROM identity.authority_grant g JOIN identity.appointment a ON a.tenant_id=g.tenant_id AND a.appointment_id=g.grantee_appointment_id JOIN identity.principal p ON p.tenant_id=a.tenant_id AND p.principal_id=a.principal_id WHERE p.principal_kind='SERVICE'")
    expected=[{'tenant':plan['tenantId'],'id':id,'appointment':value['appointmentId'],'granted_by':state['appointments']['dingqiming_bootstrap'],'scope':state['organizations']['ROOT'],'authority':code,'state':'ACTIVE','valid':True} for code,id in value['grants'].items()]
    from .assembly import SERVICE_CODES
    if set(value['grants'])!=set(SERVICE_CODES) or sorted(grants,key=lambda r:r['authority'])!=sorted(expected,key=lambda r:r['authority']):raise RuntimeError('Exact technical grant UUID, scope or validity differs')


def business_empty(root):
    schemas=('party','lead','opportunity','conflict','contract','transfer','responsibility','external_action','evidence')
    configuration={'opportunity.quote_approval_policy','opportunity.quote_approval_policy_signer','contract.approval_policy','contract.approval_policy_member'}
    names=[name for name in database.observe(root)['tables'] if name.split('.')[0] in schemas and name not in configuration]
    if any(not re.fullmatch(r'[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*',name) for name in names):raise RuntimeError('Unexpected business catalog identifier')
    # One read-only statement gives all transaction counts the same snapshot.
    if names and database.sql(root,'SELECT coalesce(sum(n),0) FROM ('+' UNION ALL '.join('SELECT count(*) AS n FROM '+name for name in names)+') t')!='0':raise RuntimeError('Initialization has unexpected business facts')
    return True


def initialization(root: Path,config: dict) -> dict:
    with journal.locked(root) as root:
        state=journal._read(root,root/'initialization.json');plan=journal._read(root,root/'identity/plan.json')
        if state['configDigest']!=digest(config) or state['operationId']!=plan['operationId']:raise RuntimeError('Original initialization configuration differs')
        tenant(root,plan['tenantId'],config['tenantName'])
        for mapping in ('organizations','principals','appointments'):
            for id in state[mapping].values():uuid.UUID(id)
        organizations=_rows(root,'SELECT organization_unit_id AS id,parent_organization_unit_id AS parent,unit_code AS code,display_name AS name,state FROM identity.organization_unit')
        expected=[{'id':state['organizations'][o['code']],'parent':None if o['parent'] is None else state['organizations'][o['parent']],'code':o['code'],'name':o['name'],'state':'ACTIVE'} for o in config['organizations']]
        if sorted(organizations,key=lambda r:r['id'])!=sorted(expected,key=lambda r:r['id']):raise RuntimeError('Exact seven organization facts differ')
        key=base64.b64decode(identity.secret_file(root/'secrets/subject-key.txt'))
        principals=_rows(root,"SELECT principal_id AS id,display_name AS name,state,identity_provider_code AS provider,encode(external_subject_hmac,'hex') AS subject FROM identity.principal WHERE principal_kind='HUMAN'")
        expected=[{'id':state['principals'][p['username']],'name':p['name'],'state':'ACTIVE','provider':'HAIHUA','subject':hmac.new(key,plan['subjects'][p['username']].encode(),hashlib.sha256).hexdigest()} for p in config['people']]
        if sorted(principals,key=lambda r:r['id'])!=sorted(expected,key=lambda r:r['id']):raise RuntimeError('Exact seventeen original HUMAN principals differ')
        appointments=_rows(root,"SELECT a.appointment_id AS id,a.principal_id AS principal,a.organization_unit_id AS organization,a.role_code AS role,a.state,(a.effective_from<=clock_timestamp() AND a.effective_until IS NULL) AS valid FROM identity.appointment a JOIN identity.principal p ON p.tenant_id=a.tenant_id AND p.principal_id=a.principal_id WHERE p.principal_kind='HUMAN'")
        expected=[{'id':state['appointments'][a['key']],'principal':state['principals'][p['username']],'organization':state['organizations'][a['organization']],'role':a['role'],'state':'ACTIVE','valid':True} for p in config['people'] for a in p['appointments']]
        if sorted(appointments,key=lambda r:r['id'])!=sorted(expected,key=lambda r:r['id']):raise RuntimeError('Exact twenty HUMAN appointments differ')
        grants=_rows(root,"SELECT g.grantee_appointment_id AS appointment,g.scope_organization_unit_id AS scope,g.authority_code AS authority,g.state,(g.valid_from<=clock_timestamp() AND g.valid_until IS NULL) AS valid FROM identity.authority_grant g JOIN identity.appointment a ON a.tenant_id=g.tenant_id AND a.appointment_id=g.grantee_appointment_id JOIN identity.principal p ON p.tenant_id=a.tenant_id AND p.principal_id=a.principal_id WHERE p.principal_kind='HUMAN'")
        expected=[{'appointment':state['appointments'][a['key']],'scope':state['organizations'][g['scope']],'authority':g['authority'],'state':'ACTIVE','valid':True} for p in config['people'] for a in p['appointments'] for g in a['grants']]
        order=lambda r:(r['appointment'],r['authority'],r['scope'])
        if sorted(grants,key=order)!=sorted(expected,key=order):raise RuntimeError('Exact HUMAN authority and scope inventory differs')
        technical(root,plan,state);business_empty(root);accounts=identity.verify(root);database.verify_schema(root,assert_role_boundaries=False)
        return {'status':'PASS','organizations':7,'humanPrincipals':17,'humanAppointments':20,'humanGrants':len(expected),'passwordUpdatesRequired':accounts['passwordUpdatesRequired'],'leadAssignment':'PENDING_ADMIN_ASSIGNMENT','templates':'CANDIDATE_NOT_APPROVED','businessFacts':0}


def runtime_ready(root: Path,descriptor: dict) -> dict:
    with journal.locked(root) as root:
        plan=journal._read(root,root/'identity/plan.json')
        target={'52-plus-2-r2-v20':'1060','52-plus-2-r2-v22':'1080'}.get(descriptor['schemaVersion'])
        if target is None:raise RuntimeError('Only a complete supported release can qualify health')
        observed=database.verify_schema(root,target,assert_role_boundaries=False)
        gate=observed['gate']
        if gate['operating_mode']!='ACTIVE' or gate['active_release_digest']!=descriptor['files'][descriptor['jar']] or gate['active_manifest_hash']!=descriptor['manifestHash']:raise RuntimeError('Actual active release gate differs')
        from .deployment import verify_ready
        full=verify_ready(root,descriptor)
        return {'status':'PASS','schemaVersion':descriptor['schemaVersion'],'descriptorDigest':descriptor['descriptorDigest'],'fullRuntime':full}


def ingress_ready(root: Path,descriptor: dict) -> dict:
    from . import runtime
    import time
    with journal.locked(root) as root:
        plan=journal._read(root,root/'identity/plan.json');launch=journal._read(root,root/'launch.json');resources=runtime.load(root)
        entry=launch['ingress']
        if launch['descriptorDigest']!=descriptor['descriptorDigest'] or resources['ingress']!=entry['name']:raise RuntimeError('Current HTTPS ingress differs')
        deadline=time.monotonic()+30
        while True:
            value=runtime.owned(root,'container',entry['name'])
            if value['Config']['Labels'].get('ols.launch')!=entry['digest'] or not value['State']['Running']:raise RuntimeError('Current ingress launch failed')
            try:
                response=identity.http(root,plan['origin']+'/')
                if response['status']==200 and hashlib.sha256(response['body'].encode('utf-8')).hexdigest()==descriptor['spaFiles']['index.html']:
                    return {'status':'PASS','entry':'VERIFIED_TLS_EXACT_SPA','descriptorDigest':descriptor['descriptorDigest']}
            except RuntimeError:pass
            if time.monotonic()>deadline:raise RuntimeError('Actual HTTPS ingress readiness unknown')
            time.sleep(.25)
