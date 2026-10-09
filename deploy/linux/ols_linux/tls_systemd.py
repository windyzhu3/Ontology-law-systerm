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


def observe(service):
    """Consume a qualified transport observation, never infer it from ActiveState.

    The current systemd transport lacks the complete process/credential proof and
    therefore remains blocked. Its qualification adapter must supply these facts
    before this controller can cause any service effect.
    """
    from . import tls_proxy
    value=tls_proxy._service(service)
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
        before=observe(service)
        if saved is None:
            saved={'intent':identity,'before':before};journal._write(root,path,saved)
        if before['running'] and before['credentials']==expected_credentials:
            # On response loss this is the same original intent, not a fresh load.
            if saved['before']['credentials']!=expected_credentials:verify_restart(saved['before'],before)
            return before
        if before['running']:
            runtime.run(['systemctl','stop',service['name']])
            if observe(service)['running']:raise RuntimeError('Systemd stop unconfirmed')
        runtime.run(['systemctl','start',service['name']])
        after=observe(service)
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
UNIT_PROPERTIES=('FragmentPath','DropInPaths','Type','User','Group','ExecStart','ExecStartPre','ExecReload',
                 'LoadCredential','KillMode','KillSignal','TimeoutStopUSec','Restart','RestartUSec',
                 'NoNewPrivileges','PrivateTmp','PrivateDevices','ProtectHome','ProtectSystem',
                 'ProtectKernelTunables','ProtectKernelModules','ProtectControlGroups',
                 'RestrictAddressFamilies','CapabilityBoundingSet','UMask','StateDirectory',
                 'StateDirectoryMode','Environment','MemoryMax','RootDirectory','RootImage',
                 'ReadWritePaths','ReadOnlyPaths','InaccessiblePaths','BindPaths','BindReadOnlyPaths')
STATE_PROPERTIES=('ActiveState','SubState','MainPID','ControlPID','ControlGroup','NeedDaemonReload')


def unit_snapshot(name,properties):
    from . import runtime
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.@-]*\.service',name) or set(properties)!=set(UNIT_PROPERTIES):
        raise RuntimeError('Exact unit and complete loaded-property binding required')
    result=runtime.run(['systemctl','show',name,'--property='+','.join((*UNIT_PROPERTIES,*STATE_PROPERTIES))])
    rows={}
    for line in result.stdout.decode().splitlines():
        key,separator,value=line.partition('=')
        if not separator or key in rows:raise RuntimeError('Ambiguous systemd property response')
        if key.startswith('Exec'):
            commands=re.findall(r'\{ path=([^;]+) ; argv\[\]=(.+?) ; ignore_errors=(yes|no) ;',value)
            if value and not commands:raise RuntimeError('Unsupported systemd executable property format')
            if any(ignored!='no' or not argv.startswith(path.strip()+' ') for path,argv,ignored in commands):
                raise RuntimeError('Ignored or indirect systemd executable is unsupported')
            value='\n'.join(argv for _,argv,_ in commands)
        rows[key]=value
    if set(rows)!=set(UNIT_PROPERTIES)|set(STATE_PROPERTIES):raise RuntimeError('Incomplete systemd property response')
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


def observe_service(service):
    """Observe a fully bound profile. Registration/loaded TLS qualification is separate."""
    profile=service.get('systemd')
    required={'unitFile','immutableFiles','includes','mainConfig','properties','process','credentialNames'}
    if not isinstance(profile,dict) or set(profile)!=required:
        raise RuntimeError('Qualified systemd observation profile missing')
    if set(profile['properties'])!=set(UNIT_PROPERTIES):raise RuntimeError('Complete loaded unit property binding required')
    if profile['unitFile']['sha256']!=service.get('identity'):raise RuntimeError('Original unit file identity differs')
    verify_files([profile['unitFile'],*profile['immutableFiles']]);verify_includes(profile['includes'])
    process_profile=profile['process'];commands=start_commands(service['role'],profile['mainConfig'])
    if profile['properties']['ExecStart']!=' '.join(commands['start']) or profile['properties']['ExecStartPre']!=' '.join(commands['check']):
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
    value=verify_process(process_profile,state,process,cgroup_pids(process_profile['cgroup']))
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
