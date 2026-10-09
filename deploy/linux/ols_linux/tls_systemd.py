"""Fail-closed qualification primitives for the registered nginx/Caddy units.

These observations are necessary but not sufficient for loaded TLS proof. The
rotation controller must also prove listener ownership and all live consumers.
No function here adopts a service, executes supplied argv, or changes a unit.
"""
import glob
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import socket
import time


def _path(value):
    path=Path(value)
    if not path.is_absolute() or '..' in path.parts or str(path)!=str(value):
        raise RuntimeError('Exact absolute registered path required')
    return path


def _read(path,*,root_fd=None):
    """Open each component without following links, including parent directories."""
    path=_path(path)
    fd=os.open('/',os.O_RDONLY|os.O_DIRECTORY) if root_fd is None else os.dup(root_fd)
    try:
        for part in path.parts[1:-1]:
            child=os.open(part,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=fd)
            os.close(fd);fd=child
        file_fd=os.open(path.name,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK,dir_fd=fd)
        with os.fdopen(file_fd,'rb') as stream:
            before=os.fstat(stream.fileno())
            if not stat.S_ISREG(before.st_mode):raise RuntimeError('Registered path is not a regular file')
            if before.st_size>1024*1024:raise RuntimeError('Registered file exceeds observation bound')
            data=stream.read(1024*1024+1);after=os.fstat(stream.fileno())
            if len(data)>1024*1024:raise RuntimeError('Registered file exceeds observation bound')
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
                    argv=[part.decode() for part in raw.rstrip(b'\0').split(b'\0')],
                    uid=int(status['Uid'].split()[1]),gid=int(status['Gid'].split()[1]),
                    cgroup=groups[0][3:])
    try:
        first=snapshot();second=snapshot()
    except (OSError,ValueError,KeyError,IndexError) as error:
        raise RuntimeError('Proxy process observation unavailable') from error
    if first!=second:raise RuntimeError('Proxy process changed during observation')
    return first


def observe(service,*,root=None,operation_id=None):
    """Consume a qualified transport observation, never infer it from ActiveState.

    The transport supplies file, manager, process, listener and loaded-credential
    proof. Separate real-systemd qualification remains required for admission.
    """
    from . import tls_proxy
    selected=operation_service(root,operation_id,service) if root is not None and 'immutableFiles' in service.get('systemd',{}) else service
    value=tls_proxy._service(selected)
    if set(value)!={'running','process','credentials'}:
        raise RuntimeError('Qualified systemd process and credential observation missing')
    return value


def control(root,operation_id,service,action,expected_credentials):
    """Original-operation credential load, with crash-safe restart intent.

    A reload is deliberately insufficient for changed LoadCredential bytes. This
    controller is internal; it does not grant registration or deployment admission.
    """
    from . import journal,runtime,tls_proxy
    if action!='load' or service.get('transport')!='systemd' or service.get('role')!='caddy':
        raise RuntimeError('Only registered systemd Caddy credential load is supported')
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.@-]*\.service',service.get('name','')):
        raise RuntimeError('Exact registered unit name required')
    _credentials(expected_credentials)
    if sorted(expected_credentials)!=service.get('systemd',{}).get('credentialNames'):
        raise RuntimeError('Registered credential names differ')
    with journal.locked(root) as root:
        op=journal.current(root)
        if op['operationId']!=operation_id or op['kind']!='rotate-public-tls' or op['phase'] not in {'PROXY_SWITCHING','ROLLBACK_SWITCHING','ACTIVATING','ROLLBACK_ACTIVATING'}:
            raise RuntimeError('Original proxy switching phase required')
        registered=journal._read(root,root/'operations'/(operation_id+'-proxy.json'))
        if sum(row['service']==service for row in registered['services'])!=1:
            raise RuntimeError('Exact original proxy registration required')
        direction='rollback' if op['phase'].startswith('ROLLBACK') else 'forward'
        path=root/'operations'/(operation_id+'-systemd-caddy-'+direction+'.json')
        identity={'service':service,'expected':expected_credentials,'direction':direction}
        saved=journal._read(root,path) if path.exists() else None
        if saved and saved['intent']!=identity:raise RuntimeError('Original credential load intent differs')
        if not tls_proxy.observe(root,operation_id)['closed']:
            raise RuntimeError('Outer proxy must be observed closed before credential load')
        before=observe(service,root=root,operation_id=operation_id)
        if saved is None:
            saved={'intent':identity,'before':before};journal._write(root,path,saved)
        if before['running'] and before['credentials']==expected_credentials:
            # A new load still requires a fresh process when source paths change,
            # even when the selected trust bytes happen to be identical.
            try:verify_restart(saved['before'],before)
            except RuntimeError:pass
            else:return before
        if before['running']:
            runtime.run(['systemctl','stop',service['name']])
            if observe(service,root=root,operation_id=operation_id)['running']:raise RuntimeError('Systemd stop unconfirmed')
        runtime.run(['systemctl','start',service['name']])
        after=await_started(service,root=root,operation_id=operation_id)
        verify_restart(saved['before'],after)
        if after['credentials']!=expected_credentials:
            raise RuntimeError('Loaded credentials differ from original intent')
        return after


def start_commands(role,configuration):
    """Fixed observed command forms; this alone does not qualify a complete unit."""
    configuration=str(_path(configuration))
    if role=='nginx':
        return {'check':['/usr/sbin/nginx','-t','-c',configuration],
                'start':['/usr/sbin/nginx','-c',configuration,'-g','daemon off;']}
    if role=='caddy':
        arguments=['--config',configuration,'--adapter','caddyfile']
        return {'check':['/usr/bin/caddy','validate',*arguments],
                'start':['/usr/bin/caddy','run',*arguments]}
    raise RuntimeError('Only the reviewed nginx/Caddy command forms are supported')


def credential_override(root,generation_id,sources):
    """Render the fixed Caddy credential set; no credential bytes are accessed.

    Installation/daemon-reload requires a separately sealed before/after unit graph
    and actual qualification. This renderer neither installs nor grants admission.
    """
    names=('server.crt','server.key','internal-ca.pem','issuer-ca.pem')
    if set(sources)!=set(names) or not re.fullmatch('[a-f0-9]{64}',generation_id):
        raise RuntimeError('Exact credential sources and sealed generation required')
    for value in [str(root),*sources.values()]:
        if not isinstance(value,str) or not re.fullmatch('/[A-Za-z0-9_./-]+',value):
            raise RuntimeError('Literal credential path required; unit specifiers are forbidden')
        _path(value)
    selected=dict(sources)
    selected['issuer-ca.pem']=str(Path(root)/'tls/generations'/generation_id/'http-trust.pem')
    # Reset first so an old manager-loaded assignment cannot remain alongside it.
    return '[Service]\nLoadCredential=\n'+''.join('LoadCredential='+name+':'+selected[name]+'\n' for name in names)


# Manager properties are compared after removing transient timestamps embedded in
# Exec* output. Comparing raw ExecStart output would break after every restart.
UNIT_PROPERTIES=('FragmentPath','DropInPaths','Type','User','Group','ExecStart','ExecStartPre','ExecReload','ExecStartPost','ExecStop','ExecStopPost','ExecCondition',
                 'LoadCredential','KillMode','KillSignal','TimeoutStopUSec','Restart','RestartUSec',
                 'NoNewPrivileges','PrivateTmp','PrivateDevices','ProtectHome','ProtectSystem',
                 'ProtectKernelTunables','ProtectKernelModules','ProtectControlGroups',
                 'RestrictAddressFamilies','CapabilityBoundingSet','UMask','StateDirectory',
                 'StateDirectoryMode','Environment','EnvironmentFiles','WorkingDirectory','SupplementaryGroups','PrivateNetwork','NetworkNamespacePath','MemoryMax','RootDirectory','RootImage',
                 'ReadWritePaths','ReadOnlyPaths','InaccessiblePaths','BindPaths','BindReadOnlyPaths')
STATE_PROPERTIES=('ActiveState','SubState','MainPID','ControlPID','ControlGroup','NeedDaemonReload')


EXEC_PROPERTIES=tuple(key for key in UNIT_PROPERTIES if key.startswith('Exec'))
STRUCTURED_PROPERTIES=(*EXEC_PROPERTIES,'LoadCredential','EnvironmentFiles','BindPaths','BindReadOnlyPaths')


def _structured_property(key,value):
    signature=('a(sasbttttuii)' if key in EXEC_PROPERTIES else
               {'LoadCredential':'a(ss)','EnvironmentFiles':'a(sb)','BindPaths':'a(ssbt)','BindReadOnlyPaths':'a(ssbt)'}[key])
    if not isinstance(value,dict) or set(value)!={'type','data'} or value['type']!=signature or not isinstance(value['data'],list):
        raise RuntimeError('Unsupported typed systemd property: '+key)
    rows=value['data']
    def string(item):return isinstance(item,str) and not any(ord(c)<32 for c in item) and item!='[unprintable]'
    if key in EXEC_PROPERTIES:
        commands=[]
        for row in rows:
            if not isinstance(row,list) or len(row)!=10:raise RuntimeError('Invalid typed executable record')
            path,argv,ignored,*status=row
            if not string(path) or not path.startswith('/') or not isinstance(argv,list) or not argv or not all(string(arg) for arg in argv) or argv[0]!=path or ignored is not False or not all(type(n) is int for n in status):
                raise RuntimeError('Ignored or indirect systemd executable is unsupported')
            commands.append(argv)
        return commands  # Preserve both command order and every argv boundary.
    if key=='LoadCredential':
        sources={}
        for row in rows:
            if not isinstance(row,list) or len(row)!=2 or not all(string(item) for item in row):raise RuntimeError('Invalid typed credential source')
            name,path=row
            if not re.fullmatch(r'[A-Za-z0-9_.-]+',name) or name in sources or not path.startswith('/') or any(c.isspace() for c in path) or ':' in path:
                raise RuntimeError('Ambiguous credential source binding')
            _path(path);sources[name]=path
        return ' '.join(name+':'+path for name,path in sorted(sources.items()))
    for row in rows:
        if not isinstance(row,list):raise RuntimeError('Invalid typed systemd array')
        valid=(len(row)==2 and string(row[0]) and type(row[1]) is bool) if key=='EnvironmentFiles' else (len(row)==4 and string(row[0]) and string(row[1]) and type(row[2]) is bool and type(row[3]) is int and row[3]>=0)
        if not valid:raise RuntimeError('Invalid typed systemd array')
    return rows


def unit_snapshot(name,properties):
    from . import runtime
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.@-]*\.service',name) or set(properties)!=set(UNIT_PROPERTIES):
        raise RuntimeError('Exact unit and complete loaded-property binding required')
    scalar=tuple(key for key in (*UNIT_PROPERTIES,*STATE_PROPERTIES) if key not in STRUCTURED_PROPERTIES)
    result=runtime.run(['systemctl','show',name,'--all','--no-pager','--property='+','.join(scalar)])
    rows={}
    for line in result.stdout.decode().splitlines():
        key,separator,value=line.partition('=')
        if not separator or key in rows or key not in scalar or '[unprintable]' in value:raise RuntimeError('Ambiguous systemd property response')
        rows[key]=value
    if set(rows)!=set(scalar):raise RuntimeError('Incomplete systemd property response')
    # systemctl's human renderer repeats Exec* keys, omits empty struct arrays,
    # and prints LoadCredential as [unprintable] on systemd 252. Read those exact
    # properties from D-Bus instead; no credential contents or GetAll request.
    object_path='/org/freedesktop/systemd1/unit/'+''.join(c if c.isascii() and c.isalnum() else '_'+format(ord(c),'02x') for c in name)
    result=runtime.run(['busctl','--system','--no-pager','--json=short','get-property','org.freedesktop.systemd1',object_path,'org.freedesktop.systemd1.Service',*STRUCTURED_PROPERTIES])
    def unique(pairs):
        value={}
        for key,item in pairs:
            if key in value:raise RuntimeError('Duplicate typed systemd JSON field')
            value[key]=item
        return value
    try:
        lines=result.stdout.decode().splitlines()
        if len(lines)!=len(STRUCTURED_PROPERTIES):raise RuntimeError('Incomplete typed systemd property response')
        for key,line in zip(STRUCTURED_PROPERTIES,lines):rows[key]=_structured_property(key,json.loads(line,object_pairs_hook=unique))
    except (ValueError,UnicodeError) as error:raise RuntimeError('Malformed typed systemd property response') from error
    return {'properties':{k:rows[k] for k in UNIT_PROPERTIES},'state':{k:rows[k] for k in STATE_PROPERTIES}}


def _binary_sha(path):
    from .bundle import sha
    return sha(Path(path))


def cgroup_pids(group):
    relative=_path(group)
    if str(relative)=='/':raise RuntimeError('Proxy cannot own the entire cgroup hierarchy')
    root=Path('/sys/fs/cgroup')/str(relative).lstrip('/')
    if not root.exists():return []  # Inactive systemd units normally lose their cgroup.
    if root.resolve()!=root:raise RuntimeError('Linked cgroup path')
    values=set()
    try:
        for file in [root/'cgroup.procs',*root.glob('**/cgroup.procs')]:
            for line in file.read_text().splitlines():
                if not line.isdigit() or int(line)<=0:raise RuntimeError('Invalid cgroup process observation')
                values.add(int(line))
    except OSError as error:raise RuntimeError('Proxy cgroup observation unavailable') from error
    return sorted(values)


class Starting(RuntimeError):
    """Only an absent owned listener may be retried during bounded startup."""


def _sockets(kind):
    rows=[]
    for suffix,family in (('',socket.AF_INET),('6',socket.AF_INET6)):
        path=Path('/proc/net')/(kind+suffix)
        for line in path.read_text().splitlines()[1:]:
            fields=line.split()
            if kind=='tcp' and fields[3]!='0A':continue
            address,port=fields[1].split(':');raw=bytes.fromhex(address)
            raw=b''.join(raw[i:i+4][::-1] for i in range(0,len(raw),4))
            rows.append((socket.inet_ntop(family,raw),int(port,16),fields[9]))
    return rows


def verify_listeners(expected,pids,running):
    """Prove every TCP listener belongs to the registered cgroup in this netns."""
    wanted=set()
    for row in expected:
        if set(row)!={'address','port'} or type(row['port']) is not int or not 0<row['port']<65536:
            raise RuntimeError('Exact registered listeners required')
        try:socket.inet_pton(socket.AF_INET6 if ':' in row['address'] else socket.AF_INET,row['address'])
        except (OSError,TypeError):raise RuntimeError('Literal listener address required')
        wanted.add((row['address'],row['port']))
    if not wanted or len(wanted)!=len(expected):raise RuntimeError('Nonempty unique listener set required')
    owned=set();namespace=os.stat('/proc/self/ns/net')
    try:
        for pid in pids:
            if type(pid) is not int or pid<=0:raise RuntimeError('Exact cgroup PID required')
            actual=os.stat(f'/proc/{pid}/ns/net')
            if (actual.st_dev,actual.st_ino)!=(namespace.st_dev,namespace.st_ino):raise RuntimeError('Proxy network namespace differs')
            for fd in Path(f'/proc/{pid}/fd').iterdir():
                try:target=os.readlink(fd)
                except FileNotFoundError:continue
                if re.fullmatch(r'socket:\[[0-9]+\]',target):owned.add(target[8:-1])
        tcp=_sockets('tcp');udp=_sockets('udp')
    except OSError as error:raise RuntimeError('Kernel listener observation unavailable') from error
    ports={port for _,port in wanted}
    # A foreign or wildcard listener on a registered port cannot establish closure
    # or startup, even if another owned listener happens to answer our probe.
    if any(port in ports and inode not in owned for _,port,inode in tcp):
        raise RuntimeError('Foreign listener occupies registered port')
    actual={(address,port) for address,port,inode in tcp if inode in owned}
    if any(inode in owned for _,_,inode in udp):raise RuntimeError('Unregistered proxy UDP listener')
    if not running:
        if actual or any(port in ports for _,port,_ in tcp):raise RuntimeError('Proxy listener remains after stop')
        return
    if actual-wanted:raise RuntimeError('Proxy owns an unregistered listener')
    if actual!=wanted:raise Starting('Registered proxy listeners not ready')


def await_started(service,*,root=None,operation_id=None,timeout=5):
    deadline=time.monotonic()+timeout
    while True:
        try:
            value=observe(service,root=root,operation_id=operation_id)
            if not value['running']:raise Starting('Registered proxy has not started')
            return value
        except Starting:
            if time.monotonic()>=deadline:raise RuntimeError('Registered proxy startup timed out')
            time.sleep(.1)


def verify_configuration_graph(service):
    """The reviewed profile has no nested nginx includes or Caddy imports."""
    profile=service['systemd'];main=profile['mainConfig']
    files={row['path'] for row in profile['immutableFiles']}
    if main not in files or service['config'] not in files:raise RuntimeError('Primary configuration graph missing')
    def text(path):return re.sub(r'#[^\n]*','',_read(path)[0].decode())
    if service['role']=='caddy':
        if main!=service['config'] or profile['includes'] or re.search(r'(?m)^\s*import\s',text(main)):
            raise RuntimeError('Caddy import or external configuration is unsupported')
        return
    if len(profile['includes'])!=1:raise RuntimeError('One closed nginx include directory required')
    pattern,members=next(iter(profile['includes'].items()))
    if len(members)!=2 or service['config'] not in members or any(path not in files for path in members):
        raise RuntimeError('Exact HTTP/HTTPS fragment graph required')
    content=text(main)
    if re.search(r'\bload_module\s',content):raise RuntimeError('External nginx modules are unsupported')
    includes=re.findall(r'\binclude\s+([^;]+);',content)
    if includes.count(pattern)!=1 or len(includes)!=2:raise RuntimeError('Exact main configuration include graph required')
    literals=[path for path in includes if path!=pattern]
    if len(literals)!=1 or literals[0] not in files or literals[0] in members or literals[0]==main:
        raise RuntimeError('Exact registered MIME include required')
    for path in [*members,*literals]:
        if re.search(r'\b(?:include|load_module)\s',text(path)):
            raise RuntimeError('Nested nginx includes/modules are unsupported')


def observe_service(service):
    """Observe a fully bound profile. Registration/loaded TLS qualification is separate."""
    profile=service.get('systemd')
    required={'unitFile','immutableFiles','includes','mainConfig','properties','process','credentialNames','listeners'}
    if not isinstance(profile,dict) or set(profile)!=required:
        raise RuntimeError('Qualified systemd observation profile missing; process state unknown')
    if set(profile['properties'])!=set(UNIT_PROPERTIES):raise RuntimeError('Complete loaded unit property binding required')
    if profile['unitFile']['sha256']!=service.get('identity'):raise RuntimeError('Original unit file identity differs')
    verify_files([profile['unitFile'],*profile['immutableFiles']]);verify_includes(profile['includes'])
    verify_configuration_graph(service)
    process_profile=profile['process'];commands=start_commands(service['role'],profile['mainConfig'])
    if profile['properties']['ExecStart']!=[commands['start']] or profile['properties']['ExecStartPre']!=[commands['check']]:
        raise RuntimeError('Registered startup does not use the fixed proxy commands')
    if profile['properties']['FragmentPath']!=profile['unitFile']['path']:
        raise RuntimeError('Manager unit fragment is not the registered file')
    permitted=[commands['start']]
    if service['role']=='nginx':permitted.append(['nginx: master process '+' '.join(commands['start'])])
    if process_profile['argv'] not in permitted:
        raise RuntimeError('Registered process does not load the exact approved configuration')
    if service['role']=='nginx' and profile['properties']['Type']!='simple':raise RuntimeError('Qualified nginx must use the simple foreground profile')
    if service['role']=='caddy' and profile['properties']['ExecReload']:raise RuntimeError('Qualified Caddy must use controlled stop/start')
    if process_profile['executable']!=commands['start'][0] or _binary_sha(process_profile['executable'])!=service['image'].removeprefix('sha256:'):
        raise RuntimeError('Registered systemd executable differs')
    first=unit_snapshot(service['name'],profile['properties'])
    if first['properties']!=profile['properties'] or first['state']['NeedDaemonReload']!='no':
        raise RuntimeError('Loaded unit differs or daemon reload is pending')
    state=dict(first['state']);pid=state['MainPID']
    if not pid.isdigit():raise RuntimeError('Invalid manager PID')
    process=read_process(int(pid)) if int(pid)>0 else None
    # An inactive unit may have no ControlGroup property, but its original cgroup
    # must still be checked for surviving workers before reporting closure.
    if state['ActiveState']=='inactive' and state['SubState']=='dead' and not state['ControlGroup']:
        state['ControlGroup']=process_profile['cgroup']
    pids=cgroup_pids(process_profile['cgroup'])
    value=verify_process(process_profile,state,process,pids)
    verify_listeners(profile['listeners'],pids,value['running'])
    value['credentials']={}
    if value['running'] and profile['credentialNames']:
        value['credentials']=loaded_credentials(service['name'],process,profile['credentialNames'])
    if unit_snapshot(service['name'],profile['properties'])!=first:
        raise RuntimeError('Systemd unit changed during observation')
    verify_files([profile['unitFile'],*profile['immutableFiles']]);verify_includes(profile['includes'])
    return value


def credential_hashes_at(root_fd,name,names):
    """Hash credential bytes inside an already-open, kernel-bound process root."""
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.@-]*\.service',name) or not names or names!=sorted(set(names)):
        raise RuntimeError('Exact unit credential names required')
    _credentials({key:'0'*64 for key in names})
    return {key:hashlib.sha256(_read(Path('/run/credentials')/name/key,root_fd=root_fd)[0]).hexdigest() for key in names}


def loaded_credentials(name,process,names):
    """No secret bytes leave this function; PrivateMounts paths use the process root."""
    if read_process(process['pid'])!=process:raise RuntimeError('Credential process identity changed')
    proc=Path('/proc')/str(process['pid'])
    try:
        environment=(proc/'environ').read_bytes().split(b'\0')
        directories=[item.partition(b'=')[2] for item in environment if item.startswith(b'CREDENTIALS_DIRECTORY=')]
        if directories!=[('/run/credentials/'+name).encode()]:raise RuntimeError('Loaded credential directory differs')
        # Following this kernel-managed link is intentional; all subsequent file
        # components are opened relative to its fd without following symlinks.
        fd=os.open(proc/'root',os.O_RDONLY|os.O_DIRECTORY)
        try:value=credential_hashes_at(fd,name,names)
        finally:os.close(fd)
    except OSError as error:raise RuntimeError('Loaded credential observation unavailable') from error
    if read_process(process['pid'])!=process:raise RuntimeError('Credential process changed during observation')
    return value


def _atomic_file(path,data,metadata):
    """Preserve approved owner/mode before exposing the replacement inode."""
    import secrets
    path=_path(path)
    if path.parent.resolve()!=path.parent or path.is_symlink():raise RuntimeError('Linked controlled configuration path')
    path.parent.mkdir(mode=0o700,parents=True,exist_ok=True)
    directory=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
    temporary='.'+path.name+'.'+secrets.token_hex(8)
    try:
        fd=os.open(temporary,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600,dir_fd=directory)
        with os.fdopen(fd,'wb') as output:
            os.fchown(output.fileno(),metadata['uid'],metadata['gid'])
            os.fchmod(output.fileno(),metadata['mode'])
            output.write(data);output.flush();os.fsync(output.fileno())
        os.replace(temporary,path.name,src_dir_fd=directory,dst_dir_fd=directory);os.fsync(directory)
    finally:
        try:os.unlink(temporary,dir_fd=directory)
        except FileNotFoundError:pass
        os.close(directory)


def _credential_intent_path(root,operation_id):
    return root/'operations'/(operation_id+'-systemd-credential-sources.json')


def switch_credential_sources(root,operation_id,service,generation_id,action):
    """Install/revert only the sealed Caddy LoadCredential drop-in, while closed."""
    import copy
    from . import journal,runtime,tls_proxy
    if action not in {'forward','rollback'} or service.get('role')!='caddy' or service.get('transport')!='systemd':
        raise RuntimeError('Exact Caddy credential source action required')
    with journal.locked(root) as root:
        op=journal.current(root)
        allowed={'PROXY_SWITCHING','ACTIVATING'} if action=='forward' else {'ROLLBACK_SWITCHING','ROLLBACK_ACTIVATING'}
        if op['operationId']!=operation_id or op['kind']!='rotate-public-tls' or op['phase'] not in allowed:
            raise RuntimeError('Original credential source phase required')
        registry=journal._read(root,root/'operations'/(operation_id+'-proxy.json'))
        if sum(row['service']==service for row in registry['services'])!=1:raise RuntimeError('Original Caddy registration differs')
        profile=service['systemd'];unit=Path(profile['unitFile']['path'])
        if unit.name!=service['name']:raise RuntimeError('Unit name and fragment differ')
        destination=unit.parent/(service['name']+'.d')/'50-ols-public-tls.conf'
        sources={}
        for token in profile['properties']['LoadCredential'].split():
            key,separator,value=token.partition(':')
            if not separator or key in sources:raise RuntimeError('Ambiguous original credential sources')
            sources[key]=value
        after=credential_override(root,generation_id,sources)
        trust=root/'tls/generations'/generation_id/'http-trust.pem'
        intent_path=_credential_intent_path(root,operation_id)
        intent=journal._read(root,intent_path) if intent_path.exists() else None
        identity={'service':service,'generationId':generation_id,'path':str(destination),'after':after}
        if intent and intent['identity']!=identity:raise RuntimeError('Original credential source intent differs')
        verify_files([profile['unitFile'],*[r for r in profile['immutableFiles'] if r['path']!=str(destination)]])
        siblings=sorted(str(p) for p in destination.parent.glob('*.conf'))
        if siblings not in ([],[str(destination)]):raise RuntimeError('Unregistered systemd drop-in exists')
        current=_read(destination)[0].decode() if destination.exists() else None
        if intent is None:
            if action!='forward':raise RuntimeError('Original forward credential intent missing')
            if current is not None:
                records=[r for r in profile['immutableFiles'] if r['path']==str(destination)]
                if len(records)!=1 or profile['properties']['DropInPaths']!=str(destination):raise RuntimeError('Unregistered existing credential drop-in')
                verify_files(records);metadata=records[0]
            else:
                if profile['properties']['DropInPaths']:raise RuntimeError('Original drop-in reference unavailable')
                metadata={'uid':os.geteuid(),'gid':os.getegid(),'mode':0o600}
            trust_hash=hashlib.sha256(_read(trust)[0]).hexdigest()
            intent={'identity':identity,'before':current,'metadata':metadata,'directoryExisted':destination.parent.exists(),'trustSha256':trust_hash}
            if not tls_proxy.observe(root,operation_id)['closed']:raise RuntimeError('Outer proxy must be closed')
            journal._write(root,intent_path,intent)
        if current not in (intent['before'],after):raise RuntimeError('Owned credential drop-in was changed')
        if action=='forward' and hashlib.sha256(_read(trust)[0]).hexdigest()!=intent['trustSha256']:
            raise RuntimeError('Original generation trust changed')
        if not tls_proxy.observe(root,operation_id)['closed']:raise RuntimeError('Outer proxy must be closed')
        desired=after if action=='forward' else intent['before']
        if current!=desired:
            if desired is None:
                destination.unlink()
                fd=os.open(destination.parent,os.O_RDONLY|os.O_DIRECTORY)
                try:os.fsync(fd)
                finally:os.close(fd)
                if not intent['directoryExisted']:destination.parent.rmdir()
            else:_atomic_file(destination,desired.encode(),intent['metadata'])
        # Reissuing only this metadata refresh is safe after a lost response; actual
        # manager properties and live credentials still have to reconcile afterward.
        runtime.run(['systemctl','daemon-reload'])
        if action=='rollback':return service
        selected=copy.deepcopy(service);updated=selected['systemd']
        selected_sources=dict(sources,**{'issuer-ca.pem':str(trust)})
        updated['properties']['LoadCredential']=' '.join(key+':'+value for key,value in sorted(selected_sources.items()))
        updated['properties']['DropInPaths']=str(destination)
        record=dict(intent['metadata'],path=str(destination),sha256=hashlib.sha256(after.encode()).hexdigest())
        updated['immutableFiles']=[r for r in profile['immutableFiles'] if r['path']!=str(destination)]+[record]
        return selected


def _configuration_record(service):
    records=[r for r in service['systemd']['immutableFiles'] if r['path']==service['config']]
    if len(records)!=1:raise RuntimeError('Exact primary configuration record required')
    return records[0]


def read_configuration(service):
    data,info=_read(service['config']);record=_configuration_record(service)
    if (info.st_uid,info.st_gid,stat.S_IMODE(info.st_mode))!=(record['uid'],record['gid'],record['mode']):
        raise RuntimeError('Registered configuration ownership or mode differs')
    return data


def write_configuration(service,data,*,allowed):
    if read_configuration(service) not in allowed:raise RuntimeError('Live configuration differs from original operation')
    _atomic_file(Path(service['config']),data,_configuration_record(service))
    if read_configuration(service)!=data:raise RuntimeError('Configuration installation not observed')


def replace_public_paths(service,data,paths):
    if service['role']=='caddy':
        if service['tlsPaths']:raise RuntimeError('Systemd Caddy trust must use registered credentials')
        return data
    if set(service['tlsPaths'])!={'certificate','privateKey'}:raise RuntimeError('Exact public nginx TLS references required')
    for name,directive in [('certificate','ssl_certificate'),('privateKey','ssl_certificate_key')]:
        old=service['tlsPaths'][name];new=paths[name]
        for value in (old,new):
            if not re.fullmatch('/[A-Za-z0-9_./-]+',value):raise RuntimeError('Literal public TLS path required')
            _path(value)
        pattern=rb'(?m)^(\s*'+directive.encode()+rb'\s+)([^;\n]+)(;)'
        matches=list(re.finditer(pattern,data))
        if len(matches)!=1 or matches[0].group(2)!=old.encode():raise RuntimeError('Declared public TLS directive differs')
        data=re.sub(pattern,lambda match:match.group(1)+new.encode()+match.group(3),data)
    return data


def nginx_candidate(service,candidate):
    """Validate the HTTPS fragment in its complete, closed original include graph."""
    from . import runtime
    profile=service['systemd'];candidate=_path(candidate)
    verify_configuration_graph(service)
    if re.search(rb'\b(?:include|load_module)\s',re.sub(rb'#[^\n]*',b'',_read(candidate)[0])):raise RuntimeError('Nested candidate includes/modules are unsupported')
    verify_includes(profile['includes'])
    verify_files([record for record in profile['immutableFiles'] if record['path']!=service['config']])
    if len(profile['includes'])!=1:raise RuntimeError('One closed nginx conf.d include is required')
    pattern,members=next(iter(profile['includes'].items()))
    if len(members)!=2 or service['config'] not in members:raise RuntimeError('Exactly HTTP and HTTPS fragments are required')
    main=_read(profile['mainConfig'])[0].decode()
    includes=re.findall(r'\binclude\s+([^;\s]+)\s*;',re.sub(r'#[^\n]*','',main))
    known={row['path'] for row in profile['immutableFiles']}
    if includes.count(pattern)!=1 or any(value!=pattern and value not in known for value in includes):
        raise RuntimeError('Unregistered nginx include reference')
    replacements='\n'.join('include '+(str(candidate) if value==service['config'] else value)+';' for value in members)
    main,count=re.subn(r'\binclude\s+'+re.escape(pattern)+r'\s*;',lambda _:replacements,main)
    if count!=1:raise RuntimeError('Original nginx include differs')
    target=candidate.with_name(candidate.name+'.nginx-main.conf')
    data=main.encode()
    if target.exists() and _read(target)[0]!=data:raise RuntimeError('Original complete candidate differs')
    if not target.exists():runtime.private_file(target,data)
    return target


def credential_variant(service,intent,forward):
    import copy
    if not forward:return copy.deepcopy(service)
    selected=copy.deepcopy(service);profile=selected['systemd'];destination=intent['identity']['path']
    sources={token.partition(':')[0]:token.partition(':')[2] for token in profile['properties']['LoadCredential'].split()}
    lines=intent['identity']['after'].splitlines()
    new_sources={line.removeprefix('LoadCredential=').partition(':')[0]:line.partition(':')[2] for line in lines if line.startswith('LoadCredential=') and ':' in line}
    if set(new_sources)!=set(sources):raise RuntimeError('Credential override set differs')
    profile['properties']['LoadCredential']=' '.join(k+':'+v for k,v in sorted(new_sources.items()))
    profile['properties']['DropInPaths']=destination
    record=dict(intent['metadata'],path=destination,sha256=hashlib.sha256(intent['identity']['after'].encode()).hexdigest())
    profile['immutableFiles']=[r for r in profile['immutableFiles'] if r['path']!=destination]+[record]
    return selected


def operation_service(root,operation_id,service):
    """Derive only sealed before/after file variants, never adopt observed bytes."""
    import copy
    from . import journal
    value=journal._read(root,root/'operations'/(operation_id+'-proxy.json'))
    rows=[r for r in value['services'] if r['service']==service]
    if len(rows)!=1:raise RuntimeError('Original proxy service binding differs')
    row=rows[0];selected=copy.deepcopy(service)
    intent_path=_credential_intent_path(root,operation_id)
    if service['role']=='caddy' and intent_path.exists():
        intent=journal._read(root,intent_path)
        if intent['identity']['service']!=service:raise RuntimeError('Original Caddy source binding differs')
        destination=Path(intent['identity']['path'])
        actual=_read(destination)[0].decode() if destination.exists() else None
        if actual not in (intent['before'],intent['identity']['after']):raise RuntimeError('Credential source override changed')
        selected=credential_variant(service,intent,actual==intent['identity']['after'])
    data=read_configuration(service)
    allowed=[row['before'].encode(),row['after'].encode()]
    maintenance=root/'operations'/(operation_id+'-issuer-maintenance.json')
    if service['role']=='nginx' and maintenance.exists():
        saved=journal._read(root,maintenance)
        if saved['service']!=service:raise RuntimeError('Original maintenance service differs')
        allowed.append(saved['configuration'].encode())
    if data not in allowed:raise RuntimeError('Live proxy configuration conflicts with original operation')
    for record in selected['systemd']['immutableFiles']:
        if record['path']==service['config']:record['sha256']=hashlib.sha256(data).hexdigest()
    return selected


def reconcile_manager(root,operation_id):
    """Recover an interrupted owned drop-in write before ordinary observation."""
    from . import journal,runtime,tls_proxy
    path=_credential_intent_path(root,operation_id)
    if not path.exists():return
    intent=journal._read(root,path);service=intent['identity']['service']
    selected=operation_service(root,operation_id,service)
    verify_files([selected['systemd']['unitFile'],*selected['systemd']['immutableFiles']])
    destination=Path(intent['identity']['path'])
    members=sorted(str(p) for p in destination.parent.glob('*.conf'))
    if members not in ([],[str(destination)]):raise RuntimeError('Unregistered systemd drop-in exists')
    snapshot=unit_snapshot(service['name'],selected['systemd']['properties'])
    if snapshot['state']['NeedDaemonReload']=='no' and snapshot['properties']==selected['systemd']['properties']:return
    before=service['systemd']['properties'];after=credential_variant(service,intent,True)['systemd']['properties']
    if snapshot['properties'] not in (before,after):raise RuntimeError('Unrecognized loaded unit cannot be reconciled')
    op=journal.current(root)
    if op['operationId']!=operation_id or op['phase'] not in {'PROXY_SWITCHING','ACTIVATING','ROLLBACK_SWITCHING','ROLLBACK_ACTIVATING'}:
        raise RuntimeError('Original credential source reconciliation phase required')
    proxy=journal._read(root,root/'operations'/(operation_id+'-proxy.json'))
    outer=next(r['service'] for r in proxy['services'] if r['service']['role']=='nginx')
    observed=tls_proxy._state(root,operation_id,outer)
    if observed['running']:
        from . import tls_maintenance
        if not tls_maintenance.configured(root,operation_id,outer):raise RuntimeError('Outer proxy must be closed for manager reconciliation')
        tls_proxy._action(outer,'stop')
        if tls_proxy._state(root,operation_id,outer)['running']:raise RuntimeError('Outer proxy stop unconfirmed')
    runtime.run(['systemctl','daemon-reload'])
    actual=unit_snapshot(service['name'],selected['systemd']['properties'])
    if actual['state']['NeedDaemonReload']!='no' or actual['properties']!=selected['systemd']['properties']:
        raise RuntimeError('Owned manager refresh remains unknown')
