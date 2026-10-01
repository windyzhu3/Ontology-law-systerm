"""Read-only independent B19 payment gate and unique intake reconciliation."""
import json, subprocess, sys
import prepare as p
mode=sys.argv[1]
if mode not in ('before','after'):raise RuntimeError('Explicit before/after required')
c=json.loads((p.RUNTIME/'B19-gate4000-context.json').read_text())
oid=c['opportunity']['id'];tenant='a1db3741-7ca1-480b-b3ea-8e75e8a4b4a2'
sql=f"""BEGIN READ ONLY;
select json_build_object(
'payments',(select count(*) from contract.payment_confirmation x join contract.contract y using(tenant_id,contract_id) where x.tenant_id='{tenant}' and y.opportunity_id='{oid}'),
'amountMinor',(select sum(x.amount_minor) from contract.payment_confirmation x join contract.contract y using(tenant_id,contract_id) where x.tenant_id='{tenant}' and y.opportunity_id='{oid}'),
'archives',(select count(*) from contract.signature_archive where tenant_id='{tenant}' and opportunity_id='{oid}'),
'executionVerifications',(select count(*) from contract.execution_verification where tenant_id='{tenant}' and opportunity_id='{oid}'),
'acceptedIntakes',(select count(*) from transfer.intake where tenant_id='{tenant}' and opportunity_id='{oid}' and outcome_code='ACCEPT'),
'uniqueMatters',(select count(distinct matter_id) from transfer.intake where tenant_id='{tenant}' and opportunity_id='{oid}' and outcome_code='ACCEPT'),
'allAcceptedMatters',(select count(distinct matter_id) from transfer.intake where tenant_id='{tenant}' and outcome_code='ACCEPT'),
'classifications',(select count(*) from transfer.classification where tenant_id='{tenant}' and opportunity_id='{oid}'));
COMMIT;"""
r=subprocess.run(['docker','exec',p.PREFIX+'-business-db','psql','-U','postgres','-d','law_contract_runtime','-At','-v','ON_ERROR_STOP=1','-c',sql],capture_output=True,check=True)
facts=json.loads(next(line for line in r.stdout.decode().splitlines() if line.startswith('{')))
assert facts['payments']==2 and facts['amountMinor']==1000000
assert facts['archives']==facts['executionVerifications']==1
if mode=='before':
 assert facts['acceptedIntakes']==facts['uniqueMatters']==facts['classifications']==0
else:
 before=json.loads((p.RUNTIME/'B19-facts-before.json').read_text())['facts']
 recovery=json.loads((p.RUNTIME/'B19-intake-unknown-PASS.json').read_text())
 assert recovery['actualPostCount']==1 and not recovery['recoveryReplayed']
 assert facts['acceptedIntakes']==facts['uniqueMatters']==facts['classifications']==1
 assert facts['allAcceptedMatters']==before['allAcceptedMatters']+1
(p.RUNTIME/f'B19-facts-{mode}.json').write_text(json.dumps({'status':'PASS','facts':facts,'scope':'Owned tenant read-only original immutable facts; no SQL fact writes'},indent=2),encoding='utf-8')
print('B19 independent '+mode+' facts PASS')
