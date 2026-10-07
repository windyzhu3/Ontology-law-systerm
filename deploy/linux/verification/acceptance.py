"""Read live owned instances; incomplete or skipped native scenarios fail closed."""
import argparse
import json
import os
from pathlib import Path
import re
import sys
sys.dont_write_bytecode=True
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from ols_linux import bundle,business_config,checkpoint,identity,journal,runtime,verify

SCENARIOS={'empty','upgrade','business'}


def require_chains(cases):
    combinations={(department,entry) for department in ('SALES_1','SALES_2') for entry in ('QUOTE','DIRECT')}
    if len(cases)!=4 or {(c.get('department'),c.get('entry')) for c in cases}!=combinations:
        raise RuntimeError('All four distinct department/entry chains are required')
    if any(c.get('status')!='PASS' or c.get('classified') is not True or c.get('ownersMatch') is not True for c in cases):
        raise RuntimeError('A real classified chain or configured owner proof is absent')


def require_coverage(scenario,reports):
    required=SCENARIOS if scenario=='all' else {scenario}
    if not required<=reports.keys() or any(reports[name].get('status')!='PASS' for name in required):
        raise RuntimeError('Real scenario completion absent; SKIP is never PASS')
    if 'business' in required:require_chains(reports['business']['cases'])


def empty(root):
    state=journal._read(root,root/'initialization.json');config=state['config']
    result=verify.initialization(root,config)
    if result['businessFacts']!=0 or result['humanAppointments']!=20 or result['passwordUpdatesRequired']!=15:
        raise RuntimeError('Production initialization inventory changed')
    business_config.verify_configuration(root,config)
    candidate=journal._read(root,root/'current-release.json');descriptor=candidate['descriptor']
    bundle.verify(descriptor,Path(candidate['directory']))
    verify.runtime_ready(root,descriptor);verify.ingress_ready(root,descriptor)
    security=journal._read(root,root/'verification/initialization-security-report.json')
    if any(security.get(key) is not True for key in ('selfGrantRejected','forgedHumanSourceRejected','nonAuditAppointmentRejected','bothDirectorsAuditAllowed')):
        raise RuntimeError('Required real HUMAN security probes are absent')
    return dict(result,descriptorDigest=descriptor['descriptorDigest'],sourceCommit=descriptor['commit'],fullRuntime=True)


def upgrade(root):
    proof=journal._read(root,root/'verification/native-upgrade.json')
    if proof.get('status')!='PASS' or proof.get('nativeOldRuntimeReopened') is not True:
        raise RuntimeError('Native v20 upgrade and reopened linked restore proof absent')
    original=proof['operationId'];cp=checkpoint.verified(root,original)
    if cp['observed']['gate']['schema_contract_version']!='52-plus-2-r2-v20':raise RuntimeError('Original native v20 checkpoint differs')
    checkpoint.assert_preserved(cp['businessFacts'],checkpoint.table_facts(root))
    current=journal._read(root,root/'current-release.json')
    if current['descriptor']['schemaVersion']!='52-plus-2-r2-v20':raise RuntimeError('Original native release was not restored')
    verify.runtime_ready(root,current['descriptor']);verify.ingress_ready(root,current['descriptor'])
    required=('twoMigrationsInterruptedAndResumed','oldFactDigestsPreserved','linkedRestoreVerified','committedUnknownDidNotRemigrate')
    if any(proof.get(key) is not True for key in required):raise RuntimeError('Required live migration evidence incomplete')
    return proof


def business(root):
    proof=journal._read(root,root/'verification/native-business.json')
    require_chains(proof.get('cases',[]))
    required=('realBrowserPkce','accountSwitchAndLogout','manualAndBatchOwnSource','originalCommandRecovery',
              'separateReviewedSyntheticTemplates','configuredOwnerNegatives','independentAssignmentGrant')
    if proof.get('status')!='PASS' or any(proof.get(key) is not True for key in required):
        raise RuntimeError('Required real business/browser proof incomplete')
    current=journal._read(root,root/'current-release.json')
    verify.runtime_ready(root,current['descriptor']);verify.ingress_ready(root,current['descriptor'])
    for case in proof['cases']:
        # The final driver saves actual read-only table facts, not screenshots alone.
        if not case.get('commandIds') or not case.get('matterId') or not case.get('ownerReferences'):
            raise RuntimeError('Original receipts or final matter references absent')
    return proof


def run(run_id,scenario,inputs_file):
    if os.name=='nt':raise RuntimeError('Real native acceptance requires Linux')
    if not re.fullmatch('[a-z0-9][a-z0-9-]{0,38}',run_id):raise ValueError('Unique safe acceptance run ID required')
    if inputs_file is None:raise RuntimeError('Three separately registered native scenario runtimes are required')
    inputs_path=Path(inputs_file).absolute()
    if inputs_path.resolve()!=inputs_path or inputs_path.stat().st_mode & 0o077:raise RuntimeError('Private unlinked input map required')
    inputs=json.loads(inputs_path.read_text(encoding='utf-8'))
    required=sorted(SCENARIOS if scenario=='all' else {scenario});reports={};instances=set()
    for name in required:
        root=journal.safe_root(Path(inputs[name]))
        resources=runtime.load(root)
        if not resources.get('verification') or resources['instanceId'] in instances:
            raise RuntimeError('Three isolated owned verification instances required')
        instances.add(resources['instanceId'])
        with journal.locked(root):reports[name]=globals()[name](root)
    require_coverage(scenario,reports)
    return {'status':'PASS','runId':run_id,'scenario':scenario,'reports':reports}


def main(argv=None):
    parser=argparse.ArgumentParser();parser.add_argument('--run-id',required=True)
    parser.add_argument('--scenario',choices=sorted(SCENARIOS|{'all'}),required=True)
    parser.add_argument('--inputs-file',type=Path);args=parser.parse_args(argv)
    try:print(json.dumps(run(args.run_id,args.scenario,args.inputs_file),ensure_ascii=False));return 0
    except (RuntimeError,ValueError,KeyError,OSError):
        print(json.dumps({'status':'INCOMPLETE','runId':args.run_id,'scenario':args.scenario,
                          'reason':'Native coverage incomplete; original private evidence retained'}));return 1


if __name__=='__main__':raise SystemExit(main())
