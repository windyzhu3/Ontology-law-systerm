"""Streamed operator bootstrap. stdin is a private JSON envelope; no downloaded code execution."""
import base64
import hashlib
import io
import json
import math
import shutil
import ssl
import os
from pathlib import Path
import pwd
import subprocess
import sys
import tarfile
import time

BASE=Path('/run/ols-tls-qualification');STATE=Path('/var/lib/ols-tls-qualification')
SLICE='ols-tls-qualification.slice';SCOPE='ols-tls-qualification-control.scope'
NAMES=['ols-tls-qualification-'+role+'.service' for role in ('nginx','caddy','upstream')]
SLICE_TEXT='[Slice]\nMemoryMax=512M\nMemoryHigh=256M\nMemorySwapMax=0\nCPUQuota=50%\nTasksMax=128\n'


def command(args,**kw):return subprocess.run(args,check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=70,**kw)


def admit_unit_state(name,state,*,cgroup_exists):
    # Merely querying a slice may synthesize an inactive, fileless systemd unit.
    # This exception is only for our exact slice, never service/scope/parent units.
    if name not in [*NAMES,SLICE,SCOPE]:raise RuntimeError('Unregistered fixture unit name')
    expected={'LoadState':'not-found','ActiveState':'inactive','SubState':'dead',
              'FragmentPath':'','SourcePath':'','DropInPaths':'','ControlGroup':'','Transient':'no'}
    implicit=dict(expected,LoadState='loaded')
    if cgroup_exists or not (state==expected or name==SLICE and state==implicit):
        raise RuntimeError('Fixture unit has existing configuration, activity or cgroup')


def read_psi(path):
    try:text=Path(path).read_text()
    except FileNotFoundError:return None
    try:
        full=[line for line in text.splitlines() if line.startswith('full ')]
        if len(full)!=1:raise ValueError('full pressure row missing')
        values=dict(part.split('=',1) for part in full[0].split()[1:]);value=float(values['avg10'])
        if not math.isfinite(value) or not 0<=value<=100:raise ValueError('invalid pressure value')
        return value
    except (ValueError,KeyError) as error:raise RuntimeError('Memory pressure observation malformed') from error


def resource_sample(proc=Path('/proc')):
    try:
        mem=dict(line.split(':',1) for line in (proc/'meminfo').read_text().splitlines())
        vm={key:int(value) for key,value in (line.split() for line in (proc/'vmstat').read_text().splitlines())}
        available=int(mem['MemAvailable'].split()[0])*1024
        swap=vm['pswpin']+vm['pswpout']
    except (OSError,ValueError,KeyError) as error:raise RuntimeError('Required MemAvailable/swap counters unavailable') from error
    def total(name):
        if name in vm:return vm[name]
        values=[value for key,value in vm.items() if key.startswith(name+'_') and key!=name+'_throttle']
        return sum(values) if values else None
    psi=read_psi(proc/'pressure/memory');reclaim=total('pgscan_direct');stalls=total('allocstall')
    if psi is None and (reclaim is None or stalls is None):raise RuntimeError('PSI unavailable and direct-reclaim/allocation-stall counters missing; unsupported')
    if available<0 or any(value<0 for value in vm.values()):raise RuntimeError('Invalid memory counters')
    return {'time':time.monotonic(),'available':available,'swapPages':swap,'psi':psi,
            'pressureMode':'psi' if psi is not None else 'vmstat-fallback',
            'directReclaim':reclaim,'allocationStalls':stalls,'load':os.getloadavg()[0]}


def resource_pressure(before,after):
    if before['pressureMode']!=after['pressureMode']:raise RuntimeError('Memory pressure capability changed during observation')
    seconds=after['time']-before['time']
    if seconds<=0:raise RuntimeError('Invalid resource sample interval')
    delta=after['swapPages']-before['swapPages']
    if delta<0:raise RuntimeError('Swap counters reset during observation')
    if after['psi'] is not None:
        return after['psi']>2 or delta*os.sysconf('SC_PAGE_SIZE')/seconds>4*1024**2
    # No PSI measurement is invented. In fallback mode even small sustained
    # swapping or direct reclaim/stalls stop the test, below the normal rate gate.
    differences=[]
    for key in ('directReclaim','allocationStalls'):
        if before[key] is None or after[key] is None or after[key]<before[key]:raise RuntimeError('Direct-reclaim counters unavailable or reset')
        differences.append(after[key]-before[key])
    return delta>0 or any(differences)


def host_capabilities():
    controllers=Path('/sys/fs/cgroup/cgroup.controllers').read_text().split()
    if not {'memory','cpu','pids'}<=set(controllers):raise RuntimeError('Required cgroup v2 memory/cpu/pids controllers unavailable')
    version=command(['systemctl','--version']).stdout.decode().splitlines()[0]
    if not version.split()[1].isdigit() or int(version.split()[1])<247:raise RuntimeError('systemd 247+ with LoadCredential required')
    if not ssl.HAS_TLSv1_3:raise RuntimeError('Existing Python/OpenSSL lacks TLS1.3')
    status=dict(line.split(':',1) for line in Path('/proc/self/status').read_text().splitlines())
    effective=int(status['CapEff'].strip(),16)
    required={'CHOWN':0,'DAC_OVERRIDE':1,'FOWNER':3,'KILL':5,'SETGID':6,'SETUID':7,'SYS_PTRACE':19,'SYS_ADMIN':21}
    missing=[name for name,bit in required.items() if not effective&(1<<bit)]
    if missing:raise RuntimeError('Required controller capabilities unavailable: '+','.join(missing))
    selinux=Path('/sys/fs/selinux/enforce')
    enforcing=selinux.exists() and selinux.read_text().strip()=='1'
    # Do not guess SELinux domain/label/port permissions or alter host policy.
    if enforcing:raise RuntimeError('SELinux enforcing: fixed test paths/ports require read-only policy qualification before this runner is supported; no policy changes permitted')
    for path in ['/proc/net/'+kind for kind in ('tcp','tcp6','udp','udp6')]:
        if not Path(path).is_file():raise RuntimeError('Required kernel socket observation unavailable: '+path)
    return {'systemd':version,'python':sys.version.split()[0],'openssl':ssl.OPENSSL_VERSION,
            'opensslCommand':command(['openssl','version']).stdout.decode().strip(),
            'cgroupControllers':controllers,'selinuxEnforcing':enforcing,'requiredCapabilitiesPresent':True,
            'tls13':True,'diskTarget':'/var/lib','diskAvailableBytes':os.statvfs('/var/lib').f_bavail*os.statvfs('/var/lib').f_frsize,
            'testCgroupLimits':'verified from kernel after scope creation, before any certificates/services'}


def main():
    os.umask(0o077)
    if sys.argv[1:] not in ([],['--check-only']):raise RuntimeError('Only --check-only is supported')
    raw=sys.stdin.buffer.read(8*1024*1024+1)
    if len(raw)>8*1024*1024:raise RuntimeError('Envelope too large')
    value=json.loads(raw);archive=base64.b64decode(value['archive'],validate=True);inputs=value['inputs']
    if hashlib.sha256(archive).hexdigest()!=value['archiveSha256']:raise RuntimeError('Operator package digest mismatch')
    if os.geteuid()!=0 or Path('/proc/1/comm').read_text().strip()!='systemd' or sys.version_info<(3,10):raise RuntimeError('Existing root/systemd/Python3.10+ required')
    for account in ('nginx','caddy'):pwd.getpwnam(account)
    for executable in ('systemctl','systemd-run','busctl','mount','umount','openssl'):
        if not shutil.which(executable):raise RuntimeError('Required existing executable missing: '+executable)
    capabilities=host_capabilities()
    for binary,expected in [('/usr/sbin/nginx','9cf471abeb2d00b07ab215264fdd87100f5b5e52ccc5f6045acb469929dfb576'),('/usr/bin/caddy','33cd4c300c46fef824abe017fb3c5698698c41cd7eaf9cf097126d5ac6e4cf4a')]:
        if hashlib.sha256(Path(binary).read_bytes()).hexdigest()!=expected:raise RuntimeError('Proxy binary hash differs')
    paths=[BASE,STATE,Path('/etc/systemd/system')/(NAMES[1]+'.d'),*[Path('/etc/systemd/system')/name for name in [*NAMES,SLICE]]]
    if any(p.exists() or p.is_symlink() for p in paths):raise RuntimeError('Fixture path collision; no replacement names or deletion permitted')
    for name in [*NAMES,SLICE,SCOPE]:
        properties='LoadState,ActiveState,SubState,FragmentPath,SourcePath,DropInPaths,ControlGroup,Transient'
        result=subprocess.run(['systemctl','show',name,'--property='+properties],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=10)
        state={}
        for line in result.stdout.decode().splitlines():
            key,separator,entry=line.partition('=')
            if not separator or key in state:raise RuntimeError('Ambiguous fixture unit observation')
            state[key]=entry
        group=Path('/sys/fs/cgroup/ols.slice/ols-tls.slice')/SLICE
        if name!=SLICE:group=group/name
        admit_unit_state(name,state,cgroup_exists=group.exists())
    for kind in ('tcp','tcp6','udp','udp6'):
        for line in Path('/proc/net',kind).read_text().splitlines()[1:]:
            if int(line.split()[1].split(':')[1],16) in range(29843,29849):raise RuntimeError('Authorized fixture port occupied')
    if 'memory' not in Path('/sys/fs/cgroup/cgroup.controllers').read_text().split():raise RuntimeError('cgroup v2 memory controller required')
    mem=dict(line.split(':',1) for line in Path('/proc/meminfo').read_text().splitlines())
    if int(mem['MemAvailable'].split()[0])*1024<1024**3 or os.statvfs('/var/lib').f_bavail*os.statvfs('/var/lib').f_frsize<2*1024**3:raise RuntimeError('Admission headroom insufficient')
    before=resource_sample();time.sleep(5);after=resource_sample()
    if min(before['available'],after['available'])<1024**3 or resource_pressure(before,after):raise RuntimeError('Current memory pressure exceeds admission limit')
    cli=Path(inputs['healthCli'])
    if cli.name!='linux.py' or not cli.is_absolute() or hashlib.sha256(cli.read_bytes()).hexdigest()!=inputs['healthCliSha256']:raise RuntimeError('Exact reviewed original health CLI required')
    health=json.loads(command([inputs['healthPython'],'-B',str(cli),'--runtime',inputs['healthRuntime'],'health']).stdout)
    if health.get('status')!='PASS':raise RuntimeError('Original production health failed')
    # Validate the complete archive before creating any resource, no links/devices.
    stream=tarfile.open(fileobj=io.BytesIO(archive),mode='r:gz');members=stream.getmembers()
    if len(members)>500 or sum(m.size for m in members)>4*1024*1024:raise RuntimeError('Package exceeds fixed bound')
    names=set()
    for member in members:
        name=Path(member.name)
        if not member.isfile() or name.is_absolute() or '..' in name.parts or member.name in names:raise RuntimeError('Unsafe archive member')
        names.add(member.name)
    if sys.argv[1:]==['--check-only']:
        print(json.dumps({'status':'PREFLIGHT_PASS','resourcesCreated':False,'capabilities':capabilities,'resourceAdmission':{'before':before,'after':after}}));return
    value['resourceAdmission']={'before':before,'after':after,'capabilities':capabilities}
    raw=json.dumps(value).encode()
    BASE.mkdir(mode=0o711);STATE.mkdir(mode=0o711)
    command(['mount','-t','tmpfs','-o','size=255m,mode=0711,nosuid,nodev','ols-tls-qualification',str(BASE)])
    (BASE/'state').mkdir(mode=0o711);command(['mount','--bind',str(BASE/'state'),str(STATE)])
    slice_path=Path('/etc/systemd/system')/SLICE
    with slice_path.open('x') as output:output.write(SLICE_TEXT)
    slice_path.chmod(0o644);command(['systemctl','daemon-reload'])
    # Bounded extraction and all subsequent setup execute in the capped scope.
    stage=r'''
import base64,hashlib,io,json,os,sys,tarfile
from pathlib import Path
v=json.load(sys.stdin);base=Path('/run/ols-tls-qualification');os.umask(0o077)
for name in ('inputs','evidence'):(base/name).mkdir(mode=0o700,exist_ok=True)
package=base/'package';package.mkdir(mode=0o755)
with tarfile.open(fileobj=io.BytesIO(base64.b64decode(v['archive'])),mode='r:gz') as archive:
 for member in archive.getmembers():
  target=package/member.name;target.parent.mkdir(parents=True,exist_ok=True)
  p=target.parent
  while p!=base:p.chmod(0o755);p=p.parent
  with target.open('xb') as output:output.write(archive.extractfile(member).read())
  target.chmod(0o644)
(base/'inputs/operator.json').write_text(json.dumps(v['inputs']))
(base/'inputs/resource-admission.json').write_text(json.dumps(v['resourceAdmission']))
(base/'inputs/package-receipt.json').write_text(json.dumps({'archiveSha256':v['archiveSha256'],'files':{str(p.relative_to(package)):hashlib.sha256(p.read_bytes()).hexdigest() for p in package.rglob('*') if p.is_file()}}))
sys.path.insert(0,str(package));sys.path.insert(0,str(package/'verification/systemd_qualification'))
import runner
sys.argv=['runner.py','prepare','--inputs-file',str(base/'inputs/operator.json')];runner.main()
from ols_linux import journal
op=journal.current(base/'runtime')['operationId']
sys.argv=['runner.py','run','--inputs-file',str(base/'inputs/operator.json'),'--operation-id',op];runner.main()
'''
    # Inherit operator output; never forward private health responses into public logs.
    process=subprocess.run(['systemd-run','--scope','--quiet','--unit='+SCOPE,'--slice='+SLICE,sys.executable,'-B','-c',stage],input=raw)
    if process.returncode:raise RuntimeError('Qualification failed; retain original runtime/evidence, stop only fixture units, do not bootstrap again')


if __name__=='__main__':main()
