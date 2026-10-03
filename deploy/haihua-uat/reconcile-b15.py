"""Tenant-filtered read-only proof that a new version does not waive the conflict."""
import json
import subprocess
import prepare as p

tenant='a1db3741-7ca1-480b-b3ea-8e75e8a4b4a2'
original=json.loads((p.RUNTIME/'HH-B15-20261001-R2-customer-receipts.json').read_text())
oid=original[0]['path'].split('/')[4]
sql=f"""BEGIN READ ONLY;
select json_build_object(
'versions',count(distinct v.contract_revision_id),
'reviewedVersions',count(distinct q.contract_revision_id),
'blockedDecisions',count(distinct d.revision_review_decision_id) filter(where d.decision_code='BLOCKED'),
'otherDecisions',count(distinct d.revision_review_decision_id) filter(where d.decision_code<>'BLOCKED'),
'approvals',(select count(*) from contract.revision_approval_request a join contract.contract_revision r using(tenant_id,contract_revision_id) join contract.contract c using(tenant_id,contract_id) where a.tenant_id='{tenant}' and c.opportunity_id='{oid}'),
'archives',(select count(*) from contract.signature_archive where tenant_id='{tenant}' and opportunity_id='{oid}'),
'intakes',(select count(*) from transfer.intake where tenant_id='{tenant}' and opportunity_id='{oid}'))
from contract.contract c join contract.contract_revision v using(tenant_id,contract_id)
left join contract.revision_review_request q using(tenant_id,contract_revision_id)
left join contract.revision_review_decision d on d.tenant_id=q.tenant_id and d.request_id=q.revision_review_request_id
where c.tenant_id='{tenant}' and c.opportunity_id='{oid}';
COMMIT;"""
r=subprocess.run(['docker','exec',p.PREFIX+'-business-db','psql','-U','postgres','-d','law_contract_runtime','-At','-v','ON_ERROR_STOP=1','-c',sql],capture_output=True)
if r.returncode:
    (p.RUNTIME/'B15-readonly-diagnostic.log').write_bytes(r.stderr)
    raise RuntimeError('Private read-only diagnostic preserved')
facts=json.loads(next(v for v in r.stdout.decode().splitlines() if v.startswith('{')))
assert facts=={'versions':2,'reviewedVersions':2,'blockedDecisions':2,'otherDecisions':0,'approvals':0,'archives':0,'intakes':0},facts
(p.RUNTIME/'B15-re-review-facts-PASS.json').write_text(json.dumps({'status':'PASS','businessWrites':0,'facts':facts},indent=2))
print('B15 independent immutable two-version/two-BLOCKED proof PASS; no approval/archive/intake')
