"""Saved original commands sent only with an exact, currently authorized HUMAN actor."""
import base64
import json
import os
from pathlib import Path
import re
import time
import uuid
from urllib.parse import urlencode
from . import identity,journal,runtime
from .config import canonical,digest

ADMINS={'dingqiming','huangxuexue'}
PATH=re.compile(r'/api/v1/admin/identity/(principals|organizations|appointments|authority-grants|roles)(/[0-9a-f-]{36}/(display-name|suspend|resume|disable|close|end|revoke|deactivate|reactivate))?')


def session(root: Path, file: Path, username: str) -> dict:
    path=Path(file).absolute()
    if path.resolve()!=path or not path.is_file() or (os.name!='nt' and path.stat().st_mode & 0o077):raise RuntimeError('Protected unlinked HUMAN session file required')
    value=json.loads(path.read_text(encoding='utf-8'));plan=journal._read(root,root/'identity/plan.json')
    if username not in ADMINS or value.get('issuer')!=plan['issuer'] or value.get('subject')!=plan['subjects'][username] or value.get('clientId')!=plan['spaClient']:
        raise RuntimeError('Exact original HUMAN session required')
    try:
        claims=json.loads(base64.urlsafe_b64decode(value['accessToken'].split('.')[1]+'==='))
        if claims['iss']!=plan['issuer'] or claims['sub']!=value['subject'] or plan['audience'] not in ([claims['aud']] if isinstance(claims['aud'],str) else claims['aud']):raise ValueError()
    except (ValueError,KeyError,IndexError):raise RuntimeError('HUMAN token binding invalid') from None
    if claims['exp']<=time.time()+15:
        refresh_path=root/'identity'/('refresh-'+username+'.json')
        old=digest({'refresh':value.get('refreshToken')})
        if refresh_path.exists():
            previous=journal._read(root,refresh_path)
            if previous['state']=='UNKNOWN' and previous['refreshDigest']==old:
                raise RuntimeError('Refresh result unknown; obtain a fresh HUMAN browser session')
        journal._write(root,refresh_path,{'state':'UNKNOWN','refreshDigest':old})
        response=identity.http(root,plan['issuer']+'/protocol/openid-connect/token',method='POST',headers={'Content-Type':'application/x-www-form-urlencoded'},
            body=urlencode({'grant_type':'refresh_token','client_id':plan['spaClient'],'refresh_token':value.get('refreshToken','')}))
        if response['status']!=200:raise RuntimeError('HUMAN session expired; obtain a fresh browser session')
        tokens=json.loads(response['body']);claims=json.loads(base64.urlsafe_b64decode(tokens['access_token'].split('.')[1]+'==='))
        if claims['sub']!=value['subject'] or claims['iss']!=plan['issuer']:raise RuntimeError('Refreshed HUMAN binding differs')
        value.update(accessToken=tokens['access_token'],refreshToken=tokens['refresh_token'],idToken=tokens.get('id_token'),expiresAt=claims['exp'])
        runtime.private_file(path,canonical(value))
        journal._write(root,refresh_path,{'state':'VERIFIED','refreshDigest':digest({'refresh':value['refreshToken']})})
    return value


def _request(root,url,*,method='GET',headers=None,body=None):
    plan=journal._read(root,root/'identity/plan.json')
    return identity.http(root,plan['apiOrigin']+url,method=method,headers=headers,body=body)


def _headers(root, actor, file):
    value=session(root,file,actor['username'])
    headers={'Authorization':'Bearer '+value['accessToken'],'X-Appointment-Id':actor['appointmentId']}
    response=_request(root,'/api/v1/session/context',headers=headers)
    if response['status']!=200:raise RuntimeError('Current HUMAN appointment could not be verified')
    context=json.loads(response['body'])
    if context.get('state')!='READY' or context.get('selectedAppointmentId')!=actor['appointmentId'] or context.get('selectedOnBehalfAppointmentId') is not None or context.get('canEnterIdentityAdmin') is not True:
        raise RuntimeError('Current direct HUMAN appointment lacks management authority')
    return headers


def read(root: Path, actor: dict, session_file: Path, path: str) -> dict:
    if not path.startswith('/api/v1/admin/identity/'):raise ValueError('Closed administration read required')
    response=_request(root,path,headers=_headers(root,actor,session_file))
    if response['status']!=200:raise RuntimeError('Administration disclosure unavailable')
    return json.loads(response['body'])


def _validate(root,operation_id,command):
    op=journal.read(root,operation_id)
    if op['kind']!='initialize' or journal.current(root)['operationId']!=operation_id:raise RuntimeError('Original initialization required')
    if set(command)!={'commandId','path','body','precondition','actor'} or not PATH.fullmatch(command['path']):raise ValueError('Closed original administration command required')
    if str(uuid.UUID(command['commandId']))!=command['commandId']:raise ValueError('Original UUID required')
    actor=command['actor']
    if set(actor)!={'username','appointmentId'} or actor['username'] not in ADMINS:raise RuntimeError('Only the two exact HUMAN administrators may administer initialization')
    uuid.UUID(actor['appointmentId'])
    if command['precondition'] is not None and not isinstance(command['precondition'],str):raise ValueError('Original precondition required')


def _observe(root,path,record,response):
    receipt=json.loads(response['body'])
    if response['status'] not in {200,201} or receipt.get('commandId')!=record['command']['commandId'] or receipt.get('outcome') not in {'SUCCEEDED','NO_CHANGE'}:
        if 400<=response['status']<500 and response['status'] not in {401,404,409,429}:
            journal._write(root,path,dict(record,state='REJECTED',response=response))
        raise RuntimeError('Original administration command rejected or unknown; retain its key and body')
    journal._write(root,path,dict(record,state='CONFIRMED',receipt=receipt))
    return receipt


def _send(root,path,record,headers):
    command=record['command'];headers=dict(headers,**{'Idempotency-Key':command['commandId'],'Content-Type':'application/json'})
    if command['precondition'] is not None:headers['If-Match']=command['precondition']
    return _observe(root,path,record,_request(root,command['path'],method='POST',headers=headers,body=canonical(command['body']).decode('utf-8')))


def execute(root: Path, operation_id: str, command: dict, session_file: Path) -> dict:
    with journal.locked(root) as root:
        _validate(root,operation_id,command);path=root/'admin-commands'/(command['commandId']+'.json')
        if path.exists():
            record=journal._read(root,path)
            if record['operationId']!=operation_id or record['command']!=command:raise RuntimeError('Original command body, precondition or HUMAN actor changed')
            if record['state']=='CONFIRMED':return record['receipt']
            if record['state']=='REJECTED':raise RuntimeError('Original rejected command retained; do not replace its key')
            return reconcile(root,operation_id,command['commandId'],session_file)
        headers=_headers(root,command['actor'],session_file)
        record={'operationId':operation_id,'command':command,'state':'DISPATCH_UNKNOWN'}
        journal._write(root,path,record)
        return _send(root,path,record,headers)


def reconcile(root: Path, operation_id: str, command_id: str, session_file: Path) -> dict:
    with journal.locked(root) as root:
        if str(uuid.UUID(command_id))!=command_id:raise ValueError('Exact original command UUID required')
        path=root/'admin-commands'/(command_id+'.json');record=journal._read(root,path)
        _validate(root,operation_id,record['command'])
        if record['operationId']!=operation_id:raise RuntimeError('Command belongs to another operation')
        headers=_headers(root,record['command']['actor'],session_file)
        response=_request(root,'/api/v1/commands/'+command_id+'/receipt',headers=headers)
        if response['status']==404:
            # Safe original replay; UUID, payload, Actor and precondition are immutable.
            # An expired, never-committed candidate remains rejected, never refreshed here.
            return _send(root,path,record,headers)
        return _observe(root,path,record,response)
