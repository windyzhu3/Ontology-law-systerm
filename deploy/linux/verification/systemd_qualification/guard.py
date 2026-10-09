"""Admission and continuous resource/production-health guard; read-only production access."""
import json
import os
from pathlib import Path
import threading
import time
from ols_linux import runtime,tls_systemd as sd
from fixture import BASE,ROOT,NAMES,SLICE,SCOPE,BINARIES,record,put


from bootstrap import resource_sample,resource_pressure,read_psi,production_health

def metrics():return resource_sample()


def health(inputs):
    # Explicit operator-supplied original read-only entry point. No shell evaluation.
    cli=Path(inputs['healthCli']);python=Path(inputs['healthPython']);root=Path(inputs['healthRuntime'])
    if not all(p.is_absolute() for p in (cli,python,root)) or cli.name!='linux.py':raise RuntimeError('Exact original health entrypoint required')
    if record(cli)['sha256']!=inputs['healthCliSha256']:raise RuntimeError('Original health CLI changed')
    if 'maintenanceBaseline' in inputs:return production_health(inputs)
    result=runtime.run([str(python),'-B',str(cli),'--runtime',str(root),'health'],timeout=60)
    value=json.loads(result.stdout)
    if value.get('status')!='PASS':raise RuntimeError('Original production health is not PASS')
    return value


def baseline(inputs):
    files=[]
    for path in inputs['nonSecretFiles']:
        # Only explicitly supplied public configuration/unit files, never keys.
        if not path.endswith(('.service','.conf','.Caddyfile','.types')):raise RuntimeError('Only non-secret configuration files allowed')
        files.append(record(path))
    units={}
    for name in inputs['productionUnits']:
        if not name.endswith('.service') or name in NAMES.values():raise RuntimeError('Explicit original service names required')
        units[name]=sd.unit_snapshot(name,dict.fromkeys(sd.UNIT_PROPERTIES,''))
    return {'files':files,'units':units}


def admission(inputs):
    if os.geteuid()!=0 or Path('/proc/1/comm').read_text().strip()!='systemd':raise RuntimeError('Actual host systemd/root required')
    if 'memory' not in Path('/sys/fs/cgroup/cgroup.controllers').read_text().split():raise RuntimeError('cgroup v2 memory controller required')
    for binary,expected in BINARIES.values():
        if sd._binary_sha(binary)!=expected:raise RuntimeError('Proxy binary version/hash differs')
    before=metrics();time.sleep(5);after=metrics()
    if min(before['available'],after['available'])<1024**3 or resource_pressure(before,after):raise RuntimeError('Insufficient memory or current memory pressure')
    if os.statvfs('/var/lib').f_bavail*os.statvfs('/var/lib').f_frsize<2*1024**3:raise RuntimeError('At least 2GiB disk headroom required')
    health(inputs)
    return {'metrics':after,'production':baseline(inputs)}


def stop_tests():
    # Never systemctl stop a parent slice or an original production service.
    failures=[]
    for name in NAMES.values():
        try:
            result=runtime.run(['systemctl','stop',name],check=False,timeout=40)
            if result.returncode:failures.append(name)
        except Exception:failures.append(name)
    if failures:raise RuntimeError('Fixture stop unconfirmed: '+','.join(failures))


def cgroup_sample(group):
    required={'memory.max':536870912,'memory.high':268435456,'memory.swap.max':0,'pids.max':128}
    for name,expected in required.items():
        if (group/name).read_text().strip()!=str(expected):raise RuntimeError('Actual test cgroup limit differs: '+name)
    quota,period=(group/'cpu.max').read_text().split()
    if not quota.isdigit() or int(quota)*2!=int(period):raise RuntimeError('Actual test CPU cap differs')
    events={key:int(value) for key,value in (line.split() for line in (group/'memory.events').read_text().splitlines())}
    if not {'max','high','oom','oom_kill'}<=events.keys() or any(value<0 for value in events.values()):raise RuntimeError('Required cgroup memory events missing')
    return {'events':events,'psi':read_psi(group/'memory.pressure')}


def resource_step(previous,current,events,previous_events,streak,overload,limit,cgroup_psi):
    if current['available']<512*1024**2:raise RuntimeError('Host memory hard floor breached')
    if any(events[key] for key in ('max','oom','oom_kill')):raise RuntimeError('Test cgroup cap/OOM event observed')
    if any(events[key]<previous_events[key] for key in previous_events):raise RuntimeError('Test cgroup counters reset')
    pressure=resource_pressure(previous,current) or events['high']>previous_events['high'] or cgroup_psi is not None and cgroup_psi>2
    streak=streak+1 if pressure else 0
    overload=overload+current['time']-previous['time'] if current['load']>limit else 0
    if streak>=3:raise RuntimeError('Sustained memory pressure observed')
    if overload>=60:raise RuntimeError('Sustained host load limit exceeded')
    return streak,overload


class Guard:
    def __init__(self,inputs,initial):
        self.inputs=inputs;self.initial=initial;self.error=None;self.child=None;self.stop=threading.Event();self.thread=threading.Thread(target=self.watch,daemon=True);self.health_thread=threading.Thread(target=self.watch_health,daemon=True)
    def start(self):
        group=Path('/sys/fs/cgroup/ols.slice/ols-tls.slice')/SLICE
        self.initial_group=cgroup_sample(group)
        if any(self.initial_group['events'][key] for key in ('max','oom','oom_kill')):raise RuntimeError('Test cgroup already hit cap/OOM')
        put(BASE/'evidence/resource-capabilities.json',json.dumps({'hostPressureMode':self.initial['metrics']['pressureMode'],'hostPsi':self.initial['metrics']['psi'],'cgroupPsi':self.initial_group['psi'],'limitsVerified':True}))
        self.thread.start();self.health_thread.start()
    def check(self):
        if self.error:raise RuntimeError('Safety guard stopped fixture: '+self.error)
    def watch(self):
        group=Path('/sys/fs/cgroup/ols.slice/ols-tls.slice')/SLICE
        try:
            previous=metrics();previous_group=self.initial_group;streak=0;overload=0
            limit=max(os.cpu_count() or 1,self.initial['metrics']['load']+1)
            while not self.stop.wait(5):
                current=metrics();current_group=cgroup_sample(group)
                if (current_group['psi'] is None)!=(previous_group['psi'] is None):raise RuntimeError('Test cgroup pressure capability changed')
                streak,overload=resource_step(previous,current,current_group['events'],previous_group['events'],streak,overload,limit,current_group['psi'])
                previous=current;previous_group=current_group
        except Exception as error:self.fail(error)
    def watch_health(self):
        try:
            while not self.stop.wait(5):
                health(self.inputs)
                if baseline(self.inputs)!=self.initial['production']:raise RuntimeError('Original production observation changed')
                if self.stop.wait(25):return
        except Exception as error:self.fail(error)
    def fail(self,error):
        self.error=str(error)
        child=self.child
        try:
            if child is not None and child.poll() is None:child.kill()
        finally:
            try:put(BASE/'evidence/guard-failure.json',json.dumps({'error':self.error,'time':time.time()}))
            finally:
                try:stop_tests()
                except Exception:pass
    def close(self):
        self.stop.set();self.thread.join(timeout=10);self.health_thread.join(timeout=65)
        if self.thread.is_alive() or self.health_thread.is_alive():raise RuntimeError('Guard finalization still pending; no PASS permitted')
        self.check()
