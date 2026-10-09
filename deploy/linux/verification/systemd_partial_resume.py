"""Streamed, one-shot amendment of the exact 00548def pre-prepare bootstrap.

No cleanup, remount, unit replacement, operation reset, or certificate regeneration.
stdin is a privately verified JSON envelope; --envelope-sha256 pins its exact bytes.
"""
import argparse,base64,hashlib,json,os,stat,subprocess,sys,types
from pathlib import Path

BASE=Path('/run/ols-tls-qualification')
STATE=Path('/var/lib/ols-tls-qualification')
ORIGINAL_ARCHIVE='e26ee290d7ed346d4f83a9aa3880671b35e1bfb755f9a320d10b84e9674f5675'
ALLOWED={'ols_linux/tls_systemd.py','verification/systemd_qualification/bootstrap.py','verification/systemd_qualification/README.md'}
INPUTS={'operator.json','resource-admission.json','package-receipt.json'}


def sha(data):return hashlib.sha256(data).hexdigest()


def validate_partial(base,envelope):
    if base.resolve()!=base:raise RuntimeError('Linked partial root')
    if set(envelope)!={'originalInputs','replacementFiles'} or set(envelope['originalInputs'])!=INPUTS or set(envelope['replacementFiles'])!=ALLOWED:
        raise RuntimeError('Exact partial amendment envelope required')
    if {p.name for p in base.iterdir()}!={'state','package','inputs','evidence'}:raise RuntimeError('Not the untouched pre-prepare partial bootstrap')
    for path in [base,*base.rglob('*')]:
        if path.is_symlink() or not (path.is_dir() or path.is_file()):raise RuntimeError('Linked or special partial resource')
        info=path.stat();mode=stat.S_IMODE(info.st_mode)
        expected_mode=0o600 if path.parent==base/'inputs' else 0o644
        if (info.st_uid,info.st_gid)!=(0,0) or (mode&0o022 if path.is_dir() else mode!=expected_mode):raise RuntimeError('Original partial metadata differs')
    if list((base/'state').iterdir()) or list((base/'evidence').iterdir()):raise RuntimeError('Partial state/evidence is no longer empty; do not retry')
    if {p.name for p in (base/'inputs').iterdir()}!=INPUTS:raise RuntimeError('Original input membership differs')
    for name,expected in envelope['originalInputs'].items():
        if sha((base/'inputs'/name).read_bytes())!=expected:raise RuntimeError('Original input receipt differs')
    receipt=json.loads((base/'inputs/package-receipt.json').read_text())
    if receipt['archiveSha256']!=ORIGINAL_ARCHIVE or len(receipt['files'])!=44:raise RuntimeError('Wrong original bootstrap package')
    actual={str(p.relative_to(base/'package')):sha(p.read_bytes()) for p in (base/'package').rglob('*') if p.is_file()}
    if actual!=receipt['files']:raise RuntimeError('Original package membership/content differs')
    expected_dirs=set()
    for name in actual:
        parent=Path(name).parent
        while parent!=Path('.'):expected_dirs.add(str(parent));parent=parent.parent
    if {str(p.relative_to(base/'package')) for p in (base/'package').rglob('*') if p.is_dir()}!=expected_dirs:raise RuntimeError('Unknown package directory')
    decoded={}
    for name,value in envelope['replacementFiles'].items():
        if set(value)!={'sha256','data'}:raise RuntimeError('Invalid source amendment')
        data=base64.b64decode(value['data'],validate=True)
        if len(data)>1024*1024 or sha(data)!=value['sha256']:raise RuntimeError('Source amendment digest differs')
        if name.endswith('.py'):compile(data,name,'exec')
        decoded[name]=data
    return decoded


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--envelope-sha256',required=True);parser.add_argument('--apply',action='store_true');args=parser.parse_args()
    os.umask(0o077)
    raw=sys.stdin.buffer.read(2*1024*1024+1)
    if len(raw)>2*1024*1024 or sha(raw)!=args.envelope_sha256:raise RuntimeError('Exact reviewed envelope digest required')
    if os.geteuid()!=0 or Path('/proc/1/comm').read_text().strip()!='systemd':raise RuntimeError('Original systemd operator required')
    envelope=json.loads(raw);decoded=validate_partial(BASE,envelope)
    sys.path[:0]=[str(BASE/'package'),str(BASE/'package/verification/systemd_qualification')]
    import bootstrap,guard
    from ols_linux import tls_systemd as old_sd
    group=Path('/sys/fs/cgroup/ols.slice/ols-tls.slice')/bootstrap.SLICE
    expected='/ols.slice/ols-tls.slice/'+bootstrap.SLICE+'/'+bootstrap.SCOPE
    if Path('/proc/self/cgroup').read_text().strip()!='0::'+expected:raise RuntimeError('Use only the existing fixed capped control scope')
    pids={int(pid) for path in group.rglob('cgroup.procs') for pid in path.read_text().split()}
    if pids!={os.getpid()}:raise RuntimeError('Foreign or remaining fixture/control process')
    guard.cgroup_sample(group)
    mounts={}
    for line in Path('/proc/self/mountinfo').read_text().splitlines():
        left,right=line.split(' - ',1);fields=left.split();kind=right.split()
        if fields[4] in (str(BASE),str(STATE)):
            if fields[4] in mounts:raise RuntimeError('Stacked fixture mount')
            if not {'rw','nosuid','nodev'}<=set(fields[5].split(',')) or 'size=261120k' not in kind[2].split(','):raise RuntimeError('Fixture mount flags/size differ')
            mounts[fields[4]]=(fields[2],fields[3],kind[0],kind[1])
    if set(mounts)!={str(BASE),str(STATE)}:raise RuntimeError('Both original mounts required')
    device=mounts[str(BASE)][0]
    if mounts!={str(BASE):(device,'/','tmpfs','ols-tls-qualification'),str(STATE):(device,'/state','tmpfs','ols-tls-qualification')}:raise RuntimeError('Original fixture mount identity differs')
    slice_path=Path('/etc/systemd/system')/bootstrap.SLICE
    data,info=old_sd._read(slice_path)
    if data.decode()!=bootstrap.SLICE_TEXT or (info.st_uid,info.st_gid,stat.S_IMODE(info.st_mode))!=(0,0,0o644):raise RuntimeError('Original slice ownership differs')
    for name in bootstrap.NAMES:
        path=Path('/etc/systemd/system')/name
        if path.exists() or path.is_symlink() or path.with_name(name+'.d').exists():raise RuntimeError('Test service preparation already started')
        result=subprocess.run(['systemctl','show',name,'--property=LoadState,ActiveState,SubState,FragmentPath,SourcePath,DropInPaths,ControlGroup,Transient'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=10)
        if result.returncode not in (0,4):raise RuntimeError('Test unit state unavailable')
        rows={}
        for line in result.stdout.decode().splitlines():
            key,separator,value=line.partition('=')
            if not separator or key in rows:raise RuntimeError('Ambiguous test unit state')
            rows[key]=value
        bootstrap.admit_unit_state(name,rows,cgroup_exists=(group/name).exists())
    for kind in ('tcp','tcp6','udp','udp6'):
        for line in Path('/proc/net',kind).read_text().splitlines()[1:]:
            if int(line.split()[1].split(':')[1],16) in range(29843,29849):raise RuntimeError('Fixture port occupied')
    # Evaluate the verified replacement only in memory for read-only admission.
    module=types.ModuleType('ols_linux.tls_systemd');module.__package__='ols_linux'
    exec(compile(decoded['ols_linux/tls_systemd.py'],'reviewed-tls-systemd','exec'),module.__dict__)
    guard.sd=module
    inputs=json.loads((BASE/'inputs/operator.json').read_text());guard.admission(inputs)
    if not args.apply:
        print(json.dumps({'status':'PARTIAL_RESUME_PREFLIGHT_PASS','resourcesCreated':False,'originalInputsPreserved':True}));return
    # An intent blocks automatic retry if any write or subsequent stage is interrupted.
    receipt={'originalArchiveSha256':ORIGINAL_ARCHIVE,'envelopeSha256':args.envelope_sha256,'originalInputs':envelope['originalInputs'],'replacements':{name:sha(data) for name,data in decoded.items()},'stage':'before-first-prepare','operationId':None}
    intent=BASE/'evidence/partial-source-amendment.json'
    with intent.open('x') as stream:json.dump(receipt,stream);stream.flush();os.fsync(stream.fileno())
    for name,data in decoded.items():
        path=BASE/'package'/name;_,info=old_sd._read(path)
        if (info.st_uid,info.st_gid,stat.S_IMODE(info.st_mode))!=(0,0,0o644):raise RuntimeError('Original source metadata differs')
        old_sd._atomic_file(path,data,{'uid':0,'gid':0,'mode':0o644})
    stage="""
import sys
from pathlib import Path
base=Path('/run/ols-tls-qualification')
sys.path[:0]=[str(base/'package'),str(base/'package/verification/systemd_qualification')]
import runner
sys.argv=['runner.py','prepare','--inputs-file',str(base/'inputs/operator.json')];runner.main()
from ols_linux import journal
op=journal.current(base/'runtime')['operationId']
sys.argv=['runner.py','run','--inputs-file',str(base/'inputs/operator.json'),'--operation-id',op];runner.main()
"""
    os.execv(sys.executable,[sys.executable,'-B','-c',stage])


if __name__=='__main__':main()
