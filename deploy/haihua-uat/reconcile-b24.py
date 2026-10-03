"""Read-only exact original-command and unique first-intake reconciliation."""
import json
import subprocess
import prepare as p

tenant='a1db3741-7ca1-480b-b3ea-8e75e8a4b4a2'
original=json.loads((p.RUNTIME/'B24-first-intake-concurrent-original.json').read_text())
observed=json.loads((p.RUNTIME/'B24-first-intake-concurrent-PASS.json').read_text())
oid=original['endpoint'].split('/')[4]
winner=next(x['commandId'] for x in observed['results'] if x['status']==200)
loser=next(x['commandId'] for x in observed['results'] if x['status']==412)
sql=f"""BEGIN READ ONLY;
select json_build_object(
'winnerReceipts',(select count(*) from execution.command_receipt r join execution.command_execution_slot s using(tenant_id,command_execution_slot_id) join transfer.intake i on i.tenant_id=r.tenant_id and i.intake_id=r.result_fact_id where r.tenant_id='{tenant}' and s.command_id='{winner}' and r.outcome='SUCCEEDED' and r.result_fact_type='transfer.intake' and i.opportunity_id='{oid}'),
'loserReceipts',(select count(*) from execution.command_receipt r join execution.command_execution_slot s using(tenant_id,command_execution_slot_id) where r.tenant_id='{tenant}' and s.command_id='{loser}' and r.outcome='REJECTED' and r.rejection_code='STALE_SUBJECT' and r.result_fact_id is null),
'acceptedIntakes',(select count(*) from transfer.intake where tenant_id='{tenant}' and opportunity_id='{oid}' and outcome_code='ACCEPT'),
'uniqueMatters',(select count(distinct matter_id) from transfer.intake where tenant_id='{tenant}' and opportunity_id='{oid}' and outcome_code='ACCEPT'),
'matterId',(select matter_id from transfer.intake where tenant_id='{tenant}' and opportunity_id='{oid}' and outcome_code='ACCEPT'),
'classifications',(select count(*) from transfer.classification where tenant_id='{tenant}' and opportunity_id='{oid}'),
'allAcceptedMatters',(select count(distinct matter_id) from transfer.intake where tenant_id='{tenant}' and outcome_code='ACCEPT'));
COMMIT;"""
r=subprocess.run(['docker','exec',p.PREFIX+'-business-db','psql','-U','postgres','-d','law_contract_runtime','-At','-v','ON_ERROR_STOP=1','-c',sql],capture_output=True)
if r.returncode:
    (p.RUNTIME/'B24-readonly-diagnostic.log').write_bytes(r.stderr)
    raise RuntimeError('Private read-only diagnostic preserved')
facts=json.loads(next(x for x in r.stdout.decode().splitlines() if x.startswith('{')))
assert all(facts[k]==1 for k in ['winnerReceipts','loserReceipts','acceptedIntakes','uniqueMatters','classifications'])
assert facts['allAcceptedMatters']==8 and facts['matterId']
(p.RUNTIME/'B24-first-intake-facts-PASS.json').write_text(json.dumps({'status':'PASS','businessWrites':0,'facts':facts},indent=2))
print('Independent concurrent first intake exact winner/loser receipts, one intake/Matter/classification PASS; total Matters eight')
