"""Streamed operator bootstrap. stdin is a private JSON envelope; no downloaded code execution."""
import base64
import hashlib
import io
import json
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


def main():
    os.umask(0o077)
    raw=sys.stdin.buffer.read(8*1024*1024+1)
    if len(raw)>8*1024*1024:raise RuntimeError('Envelope too large')
    value=json.loads(raw);archive=base64.b64decode(value['archive'],validate=True);inputs=value['inputs']
    if hashlib.sha256(archive).hexdigest()!=value['archiveSha256']:raise RuntimeError('Operator package digest mismatch')
    if os.geteuid()!=0 or Path('/proc/1/comm').read_text().strip()!='systemd' or sys.version_info<(3,10):raise RuntimeError('Existing root/systemd/Python3.10+ required')
    for account in ('nginx','caddy'):pwd.getpwnam(account)
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
    def pressure():
        vm=dict(line.split() for line in Path('/proc/vmstat').read_text().splitlines())
        psi=next(line for line in Path('/proc/pressure/memory').read_text().splitlines() if line.startswith('full '))
        return time.monotonic(),int(vm['pswpin'])+int(vm['pswpout']),float(psi.split()[1].split('=')[1])
    before=pressure();time.sleep(5);after=pressure()
    if after[2]>2 or (after[1]-before[1])*os.sysconf('SC_PAGE_SIZE')/(after[0]-before[0])>4*1024**2:raise RuntimeError('Current memory pressure exceeds admission limit')
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
