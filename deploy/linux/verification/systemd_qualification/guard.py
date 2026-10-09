"""Admission and continuous resource/production-health guard; read-only production access."""
import json
import os
from pathlib import Path
import threading
import time
from ols_linux import runtime,tls_systemd as sd
from fixture import BASE,ROOT,NAMES,SLICE,SCOPE,BINARIES,record,put


def metrics():
    mem=dict(line.split(':',1) for line in Path('/proc/meminfo').read_text().splitlines())
    vm=dict(line.split() for line in Path('/proc/vmstat').read_text().splitlines())
    full=next(line for line in Path('/proc/pressure/memory').read_text().splitlines() if line.startswith('full '))
    return {'time':time.monotonic(),'available':int(mem['MemAvailable'].split()[0])*1024,'swapPages':int(vm['pswpin'])+int(vm['pswpout']),'psi':float(full.split()[1].split('=')[1]),'load':os.getloadavg()[0]}


def health(inputs):
    # Explicit operator-supplied original read-only entry point. No shell evaluation.
    cli=Path(inputs['healthCli']);python=Path(inputs['healthPython']);root=Path(inputs['healthRuntime'])
    if not all(p.is_absolute() for p in (cli,python,root)) or cli.name!='linux.py':raise RuntimeError('Exact original health entrypoint required')
    if record(cli)['sha256']!=inputs['healthCliSha256']:raise RuntimeError('Original health CLI changed')
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
    rate=(after['swapPages']-before['swapPages'])*os.sysconf('SC_PAGE_SIZE')/(after['time']-before['time'])
    if after['available']<1024**3 or after['psi']>2 or rate>4*1024**2:raise RuntimeError('Insufficient memory or current memory pressure')
    if os.statvfs('/').f_bavail*os.statvfs('/').f_frsize<2*1024**3:raise RuntimeError('At least 2GiB disk headroom required')
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


class Guard:
    def __init__(self,inputs,initial):
        self.inputs=inputs;self.initial=initial;self.error=None;self.child=None;self.stop=threading.Event();self.thread=threading.Thread(target=self.watch,daemon=True);self.health_thread=threading.Thread(target=self.watch_health,daemon=True)
    def start(self):self.thread.start();self.health_thread.start()
    def check(self):
        if self.error:raise RuntimeError('Safety guard stopped fixture: '+self.error)
    def watch(self):
        previous=metrics();bad_mem=bad_pressure=0;overload=0
        limit=max(os.cpu_count() or 1,self.initial['metrics']['load']+1)
        group=Path('/sys/fs/cgroup/ols.slice/ols-tls.slice')/SLICE
        try:
            while not self.stop.wait(5):
                current=metrics();seconds=current['time']-previous['time']
                rate=(current['swapPages']-previous['swapPages'])*os.sysconf('SC_PAGE_SIZE')/seconds
                bad_mem=bad_mem+1 if current['available']<512*1024**2 else 0
                bad_pressure=bad_pressure+1 if current['psi']>2 or rate>4*1024**2 else 0
                overload=overload+seconds if current['load']>limit else 0
                events=dict(line.split() for line in (group/'memory.events').read_text().splitlines())
                if bad_mem>=3 or bad_pressure>=3 or overload>=60 or any(int(events[k]) for k in ('max','oom','oom_kill')):raise RuntimeError('Resource threshold exceeded')
                previous=current
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
