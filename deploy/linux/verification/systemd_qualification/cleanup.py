"""Two-phase cleanup: export privately, then remove only exact fixture-owned units."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import sys
import tarfile
sys.path.insert(0,str(Path(__file__).resolve().parents[2]))
from ols_linux import journal,runtime,tls_systemd as sd
from fixture import BASE,ROOT,NAMES,SLICE,SCOPE,DROPIN,record,put
from guard import stop_tests


def main():
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=['export','remove-units']);parser.add_argument('--retained-sha256');args=parser.parse_args()
    if os.geteuid()!=0:raise RuntimeError('Root required')
    group=Path('/proc/self/cgroup').read_text().strip()
    if not group.endswith('/'+SCOPE) or '/'+SLICE+'/' not in group:raise RuntimeError('Cleanup must use the fixed capped scope')
    stop_tests()
    for name in NAMES.values():
        if sd.cgroup_pids('/ols.slice/ols-tls.slice/'+SLICE+'/'+name):raise RuntimeError('Fixture cgroup still populated')
    for ports in ((29843,29844),(29845,29848),(29846,29847)):
        sd.verify_listeners([{'address':'127.0.0.1','port':p} for p in ports],[],False)
    archive=BASE/'evidence/private-export.tar.gz'
    if args.action=='export':
        if archive.exists():raise RuntimeError('Original export already exists; download and retain it without overwriting')
        # Includes only this synthetic runtime/receipts. Never original production keys.
        with tarfile.open(archive,'w:gz') as output:
            for folder in (ROOT,BASE/'inputs',BASE/'evidence'):
                for path in sorted(folder.rglob('*')):
                    if path==archive:continue
                    if path.is_symlink():raise RuntimeError('Linked evidence is not exportable')
                    if path.is_file():output.add(path,arcname=str(path.relative_to(BASE)),recursive=False)
        archive.chmod(0o600)
        print(json.dumps({'path':str(archive),'sha256':record(archive)['sha256'],'bytes':archive.stat().st_size,'next':'Privately download and verify this exact digest before remove-units'}));return
    if not args.retained_sha256 or record(archive)['sha256']!=args.retained_sha256:raise RuntimeError('Exact privately retained evidence digest required')
    installed=journal._read(ROOT,ROOT/'verification/installed.json')
    expected={str(Path('/etc/systemd/system')/name) for name in [*NAMES.values(),SLICE]}
    if set(installed)!=expected:raise RuntimeError('Cleanup ownership registry differs')
    sd.verify_files(list(installed.values()))
    if DROPIN.parent.exists():
        if list(DROPIN.parent.iterdir())!=[DROPIN]:raise RuntimeError('Unexpected drop-in directory contents; leave untouched')
        oid=journal.current(ROOT)['operationId'];intent=journal._read(ROOT,ROOT/'operations'/(oid+'-systemd-credential-sources.json'))
        if str(DROPIN)!=intent['identity']['path'] or sd._read(DROPIN)[0]!=intent['identity']['after'].encode():raise RuntimeError('Owned drop-in drift; leave untouched')
        metadata=intent['metadata'];actual=record(DROPIN)
        if any(actual[k]!=metadata[k] for k in ('uid','gid','mode')):raise RuntimeError('Owned drop-in metadata drift')
        DROPIN.unlink();DROPIN.parent.rmdir()
    for path in installed:Path(path).unlink()
    runtime.run(['systemctl','daemon-reload'])
    put(BASE/'evidence/cleanup-units.json',json.dumps({'retainedSha256':args.retained_sha256,'removed':sorted(installed),'servicesStopped':True}))
    print(json.dumps({'status':'UNITS_REMOVED','next':'Exit control scope, prove slice cgroup empty, then unmount state bind and tmpfs; only rmdir empty mountpoints'}))


if __name__=='__main__':main()
