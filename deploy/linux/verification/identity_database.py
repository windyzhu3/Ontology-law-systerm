"""Actual isolated Keycloak setup; retained for browser password ceremony."""
import argparse
import json
from pathlib import Path
import secrets
import sys
import tempfile
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from ols_linux import config, identity, journal, runtime, database


def create(run_id, image):
    repo=Path(__file__).resolve().parents[3]
    cfg=config.load(repo/'deploy/linux/config/haihua.json')
    root=Path(tempfile.mkdtemp(prefix='ols-'+run_id+'-'))
    op=journal.begin(root,'initialize',config.digest(cfg))
    resources=runtime.prepare(root,{'name':'ols-'+run_id,'repo':str(repo),'ports':{'identity':24843,'entry':24844,'api':24845}})
    resources.update(verification=True,runtimeImage=image);runtime.save(root,resources)
    password='Aa'+secrets.token_hex(4)
    runtime.private_file(root/'secrets/initial-password.txt',password.encode())
    identity.prepare(root,cfg,root/'secrets/initial-password.txt')
    observed=identity.verify(root)
    assert observed['humanAccounts']==17 and observed['passwordUpdatesRequired']==17
    realm=journal._read(root,root/'identity/plan.json')['realm']
    assert database.sql(root,"SELECT password_policy FROM public.realm WHERE name='"+realm+"'",identity=True)=='length(8) and notUsername and notEmail'
    assert database.sql(root,"SELECT count(*) FROM pg_stat_activity a JOIN pg_roles r ON r.rolname=a.usename WHERE a.datname='keycloak' AND a.backend_type='client backend' AND a.pid<>pg_backend_pid() AND r.rolsuper",identity=True)=='0', 'Keycloak writer must not use a superuser'
    runtime.private_file(root/'identity/browser-input.json',config.canonical({'initialPasswordFile':str(root/'secrets/initial-password.txt'),
        'newPasswords':{username:'Aa'+secrets.token_hex(3) for username in ['dingqiming','huangxuexue']}}))
    print(json.dumps({'status':'IDENTITY_READY','runtime':str(root),'accounts':17}))
    return root


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--run-id',required=True);p.add_argument('--runtime-image',required=True);args=p.parse_args()
    create(args.run_id,args.runtime_image)
