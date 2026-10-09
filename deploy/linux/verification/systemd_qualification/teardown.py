"""Run only after cleanup scope exits; unmount only the two registered fixture mounts."""
import json
import os
from pathlib import Path
import subprocess

BASE=Path('/run/ols-tls-qualification');STATE=Path('/var/lib/ols-tls-qualification')

def main():
    if os.geteuid()!=0:raise RuntimeError('Root required')
    receipt=json.loads((BASE/'evidence/cleanup-units.json').read_text())
    if receipt.get('servicesStopped') is not True or not receipt.get('retainedSha256'):raise RuntimeError('Evidence retention and unit cleanup required')
    group=Path('/sys/fs/cgroup/ols.slice/ols-tls.slice/ols-tls-qualification.slice')
    if group.exists() and any(p.read_text().strip() for p in group.rglob('cgroup.procs')):raise RuntimeError('Fixture/control cgroups not empty; exit control scope first')
    mounts={}
    for line in Path('/proc/self/mountinfo').read_text().splitlines():
        left,right=line.split(' - ',1);fields=left.split();kind=right.split()
        if fields[4] in (str(BASE),str(STATE)):mounts[fields[4]]=(fields[3],kind[0],kind[1])
    if mounts!={str(BASE):('/','tmpfs','ols-tls-qualification'),str(STATE):('/state','tmpfs','ols-tls-qualification')}:raise RuntimeError('Exact fixture mount identities required')
    for name in ('ols-tls-qualification-nginx.service','ols-tls-qualification-caddy.service','ols-tls-qualification-upstream.service','ols-tls-qualification.slice'):
        if (Path('/etc/systemd/system')/name).exists():raise RuntimeError('Fixture unit remains')
    subprocess.run(['umount',str(STATE)],check=True);subprocess.run(['umount',str(BASE)],check=True)
    STATE.rmdir();BASE.rmdir()
    print(json.dumps({'status':'CLEANED','retainedSha256':receipt['retainedSha256'],'scope':'Only three fixture services, owned unit/drop-in files and two fixture mounts; parent slices untouched'}))

if __name__=='__main__':main()
