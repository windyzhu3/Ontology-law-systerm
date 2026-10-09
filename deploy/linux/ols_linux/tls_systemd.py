"""Fail-closed qualification primitives for the registered nginx/Caddy units.

These observations are necessary but not sufficient for loaded TLS proof. The
rotation controller must also prove listener ownership and all live consumers.
No function here adopts a service, executes supplied argv, or changes a unit.
"""
import glob
import hashlib
import os
from pathlib import Path
import re
import stat


def _path(value):
    path=Path(value)
    if not path.is_absolute() or '..' in path.parts or str(path)!=str(value):
        raise RuntimeError('Exact absolute registered path required')
    return path


def _read(path):
    """Open each component without following links, including parent directories."""
    path=_path(path)
    fd=os.open('/',os.O_RDONLY|os.O_DIRECTORY)
    try:
        for part in path.parts[1:-1]:
            child=os.open(part,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=fd)
            os.close(fd);fd=child
        file_fd=os.open(path.name,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK,dir_fd=fd)
        with os.fdopen(file_fd,'rb') as stream:
            before=os.fstat(stream.fileno())
            if not stat.S_ISREG(before.st_mode):raise RuntimeError('Registered path is not a regular file')
            data=stream.read();after=os.fstat(stream.fileno())
            if (before.st_size,before.st_mtime_ns,before.st_ctime_ns)!=(after.st_size,after.st_mtime_ns,after.st_ctime_ns):
                raise RuntimeError('Registered file changed during observation')
            return data,after
    except OSError as error:
        raise RuntimeError('Registered file unavailable or linked') from error
    finally:os.close(fd)


def verify_files(records):
    if not records:raise RuntimeError('Registered file manifest required')
    seen=set()
    for row in records:
        if set(row)!={'path','sha256','uid','gid','mode'} or row['path'] in seen:
            raise RuntimeError('Invalid or duplicate registered file')
        seen.add(row['path']);data,info=_read(row['path'])
        actual=(hashlib.sha256(data).hexdigest(),info.st_uid,info.st_gid,stat.S_IMODE(info.st_mode))
        if actual!=(row['sha256'],row['uid'],row['gid'],row['mode']):
            raise RuntimeError('Registered file content or ownership differs')


def verify_includes(patterns):
    """Bind membership as well as bytes; adding an unregistered include must fail."""
    for pattern,expected in patterns.items():
        path=_path(pattern)
        if path.name!='*.conf' or any(c in str(path.parent) for c in '*?['):
            raise RuntimeError('Only an exact registered conf directory is supported')
        if path.parent.resolve()!=path.parent:raise RuntimeError('Linked include directory')
        if sorted(glob.glob(pattern))!=expected or len(set(expected))!=len(expected):
            raise RuntimeError('Registered include membership differs')


def verify_process(profile,state,process,cgroup_pids):
    if set(profile)!={'executable','argv','uid','gid','cgroup'}:
        raise RuntimeError('Exact registered process profile required')
    if state.get('ControlGroup')!=profile['cgroup'] or state.get('ControlPID')!='0':
        raise RuntimeError('Proxy cgroup differs or control process is active')
    if state.get('ActiveState')=='inactive' and state.get('SubState')=='dead' and state.get('MainPID')=='0':
        if process is not None or cgroup_pids:raise RuntimeError('Proxy processes remain after stop')
        return {'running':False,'process':None}
    if state.get('ActiveState')!='active' or state.get('SubState')!='running' or not process:
        raise RuntimeError('Registered proxy process state unknown')
    if not str(state.get('MainPID','')).isdigit() or int(state['MainPID'])!=process.get('pid') or process.get('pid') not in cgroup_pids:
        raise RuntimeError('Proxy main process membership differs')
    if type(process.get('startTicks')) is not int or process['startTicks']<=0:
        raise RuntimeError('Proxy process start identity missing')
    if any(process.get(key)!=value for key,value in profile.items()):
        raise RuntimeError('Registered proxy process identity differs')
    return {'running':True,'process':dict(process)}


def verify_restart(before,after):
    if not after.get('running') or not after.get('process'):
        raise RuntimeError('New proxy process not observed')
    if before.get('running') and all(before['process'][key]==after['process'][key] for key in ('pid','startTicks')):
        raise RuntimeError('Original process remains; restart unproven')


def _credentials(values):
    if not values:raise RuntimeError('Exact credential hashes required')
    for name,digest in values.items():
        if not re.fullmatch(r'[A-Za-z0-9_.-]+',name) or name in {'.','..'} or not re.fullmatch('[a-f0-9]{64}',digest):
            raise RuntimeError('Invalid credential name or digest')


def credential_action(current,desired):
    _credentials(current);_credentials(desired)
    if current.keys()!=desired.keys():raise RuntimeError('Registered credential set differs')
    # systemd copies LoadCredential data when a service starts, not on ExecReload.
    return 'none' if current==desired else 'restart'


def verify_credentials(directory,expected):
    _credentials(expected);directory=_path(directory)
    for name,digest in expected.items():
        data,_=_read(directory/name)
        if hashlib.sha256(data).hexdigest()!=digest:
            raise RuntimeError('Loaded credential differs from selected generation')


def read_process(pid):
    """Read kernel process identity twice to reject exit, exec, or PID reuse races."""
    if type(pid) is not int or pid<=0:raise RuntimeError('Exact live PID required')
    root=Path('/proc')/str(pid)
    def snapshot():
        # /proc/PID/exe is deliberately a kernel link, not a registered config path.
        info=(root/'stat').read_text().rsplit(')',1)[1].split()
        status=dict(line.split(':',1) for line in (root/'status').read_text().splitlines())
        groups=(root/'cgroup').read_text().splitlines()
        if len(groups)!=1 or not groups[0].startswith('0::'):
            raise RuntimeError('Qualified cgroup v2 process required')
        raw=(root/'cmdline').read_bytes()
        if not raw.endswith(b'\0'):raise RuntimeError('Process argv unavailable')
        return dict(pid=pid,startTicks=int(info[19]),executable=os.readlink(root/'exe'),
                    argv=[part.decode() for part in raw[:-1].split(b'\0')],
                    uid=int(status['Uid'].split()[1]),gid=int(status['Gid'].split()[1]),
                    cgroup=groups[0][3:])
    try:
        first=snapshot();second=snapshot()
    except (OSError,ValueError,KeyError,IndexError) as error:
        raise RuntimeError('Proxy process observation unavailable') from error
    if first!=second:raise RuntimeError('Proxy process changed during observation')
    return first
