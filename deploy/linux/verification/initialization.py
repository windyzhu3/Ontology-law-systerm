"""Actual HUMAN initialization, original response-loss recovery and exact repeat proof."""
import argparse
import json
from pathlib import Path
import sys
import uuid
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from ols_linux import admin,config,database,initialize,identity,journal,runtime,verify


def lost_response_confirmed(root,operation_id):
    path=root/'verification/lost-admin-response.json'
    if not path.exists():return False
    proof=journal._read(root,path)
    if proof['operationId']!=operation_id:return False
    record=journal._read(root,root/'admin-commands'/(proof['commandId']+'.json'))
    return record['operationId']==operation_id and record['state']=='CONFIRMED' and record['receipt']['commandId']==proof['commandId'] and record['receipt']['outcome']=='SUCCEEDED'


def security_probes(root,sessions):
    with journal.locked(root) as root:
        if runtime.load(root).get('verification') is not True:raise RuntimeError('Negative commands require an owned verification instance')
        op=journal.current(root);state=journal._read(root,root/'initialization.json')
        def headers(appointment):
            token=admin.session(root,sessions['dingqiming'],'dingqiming')['accessToken']
            return {'Authorization':'Bearer '+token,'X-Appointment-Id':state['appointments'][appointment]}
        denied=admin._request(root,'/api/v1/admin/audit-records',headers=headers('dingqiming_bootstrap'))
        if denied['status']!=403:raise RuntimeError('Non-audit appointment unexpectedly disclosed audit records')
        for username in ('dingqiming','huangxuexue'):
            token=admin.session(root,sessions[username],username)['accessToken']
            response=admin._request(root,'/api/v1/admin/audit-records',headers={'Authorization':'Bearer '+token,'X-Appointment-Id':state['appointments'][username+'_director']})
            if response['status']!=200 or not isinstance(json.loads(response['body']).get('items'),list):raise RuntimeError('Exact director audit authority unavailable')
        path=root/'verification/negative-commands.json'
        commands={'selfGrant':{'path':'/api/v1/admin/identity/authority-grants','appointment':'dingqiming_bootstrap','body':{'appointmentId':state['appointments']['dingqiming_director'],'authorityCode':'LEAD_ASSIGN','scopeOrganizationId':state['organizations']['ROOT'],'validFrom':state['effectiveFrom'],'validUntil':None},'status':409,'code':'IDENTITY_SELF_LOCKOUT'},
          'forgedSource':{'path':'/api/v1/leads','appointment':'dingqiming_director','body':{'sourceChannelCode':'WEB_FORM','sourceAccountCode':'LINUX_SERVICE_CLOSED','sourceRecordKey':'linux-closed-management-probe','capturedAt':state['effectiveFrom'],'serviceCategoryCode':'LABOR_DISPUTE','jurisdictionCode':'CN_AH','urgencyCode':'NORMAL','legalNeedSummary':'Verification of closed intake guard'},'status':403,'code':'NOT_AUTHORIZED'}}
        if path.exists():
            saved=journal._read(root,path)
            if saved['operationId']!=op['operationId'] or any(saved['commands'][k]['command']!=v for k,v in commands.items()):raise RuntimeError('Original negative probe changed')
        else:
            saved={'operationId':op['operationId'],'commands':{k:{'commandId':str(uuid.uuid4()),'command':v} for k,v in commands.items()}}
            journal._write(root,path,saved)
        before=database.sql(root,'SELECT count(*) FROM identity.authority_grant')
        for key,record in saved['commands'].items():
            command=record['command'];h=headers(command['appointment']);h.update({'Idempotency-Key':record['commandId'],'Content-Type':'application/json'})
            response=admin._request(root,command['path'],method='POST',headers=h,body=config.canonical(command['body']).decode())
            if response['status']!=command['status'] or json.loads(response['body']).get('code')!=command['code']:raise RuntimeError('Negative HUMAN command failed to reject: '+key)
        if before!=database.sql(root,'SELECT count(*) FROM identity.authority_grant'):raise RuntimeError('Negative commands changed grants')
        verify.business_empty(root)
        report={'selfGrantRejected':True,'forgedHumanSourceRejected':True,'nonAuditAppointmentRejected':True,'bothDirectorsAuditAllowed':True,'businessFacts':0,'grantDelta':0}
        journal._write(root,root/'verification/initialization-security-report.json',report)
        return report


def prove(root: Path,sessions: dict,inject=False):
    root=journal.safe_root(root)
    if runtime.load(root).get('verification') is not True:raise RuntimeError('Fault injection and synthetic verification require an owned verification instance')
    op=journal.current(root);state=journal._read(root,root/'initialization.json');cfg=state['config']
    if inject:
        original=admin._request;lost=[]
        def transport(*args,**kwargs):
            response=original(*args,**kwargs)
            if not lost and kwargs.get('method')=='POST' and args[1]=='/api/v1/admin/identity/authority-grants' and response['status']==201:
                command_id=kwargs['headers']['Idempotency-Key']
                # The API really committed. The controller intentionally receives no
                # receipt, retaining only the already persisted original command.
                lost.append(command_id)
                journal._write(root,root/'verification/lost-admin-response.json',{'operationId':op['operationId'],'commandId':command_id})
                raise RuntimeError('Injected lost original HUMAN response')
            return response
        with patch.object(admin,'_request',side_effect=transport):
            try:initialize.resume(root,op['operationId'],sessions)
            except RuntimeError:
                if not lost:raise
        if len(lost)!=1:raise RuntimeError('Verification did not exercise an actual committed lost response')
        record=journal._read(root,root/'admin-commands'/(lost[0]+'.json'))
        if record['state']!='DISPATCH_UNKNOWN':raise RuntimeError('Lost response did not retain original unknown command')
        receipt=admin.reconcile(root,op['operationId'],lost[0],sessions[record['command']['actor']['username']])
        if receipt['commandId']!=lost[0] or receipt['outcome']!='SUCCEEDED':raise RuntimeError('Original HUMAN receipt did not reconcile')
    result=initialize.resume(root,op['operationId'],sessions)
    before=database.sql(root,"SELECT (SELECT count(*) FROM identity.principal)||':'||(SELECT count(*) FROM identity.appointment)||':'||(SELECT count(*) FROM identity.authority_grant)||':'||(SELECT count(*) FROM execution.command_execution_slot)")
    again=initialize.resume(root,op['operationId'],sessions)
    after=database.sql(root,"SELECT (SELECT count(*) FROM identity.principal)||':'||(SELECT count(*) FROM identity.appointment)||':'||(SELECT count(*) FROM identity.authority_grant)||':'||(SELECT count(*) FROM execution.command_execution_slot)")
    if before!=after or result!=again:raise RuntimeError('Repeated original initialization changed identity/command facts')
    readonly=verify.initialization(root,cfg)
    if readonly!=result:raise RuntimeError('Read-only verification differs')
    report=dict(result,originalRepeatNoDelta=True,originalLostResponseReconciled=lost_response_confirmed(root,op['operationId']),security=security_probes(root,sessions))
    journal._write(root,root/'verification/initialization-report.json',report)
    return report


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--runtime',type=Path,required=True);parser.add_argument('--sessions',type=Path,required=True);parser.add_argument('--inject-lost-response',action='store_true');args=parser.parse_args()
    file=args.sessions.absolute()
    if file.resolve()!=file or not file.is_file() or file.stat().st_mode & 0o077:raise RuntimeError('Protected original session index required')
    sessions={key:Path(value) for key,value in json.loads(file.read_text()).items()}
    if set(sessions)!=admin.ADMINS:raise RuntimeError('Both exact HUMAN session files required')
    print(json.dumps(prove(args.runtime,sessions,args.inject_lost_response)))
