"""Summarize all preserved samples only after the full controlled duration."""
from pathlib import Path
import json,math
root=Path(__file__).resolve().parents[2]/'.superpowers/haihua-restore-perf_e1'
data=json.loads((root/'performance-soak.json').read_text())
if not data.get('completedAt') or data.get('elapsedSoakMs',0)<3600000:raise RuntimeError('Controlled duration is not complete; no completion report')
def stats(samples):
 values=sorted(row['ms'] for row in samples)
 statuses={}
 for row in samples:statuses[str(row['status'])]=statuses.get(str(row['status']),0)+1
 return {'count':len(values),'statusCounts':statuses,'p95Ms':values[math.ceil(len(values)*.95)-1] if values else None,'maximumMs':values[-1] if values else None,'failures':sum(row['status']!=200 for row in samples)}
groups=[]
for endpoint in sorted({row['endpoint'] for row in data['samples']}):
 samples=[row for row in data['samples'] if row['endpoint']==endpoint]
 for name,rows in [('first-continuous-100',samples[:100]),('steady-soak-after-first100',samples[100:]),('all-preserved',samples)]:
  result=stats(rows);result.update(endpoint=endpoint,measurement=name)
  result['thresholdMs']=2000
  result['thresholdStatus']='PASS' if result['count']>=100 and result['failures']==0 and result['p95Ms']<=2000 else 'FAIL'
  groups.append(result)
report={'durationMs':data['elapsedSoakMs'],'startedAt':data['soakStartedAt'],'completedAt':data['completedAt'],'backgroundContracts':data['backgroundContracts'],'concurrency':data['concurrency'],'groups':groups,'sessionFailures':data.get('sessionFailures',[]),'resourceFailures':data['resourceFailures'],'coldServerMeasurement':'NOT_RUN','notes':['First continuous100 includes first endpoint invocations; no samples discarded.','Steady soak follows all200 initial continuous reads.','All errors and transport timeouts are retained; success rate does not replace latency gates.']}
(root/'performance-summary.json').write_text(json.dumps(report,indent=2))
print(json.dumps({'durationMinutes':report['durationMs']/60000,'groups':groups,'sessionFailureCount':len(report['sessionFailures']),'resourceFailureCount':len(report['resourceFailures'])}))
