"""Authenticated atomic original-operation records and a shared instance lock."""
from contextlib import contextmanager
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import threading
import time
import uuid
from .config import canonical

_held = threading.local()
_ID = re.compile(r'[a-f0-9]{32}')


def safe_root(runtime: Path) -> Path:
    path = Path(runtime).absolute()
    if path.resolve() != path: raise RuntimeError('Runtime symlinks are not permitted')
    if path.exists() and not path.is_dir(): raise RuntimeError('Runtime directory required')
    if os.name != 'nt' and path.exists() and (path.stat().st_uid != os.getuid() or path.stat().st_mode & 0o077):
        raise RuntimeError('Runtime must be owned by the operator with mode 0700')
    return path


def atomic(path: Path, value) -> None:
    path = Path(path)
    if path.is_symlink() or path.parent.resolve() != path.parent.absolute():
        raise RuntimeError('Private file path escapes its directory')
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    temp = path.with_name('.' + path.name + '.' + secrets.token_hex(8))
    try:
        fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'wb') as out:
            out.write(canonical(value))
            out.flush()
            os.fsync(out.fileno())
        os.replace(temp, path)
        if os.name != 'nt':
            fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
            try: os.fsync(fd)
            finally: os.close(fd)
    finally:
        if temp.exists(): temp.unlink()


def _owner(root):
    marker = root / 'instance.json'
    if not marker.is_file() or marker.is_symlink(): raise RuntimeError('Unregistered runtime')
    owner = json.loads(marker.read_text(encoding='utf-8'))
    if owner['runtime'] != str(root) or owner['version'] != 1:
        raise RuntimeError('Runtime belongs to another instance')
    return owner


@contextmanager
def locked(runtime: Path):
    root = safe_root(runtime)
    _owner(root)
    current = getattr(_held, 'paths', {})
    if str(root) in current:
        yield root
        return
    lock = root / 'instance.lock'
    if lock.is_symlink(): raise RuntimeError('Unsafe instance lock')
    fd = os.open(lock, os.O_RDWR | os.O_CREAT, 0o600)
    try:
        if os.fstat(fd).st_size == 0: os.write(fd, b'0')
        os.lseek(fd, 0, 0)
        if os.name == 'nt':
            import msvcrt
            msvcrt.locking(fd, msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        current[str(root)] = True
        _held.paths = current
        yield root
    except (BlockingIOError, PermissionError, OSError) as error:
        raise RuntimeError('Instance is busy or private storage is unavailable') from error
    finally:
        current.pop(str(root), None)
        os.close(fd)


def _key(root):
    path = root / 'journal.key'
    if path.is_symlink(): raise RuntimeError('Unsafe journal key')
    key = path.read_bytes()
    if len(key) != 32: raise RuntimeError('Invalid journal key')
    return key


def _write(root, path, payload):
    atomic(path, {'payload': payload, 'mac': hmac.new(_key(root), canonical(payload), hashlib.sha256).hexdigest()})


def _read(root, path):
    if path.is_symlink(): raise RuntimeError('Unsafe original-operation path')
    try:
        value = json.loads(path.read_text(encoding='utf-8'))
        expected = hmac.new(_key(root), canonical(value['payload']), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(value['mac'], expected): raise RuntimeError('Original operation was modified')
        return value['payload']
    except (ValueError, KeyError, OSError) as error:
        raise RuntimeError('Original operation unavailable or invalid') from error


def begin(runtime: Path, kind: str, config_digest: str) -> dict:
    if kind not in {'initialize', 'upgrade', 'publish-bytes', 'restore'} or not re.fullmatch('[a-f0-9]{64}', config_digest):
        raise ValueError('Valid operation kind and input digest required')
    root = safe_root(runtime)
    root.mkdir(parents=True, mode=0o700, exist_ok=True)
    if not (root / 'instance.json').exists():
        if list(root.iterdir()): raise RuntimeError('Refusing occupied unregistered runtime')
        # O_EXCL prevents simultaneous first registration from sharing ownership.
        fd = os.open(root / 'instance.json', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'wb') as out:
            out.write(canonical({'version': 1, 'runtime': str(root), 'instanceId': uuid.uuid4().hex}))
            out.flush(); os.fsync(out.fileno())
        fd = os.open(root / 'journal.key', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'wb') as out: out.write(secrets.token_bytes(32)); out.flush(); os.fsync(out.fileno())
    with locked(root):
        current = root / 'current-operation.json'
        if current.exists():
            previous = _read(root, current)
            operation = _read(root, root / 'operations' / (previous['operationId'] + '.json'))
            if operation['kind'] == 'initialize' and kind == 'initialize' or operation['phase'] != 'COMPLETE':
                raise RuntimeError('Original operation exists; use its explicit resume or verification command')
        operation = {'operationId': uuid.uuid4().hex, 'instanceId': _owner(root)['instanceId'], 'kind': kind,
                     'configDigest': config_digest, 'phase': 'CREATED', 'events': [], 'createdAt': time.time()}
        _write(root, root / 'operations' / (operation['operationId'] + '.json'), operation)
        _write(root, current, {'operationId': operation['operationId']})
        return operation


def read(runtime: Path, operation_id: str) -> dict:
    if not _ID.fullmatch(operation_id): raise RuntimeError('Invalid original operation ID')
    with locked(runtime) as root:
        value = _read(root, root / 'operations' / (operation_id + '.json'))
        if value['operationId'] != operation_id or value['instanceId'] != _owner(root)['instanceId']:
            raise RuntimeError('Original operation belongs to another instance')
        return value


def record(runtime: Path, operation_id: str, event: dict) -> None:
    with locked(runtime) as root:
        value = read(root, operation_id)
        if _read(root, root / 'current-operation.json')['operationId'] != operation_id or value['phase'] == 'COMPLETE':
            raise RuntimeError('Cannot mutate a non-current or completed operation')
        if not isinstance(event, dict) or not isinstance(event.get('phase'), str): raise ValueError('Named phase required')
        value['events'].append(dict(event, sequence=len(value['events']), recordedAt=time.time()))
        value['phase'] = event['phase']
        _write(root, root / 'operations' / (operation_id + '.json'), value)


def current(runtime: Path) -> dict:
    with locked(runtime) as root:
        return read(root, _read(root, root / 'current-operation.json')['operationId'])
