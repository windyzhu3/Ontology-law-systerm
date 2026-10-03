"""Read-only resource snapshot for the independent performance instance."""
from pathlib import Path
import json,subprocess,time
root=Path(__file__).resolve().parents[2]/'.superpowers/haihua-restore-perf_e1'
registry=json.loads((root/'processes.json').read_text())
pids=','.join(str(int(value['pid'])) for value in registry.values())
script='Get-Process -Id '+pids+' | Select-Object Id,ProcessName,CPU,WorkingSet64,PrivateMemorySize64,Handles | ConvertTo-Json -Compress'
def run(args):
 result=subprocess.run(args,capture_output=True,check=True)
 return result.stdout.decode('utf-8',errors='replace').strip()
prefix='ontology-law-haihua-restore-perf_e1'
processes=json.loads(run(['pwsh','-NoProfile','-Command',script]))
containers=run(['docker','stats','--no-stream','--format','{{json .}}',*[prefix+'-'+name for name in ['business-db','identity-db','keycloak','clamav']]])
connections=run(['docker','exec',prefix+'-business-db','psql','-U','postgres','-d','law_contract_runtime','-At','-c',"select json_build_object('application',application_name,'state',state,'count',count(*)) from pg_stat_activity where datname=current_database() group by application_name,state"])
queues=run(['docker','exec',prefix+'-business-db','psql','-U','postgres','-d','law_contract_runtime','-At','-c',"select json_build_object('queue','domain_event','status',status,'owner',queue_owner,'count',count(*)) from execution.domain_event_outbox group by status,queue_owner union all select json_build_object('queue','external_action','status',status,'owner',operation,'count',count(*)) from external_action.external_action_outbox group by status,operation"])
record={'atUnix':time.time(),'processes':processes,'containers':[json.loads(line) for line in containers.splitlines()],'connections':[json.loads(line) for line in connections.splitlines()],'queues':[json.loads(line) for line in queues.splitlines()]}
with (root/'performance-resources.jsonl').open('a',encoding='utf-8') as out:out.write(json.dumps(record)+'\n')
