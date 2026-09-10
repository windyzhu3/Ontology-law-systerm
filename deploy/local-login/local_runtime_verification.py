"""Read-only proof that the current controlled local release is healthy."""
import re

from local_release import app_commands, digest, encoded, files, owned_process, read_json, regular
from local_worker import LocalWorker, WorkerBoundary, worker_command


_WORKER_HEALTH = {'state': 'READY', 'requiredLoops': 3, 'listeners': 0,
                  'database': 'READY', 'mtls': 'READY'}


def _no_pending(release, runtime):
    if (any((runtime / name).exists() for name in ('apps-start.pending', 'worker-start.pending'))
            or release.journal.exists() and read_json(regular(release.journal))['phase'] != 'COMPLETE'
            or release.store.is_dir() and any(path.name.endswith('.pending') for path in release.store.iterdir())):
        raise RuntimeError('pending release or process start prevents current runtime verification')


def _current_processes(runner, release, boundary, current):
    saved = read_json(regular(runner.RUNTIME / 'processes.json'))
    if set(saved) != {'api', 'spa', 'worker'}:
        raise RuntimeError('exact API, SPA and Worker registration required')
    package = release.package(current['id'])
    commands = {**app_commands(runner, package), 'worker': worker_command(runner, package)}
    required = {'api': {'pid', 'executable', 'args', 'created'},
                'spa': {'pid', 'executable', 'args', 'created'},
                'worker': {'pid', 'executable', 'args', 'created', 'startedAt'}}
    if any(type(saved[name]) is not dict or set(saved[name]) != required[name]
           for name in required):
        raise RuntimeError('exact current process registration required')
    boundary.processes()
    actual = {}
    for name, command in commands.items():
        expected = saved[name]
        if expected['executable'] != command[0] or expected['args'] != command[1:]:
            raise RuntimeError('registered process is not from the current package')
        process = boundary.process(expected['pid'])
        if process is None:
            raise RuntimeError('current release process is stopped')
        owned_process(expected, process)
        actual[name] = process
    if len({process['pid'] for process in actual.values()}) != 3:
        raise RuntimeError('current release processes are not distinct')
    return saved


def _current_release(release):
    current = release.current()
    paths = release.paths()
    record = release.stable(current)
    package = release.package(current['id'])
    kind = record.get('kind')
    if kind not in ('controlled-local-release', 'controlled-local-source-release'):
        raise RuntimeError('current package is not a controlled release')
    provenance = record.get('provenance')
    source = provenance.get('sourceCommit') if type(provenance) is dict else None
    artifacts = {'jarSha256': digest(regular(package / 'app.jar').read_bytes()),
                 'spaFiles': files(package / 'dist'),
                 'serverSha256': digest(regular(package / 'server.mjs').read_bytes())}
    artifacts['spaSha256'] = digest(encoded(artifacts['spaFiles']))
    manifest = provenance
    if kind == 'controlled-local-source-release':
        manifest = record.get('sourceRelease')
        if (type(manifest) is not dict or manifest.get('profile') != 'LOCAL_SYNTHETIC_SOURCE_RELEASE_V1'
                or manifest.get('binaryProvenance') != provenance
                or any(manifest.get(key) != value for key, value in artifacts.items())):
            raise RuntimeError('current controlled source release evidence unavailable')
    deployment = read_json(regular(package / 'deployment.json'))
    if (not isinstance(source, str) or not re.fullmatch(r'[0-9a-f]{40}', source)
            or any(provenance.get(key) != value for key, value in artifacts.items())
            or read_json(regular(package / 'release-manifest.json')) != manifest
            or digest(encoded(manifest)) != current['gate']['active_manifest_hash']
            or artifacts['jarSha256'] != current['gate']['active_release_digest']
            or record.get('jarSha256') != current['gate']['active_release_digest']
            or record.get('manifestHash') != current['gate']['active_manifest_hash']
            or deployment.get('releaseDigest') != current['gate']['active_release_digest']
            or deployment.get('manifestHash') != current['gate']['active_manifest_hash']):
        raise RuntimeError('current controlled release evidence unavailable')
    return current, paths, record, source


def verify_current_runtime(runner, release, boundary):
    """Verify the installed current package and all three live consumers."""
    boundary.protect()
    _no_pending(release, runner.RUNTIME)
    current, paths, record, source = _current_release(release)
    processes = _current_processes(runner, release, boundary, current)

    worker = LocalWorker(runner, release, WorkerBoundary(runner, boundary))
    health = worker.health()
    if health != _WORKER_HEALTH:
        raise RuntimeError('complete current Worker health unavailable')

    _no_pending(release, runner.RUNTIME)
    final_current, final_paths, final_record, final_source = _current_release(release)
    final_processes = _current_processes(runner, release, boundary, final_current)
    if (final_current != current or final_paths != paths or final_record != record
            or final_source != source or final_processes != processes):
        raise RuntimeError('current release or process evidence changed during verification')
    return {'status': 'VERIFIED_CURRENT_RUNTIME', 'releaseId': current['id'],
            'sourceCommit': source, 'gateRevision': current['gate']['revision'],
            'workerHealth': health}
