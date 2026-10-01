"""Summarize preserved actual samples without dropping failures or changing gates."""
from pathlib import Path
import json,math
from datetime import datetime
root=Path(__file__).resolve().parents[2]/'.superpowers/haihua-restore-perf_e1'
soak=json.loads((root/'performance-soak.json').read_text())
start=datetime.fromisoformat(soak['startedAt']).timestamp()
end=datetime.fromisoformat(soak['completedAt']).timestamp()
resources=[json.loads(line) for line in (root/'performance-resources.jsonl').read_text().splitlines() if line.strip()]
resources=[r for r in resources if start<=r['atUnix']<=end]
assert resources
processes={}
for r in resources:
    for p in r['processes']:
        key=str(p['Id']);processes.setdefault(key,[]).append(p['WorkingSet64'])
result={'measuredResourceSnapshots':len(resources),'processWorkingSetBytes':{k:{'first':v[0],'last':v[-1],'minimum':min(v),'maximum':max(v)} for k,v in processes.items()},'queueStart':resources[0]['queues'],'queueEnd':resources[-1]['queues'],'connectionsStart':resources[0]['connections'],'connectionsEnd':resources[-1]['connections'],'pageGroups':[]}
for filename in ['performance-page-readiness-first-attempt.json','performance-page-readiness-before-HH006.json','performance-page-readiness.json']:
    if not (root/filename).exists():continue
    rows=json.loads((root/filename).read_text())
    for kind in sorted({r['kind'] for r in rows}):
        group=[r for r in rows if r['kind']==kind];values=sorted(r['ms'] for r in group)
        result['pageGroups'].append({'source':filename,'kind':kind,'count':len(values),'actors':sorted({r['actor'] for r in group}),'p95Ms':values[math.ceil(len(values)*.95)-1],'maximumMs':max(values),'completeExpected100':len(values)==100})
(root/'performance-page-resource-summary.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
print(json.dumps(result,indent=2))
