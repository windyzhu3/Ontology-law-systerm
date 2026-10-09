#!/usr/bin/env python3
"""The single operator entry. Secrets are supplied only through private files."""
import argparse
import json
import os
from pathlib import Path
import sys
import time
sys.dont_write_bytecode=True
sys.path.insert(0,str(Path(__file__).resolve().parent))
from ols_linux import tls_rotation,tls_status,tls_material,assembly,build,bundle,business_config,checkpoint,config,identity,initialize,journal,release,runtime,verify


def parser():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--runtime',type=Path,required=True)
    commands=p.add_subparsers(dest='command',required=True)
    prepare=commands.add_parser('prepare');prepare.add_argument('--settings-file',type=Path,required=True);prepare.add_argument('--config',type=Path,required=True)
    describe=commands.add_parser('describe-bundle')
    describe.add_argument('--repo',type=Path,required=True)
    for name in ('jar','spa'):describe.add_argument('--'+name,type=Path)
    describe.add_argument('--commit');describe.add_argument('--output',type=Path)
    describe.add_argument('--build-directory',type=Path);describe.add_argument('--oidc-settings-file',type=Path)
    initial=commands.add_parser('initialize');initial.add_argument('--config',type=Path,required=True)
    initial.add_argument('--bundle',type=Path,required=True);initial.add_argument('--initial-password-file',type=Path,required=True)
    initial.add_argument('--sessions-file',type=Path)
    initial.add_argument('--capture-admin',choices=('dingqiming','huangxuexue'))
    resume=commands.add_parser('initialize-resume');resume.add_argument('--operation-id',required=True);resume.add_argument('--sessions-file',type=Path)
    resume.add_argument('--capture-admin',choices=('dingqiming','huangxuexue'))
    check=commands.add_parser('verify-initialization');check.add_argument('--config',type=Path,required=True)
    for name in ('upgrade','publish-bytes'):
        command=commands.add_parser(name);command.add_argument('--bundle',type=Path,required=True)
    resume=commands.add_parser('upgrade-resume');resume.add_argument('--operation-id',required=True)
    restore=commands.add_parser('restore-checkpoint');restore.add_argument('--operation-id',required=True)
    for name in ('release-status','start','stop','health'):commands.add_parser(name)
    status=commands.add_parser('public-tls-status');status.add_argument('--previous-check-file',type=Path)
    rotate=commands.add_parser('rotate-public-tls');rotate.add_argument('--inputs-file',type=Path,required=True)
    for name in ('rotate-public-tls-resume','rotate-public-tls-rollback'):
        command=commands.add_parser(name);command.add_argument('--operation-id',required=True)
    forward=commands.add_parser('rotate-public-tls-forward-resume');forward.add_argument('--operation-id',required=True);forward.add_argument('--qualification-file',type=Path)
    return p


def private_json(path):
    path=Path(path).absolute()
    if path.resolve()!=path or not path.is_file() or os.name!='nt' and path.stat().st_mode&0o077:
        raise RuntimeError('An unlinked private JSON file is required')
    return json.loads(path.read_text(encoding='utf-8'))


def sessions(path):
    value=private_json(path)
    if set(value)!={'dingqiming','huangxuexue'} or any(not isinstance(p,str) or not Path(p).is_absolute() for p in value.values()):
        raise RuntimeError('Both exact HUMAN administrators need absolute private session paths')
    return {name:Path(file) for name,file in value.items()}


def original_initialization(root,inputs,sessions_file,capture_admin=None):
    operation=journal.current(root)
    if operation['kind']!='initialize' or operation['configDigest']!=config.digest(inputs['config']):raise RuntimeError('Original initialization differs')
    cfg=inputs['config'];candidate=release._candidate(Path(inputs['bundleDirectory']))
    if candidate!=inputs['descriptor'] or candidate['version']!=2 or candidate['schemaVersion']!='52-plus-2-r2-v22':
        raise RuntimeError('Original complete v22 initialization payload required')
    if (root/'initialization-final-release.json').exists():
        return initialize.finish(root,Path(inputs['bundleDirectory']),cfg)
    if not (root/'identity/bootstrap.json').exists() or journal._read(root,root/'identity/bootstrap.json')['state']!='VERIFIED':
        identity.prepare(root,cfg,Path(inputs['initialPasswordFile']))
        assembly.bootstrap(root,cfg,candidate,Path(inputs['bundleDirectory']))
    if capture_admin:
        from ols_linux.session_capture import capture
        return capture(root,capture_admin)
    if sessions_file is None:
        return {'operationId':operation['operationId'],'phase':'HUMAN_SESSIONS_REQUIRED',
                'requiredAdministrators':['dingqiming','huangxuexue'],'next':'initialize-resume --operation-id '+operation['operationId']+' --sessions-file <private-file>'}
    # Management ingress remains closed while the two real HUMAN sessions administer.
    if operation['phase']!='BUSINESS_CONFIG_READY':
        assembly.start_admin(root,candidate)
        initialize.run(root,cfg,sessions(sessions_file))
        business_config.install(root,operation['operationId'],cfg)
    return initialize.finish(root,Path(inputs['bundleDirectory']),cfg)


def dispatch(args):
    root=args.runtime;name=args.command
    if name=='public-tls-status':return tls_status.status(root,now=int(time.time()),previous_check=args.previous_check_file)
    if name=='rotate-public-tls':return tls_rotation.begin(root,json.loads(tls_material.read_private(args.inputs_file)),now=int(time.time()))
    if name=='rotate-public-tls-resume':return tls_rotation.resume(root,args.operation_id,now=int(time.time()))
    if name=='rotate-public-tls-forward-resume':return tls_rotation.forward_resume(root,args.operation_id,now=int(time.time()),qualification=private_json(args.qualification_file) if args.qualification_file else None)
    if name=='rotate-public-tls-rollback':return tls_rotation.rollback(root,args.operation_id,now=int(time.time()))
    if name=='release-status':return release.status(root)
    if name=='health':return release.health(root)
    if name=='stop':return release.stop(root)
    if name=='start':return release.start(root)
    if name=='upgrade':return release.upgrade(root,args.bundle)
    if name=='upgrade-resume':return release.resume(root,args.operation_id)
    if name=='publish-bytes':return release.publish_bytes(root,args.bundle)
    if name=='restore-checkpoint':return checkpoint.restore(root,args.operation_id)
    if name=='verify-initialization':
        cfg=config.load(args.config);result=verify.initialization(root,cfg)
        business_config.verify_configuration(root,cfg);return result
    if name=='describe-bundle':
        if args.build_directory is not None or args.oidc_settings_file is not None:
            if args.build_directory is None or args.oidc_settings_file is None or args.jar is not None or args.spa is not None or args.commit is not None:
                raise RuntimeError('Native build needs paired original target/public OIDC file, without prebuilt inputs')
            result=build.run(args.repo,args.build_directory,runtime.load(root)['runtimeImage'],private_json(args.oidc_settings_file))
        else:
            if args.jar is None or args.spa is None or args.commit is None:raise RuntimeError('Exact successful native build artifacts and source commit required')
            result=bundle.describe(args.repo,args.jar,args.spa,args.commit,include_runtime=True)
        if args.output:runtime.private_file(args.output,config.canonical(result))
        return result
    if name=='prepare':
        cfg=config.load(args.config);settings=private_json(args.settings_file)
        if not (root/'instance.json').exists():journal.begin(root,'initialize',config.digest(cfg))
        elif journal.current(root)['kind']!='initialize' or journal.current(root)['configDigest']!=config.digest(cfg):
            raise RuntimeError('Original infrastructure configuration required')
        resources=runtime.prepare(root,settings)
        image=runtime.build_image(Path(settings['repo']),root/'cache/runtime-image')
        if resources.get('runtimeImage') not in (None,image):raise RuntimeError('Original runtime image changed')
        resources['runtimeImage']=image;runtime.save(root,resources)
        return {'operationId':journal.current(root)['operationId'],'phase':'INFRASTRUCTURE_READY','runtimeImage':image}
    if name in {'initialize','initialize-resume'}:
        with journal.locked(root):
            path=root/'cli-initialization.json';op=journal.current(root)
            if name=='initialize':
                cfg=config.load(args.config);candidate=release._candidate(args.bundle)
                if op['kind']!='initialize' or op['configDigest']!=config.digest(cfg):raise RuntimeError('Original approved initialization configuration required')
                if candidate['schemaVersion']!='52-plus-2-r2-v22' or candidate['version']!=2:
                    raise RuntimeError('Fresh initialization only accepts a complete v22 payload')
                inputs={'operationId':op['operationId'],'config':cfg,'descriptor':candidate,
                        'bundleDirectory':str(args.bundle.absolute()),'initialPasswordFile':str(args.initial_password_file.absolute())}
                if path.exists() and journal._read(root,path)!=inputs:raise RuntimeError('Initialization inputs changed; use the original resume')
                if not path.exists():journal._write(root,path,inputs)
            else:
                if op['operationId']!=args.operation_id:raise RuntimeError('Only the explicit original initialization may resume')
                inputs=journal._read(root,path)
            return original_initialization(root,inputs,args.sessions_file,args.capture_admin)
    raise RuntimeError('Unrecognized operation')


def main(argv=None):
    args=parser().parse_args(argv)
    try:
        if os.name=='nt' or not args.runtime.is_absolute():raise RuntimeError('Linux and an absolute private runtime are required')
        result=dispatch(args);print(json.dumps(result,ensure_ascii=False))
        if args.command=='public-tls-status':return {'OK':0,'WARNING':2,'ACTION_REQUIRED':2,'CRITICAL':2,'BLOCKED':1,'UNKNOWN':4}.get(result.get('status'),4)
        return 3 if result.get('phase')=='HUMAN_SESSIONS_REQUIRED' else 0
    except (RuntimeError,ValueError,KeyError,OSError):
        output={'status':'BLOCKED_OR_UNKNOWN','reason':'Operation refused or result unknown; original private evidence retained'}
        try:
            op=journal.current(args.runtime);output.update(operationId=op['operationId'],phase=op['phase'])
            output['reconcile']='python3 deploy/linux/linux.py --runtime <original-private-runtime> release-status'
        except (RuntimeError,ValueError,OSError):pass
        print(json.dumps(output,ensure_ascii=False));return 1


if __name__=='__main__':raise SystemExit(main())
