"""Read-only proof of exact duplicate-payment rejection, no second amount."""
import json,subprocess
import prepare as p
x=json.loads((p.RUNTIME/'HH-G03-20261001-R2-payment-HH-B19-FIRST-4000-CONFIRM-receipt.json').read_text())
assert x['status']==409 and x['receipt']['code']=='PAYMENT_ALREADY_RECORDED'
tenant='a1db3741-7ca1-480b-b3ea-8e75e8a4b4a2';oid=x['requestBody']['responsibilityBasis']['id']
sql=f"""BEGIN READ ONLY;
select json_build_object('rejectedReceipts',(select count(*) from execution.command_receipt r join execution.command_execution_slot s using(tenant_id,command_execution_slot_id) where r.tenant_id='{tenant}' and s.command_id='{x['commandId']}' and r.outcome='REJECTED' and r.rejection_code='PAYMENT_ALREADY_RECORDED' and r.result_fact_id is null),
'confirmations',(select count(*) from contract.payment_confirmation p join contract.contract c using(tenant_id,contract_id) where p.tenant_id='{tenant}' and c.opportunity_id='{oid}'),
'acceptedMatters',(select count(distinct matter_id) from transfer.intake where tenant_id='{tenant}' and opportunity_id='{oid}' and outcome_code='ACCEPT'));
COMMIT;"""
r=subprocess.run(['docker','exec',p.PREFIX+'-business-db','psql','-U','postgres','-d','law_contract_runtime','-At','-v','ON_ERROR_STOP=1','-c',sql],capture_output=True,check=True)
facts=json.loads(next(line for line in r.stdout.decode().splitlines() if line.startswith('{')))
assert facts=={'rejectedReceipts':1,'confirmations':0,'acceptedMatters':1}
(p.RUNTIME/'B18-duplicate-facts-PASS.json').write_text(json.dumps({'status':'PASS','facts':facts,'scope':'Cross-contract separate accepted material, same configured account/transaction, rejected; original G03 matter preserved; original B19 payments checked separately'},indent=2),encoding='utf-8')
print('Independent duplicate payment rejection PASS, zero G03 confirmation, original matter preserved')
