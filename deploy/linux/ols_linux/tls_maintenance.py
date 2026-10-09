"""Restricted issuer health access on the existing nginx listeners.

Only the owned pod's exact IPv4 address and host loopback may use the two
already-public issuer health paths. Authentication and strict upstream TLS stay
unchanged. All other paths return 503, including identity administration.
"""
import ipaddress
import re
from pathlib import Path
from urllib.parse import urlsplit
from . import journal,runtime,tls_material


def render(*,listeners,identity_port,realm,sources,upstream_port,certificate,private_key,trust,fragment=False,default_server=False,public_host=None):
    if not re.fullmatch('[A-Za-z0-9_-]+',realm):raise ValueError('Exact realm required')
    if not 1<=len(listeners)<=2 or len(set(listeners))!=len(listeners):raise ValueError('One or two distinct original listeners required')
    for token in listeners:
        if not re.fullmatch(r'(?:[0-9.]+:)?[0-9]+',token):raise ValueError('Explicit IPv4 listener required')
        if ':' in token:ipaddress.IPv4Address(token.rsplit(':',1)[0])
        if not 1<=int(token.rsplit(':',1)[-1])<=65535:raise ValueError('Invalid listener')
    if sum(int(x.rsplit(':',1)[-1])==identity_port for x in listeners)!=1:raise ValueError('Original identity listener required')
    if type(upstream_port)!=int or not 1<=upstream_port<=65535:raise ValueError('Exact upstream port required')
    if not sources or len(set(sources))!=len(sources):raise ValueError('Exact source addresses required')
    for source in sources:ipaddress.IPv4Address(source)
    for path in [certificate,private_key,trust]:
        if not re.fullmatch('/[A-Za-z0-9_./-]+',path) or '..' in Path(path).parts:raise ValueError('Unsafe TLS path')
    host_policy=''
    if public_host is not None:
        ipaddress.IPv4Address(public_host)
        host_policy=f'server_name {public_host};\nif ($host != {public_host}) {{ return 421; }}\n'
        host_policy+=r'if ($request_uri ~* "^[^?]*(%2e|%2f|%5c|\\\\|\.\.)") { return 404; }'+'\n'
        host_policy+='if ($request_uri ~ "^//") { return 404; }\n'
    blocks=[]
    for listener in listeners:
        locations='location / { return 503; }'
        if int(listener.rsplit(':',1)[-1])==identity_port:
            for method,path in [('GET','certs'),('POST','token/introspect')]:
                locations+=f'''\nlocation = /realms/{realm}/protocol/openid-connect/{path} {{
if ($request_method != {method}) {{ return 405; }}
{''.join('allow '+s+'; ' for s in sources)}deny all;
proxy_pass https://127.0.0.1:{upstream_port};
proxy_ssl_verify on; proxy_ssl_protocols TLSv1.3;
proxy_ssl_server_name on; proxy_ssl_name localhost;
proxy_ssl_trusted_certificate {trust};
proxy_set_header Host $http_host; proxy_set_header X-Forwarded-Proto https;
proxy_connect_timeout 3s; proxy_read_timeout 3s; client_max_body_size 64k;
}}'''
        blocks.append(f'''server {{
listen {listener} ssl{' default_server' if default_server else ''};
{host_policy}access_log off; server_tokens off;
ssl_protocols TLSv1.3;
ssl_certificate {certificate}; ssl_certificate_key {private_key};
{locations}
}}''')
    if fragment:return '\n'.join(blocks)+'\n'
    return 'events {}\nhttp {\naccess_log off; server_tokens off;\n'+ '\n'.join(blocks)+'\n}\n'


def validate_start(service,actual,root):
    # systemd needs its own qualified argv/loaded-process profile; it must not
    # silently inherit the Docker evidence or restart a wrapper command.
    if service['transport']=='systemd':
        from . import tls_systemd_qualification
        op=journal.current(root)
        registration=journal._read(root,root/'operations'/(op['operationId']+'-proxy.json'))['registration']
        tls_systemd_qualification.require(root,registration)
        if service['role']!='nginx' or set(actual)!={'running','process','credentials'}:
            raise RuntimeError('Qualified systemd nginx startup observation required')
        return
    if service['transport']!='docker':raise RuntimeError('Unsupported maintenance service')
    for mount in actual.get('Mounts',[]):
        destination=Path(mount['Destination'])
        if destination!=root and destination.is_relative_to(root):raise RuntimeError('Nested mount shadows maintenance runtime')
    cfg=actual['Config']
    if cfg.get('Entrypoint')!=['nginx'] or cfg.get('Cmd')!=['-c',service['config'],'-g','daemon off;']:
        raise RuntimeError('Actual nginx startup configuration differs')


def original_listeners(configuration,identity_port,origin_port):
    # The two logical consumers may share a single HTTPS socket. Extra listeners
    # or HTTP configuration need a separately registered fragment, never guessing.
    text=re.sub(r'#[^\n]*','',configuration)
    rows=re.findall(r'\blisten\s+([^;]+);',text)
    if any(not re.fullmatch(r'(?:[0-9.]+:)?[0-9]+\s+ssl(?:\s+default_server)?',row.strip()) for row in rows):
        raise RuntimeError('Unsupported original maintenance listener profile')
    listeners=[row.split()[0] for row in rows]
    if len(set(listeners))!=len(listeners) or sorted(int(x.rsplit(':',1)[-1]) for x in listeners)!=sorted({identity_port,origin_port}):
        raise RuntimeError('Original public listeners differ')
    return listeners


def prepare(root,opid,rows,targets,paths):
    """Bind the exact owned pod and original listeners before any maintenance effect."""
    from . import tls_proxy
    saved=root/'operations'/(opid+'-issuer-maintenance.json')
    plan=journal._read(root,root/'identity/plan.json')
    resources=runtime.load(root)
    qualification=resources.get('fixtureKind')=='systemd-proxy-qualification'
    if qualification:
        from . import tls_systemd_qualification
        tls_systemd_qualification.fixture_scope(root,[row['service'] for row in rows])
        if resources.get('verification') is not True:raise RuntimeError('Isolated qualification marker required')
        source='127.0.0.2';pod={'Id':'qualification-loopback:'+journal._owner(root)['instanceId']}
    else:
        pod=runtime.owned(root,'container',plan['pod'])
        network=pod['NetworkSettings']['Networks'].get(resources['network'])
        if not network or not network.get('IPAddress'):raise RuntimeError('Original pod address unavailable')
        source=network['IPAddress'];ipaddress.IPv4Address(source)
    outer=next(r for r in rows if r['service']['role']=='nginx')
    observed=tls_proxy._state(root,opid,outer['service'])
    validate_start(outer['service'],observed if outer['service']['transport']=='systemd' else observed.get('actual',{}),root)
    original=outer.get('before',outer.get('configuration'))
    issuer=urlsplit(plan['issuer']);origin=urlsplit(plan['origin'])
    listeners=original_listeners(original,issuer.port or 443,origin.port or 443)
    bridge=[t for t in targets if t['role']=='bridgeIdentity']
    if len(bridge)!=1 or bridge[0].get('tlsIdentity')!='internal' or bridge[0]['verifyHost']!='localhost' or bridge[0]['connectHost']!='127.0.0.1':raise RuntimeError('Original verified Caddy identity bridge required')
    if not any(r['service']['role']=='caddy' for r in rows):raise RuntimeError('Registered Caddy required')
    text=render(listeners=listeners,identity_port=issuer.port or 443,realm=issuer.path.removeprefix('/realms/'),sources=sorted(set(['127.0.0.1',source])),upstream_port=bridge[0]['connectPort'],certificate=paths['certificate'],private_key=paths['privateKey'],trust=str(root/'certs/ca.pem'),fragment=outer['service']['transport']=='systemd',default_server=outer['service']['transport']=='systemd' and 'default_server' in original,public_host=issuer.hostname if outer['service']['transport']=='systemd' else None)
    value={'podId':pod['Id'],'podAddress':source,'configuration':text,'service':outer['service']}
    if qualification:value['sourceKind']='isolated-loopback-probe-not-a-container'
    # Forward/rollback may select different bytes; retain each exact version.
    from .config import digest
    candidate=root/'proxy'/('maintenance-'+digest(value)+'.conf')
    if candidate.exists() and tls_material.read_private(candidate)!=text.encode():raise RuntimeError('Maintenance candidate differs')
    if not candidate.exists():runtime.private_file(candidate,text.encode())
    tls_proxy._check(outer['service'],candidate)
    value['candidate']=str(candidate)
    if saved.exists():
        before=journal._read(root,saved)
        if before['podId']!=pod['Id'] or before['podAddress']!=source:raise RuntimeError('Original maintenance pod changed')
    journal._write(root,saved,value)
    return value


def start(root,opid,rows,targets,paths):
    from . import tls_proxy
    op=journal.current(root)
    if op['operationId']!=opid or op['phase'] not in {'ACTIVATING','ROLLBACK_ACTIVATING','ACTIVATION_UNKNOWN'}:raise RuntimeError('Original maintenance activation required')
    value=prepare(root,opid,rows,targets,paths);service=value['service']
    if service['transport']=='systemd':
        from . import tls_systemd
        from .config import digest
        row=next(r for r in rows if r['service']==service)
        before=tls_proxy._state(root,opid,service)
        intent_path=root/'operations'/(opid+'-maintenance-start-'+digest(value)+'.json')
        if intent_path.exists():intent=journal._read(root,intent_path)
        else:
            intent={'configuration':value['configuration'],'before':before}
            journal._write(root,intent_path,intent)
        if intent['configuration']!=value['configuration']:raise RuntimeError('Original maintenance start intent differs')
        if before['running'] and tls_proxy._read_config(service)==value['configuration'].encode():
            try:tls_systemd.verify_restart(intent['before'],before)
            except RuntimeError:pass
            else:return value
        if before['running']:tls_proxy._action(service,'stop')
        if tls_proxy._state(root,opid,service)['running']:raise RuntimeError('Maintenance stop unconfirmed')
        tls_proxy._write_config(service,value['configuration'].encode(),tls_proxy._allowed_config(root,opid,row))
        tls_proxy._action(service,'start')
        after=tls_systemd.await_started(service,root=root,operation_id=opid)
        tls_systemd.verify_restart(intent['before'],after)
        if tls_proxy._read_config(service)!=value['configuration'].encode():raise RuntimeError('Maintenance effective configuration differs')
        return value
    # Stop/start, rather than reload, removes old workers and proves which config
    # the owned process read. If interrupted, the exact intent remains replayable.
    if tls_proxy._service(service)['running']:tls_proxy._action(service,'stop')
    if tls_proxy._service(service)['running']:raise RuntimeError('Maintenance stop unconfirmed')
    runtime.private_file(Path(service['config']),value['configuration'].encode())
    tls_proxy._action(service,'start')
    if not tls_proxy._service(service)['running']:raise RuntimeError('Maintenance start unconfirmed')
    effective=runtime.run(['docker','exec',service['name'],'cat',service['config']]).stdout
    if effective!=value['configuration'].encode():raise RuntimeError('Maintenance effective configuration differs')
    return value


def configured(root,opid,service):
    path=root/'operations'/(opid+'-issuer-maintenance.json')
    if not path.exists():return False
    value=journal._read(root,path)
    from . import tls_proxy
    return value['service']==service and tls_proxy._read_config(service)==value['configuration'].encode()
