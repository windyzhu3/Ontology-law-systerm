"""Approved E1 deployment configuration only; never creates workflow outcomes."""
import uuid,json,hashlib
from prepare import deployment,RUNTIME,sql,save
d=deployment();m=json.loads((RUNTIME/'account-map.json').read_text())
if not (RUNTIME/'admin-setup-result.json').exists():raise SystemExit('complete admin setup first')
if (RUNTIME/'approval-policies.json').exists():raise SystemExit('preserve sealed policies; do not repeat')
tenant=str(uuid.UUID(d['tenantId']));records=[];statements='BEGIN;'
for dept,name in [('SALES_1','sales_manager01'),('SALES_2','sales_manager02')]:
 org=str(uuid.UUID(m['organizations'][dept]));actor=str(uuid.UUID(m['users'][name]['appointmentId']))
 q=str(uuid.uuid4());c=str(uuid.uuid4())
 digest=hashlib.sha256(json.dumps({'organization':org,'mode':'REQUIRE_APPROVAL','requirement':'LEGAL','approver':actor},sort_keys=True,separators=(',',':')).encode()).hexdigest()
 statements+=f"INSERT INTO opportunity.quote_approval_policy(tenant_id,quote_approval_policy_id,revision,organization_unit_id,policy_code,policy_version,mode,created_at) VALUES('{tenant}','{q}',0,'{org}','R2_QUOTE_APPROVAL_V1',1,'REQUIRE_APPROVAL',clock_timestamp());"
 statements+=f"INSERT INTO opportunity.quote_approval_policy_signer(tenant_id,quote_approval_policy_signer_id,revision,policy_id,appointment_id) VALUES('{tenant}','{uuid.uuid4()}',0,'{q}','{actor}');"
 statements+=f"INSERT INTO contract.approval_policy(tenant_id,approval_policy_id,organization_unit_id,policy_code,policy_version,mode,policy_digest,created_at) VALUES('{tenant}','{c}','{org}','R2_CONTRACT_APPROVAL_V1',1,'REQUIRE_APPROVAL',decode('{digest}','hex'),clock_timestamp());"
 statements+=f"INSERT INTO contract.approval_policy_member(tenant_id,approval_policy_member_id,policy_id,requirement_code,appointment_id,created_at) VALUES('{tenant}','{uuid.uuid4()}','{c}','LEGAL','{actor}',clock_timestamp());"
 records.append({'department':dept,'quotePolicyId':q,'contractPolicyId':c,'approver':name,'appointmentId':actor,'mode':'REQUIRE_APPROVAL','digest':digest})
sql(statements+'COMMIT;','approved-deployment-policies')
save('approval-policies.json',{'basis':'Approved UAT E1 trusted deployment configuration; no administrative policy UI exists; no workflow fact or outcome inserted','policies':records})
print('Two departments sealed to their own manager for quote/contract approval')
