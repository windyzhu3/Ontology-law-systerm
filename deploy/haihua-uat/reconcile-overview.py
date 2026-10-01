"""Read-only source reconciliation at the recorded UI snapshot; no business writes."""
import json,subprocess
from datetime import datetime
import prepare as p

observations=json.loads((p.RUNTIME/'overview-ui-results.json').read_text())
sql="""select json_build_object(
'leads',(select json_agg(json_build_object('id',lead_id,'at',created_at)) from lead.lead),
'opportunities',(select json_agg(json_build_object('id',opportunity_id,'at',created_at)) from opportunity.opportunity),
'signedContracts',(select json_agg(json_build_object('id',c.contract_id,'at',a.first_at)) from contract.contract c join lateral (select min(s.created_at) first_at from contract.signature_archive s join contract.signature_arrangement x on x.tenant_id=s.tenant_id and x.signature_arrangement_id=s.arrangement_id join contract.contract_revision v on v.tenant_id=x.tenant_id and v.contract_revision_id=x.contract_revision_id where s.tenant_id=c.tenant_id and v.contract_id=c.contract_id) a on a.first_at is not null),
'acceptedMatters',(select json_agg(json_build_object('id',matter_id,'at',first_at)) from (select matter_id,min(created_at) first_at from transfer.intake where outcome_code='ACCEPT' group by matter_id) x),
'overdueTasks',(select json_agg(json_build_object('id',task_occurrence_id,'at',original_sla_due_at,'state',state)) from responsibility.task_occurrence));"""
r=subprocess.run(['docker','exec',p.PREFIX+'-business-db','psql','-U','postgres','-d','law_contract_runtime','-At','-v','ON_ERROR_STOP=1','-c',sql],capture_output=True,check=True)
sources=json.loads(r.stdout);results=[]
for actor in observations:
    cutoff=datetime.fromisoformat(actor['asOf'])
    start=datetime.fromisoformat(actor['month']+'-01T00:00:00+08:00')
    for metric in actor['metrics']:
        if metric['status']=='FORBIDDEN':
            assert metric['count'] is None
            continue
        raw={x['id']:x for x in sources[metric['key']] or []}
        for item in metric['items']:
            source=raw[item['id']]
            at=datetime.fromisoformat(source['at'])
            assert at==datetime.fromisoformat(item['occurredAt'])
            if metric['key']=='overdueTasks':assert at<cutoff and source['state']=='OPEN'
            else:assert start<=at<cutoff
        results.append({'actor':actor['username'],'metric':metric['key'],'count':metric['count'],'sourceMatches':len(metric['items'])})
for metric in ['leads','opportunities','signedContracts','acceptedMatters']:
    managers=[a for a in observations if a['username'] in ['sales_manager01','sales_manager02']]
    visible=[x['id'] for a in managers for m in a['metrics'] if m['key']==metric for x in m.get('items',[])]
    cutoff=min(datetime.fromisoformat(a['asOf']) for a in managers)
    start=datetime.fromisoformat(managers[0]['month']+'-01T00:00:00+08:00')
    expected={x['id'] for x in sources[metric] or [] if start<=datetime.fromisoformat(x['at'])<cutoff}
    assert len(visible)==len(set(visible)) and set(visible)==expected
report={'status':'PASS','scope':'Every authorized detail maps to original fact/time; two department managers partition all four monthly source sets without overlap/omission. Overdue source/state/time verified per visible row; forbidden counts stay null.','results':results}
(p.RUNTIME/'overview-source-reconciliation-PASS.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print('Independent overview facts/time and two-manager four-metric partition PASS')
