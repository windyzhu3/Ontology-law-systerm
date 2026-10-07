"""Bounded trusted business settings and existing approval-policy facts only."""
from pathlib import Path
import json
import uuid
from . import config as configuration,assembly,bundle,database,journal,runtime,verify

STAGES={'AWAIT_REVIEW','AWAIT_VERIFICATION','ARCHIVE','CHECK_RECEIPT','REVIEW_TRANSFER','INTAKE','CLASSIFY'}


def settings(config: dict,state: dict,tenant_id: str) -> dict:
    uuid.UUID(tenant_id)
    for mapping in ('principals','appointments','organizations'):
        for value in state[mapping].values():uuid.UUID(value)
    props={}
    for i,source in enumerate(config['intakeSources']):
        prefix=f'ols.api.human-intake-bindings[{i}].'
        props.update({prefix+'tenant-id':tenant_id,prefix+'principal-id':state['principals'][source['username']],prefix+'source-account-code':source['account']})
        prefix=f"ols.api.sources[{source['account']}]."
        props.update({prefix+'assignment-mode':'MANUAL',prefix+'routing-organization-root-codes[0]':source['organization'],prefix+'routing-supervisor-root-code':source['organization'],prefix+'source-intake-root-code':source['organization'],prefix+'business-timezone':'Asia/Shanghai'})
        prefix=f'ols.api.intake-sources[{i}].'
        for key,value in {'source-account-code':source['account'],'display-name':source['name'],'source-channel-code':source['channel'],'service-category-code':source['category'],'jurisdiction-code':source['jurisdiction'],'urgency-code':source['urgency']}.items():props[prefix+key]=value
    seen=set()
    for i,route in enumerate(config['responsibilityRoutes']):
        key=(route['sourceOrganization'],route['stage'])
        if route['stage'] not in STAGES or key in seen:raise ValueError('Only unique approved business responsibility stages permitted')
        seen.add(key)
        prefix=f'ols.api.responsibility-routes[{i}].'
        props.update({prefix+'tenant-id':tenant_id,prefix+'source-organization-id':state['organizations'][route['sourceOrganization']],prefix+'stage-code':route['stage'],prefix+'appointment-id':state['appointments'][route['appointment']]})
    props[f'ols.api.tenant-keys[{tenant_id}].payment.account-code']='HAIHUA_ACCEPTANCE'
    props[f'ols.api.tenant-keys[{tenant_id}].payment.account-label']=config['defaults']['receiptLabel']
    props[f'ols.api.tenant-keys[{tenant_id}].transfer-destination-organization-id']=state['organizations']['CASE_ADMIN']
    return props


def policy_plan(config,state,tenant_id):
    uuid.UUID(tenant_id);result=[]
    for policy in config['approvalPolicies']:
        if policy['quoteCode']!='R2_QUOTE_APPROVAL_V1' or policy['contractCode']!='R2_CONTRACT_APPROVAL_V1' or len(policy['approvers'])!=1:raise RuntimeError('Existing reviewed approval policies required')
        organization=state['organizations'][policy['organization']];approver=state['appointments'][policy['approvers'][0]]
        uuid.UUID(organization);uuid.UUID(approver)
        result.append({'organization':organization,'approver':approver,'quotePolicyId':str(uuid.uuid4()),'quoteSignerId':str(uuid.uuid4()),'contractPolicyId':str(uuid.uuid4()),'contractMemberId':str(uuid.uuid4()),'version':1,
            'digest':configuration.digest({'organization':organization,'mode':'REQUIRE_APPROVAL','requirement':'LEGAL','approver':approver})})
    return result


def policy_statement(tenant_id,plan):
    uuid.UUID(tenant_id);statement='BEGIN;'
    for p in plan:
        for key in ('organization','approver','quotePolicyId','quoteSignerId','contractPolicyId','contractMemberId'):uuid.UUID(p[key])
        expected=configuration.digest({'organization':p['organization'],'mode':'REQUIRE_APPROVAL','requirement':'LEGAL','approver':p['approver']})
        if p['version']!=1 or p['digest']!=expected:raise RuntimeError('Original policy digest or version differs')
        t=tenant_id;o=p['organization'];a=p['approver'];q=p['quotePolicyId'];c=p['contractPolicyId']
        statement+=f" INSERT INTO opportunity.quote_approval_policy(tenant_id,quote_approval_policy_id,revision,organization_unit_id,policy_code,policy_version,mode,created_at) VALUES('{t}','{q}',0,'{o}','R2_QUOTE_APPROVAL_V1',1,'REQUIRE_APPROVAL',clock_timestamp());"
        statement+=f" INSERT INTO opportunity.quote_approval_policy_signer(tenant_id,quote_approval_policy_signer_id,revision,policy_id,appointment_id) VALUES('{t}','{p['quoteSignerId']}',0,'{q}','{a}');"
        statement+=f" INSERT INTO contract.approval_policy(tenant_id,approval_policy_id,organization_unit_id,policy_code,policy_version,mode,policy_digest,created_at) VALUES('{t}','{c}','{o}','R2_CONTRACT_APPROVAL_V1',1,'REQUIRE_APPROVAL',decode('{p['digest']}','hex'),clock_timestamp());"
        statement+=f" INSERT INTO contract.approval_policy_member(tenant_id,approval_policy_member_id,policy_id,requirement_code,appointment_id,created_at) VALUES('{t}','{p['contractMemberId']}','{c}','LEGAL','{a}',clock_timestamp());"
    return statement+' COMMIT;'


POLICY_QUERIES={
 'quotes':'SELECT tenant_id AS tenant,quote_approval_policy_id AS id,organization_unit_id AS organization,policy_code AS code,policy_version AS version,mode,revision FROM opportunity.quote_approval_policy',
 'signers':'SELECT tenant_id AS tenant,quote_approval_policy_signer_id AS id,policy_id AS policy,appointment_id AS appointment,revision FROM opportunity.quote_approval_policy_signer',
 'contracts':"SELECT tenant_id AS tenant,approval_policy_id AS id,organization_unit_id AS organization,policy_code AS code,policy_version AS version,mode,encode(policy_digest,'hex') AS digest FROM contract.approval_policy",
 'members':'SELECT tenant_id AS tenant,approval_policy_member_id AS id,policy_id AS policy,requirement_code AS requirement,appointment_id AS appointment FROM contract.approval_policy_member'}


def policy_inventory(root):return {key:sorted(verify._rows(root,query),key=lambda r:r['id']) for key,query in POLICY_QUERIES.items()}


def policy_expected(tenant_id,plan):
    result={key:[] for key in POLICY_QUERIES}
    for p in plan:
        result['quotes'].append({'tenant':tenant_id,'id':p['quotePolicyId'],'organization':p['organization'],'code':'R2_QUOTE_APPROVAL_V1','version':1,'mode':'REQUIRE_APPROVAL','revision':0})
        result['signers'].append({'tenant':tenant_id,'id':p['quoteSignerId'],'policy':p['quotePolicyId'],'appointment':p['approver'],'revision':0})
        result['contracts'].append({'tenant':tenant_id,'id':p['contractPolicyId'],'organization':p['organization'],'code':'R2_CONTRACT_APPROVAL_V1','version':1,'mode':'REQUIRE_APPROVAL','digest':p['digest']})
        result['members'].append({'tenant':tenant_id,'id':p['contractMemberId'],'policy':p['contractPolicyId'],'requirement':'LEGAL','appointment':p['approver']})
    return {key:sorted(rows,key=lambda r:r['id']) for key,rows in result.items()}


def ensure_policies(root,tenant_id,plan):
    with journal.locked(root) as root:
        path=root/'business/configuration.json';state=journal._read(root,path)
        if state['operationId']!=journal.current(root)['operationId'] or state['policies']!=plan:raise RuntimeError('Original policy configuration differs')
        actual=policy_inventory(root);expected=policy_expected(tenant_id,plan)
        if actual!=expected:
            if set(actual)!=set(expected) or any(actual.values()):raise RuntimeError('Foreign or partial policy inventory is not adopted')
            if state['policyState']=='VERIFIED':raise RuntimeError('Verified original policies disappeared')
            state['policyState']='UNKNOWN';journal._write(root,path,state)
            database.sql(root,policy_statement(tenant_id,plan))
            if policy_inventory(root)!=expected:raise RuntimeError('Original policy effects remain unknown')
        state['policyState']='VERIFIED';journal._write(root,path,state)


def template_inventory(directory,config):
    directory=Path(directory);manifest=json.loads((directory/'manifest.json').read_text(encoding='utf-8'))
    if {t['code'] for t in manifest['templates']}!=set(config['contractTemplates']) or len(manifest['templates'])!=3:raise RuntimeError('Exact three candidate templates required')
    for template in manifest['templates']:
        if template['status']!='CANDIDATE_NOT_APPROVED' or template['candidateVersion']!=1 or set(template['files'])!={template['code']+suffix for suffix in ('.docx','.pdf','.fields.json')}:raise RuntimeError('Only original candidate files permitted')
        for name,expected in template['files'].items():
            path=directory/name
            if path.resolve()!=path.absolute() or bundle.sha(path)!=expected:raise RuntimeError('Candidate template bytes differ')
    if bundle.sha(directory/'sources.json')!=manifest['sourcesSha256']:raise RuntimeError('Original candidate source record differs')
    return {'status':'CANDIDATE_NOT_APPROVED','manifestHash':bundle.sha(directory/'manifest.json'),'files':bundle.inventory(directory)}


def verify_configuration(root,config):
    with journal.locked(root) as root:
        state=journal._read(root,root/'initialization.json');saved=journal._read(root,root/'business/configuration.json');plan=journal._read(root,root/'identity/plan.json')
        mappings={key:state[key] for key in ('principals','appointments','organizations')}
        if saved['configDigest']!=configuration.digest(config) or saved['mappingDigest']!=configuration.digest(mappings) or saved['settings']!=settings(config,state,plan['tenantId']):raise RuntimeError('Exact original business settings differ')
        if saved['policyState']!='VERIFIED' or policy_inventory(root)!=policy_expected(plan['tenantId'],saved['policies']):raise RuntimeError('Exact original approval policies differ')
        for name,expected in saved['files'].items():
            path=root/name
            if path.resolve()!=path.absolute() or bundle.sha(path)!=expected:raise RuntimeError('Original trusted configuration file changed')
        return {'status':'PASS','humanSourceBindings':17,'responsibilityRoutes':14,'departmentApprovalPolicies':2,'templates':'CANDIDATE_NOT_APPROVED','leadAssignment':'PENDING_ADMIN_ASSIGNMENT'}


def install(root: Path,operation_id: str,config: dict) -> dict:
    with journal.locked(root) as root:
        op=journal.read(root,operation_id)
        if op['kind']!='initialize' or journal.current(root)['operationId']!=operation_id or op['configDigest']!=configuration.digest(config):raise RuntimeError('Original initialization configuration required')
        verify.initialization(root,config)
        state=journal._read(root,root/'initialization.json');plan=journal._read(root,root/'identity/plan.json');path=root/'business/configuration.json'
        mappings={key:state[key] for key in ('principals','appointments','organizations')};props=settings(config,state,plan['tenantId'])
        if path.exists():
            saved=journal._read(root,path)
            if saved['operationId']!=operation_id or saved['configDigest']!=configuration.digest(config) or saved['mappingDigest']!=configuration.digest(mappings) or saved['settings']!=props:raise RuntimeError('Original business configuration changed')
        else:
            if any(policy_inventory(root).values()):raise RuntimeError('Existing policies are not first initialization data')
            saved={'operationId':operation_id,'configDigest':configuration.digest(config),'mappingDigest':configuration.digest(mappings),'settings':props,'policies':policy_plan(config,state,plan['tenantId']),'policyState':'PLANNED'}
            journal._write(root,path,saved)
        ensure_policies(root,plan['tenantId'],saved['policies'])
        installed=journal._read(root,root/'installed-candidate.json');descriptor=installed['descriptor'];bundle.verify(descriptor,Path(installed['directory']))
        api=assembly.properties(root,descriptor,plan,journal._read(root,root/'assembly/service.json'))
        api={k:v for k,v in api.items() if not k.startswith(('ols.api.human-intake-bindings[','ols.api.intake-sources[','ols.api.responsibility-routes['))}
        api.update(props);api[f"ols.api.tenant-keys[{plan['tenantId']}].payment.transaction-hmac"]=assembly._secrets(root)['payment-key']
        representative=config['defaults']['representative'];defaults=dict(config['defaults'],representativePrincipalId=state['principals'][representative],representativeName=next(p['name'] for p in config['people'] if p['username']==representative),signingAuthority='REQUIRES_PER_CONTRACT_VERIFICATION')
        templates=template_inventory(Path(runtime.load(root)['repo'])/'deploy/linux/templates',config)
        files={'config/business-api.properties':('\n'.join(k+'='+str(v) for k,v in api.items())+'\n').encode('ascii','backslashreplace'),'config/business-defaults.json':configuration.canonical(defaults),'config/template-candidates.json':configuration.canonical(templates)}
        saved=journal._read(root,path)
        for name,data in files.items():
            file=root/name
            if file.exists() and file.read_bytes()!=data:raise RuntimeError('Original trusted configuration file differs')
            if not file.exists():runtime.private_file(file,data)
        hashes={name:bundle.sha(root/name) for name in files}
        if 'files' in saved and saved['files']!=hashes:raise RuntimeError('Original configuration hashes changed')
        saved.update(files=hashes,templateCandidates=templates);journal._write(root,path,saved)
        result=verify_configuration(root,config);journal.record(root,operation_id,{'phase':'BUSINESS_CONFIG_READY','configurationDigest':configuration.digest(saved)})
        return result
