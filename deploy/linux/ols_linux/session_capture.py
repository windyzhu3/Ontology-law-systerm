"""An operator types the callback of their own real browser authorization ceremony."""
import base64
import getpass
import hashlib
import json
from pathlib import Path
import secrets
import time
import uuid
from urllib.parse import parse_qs,urlencode,urlsplit
from . import identity,journal,runtime
from .config import canonical,digest


def callback_code(value,origin,state):
    actual=urlsplit(value);expected=urlsplit(origin)
    query=parse_qs(actual.query,keep_blank_values=True)
    if actual.scheme!='https' or (actual.scheme,actual.netloc)!=(expected.scheme,expected.netloc) or actual.path!='/auth/callback' or actual.fragment:
        raise RuntimeError('Only the original browser callback is accepted')
    if query.get('state')!=[state] or len(query.get('code',[]))!=1 or not query['code'][0] or 'error' in query:
        raise RuntimeError('Original state and one actual authorization code required')
    return query['code'][0]


def claims(token):
    try:return json.loads(base64.urlsafe_b64decode(token.split('.')[1]+'==='))
    except (ValueError,IndexError,TypeError):raise RuntimeError('Actual provider token format invalid') from None


def validate_tokens(tokens,plan,username,nonce):
    access=claims(tokens['access_token']);idtoken=claims(tokens['id_token'])
    for value in (access,idtoken):
        if value.get('iss')!=plan['issuer'] or value.get('sub')!=plan['subjects'][username]:raise RuntimeError('Actual original HUMAN subject or issuer differs')
    audiences=lambda value: value if isinstance(value,list) else [value]
    if plan['audience'] not in audiences(access.get('aud')) or plan['spaClient'] not in audiences(idtoken.get('aud')) or idtoken.get('nonce')!=nonce:
        raise RuntimeError('Actual original HUMAN audience or nonce differs')
    if not isinstance(tokens.get('refresh_token'),str) or not tokens['refresh_token'] or type(access.get('exp')) is not int or access['exp']<=time.time():
        raise RuntimeError('Actual current HUMAN session unavailable')
    return access


def session_value(tokens,plan,username,nonce):
    actual=validate_tokens(tokens,plan,username,nonce)
    return {'username':username,'issuer':plan['issuer'],'clientId':plan['spaClient'],'subject':plan['subjects'][username],
            'accessToken':tokens['access_token'],'refreshToken':tokens['refresh_token'],'idToken':tokens['id_token'],'expiresAt':actual['exp']}


def capture(root: Path,username: str):
    if username not in {'dingqiming','huangxuexue'}:raise RuntimeError('Only the two confirmed HUMAN initialization administrators may capture a session')
    with journal.locked(root) as root:
        op=journal.current(root);plan=journal._read(root,root/'identity/plan.json')
        if op['kind']!='initialize' or op['operationId']!=plan['operationId'] or (root/'initialization-final-release.json').exists():
            raise RuntimeError('Manual initialization capture requires the original closed initialization')
        if journal._read(root,root/'identity/bootstrap.json')['state']!='VERIFIED':raise RuntimeError('Original offline bootstrap verification required')
        identity.verify(root)
        if runtime.load(root)['ingress']:raise RuntimeError('Initialization callback must remain closed for manual capture')
        attempt=uuid.uuid4().hex;verifier=secrets.token_urlsafe(32);state=secrets.token_urlsafe(32);nonce=secrets.token_urlsafe(32)
        path=root/'identity/session-captures'/(attempt+'.json')
        record={'operationId':op['operationId'],'username':username,'state':'AUTHORIZATION_REQUESTED',
                'pkceVerifier':verifier,'oidcState':state,'nonce':nonce,'createdAt':time.time()}
        journal._write(root,path,record)
        challenge=base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip('=')
        params={'client_id':plan['spaClient'],'response_type':'code','scope':'openid','redirect_uri':plan['origin']+'/auth/callback',
                'state':state,'nonce':nonce,'code_challenge':challenge,'code_challenge_method':'S256','login_hint':username,'prompt':'login'}
        print('Open this address in your browser and sign in as '+username+':',flush=True)
        print(plan['issuer']+'/protocol/openid-connect/auth?'+urlencode(params),flush=True)
        print('Complete the required password update. The closed callback page may fail to load; copy its entire address into the hidden terminal prompt.',flush=True)
        callback=getpass.getpass('Original callback URL (hidden): ')
        code=callback_code(callback,plan['origin'],state)
        record.update(state='TOKEN_EXCHANGE_UNKNOWN',authorizationCodeDigest=digest({'code':code}));journal._write(root,path,record)
        response=identity.http(root,plan['issuer']+'/protocol/openid-connect/token',method='POST',
            headers={'Content-Type':'application/x-www-form-urlencoded'},body=urlencode({'grant_type':'authorization_code',
                'client_id':plan['spaClient'],'redirect_uri':plan['origin']+'/auth/callback','code':code,'code_verifier':verifier}))
        if response['status']!=200:raise RuntimeError('Original browser authorization exchange rejected or unknown; use a fresh explicit ceremony')
        tokens=json.loads(response['body']);value=session_value(tokens,plan,username,nonce)
        session=root/'identity'/(username+'-session.json')
        runtime.private_file(session,canonical(value))
        record.update(state='VERIFIED',sessionFile=str(session),sessionDigest=hashlib.sha256(session.read_bytes()).hexdigest());journal._write(root,path,record)
        files={name:str(root/'identity'/(name+'-session.json')) for name in ('dingqiming','huangxuexue')}
        index=root/'identity/sessions.json';runtime.private_file(index,canonical(files))
        return {'operationId':op['operationId'],'phase':'HUMAN_SESSION_CAPTURED','username':username,
                'sessionsFile':str(index),'next':'initialize-resume --operation-id '+op['operationId']+' --sessions-file '+str(index)}
