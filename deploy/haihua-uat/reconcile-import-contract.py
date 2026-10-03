"""Independent immutable fact lookup for original recorded recovery commands."""
import json,subprocess,re
import prepare as p
tenant='a1db3741-7ca1-480b-b3ea-8e75e8a4b4a2'
writes=[]
for name in ['HH-B04-import-PASS.json','HH-B04-S2-import-PASS.json']:
 writes+=json.loads((p.RUNTIME/name).read_text())['writes']
writes+=json.loads((p.RUNTIME/'HH-B04-S2-correction-requests.json').read_text())
keys=[v['commandId'] for v in writes]
assert len(keys)==len(set(keys))==8
contract=json.loads((p.RUNTIME/'contract-unknown-original.json').read_text())
assert contract['receipt']['outcome']=='SUCCEEDED'
assert all(re.fullmatch('[0-9a-f-]{36}',v) for v in keys+[contract['commandId']])
ids=','.join("'"+v+"'::uuid" for v in keys)
sql=f"""BEGIN READ ONLY;
select json_build_object('import', (select json_build_object('receipts',count(*),'uniqueLeads',count(distinct l.lead_id),'uniqueScopedSourceKeys',count(distinct (l.source_account_code,l.source_record_key_digest)),'allSucceeded',bool_and(r.outcome='SUCCEEDED')) from execution.command_receipt r join execution.command_execution_slot s using(tenant_id,command_execution_slot_id) join lead.lead l on l.tenant_id=r.tenant_id and l.lead_id=r.result_fact_id where r.tenant_id='{tenant}' and s.command_id in ({ids})),
'contract',(select json_build_object('receipts',count(*),'reviewDecisions',count(distinct d.revision_review_decision_id),'allSucceeded',bool_and(r.outcome='SUCCEEDED')) from execution.command_receipt r join execution.command_execution_slot s using(tenant_id,command_execution_slot_id) join contract.revision_review_decision d on d.tenant_id=r.tenant_id and d.revision_review_decision_id=r.result_fact_id where r.tenant_id='{tenant}' and s.command_id='{contract['commandId']}'));
COMMIT;"""
r=subprocess.run(['docker','exec',p.PREFIX+'-business-db','psql','-U','postgres','-d','law_contract_runtime','-At','-v','ON_ERROR_STOP=1','-c',sql],capture_output=True)
if r.returncode:
 (p.RUNTIME/'import-contract-readonly-diagnostic.txt').write_bytes(r.stderr)
 raise RuntimeError('Read-only SQL failed; diagnostic preserved privately')
facts=json.loads(next(line for line in r.stdout.decode().splitlines() if line.startswith('{')))
assert facts['import']=={'receipts':8,'uniqueLeads':8,'uniqueScopedSourceKeys':8,'allSucceeded':True}
assert facts['contract']=={'receipts':1,'reviewDecisions':1,'allSucceeded':True}
(p.RUNTIME/'import-contract-facts-PASS.json').write_text(json.dumps({'status':'PASS','facts':facts,'businessWrites':0},indent=2),encoding='utf-8')
print('Independent imports 8 exact leads/source keys, original contract recovery one review decision PASS')
