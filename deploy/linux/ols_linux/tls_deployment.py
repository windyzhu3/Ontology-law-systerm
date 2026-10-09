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
            if name not in known and runtime.inspect('container',name):raise RuntimeError('Foreign TLS replacement container')
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
