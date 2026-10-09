"""Real-systemd qualification admission; the fixture exception cannot adopt production."""
from pathlib import Path
from . import journal,runtime,tls_material
from .bundle import sha
from .config import digest

CASES={f'Q{i:02d}' for i in range(1,13)}


def implementation_digest():
    base=Path(__file__).parent
    names=['tls_systemd.py','tls_systemd_qualification.py','tls_proxy.py','tls_maintenance.py','tls_probe.py','tls_rotation.py','tls_original.py']
    files={name:sha(base/name) for name in names}
    fixture=base.parent/'verification/systemd_qualification'
    for path in sorted(fixture.rglob('*')):
        if path.is_file() and path.suffix in {'.py','.conf','.Caddyfile'}:files[str(path.relative_to(base.parent))]=sha(path)
    return digest(files)


def fixture_scope(root,services):
    base=Path('/run/ols-tls-qualification')
    if root!=base/'runtime' or {r.get('role') for r in services}!={'nginx','caddy'} or len(services)!=2:
        raise RuntimeError('Only the exact isolated qualification root is exempt')
    for row in services:
        if row['name']!='ols-tls-qualification-'+row['role']+'.service':raise RuntimeError('Production service cannot use fixture admission')
        paths=[row['config'],*row['tlsPaths'].values()]
        profile=row['systemd']
        dropin='/etc/systemd/system/ols-tls-qualification-caddy.service.d/50-ols-public-tls.conf'
        exceptions={'/etc/nginx/mime.types'} if row['role']=='nginx' else {dropin}
        paths.extend(r['path'] for r in profile['immutableFiles'] if r['path'] not in exceptions)
        expected=[{'address':'127.0.0.1','port':p} for p in ((29845,29848) if row['role']=='nginx' else (29846,29847))]
        if profile.get('listeners')!=expected:raise RuntimeError('Fixture listeners must use the exact authorized loopback ports')
        paths.extend(token.partition(':')[2] for token in profile['properties']['LoadCredential'].split())
        if 'mainConfig' in profile:paths.append(profile['mainConfig'])
        if any(not Path(p).is_relative_to(base) or '..' in Path(p).parts for p in paths):
            raise RuntimeError('Fixture admission cannot access production configuration/materials')
        if 'unitFile' in profile and profile['unitFile']['path']!='/etc/systemd/system/'+row['name']:
            raise RuntimeError('Only exact newly authorized fixture unit paths are allowed')


def require(root,registration):
    proof=registration.get('qualification')
    if not isinstance(proof,dict):raise RuntimeError('Real systemd qualification required')
    if proof=={'mode':'isolated'}:
        fixture_scope(root,registration['services'])
        resources=runtime.load(root)
        if resources.get('verification') is not True or resources.get('fixtureKind')!='systemd-proxy-qualification':
            raise RuntimeError('Original isolated qualification fixture required')
        return
    if (root/'current-operation.json').exists():
        op=journal.current(root)
        path=root/'operations'/(op['operationId']+'-systemd-qualification-amendment.json')
        if path.exists():
            amendment=journal._read(root,path)
            if op['phase']=='COMPLETE' and amendment['registrationDigest']!=digest(registration):
                return _report(registration,proof)
            if amendment['operationId']!=op['operationId'] or amendment['registrationDigest']!=digest(registration) or amendment['previousProof']!=proof or amendment['implementationDigest']!=implementation_digest():
                raise RuntimeError('Qualification amendment binding differs')
            proof=amendment['proof']
    _report(registration,proof)


def amend(root,operation_id,registration,proof):
    """Append explicit new qualification admission without rewriting registration."""
    with journal.locked(root):
        op=journal.current(root)
        if op['operationId']!=operation_id or op['kind']!='rotate-public-tls' or op['phase']!='ROLLBACK_BLOCKED':
            raise RuntimeError('Qualification amendment requires original blocked rollback')
        if registration.get('qualification',{}).get('mode')!='report':raise RuntimeError('Original production report binding required')
        _report(registration,proof)
        value={'operationId':operation_id,'registrationDigest':digest(registration),'previousProof':registration['qualification'],'proof':proof,'implementationDigest':implementation_digest()}
        path=root/'operations'/(operation_id+'-systemd-qualification-amendment.json')
        if path.exists():
            if journal._read(root,path)!=value:raise RuntimeError('Original qualification amendment differs')
        else:journal._write(root,path,value)


def _report(registration,proof):
    if set(proof)!={'mode','root','sha256'} or proof['mode']!='report':raise RuntimeError('Sealed real-systemd qualification report required')
    report_root=Path(proof['root']);path=report_root/'verification/systemd-proxy-qualification.json'
    if sha(path)!=proof['sha256']:raise RuntimeError('Qualification report digest differs')
    report=journal._read(report_root,path)
    if report.get('status')!='PASS' or report.get('implementationDigest')!=implementation_digest():
        raise RuntimeError('Current implementation has not passed systemd qualification')
    if report.get('manager',{}).get('comm')!='systemd' or report['manager'].get('pid')!=1:
        raise RuntimeError('Actual systemd manager evidence required')
    if set(report.get('cases',{}))!=CASES or any(value.get('status')!='PASS' for value in report['cases'].values()):
        raise RuntimeError('Incomplete systemd qualification cases')
    for relative,expected in report.get('evidence',{}).items():
        target=report_root/relative
        if Path(relative).is_absolute() or '..' in Path(relative).parts or target.resolve()!=target or sha(target)!=expected:
            raise RuntimeError('Qualification evidence differs')
    if not report.get('evidence'):raise RuntimeError('Systemd qualification evidence missing')
    for service in registration['services']:
        if report.get('binaries',{}).get(service['role'])!=service['image']:
            raise RuntimeError('Proxy executable is not the qualified version')
