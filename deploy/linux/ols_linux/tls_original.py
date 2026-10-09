"""Per-target original public TLS bindings for the first legacy systemd rotation."""
import hashlib
import os
from pathlib import Path
import ssl
import stat
from . import tls_material,tls_systemd
from .config import digest

PUBLIC_ROLES={'nativeIdentity','nativeEntry','publicIdentity','publicEntry'}


def _snapshot(path,*,private=False):
    requested=tls_systemd._path(path)
    resolved=requested.resolve(strict=True)
    data,info=tls_systemd._read(resolved)
    if private and (info.st_uid!=os.geteuid() or stat.S_IMODE(info.st_mode)&0o077):
        raise RuntimeError('Original proxy key must remain owned and private')
    if requested.resolve(strict=True)!=resolved:raise RuntimeError('Original TLS reference changed during capture')
    return {'requested':str(requested),'resolved':str(resolved),'sha256':hashlib.sha256(data).hexdigest(),
            'uid':info.st_uid,'gid':info.st_gid,'mode':stat.S_IMODE(info.st_mode),'private':private}


def capture(old,registration):
    if registration.get('version')!=2:return old
    services=[row for row in registration['services'] if row['role']=='nginx']
    if len(services)!=1:raise RuntimeError('Exact original outer proxy required')
    paths=services[0]['tlsPaths'];certificate=_snapshot(paths['certificate']);key=_snapshot(paths['privateKey'],private=True)
    metadata=tls_material.metadata(Path(certificate['resolved']))
    public={'leafDerSha256':tls_material.fingerprint(tls_material.certificates(tls_systemd._read(certificate['resolved'])[0])[0]),
            'notAfter':int(ssl.cert_time_to_seconds(metadata['notAfter']))}
    if old['version']!=0:
        if public!=dict((k,old['candidate'][k]) for k in public):raise RuntimeError('Original managed public TLS identity drifted')
        return old
    native={key:old['candidate'][key] for key in ('leafDerSha256','notAfter')}
    targets={role:dict(native if role.startswith('native') else public) for role in PUBLIC_ROLES}
    files=[certificate,key]
    if _snapshot(paths['certificate'])!=certificate or _snapshot(paths['privateKey'],private=True)!=key:
        raise RuntimeError('Original proxy TLS files changed during capture')
    return dict(old,originalPublicTargets=targets,originalPublicFiles=files,originalPublicBindingDigest=digest({'targets':targets,'files':files}))


def _binding(old):
    if 'originalPublicTargets' not in old:return None
    targets=old['originalPublicTargets'];files=old['originalPublicFiles']
    if old['version']!=0 or set(targets)!=PUBLIC_ROLES or digest({'targets':targets,'files':files})!=old['originalPublicBindingDigest']:
        raise RuntimeError('Original per-target TLS binding differs')
    return targets


def require_newer(old,candidate):
    targets=_binding(old)
    if targets and candidate['notAfter']<=max(value['notAfter'] for value in targets.values()):
        raise RuntimeError('Candidate must extend every original public TLS identity')


def validate(old,*,now):
    targets=_binding(old)
    if any(row['notAfter']<=now for row in (targets or {'native':old['candidate']}).values()):
        raise RuntimeError('Expired original certificate cannot reopen service')
    if targets:
        for record in old['originalPublicFiles']:
            if _snapshot(record['requested'],private=record['private'])!=record:
                raise RuntimeError('Original TLS file or live reference drifted; rollback refused')


def expected_leaf(generation,role):
    targets=_binding(generation)
    return targets[role]['leafDerSha256'] if targets and role in targets else generation['candidate']['leafDerSha256']
