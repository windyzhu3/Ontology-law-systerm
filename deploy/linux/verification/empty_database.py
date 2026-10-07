import argparse
import json
from pathlib import Path
from harness import create,REPO
from ols_linux import database,journal,runtime


def verify(root: Path):
    op=journal.current(root)
    database.roles(root,op['operationId'])
    database.verify_sources(REPO)
    database.flyway(root,op['operationId'],'migrate')
    database.flyway(root,op['operationId'],'validate')
    database.runtime_logins(root,op['operationId'])
    observed=database.verify_schema(root)
    assert len([r for r in observed['history'] if r['version']])==43
    assert observed['gate']['schema_contract_version']=='52-plus-2-r2-v22'
    assert database.sql(root,"SELECT count(*) FROM pg_roles WHERE rolname IN ('law_api_login','law_worker_login') AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole")=='2'
    for key,db in [('businessDb','law_contract_runtime'),('identityDb','keycloak')]:
        refusal=runtime.run(['docker','exec',runtime.load(root)['containers'][key],'psql',f'host=127.0.0.1 dbname={db} user=postgres sslmode=disable','-c','SELECT 1'],check=False)
        assert refusal.returncode and b'pg_hba.conf rejects connection' in refusal.stderr, 'Unencrypted database client was not rejected'
    journal.record(root,op['operationId'],{'phase':'EMPTY_SCHEMA_VERIFIED','schemaVersion':observed['gate']['schema_contract_version'],'tables':len(observed['tables'])})
    return {'status':'PASS','schemaVersion':observed['gate']['schema_contract_version'],'sqlMigrations':43,'tables':len(observed['tables'])}


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--run-id',required=True);parser.add_argument('--runtime-image');args=parser.parse_args()
    root=create(args.run_id)
    try:
        result=verify(root)
        if args.runtime_image:
            from https_entry import verify as verify_https
            result['httpsBoundary']=verify_https(root,args.runtime_image)
            from infrastructure_failures import verify as verify_infrastructure
            result['infrastructureFailures']=verify_infrastructure(root,args.runtime_image)
        print(json.dumps(dict(result,runtime=str(root))))
    except Exception:
        print('Failed verification instance retained: '+str(root),file=__import__('sys').stderr)
        raise
    else:
        # Keep private files/evidence, delete only owned verification Docker resources.
        runtime.cleanup_verification(root)
