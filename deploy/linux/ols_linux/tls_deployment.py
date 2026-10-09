"""Derive TLS-only configurations without changing the sealed application release."""
import copy
import json
from pathlib import Path
from . import journal,runtime,tls_generation,tls_material
from .config import digest,canonical
from .bundle import sha


def prepare(root: Path,operation_id: str,candidate: dict,trust: dict) -> dict:
    with journal.locked(root) as root:
        tls_generation.current(root,operation_id)
        original=journal._read(root,root/'launch.json');old=tls_generation.resolve(root)
        layout=tls_generation.layout(root,operation_id,candidate);gid=layout['generationId']
        binding=journal._read(root,Path(original['binding']))
        if digest(binding)!=original['bindingDigest']:raise RuntimeError('Original deployment binding differs')
        directory=root/'deployments'/original['descriptorDigest']/'tls'/gid
        config_map={};files={}
        for name,expected in binding['files'].items():
            path=root/name
            if sha(path)!=expected or path.resolve()!=path.absolute():raise RuntimeError('Original configuration changed')
            if path.name not in {'api.properties','worker.properties','entry.json'}:raise RuntimeError('Unknown TLS deployment configuration')
            data=path.read_bytes()
            if path.name=='entry.json':
                entry=json.loads(data);entry.update(certificate=layout['paths']['certificate'],privateKey=layout['paths']['privateKey'],ca=trust['httpTrust'])
                data=canonical(entry)
            else:
                data=data.replace(old['paths']['javaTrustStore'].encode(),trust['javaTrustStore'].encode())
            target=directory/path.name
            if target.exists() and target.read_bytes()!=data:raise RuntimeError('Original TLS configuration differs')
            if not target.exists():runtime.private_file(target,data)
            config_map[str(path)]=str(target);files[str(target.relative_to(root))]=sha(target)
        launch=copy.deepcopy(original);record=copy.deepcopy(binding);record['files']=files.copy()
        for entry in [*launch['containers'],launch['ingress']]:
            role=entry['role']
            if role not in {'api','worker','entry'}:continue
            oldname=entry['name'];newname=oldname+'-tls-'+operation_id[:8]
            args=[]
            for value in entry['args']:
                if value==oldname:value=newname
                for source,target in config_map.items():value=value.replace(source,target)
                if value.startswith('ols.operation='):value='ols.operation='+operation_id
                args.append(value)
            sealed=digest({'parentLaunch':entry['digest'],'generationId':gid,'args':args})
            args=[('ols.launch='+sealed if value.startswith('ols.launch=') else value) for value in args]
            entry.update(name=newname,args=args,digest=sealed);record['names'][role]=newname
        record['tlsGenerationId']=gid;record['parentBindingDigest']=original['bindingDigest']
        path=directory/'binding.json'
        if path.exists() and journal._read(root,path)!=record:raise RuntimeError('Original derived binding differs')
        if not path.exists():journal._write(root,path,record)
        files[str(path.relative_to(root))]=sha(path)
        launch.update(binding=str(path),bindingDigest=digest(record),tlsGenerationId=gid)
        helper=Path(layout['paths']['certificate']).parent/'https-json.mjs'
        data=(Path(__file__).parents[1]/'runtime/tls-https-json.mjs').read_bytes()
        if helper.exists() and helper.read_bytes()!=data:raise RuntimeError('Original TLS helper differs')
        if not helper.exists():runtime.private_file(helper,data)
        files[str(helper.relative_to(root))]=sha(helper)
        return {'launch':launch,'files':files,'parentLaunch':original,'httpHelper':str(helper)}


def copy_identity(root,name,generation):
    from . import identity
    if runtime.owned(root,'container',name)['State']['Running']:raise RuntimeError('Stop identity before TLS copy')
    p=generation['paths']
    identity._copy_files(name,'/opt/keycloak/conf',{'server.crt':tls_material.read_private(p['certificate']),
                                                   'server.key':tls_material.read_private(p['privateKey'])})


def _recover_retained_entry(root,operation_id,generation,entry):
    """Recognize only the original stopped entry lost from resources by rollback."""
    op=tls_generation.current(root,operation_id)
    if op['phase']!='SWITCHING' or not any(e.get('recoveryFrom')=='ROLLBACK_BLOCKED' for e in op['events']):
        raise RuntimeError('Foreign TLS replacement container')
    saved=journal._read(root,root/'operations'/(operation_id+'-tls.json'))
    sealed=tls_generation.read(root,saved['generationId'])
    if sealed!=generation or sealed['operationId']!=operation_id or entry!=sealed['deployment']['launch']['ingress'] or entry['role']!='entry':
        raise RuntimeError('Original retained entry binding differs')
    args=entry['args'];options={};labels={};log={};mounts=[];index=3
    if args[:3]!=['docker','run','-d']:raise RuntimeError('Original entry command differs')
    single={'--name','--network','--log-driver','--memory','--cpus','--entrypoint'}
    while index<len(args) and args[index].startswith('--'):
        flag=args[index]
        if index+1>=len(args):raise RuntimeError('Original entry option incomplete')
        value=args[index+1];index+=2
        if flag in single and flag not in options:options[flag]=value
        elif flag in {'--label','--log-opt'}:
            key,sep,item=value.partition('=');target=labels if flag=='--label' else log
            if not sep or key in target:raise RuntimeError('Original entry option ambiguous')
            target[key]=item
        elif flag=='--mount':mounts.append(value)
        else:raise RuntimeError('Unsupported retained entry option')
    if set(options)!=single or options['--name']!=entry['name'] or options['--entrypoint']!='node' or options['--memory']!='768m' or options['--cpus']!='2' or options['--log-driver']!='local' or log!={'max-size':'10m','max-file':'2'} or mounts!=[f'type=bind,source={root},target={root},readonly']:
        raise RuntimeError('Original retained entry profile differs')
    if index+2>=len(args) or not options['--network'].startswith('container:'):raise RuntimeError('Original entry image/command/network missing')
    image=args[index];command=args[index+1:]
    expected_labels={runtime.LABEL:op['instanceId'],'ols.operation':operation_id,'ols.launch':entry['digest']}
    if labels!=expected_labels:raise RuntimeError('Original entry labels differ')
    actual=runtime.owned(root,'container',entry['name']);cfg=actual['Config'];host=actual['HostConfig']
    image_info=runtime.inspect('image',image)
    if not image_info or actual['Image']!=image_info['Id'] or cfg['Image']!=image:raise RuntimeError('Retained entry image differs')
    image_config=image_info.get('Config') or {}
    if actual.get('Name')!='/'+entry['name'] or actual['State']['Running'] or cfg.get('Labels')!=dict(image_config.get('Labels') or {},**expected_labels):raise RuntimeError('Retained entry identity or state differs')
    if cfg.get('Entrypoint')!=['node'] or cfg.get('Cmd')!=command or actual.get('Path')!='node' or actual.get('Args')!=command or cfg.get('Env',[])!=image_config.get('Env',[]) or cfg.get('User','')!=image_config.get('User','') or cfg.get('WorkingDir','')!=image_config.get('WorkingDir',''):
        raise RuntimeError('Retained entry effective command differs')
    pod=runtime.owned(root,'container',options['--network'].removeprefix('container:'))
    if host.get('NetworkMode') not in {options['--network'],'container:'+pod['Id']} or host.get('PortBindings') or host.get('PublishAllPorts') or actual.get('NetworkSettings',{}).get('Ports'):
        raise RuntimeError('Retained entry network or ports differ')
    effective=[{k:m.get(k) for k in ('Type','Source','Destination','RW')} for m in actual.get('Mounts',[])]
    if effective!=[{'Type':'bind','Source':str(root),'Destination':str(root),'RW':False}]:raise RuntimeError('Retained entry mounts differ')
    if host.get('Memory')!=805306368 or host.get('NanoCpus')!=2000000000 or host.get('LogConfig')!={'Type':'local','Config':log}:
        raise RuntimeError('Retained entry resource limits differ')
    if any(host.get(k) for k in ('Privileged','PublishAllPorts','Binds','VolumesFrom','CapAdd','Devices','DeviceRequests','DeviceCgroupRules','SecurityOpt','ExtraHosts','AutoRemove','PidMode','UTSMode','UsernsMode')) or host.get('IpcMode') not in {None,'','private'} or host.get('RestartPolicy',{}).get('Name','no') not in {'','no'}:
        raise RuntimeError('Retained entry host privileges differ')


def switch(root: Path,operation_id: str,generation: dict) -> None:
    with journal.locked(root) as root:
        op=tls_generation.current(root,operation_id)
        if op['phase']!='SWITCHING':raise RuntimeError('TLS switch phase required')
        resources=runtime.load(root);launch=generation['deployment']['launch']
        known=set(resources['containers'].values())|set(resources['writers'])
        for name in known|({resources['ingress']} if resources.get('ingress') else set()):
            if runtime.inspect('container',name) and runtime.owned(root,'container',name)['State']['Running'] and name in set(resources['writers'])|{resources.get('ingress')}:
                raise RuntimeError('TLS switch requires stopped writers')
        for entry in [*launch['containers'],launch['ingress']]:
            name=entry['name'];role=entry['role']
            if name not in known and runtime.inspect('container',name):
                _recover_retained_entry(root,operation_id,generation,entry)
            old=resources['containers'].get(role)
            if old and old!=name:resources['containers']['tlsRetained'+role+operation_id]=old
            resources['containers'][role]=name
            if role!='entry' and name not in resources['writers']:resources['writers'].append(name)
        resources['ingress']=launch['ingress']['name'];runtime.save(root,resources)
        for entry in [*launch['containers'],launch['ingress']]:
            if entry['role'] in {'identity','scanner'}:continue
            actual=runtime.inspect('container',entry['name'])
            if actual:
                if runtime.owned(root,'container',entry['name'])['Config']['Labels'].get('ols.launch')!=entry['digest']:raise RuntimeError('TLS launch differs')
            else:
                from .deployment import create_args
                runtime.run(create_args(entry['args']))
        copy_identity(root,resources['containers']['identity'],generation)
        tls_generation.select(root,operation_id,generation['parentGenerationId'],generation['generationId'])
        journal._write(root,root/'launch.json',launch)


def verify_copies(root: Path,generation: dict) -> dict:
    """Read certificates only: private key is verified locally, never returned."""
    resources=runtime.load(root);name=resources['containers']['identity'];runtime.owned(root,'container',name)
    value=runtime.run(['docker','cp',name+':/opt/keycloak/conf/server.crt','-']).stdout
    import io,tarfile
    with tarfile.open(fileobj=io.BytesIO(value)) as archive:
        members=archive.getmembers()
        if len(members)!=1 or not members[0].isfile():raise RuntimeError('Identity certificate copy ambiguous')
        cert=archive.extractfile(members[0]).read()
    fp=tls_material.fingerprint(tls_material.certificates(cert)[0])
    if fp!=generation['candidate']['leafDerSha256']:raise RuntimeError('Identity still holds another certificate')
    return {'identityLeafSha256':fp}
