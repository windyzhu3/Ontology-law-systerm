"""Trusted synthetic template/firm binding only, after real scanned material acceptance."""
import uuid,json,hashlib
from prepare import ROOT,RUNTIME,deployment,sql,save
if (RUNTIME/'synthetic-template.json').exists():raise SystemExit('preserve sealed template')
tenant=str(uuid.UUID(deployment()['tenantId']));m=json.loads((RUNTIME/'account-map.json').read_text());context=json.loads((RUNTIME/'template-material-context.json').read_text())
materials=[x for x in context['data']['versions'] if x['fileName']=='synthetic-fillable-template.pdf']
if len(materials)!=1:raise SystemExit('require exactly one real accepted template material')
material=str(uuid.UUID(materials[0]['selector']['id']));actor=str(uuid.UUID(m['users']['sales_manager01']['appointmentId']))
template,party,profile,binding=[str(uuid.uuid4()) for _ in range(4)];digest=hashlib.sha256((ROOT/'.local/t09/synthetic-fillable-template.pdf').read_bytes()).hexdigest()
statement=f"""BEGIN;
SELECT contract.fn_assert_r2_material('{tenant}','{material}',decode('{digest}','hex'),NULL);
INSERT INTO party.party(tenant_id,party_id,party_type,canonical_name,status,revision) VALUES('{tenant}','{party}','ORGANIZATION','海华律师事务所（合成测试，不用于签约）','ACTIVE',0);
INSERT INTO party.profile_version(tenant_id,profile_version_id,revision,party_id,party_revision,party_type,canonical_name,created_by_appointment_id,created_at) VALUES('{tenant}','{profile}',0,'{party}',0,'ORGANIZATION','海华律师事务所（合成测试，不用于签约）','{actor}',clock_timestamp());
INSERT INTO contract.template_version(tenant_id,template_version_id,document_code,version_no,evidence_version_id,body_sha256,approved_by_appointment_id,approved_at,created_at) VALUES('{tenant}','{template}','HH_UAT_SYNTHETIC_NOT_LEGAL',1,'{material}',decode('{digest}','hex'),'{actor}',clock_timestamp(),clock_timestamp());
INSERT INTO contract.template_signing_party(tenant_id,template_signing_party_id,template_version_id,party_id,party_revision,profile_version_id,party_snapshot_digest,role_code,created_by_appointment_id,created_at) VALUES('{tenant}','{binding}','{template}','{party}',0,'{profile}',sha256(convert_to('{profile}','UTF8')),'FIRM','{actor}',clock_timestamp());
COMMIT;"""
sql(statement,'synthetic-template-infrastructure')
save('synthetic-template.json',{'basis':'Approved UAT trusted synthetic configuration, not formally audited legal template','templateId':template,'firmPartyId':party,'firmProfileId':profile,'bindingId':binding,'materialId':material,'sha256':digest,'approvedBy':actor,'source':'.local/t09/synthetic-fillable-template.pdf','fields':['customer','parties','scope','fees','payment','signing','transfer','basis']})
print('Synthetic template sealed against real accepted material; formal template applicability NOT_RUN')
