"""Independent read-only counts for recorded P4 commands; never manufacture facts."""
import json,re,subprocess
from datetime import datetime
import prepare as p
finance=json.loads((p.RUNTIME/'finance-unknown-original.json').read_text())
intake=json.loads((p.RUNTIME/'intake-unknown-original.json').read_text())
verified=json.loads((p.RUNTIME/'P4-intake-original-verified.json').read_text())[0]
assert verified['commandId']==intake['commandId'] and verified['receipt']['outcome']=='SUCCEEDED'
ids=[finance['endpoint'].split('/')[4],intake['endpoint'].split('/')[4]]
assert all(re.fullmatch(r'[0-9a-f-]{36}',v) for v in ids)
cut=datetime.fromisoformat(json.loads((p.RUNTIME/'P4-finance-intake-release.json').read_text())['releasedAt']).isoformat()
sql=f"""select json_build_object(
'before',json_build_object('paymentConfirmations',(select count(*) from contract.payment_confirmation where confirmed_at<'{cut}'::timestamptz),'paymentReviews',(select count(*) from contract.payment_review where created_at<'{cut}'::timestamptz),'acceptedIntakes',(select count(*) from transfer.intake where outcome_code='ACCEPT' and created_at<'{cut}'::timestamptz)),
'after',json_build_object('paymentConfirmations',(select count(*) from contract.payment_confirmation),'paymentReviews',(select count(*) from contract.payment_review),'acceptedIntakes',(select count(*) from transfer.intake where outcome_code='ACCEPT')),
'finance',json_build_object('confirmations',(select count(*) from contract.payment_confirmation x join contract.contract c using(tenant_id,contract_id) where c.opportunity_id='{ids[0]}'::uuid),'amountMinor',(select sum(x.amount_minor) from contract.payment_confirmation x join contract.contract c using(tenant_id,contract_id) where c.opportunity_id='{ids[0]}'::uuid),'reviews',(select count(*) from contract.payment_review where opportunity_id='{ids[0]}'::uuid)),
'intake',json_build_object('acceptedRows',(select count(*) from transfer.intake where opportunity_id='{ids[1]}'::uuid and outcome_code='ACCEPT'),'uniqueMatters',(select count(distinct matter_id) from transfer.intake where opportunity_id='{ids[1]}'::uuid and outcome_code='ACCEPT'),'classifications',(select count(*) from transfer.classification where opportunity_id='{ids[1]}'::uuid)),
'allAcceptedUniqueMatters',(select count(distinct matter_id) from transfer.intake where outcome_code='ACCEPT'));"""
r=subprocess.run(['docker','exec',p.PREFIX+'-business-db','psql','-U','postgres','-d','law_contract_runtime','-At','-v','ON_ERROR_STOP=1','-c',sql],capture_output=True,check=True)
facts=json.loads(r.stdout)
assert facts['before']=={'paymentConfirmations':3,'paymentReviews':5,'acceptedIntakes':5}
assert facts['after']=={'paymentConfirmations':4,'paymentReviews':6,'acceptedIntakes':6}
assert facts['finance']=={'confirmations':1,'amountMinor':1000000,'reviews':1}
assert facts['intake']['acceptedRows']==facts['intake']['uniqueMatters']==1
assert facts['allAcceptedUniqueMatters']==6
intervals=[{'actor':'finance','start':finance['requestStartedAt'],'commit':finance['receipt']['completedAt']},{'actor':'intake','start':intake['requestStartedAt'],'commit':verified['receipt']['completedAt']}]
overlap=max(datetime.fromisoformat(v['start']) for v in intervals)<min(datetime.fromisoformat(v['commit']) for v in intervals)
assert overlap
report={'status':'PASS','scope':'Unique financial and intake facts, exact-key replay/conflict preserve facts, actual request-to-commit overlap. Intake unknown UI remains unverified.','intervals':intervals,'overlap':overlap,'facts':facts,'initialBeforeCountCorrection':'Original pre-count queried ACCEPTED instead of actual closed code ACCEPT; original file retained; correct cutoff reconstruction shown.'}
(p.RUNTIME/'P4-recovery-fact-reconciliation.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print('Independent P4 count/replay reconciliation PASS: one10000 payment, one new unique matter; prior five preserved; overlapping commits')
