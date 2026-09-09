"""One local API/SPA release, saved legacy bytes, and explicit interrupted-switch recovery.

The controller supplies prebuilt artifacts. This module never builds, bootstraps,
changes schema, or writes business facts. All mutable records are ACL protected.
"""
import copy
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import uuid

JAVA_SOURCE = 'backend/src/main/java/io/github/windyzhu3/ontologylaw/'
SOURCE_FILES = (
    'contracts/openapi/ontology-law-api.yaml',
    'database/schema-contract-52-plus-2/generated/schema-contract-manifest.json',
    'database/schema-contract-52-plus-2/runtime/toolchain.lock.json',
    'deploy/identity/identity-toolchain.lock.json',
    JAVA_SOURCE + 'execution/CommandEnvelope.java',
    JAVA_SOURCE + 'api/security/ActorContextResolver.java',
    JAVA_SOURCE + 'execution/R1CommandPolicy.java',
    JAVA_SOURCE + 'execution/R1EventPolicy.java',
    JAVA_SOURCE + 'execution/CommandHandler.java',
    JAVA_SOURCE + 'lead/R1SourcePolicyRegistry.java',
    JAVA_SOURCE + 'api/ApiRuntimeAssembly.java',
    JAVA_SOURCE + 'worker/WorkerRuntimeAssembly.java',
    'apps/workbench/src/features/session/sessionConfiguration.ts',
    'deploy/local-login/server.mjs',
)
PUBLIC_OIDC = {'VITE_OIDC_ISSUER': 'https://localhost:19443/realms/local-r1',
               'VITE_OIDC_CLIENT_ID': 'local-r1-spa', 'VITE_OIDC_AUDIENCE': 'local-r1-api',
               'VITE_APP_ORIGIN': 'https://localhost:19444'}
TOOLCHAIN = {'java': '25.0.4.1+1', 'node': '24.20.0', 'npm': '11.9.0'}
GATE_KEYS = {'deployment_state_key', 'operating_mode', 'active_release_digest',
             'active_manifest_hash', 'schema_contract_version', 'revision', 'changed_at'}


def encoded(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode()


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read_json(path):
    return json.loads(path.read_bytes())


def atomic(path, data):
    """Persist content before exposing the replacement; failure leaves the journal pending."""
    temporary = path.with_name(path.name + '.' + uuid.uuid4().hex + '.tmp')
    with temporary.open('xb') as stream:
        stream.write(data if isinstance(data, bytes) else encoded(data))
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


def regular(path):
    if path.is_symlink() or getattr(path, 'is_junction', lambda: False)():
        raise RuntimeError('linked artifact rejected')
    if not path.is_file():
        raise RuntimeError('missing artifact')
    return path


def files(folder):
    if not folder.is_dir() or folder.is_symlink() or getattr(folder, 'is_junction', lambda: False)():
        raise RuntimeError('missing or linked artifact directory')
    result = {}
    for path in sorted(folder.rglob('*')):
        if path.is_symlink() or getattr(path, 'is_junction', lambda: False)():
            raise RuntimeError('linked artifact rejected')
        if path.is_file():
            result[path.relative_to(folder).as_posix()] = digest(path.read_bytes())
    if not result:
        raise RuntimeError('empty artifact directory')
    return result


def gate_valid(gate):
    if (set(gate) != GATE_KEYS or gate['deployment_state_key'] != 'PRIMARY'
            or gate['operating_mode'] != 'ACTIVE' or gate['schema_contract_version'] != '52-plus-2-v1.2'
            or type(gate['revision']) is not int or gate['revision'] < 0
            or not re.fullmatch(r'[0-9a-f]{64}', gate['active_release_digest'])
            or not re.fullmatch(r'[0-9a-f]{64}', gate['active_manifest_hash'])):
        raise RuntimeError('unexpected deployment gate')
    datetime.fromisoformat(gate['changed_at'].replace('Z', '+00:00'))


def change_config(data, release, manifest):
    text = data.decode('utf-8')
    for name, value in [('release-digest', release), ('manifest-hash', manifest)]:
        text, count = re.subn(r'(?m)^ols\.api\.database\.' + name + r'=[^\r\n]*',
                             'ols.api.database.' + name + '=' + value, text)
        if count != 1:
            raise RuntimeError('ambiguous API configuration')
    return text.encode('utf-8')


class LocalRelease:
    def __init__(self, root, runtime, boundary):
        self.root, self.runtime, self.boundary = Path(root), Path(runtime), boundary
        self.store = self.runtime / 'releases'
        self.pointer = self.runtime / 'current-release.json'
        self.journal = self.runtime / 'release-journal.json'
        self.jar = self.root / 'backend/target/ontology-law-system-0.1.0-SNAPSHOT.jar'
        self.dist = self.root / 'apps/workbench/dist'

    def check(self, pending=False):
        self.boundary.protect()
        if not pending and self.journal.exists() and read_json(self.journal)['phase'] != 'COMPLETE':
            raise RuntimeError('interrupted release; inspect status and recover explicitly')

    def package(self, name):
        if not isinstance(name, str) or not re.fullmatch(r'[0-9a-f]{32}', name):
            raise RuntimeError('invalid controlled release id')
        path = self.store / name
        if path.is_symlink() or getattr(path, 'is_junction', lambda: False)():
            raise RuntimeError('linked release rejected')
        return path

    def load(self, name):
        path = self.package(name)
        result = read_json(regular(path / 'release.json'))
        if result['id'] != name:
            raise RuntimeError('release identity mismatch')
        actual = files(path)
        actual.pop('release.json')
        if actual != result['files']:
            raise RuntimeError('saved release byte drift')
        return result

    def current(self):
        self.check()
        state = read_json(self.pointer)
        record = self.load(state['id'])
        if digest(encoded(record)) != state['descriptorHash']:
            raise RuntimeError('release descriptor drift')
        return state

    def materials(self):
        result = {}
        for folder in ('secrets', 'certs'):
            target = self.runtime / folder
            if target.exists():
                result.update({folder + '/' + name: value for name, value in files(target).items()})
        for path in self.runtime.iterdir():
            if path.is_file() and (path.suffix in ('.p12', '.pem', '.crt', '.key', '.pfx')
                                  or path.name in ('operator.json', 'original-manifest.json', 'service-fixture.json')):
                result[path.name] = digest(regular(path).read_bytes())
        if 'operator.json' not in result or 'original-manifest.json' not in result:
            raise RuntimeError('original bootstrap material missing')
        return result

    def check_materials(self, expected):
        # Subsequent approved Worker files are additions, not replacements of
        # the original bootstrap/key/certificate set.
        if any(digest(regular(self.runtime / name).read_bytes()) != value for name, value in expected.items()):
            raise RuntimeError('original material drift')

    def sources(self):
        result = {name: digest(regular(self.root / name).read_bytes()) for name in SOURCE_FILES}
        # Preserve exact existing inputs, including Resolver/route implementations outside
        # the named entry points. This is evidence of sources, not a substitute contract.
        for folder in ('backend/src/main', 'apps/workbench/src', 'database/schema-contract-52-plus-2/generated'):
            result.update({folder + '/' + name: value for name, value in files(self.root / folder).items()})
        for name in ('pom.xml', 'backend/pom.xml', 'package.json', 'package-lock.json', 'apps/workbench/package.json', 'apps/workbench/vite.config.ts'):
            if (self.root / name).exists():
                result[name] = digest(regular(self.root / name).read_bytes())
        return dict(sorted(result.items()))

    def build_inputs(self, commit):
        if not re.fullmatch('[0-9a-f]{40}', commit):
            raise RuntimeError('exact source commit required')
        self.boundary.provenance(commit)
        return {'sourceCommit': commit, 'toolchain': TOOLCHAIN, 'publicOidc': PUBLIC_OIDC, 'sources': self.sources()}

    def capture_inputs(self, commit):
        self.check()
        self.stable(self.current())
        atomic(self.runtime / 'build-inputs.json', self.build_inputs(commit))

    def describe(self, commit, build_exit_codes):
        self.check()
        inputs = self.build_inputs(commit)
        if inputs != read_json(regular(self.runtime / 'build-inputs.json')):
            raise RuntimeError('source or build configuration changed since before-build checkpoint')
        spa = files(self.dist)
        if 'index.html' not in spa:
            raise RuntimeError('SPA entry missing')
        return {**inputs,
                'buildExitCodes': build_exit_codes, 'jarSha256': digest(regular(self.jar).read_bytes()),
                'spaFiles': spa, 'spaSha256': digest(encoded(spa)),
                'serverSha256': digest(regular(self.root / 'deploy/local-login/server.mjs').read_bytes())}

    def seal(self, folder, record):
        record['files'] = files(folder)
        atomic(folder / 'release.json', record)
        final = self.package(record['id'])
        os.rename(folder, final)
        self.load(record['id'])
        return record

    def copy_artifacts(self, target):
        shutil.copy2(regular(self.jar), target / 'app.jar')
        shutil.copytree(self.dist, target / 'dist')
        shutil.copy2(regular(self.root / 'deploy/local-login/server.mjs'), target / 'server.mjs')

    def snapshot(self):
        self.check()
        if self.pointer.exists() or self.store.exists():
            raise RuntimeError('snapshot already exists or is partial; preserve and investigate')
        state = self.boundary.read()
        gate_valid(state['gate'])
        deployment_bytes = regular(self.runtime / 'deployment.json').read_bytes()
        deployment = json.loads(deployment_bytes)
        config = regular(self.runtime / 'application.properties').read_bytes()
        jar_hash, spa, material = digest(regular(self.jar).read_bytes()), files(self.dist), self.materials()
        if (jar_hash != state['gate']['active_release_digest'] or jar_hash != deployment['releaseDigest']
                or deployment['manifestHash'] != state['gate']['active_manifest_hash']
                or change_config(config, jar_hash, deployment['manifestHash']) != config):
            raise RuntimeError('legacy artifact/configuration/gate mismatch')
        self.store.mkdir()
        name = uuid.uuid4().hex
        pending = self.store / (name + '.pending')
        pending.mkdir()
        self.copy_artifacts(pending)
        (pending / 'legacy-server.mjs').write_bytes(self.boundary.legacy_server())
        (pending / 'application.properties').write_bytes(config)
        (pending / 'deployment.json').write_bytes(deployment_bytes)
        record = {'id': name, 'kind': 'legacy-byte-snapshot', 'gate': state['gate'], 'schema': state['schema'],
                  'materials': material, 'jarSha256': jar_hash, 'spaFiles': spa,
                  'historicalManifestOnly': True,
                  'schemaSources': files(self.root / 'database/schema-contract-52-plus-2/generated')}
        record['hostMode'] = 'saved-legacy-jar-dist-with-pinned-bridge-host'
        if digest((pending / 'app.jar').read_bytes()) != jar_hash or files(pending / 'dist') != spa:
            raise RuntimeError('legacy copy drift')
        if (self.boundary.read() != state or self.materials() != material
                or (self.runtime / 'application.properties').read_bytes() != config
                or (self.runtime / 'deployment.json').read_bytes() != deployment_bytes):
            raise RuntimeError('snapshot inputs changed')
        record = self.seal(pending, record)
        atomic(self.pointer, {'id': name, 'gate': state['gate'], 'descriptorHash': digest(encoded(record))})
        return name

    def stable(self, current, config=True):
        record = self.load(current['id'])
        observed = self.boundary.read()
        if observed != {'gate': current['gate'], 'schema': record['schema']}:
            raise RuntimeError('gate or schema drift; no release mutation allowed')
        self.check_materials(record['materials'])
        if config:
            for name in ('application.properties', 'deployment.json'):
                if regular(self.runtime / name).read_bytes() != (self.package(current['id']) / name).read_bytes():
                    raise RuntimeError('live configuration drift')
        return record

    def stage(self, request):
        self.check()
        current = self.current()
        old = self.stable(current)
        if files(self.root / 'database/schema-contract-52-plus-2/generated') != old['schemaSources']:
            raise RuntimeError('schema source change is outside local byte release')
        if request.get('buildExitCodes') != {'jar': 0, 'spa': 0}:
            raise RuntimeError('successful prebuilt artifact evidence required')
        if request != self.describe(request.get('sourceCommit', ''), request['buildExitCodes']):
            raise RuntimeError('candidate artifact, source, public configuration or toolchain drift')
        name = uuid.uuid4().hex
        pending = self.store / (name + '.pending')
        pending.mkdir()
        self.copy_artifacts(pending)
        if (digest((pending / 'app.jar').read_bytes()) != request['jarSha256']
                or files(pending / 'dist') != request['spaFiles']
                or digest((pending / 'server.mjs').read_bytes()) != request['serverSha256']):
            raise RuntimeError('candidate copy drift')
        manifest = copy.deepcopy(request)
        (pending / 'release-manifest.json').write_bytes(encoded(manifest))
        manifest_hash = digest(encoded(manifest))
        config = (self.package(current['id']) / 'application.properties').read_bytes()
        (pending / 'application.properties').write_bytes(change_config(config, request['jarSha256'], manifest_hash))
        deployment = read_json(self.package(current['id']) / 'deployment.json')
        deployment.update(releaseDigest=request['jarSha256'], manifestHash=manifest_hash)
        (pending / 'deployment.json').write_bytes(encoded(deployment))
        record = {'id': name, 'kind': 'controlled-local-release', 'previous': current,
                  'schema': old['schema'], 'materials': old['materials'],
                  'schemaSources': old['schemaSources'],
                  'jarSha256': request['jarSha256'], 'manifestHash': manifest_hash, 'provenance': request}
        self.stable(current)
        if request != self.describe(request['sourceCommit'], request['buildExitCodes']):
            raise RuntimeError('candidate changed while staging')
        self.seal(pending, record)
        return name

    def next_state(self, current, target):
        record = self.load(target)
        deployment = read_json(self.package(target) / 'deployment.json')
        gate = {**current['gate'], 'revision': current['gate']['revision'] + 1,
                'changed_at': datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%S.%fZ'),
                'active_release_digest': deployment['releaseDigest'], 'active_manifest_hash': deployment['manifestHash']}
        gate_valid(gate)
        return {'id': target, 'gate': gate, 'descriptorHash': digest(encoded(record))}

    def install(self, state):
        record = self.load(state['id'])
        if digest(encoded(record)) != state['descriptorHash']:
            raise RuntimeError('target descriptor drift')
        for name in ('application.properties', 'deployment.json'):
            atomic(self.runtime / name, (self.package(state['id']) / name).read_bytes())
        atomic(self.pointer, state)

    def switch(self, target):
        current = self.current()
        old = self.stable(current)
        new = self.load(target)
        if new['schema'] != old['schema'] or new['materials'] != old['materials']:
            raise RuntimeError('rollback requires unchanged schema and original material')
        self.boundary.stopped()
        desired = self.next_state(current, target)
        journal = {'phase': 'CAS_PENDING', 'old': current, 'new': desired, 'schema': old['schema']}
        if self.journal.exists():
            atomic(self.runtime / ('release-journal-' + uuid.uuid4().hex + '.json'), self.journal.read_bytes())
        atomic(self.journal, journal)
        self.boundary.cas(current['gate'], desired['gate'], old['schema'])
        journal['phase'] = 'FILES_PENDING'
        atomic(self.journal, journal)
        self.install(desired)
        self.stable(desired)
        journal['phase'] = 'COMPLETE'
        atomic(self.journal, journal)

    def activate(self, name):
        self.check()
        candidate = self.load(name)
        if candidate.get('previous') != self.current():
            raise RuntimeError('candidate was staged against a different old deployment')
        self.switch(name)

    def rollback(self):
        self.check()
        current = self.current()
        record = self.load(current['id'])
        if 'previous' not in record:
            raise RuntimeError('no saved predecessor')
        self.switch(record['previous']['id'])

    def status(self):
        self.check(pending=True)
        journal = read_json(self.journal) if self.journal.exists() else {'phase': 'NO_SWITCH'}
        observed = self.boundary.read()
        relation = 'other'
        for key in ('old', 'new', 'recovery'):
            if key in journal and journal[key]['gate'] == observed['gate']:
                relation = key
        return {'phase': journal['phase'], 'observedGateRelation': relation,
                'schemaMatches': 'schema' not in journal or observed['schema'] == journal['schema']}

    def recover(self, direction):
        self.check(pending=True)
        journal = read_json(self.journal)
        if journal['phase'] == 'COMPLETE' or direction not in ('complete', 'rollback'):
            raise RuntimeError('no matching explicit recovery')
        observed = self.boundary.read()
        old, new = journal['old'], journal['new']
        for state in (old, new):
            record = self.load(state['id'])
            if digest(encoded(record)) != state['descriptorHash']:
                raise RuntimeError('saved recovery material drift')
            self.check_materials(record['materials'])
        if observed['schema'] != journal['schema']:
            raise RuntimeError('schema changed; byte recovery forbidden')
        self.boundary.stopped()
        if 'recovery' in journal:
            if direction != 'rollback':
                raise RuntimeError('rollback recovery already selected')
            desired = journal['recovery']
            if observed['gate'] == new['gate']:
                self.boundary.cas(new['gate'], desired['gate'], journal['schema'])
            elif observed['gate'] != desired['gate']:
                raise RuntimeError('unknown gate; preserve and investigate')
        elif observed['gate'] == old['gate']:
            if direction != 'rollback':
                raise RuntimeError('CAS did not apply; explicitly rollback then restage')
            desired = old
        elif observed['gate'] == new['gate']:
            desired = new
            if direction == 'rollback':
                desired = self.next_state(new, old['id'])
                journal['recovery'] = desired
                journal['phase'] = 'ROLLBACK_CAS_PENDING'
                atomic(self.journal, journal)
                self.boundary.cas(new['gate'], desired['gate'], journal['schema'])
        else:
            raise RuntimeError('unknown gate; preserve and investigate')
        self.install(desired)
        self.stable(desired)
        journal['phase'] = 'COMPLETE'
        atomic(self.journal, journal)

    def paths(self):
        current = self.current()
        self.stable(current)
        package = self.package(current['id'])
        return {'jar': package / 'app.jar', 'dist': package / 'dist', 'server': package / 'server.mjs',
                'runtime': self.runtime, 'release': current['gate']['active_release_digest'],
                'manifest': current['gate']['active_manifest_hash']}

    def derived_operator(self):
        self.paths()
        current = self.current()
        original = regular(self.runtime / 'operator.json').read_bytes()
        derived = json.loads(original)
        derived['database']['releaseDigest'] = current['gate']['active_release_digest']
        derived['database']['manifestHash'] = current['gate']['active_manifest_hash']
        path = self.runtime / 'operator-current-release.json'
        atomic(path, derived)
        if read_json(path) != derived or regular(self.runtime / 'operator.json').read_bytes() != original:
            raise RuntimeError('derived operator verification failed')
        self.boundary.protect()
        return path


# Canonical complete gate: timestamps use an unambiguous UTC representation in
# both the durable journal and the SQL comparison (no session timezone drift).
GATE_SQL = """jsonb_build_object(
 'deployment_state_key',deployment_state_key,'operating_mode',operating_mode,
 'active_release_digest',encode(active_release_digest,'hex'),
 'active_manifest_hash',encode(active_manifest_hash,'hex'),
 'schema_contract_version',schema_contract_version,'revision',revision,
 'changed_at',to_char(changed_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'))"""
SCHEMA_SQL = """(SELECT jsonb_build_object(
 'namespaces',(SELECT jsonb_agg(jsonb_build_array(n.oid,n.nspname,n.nspowner,n.nspacl) ORDER BY n.oid)
   FROM pg_namespace n WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema'),
 'relations',(SELECT jsonb_agg(jsonb_build_array(c.oid,c.relname,c.relnamespace,c.relkind,c.relowner,c.relacl,c.relrowsecurity,c.relforcerowsecurity,c.reloptions) ORDER BY c.oid)
   FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema'),
 'columns',(SELECT jsonb_agg(to_jsonb(a) ORDER BY a.attrelid,a.attnum) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace
   WHERE a.attnum>0 AND n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema'),
 'constraints',(SELECT jsonb_agg(to_jsonb(c) ORDER BY c.oid) FROM pg_constraint c JOIN pg_namespace n ON n.oid=c.connamespace
   WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema'),
 'functions',(SELECT jsonb_agg(to_jsonb(p) ORDER BY p.oid) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
   WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema'),
 'triggers',(SELECT jsonb_agg(to_jsonb(t) ORDER BY t.oid) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace
   WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema'),
 'defaults',(SELECT jsonb_agg(to_jsonb(d) ORDER BY d.oid) FROM pg_attrdef d JOIN pg_class c ON c.oid=d.adrelid JOIN pg_namespace n ON n.oid=c.relnamespace
   WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema'),
 'indexes',(SELECT jsonb_agg(to_jsonb(i) ORDER BY i.indexrelid) FROM pg_index i JOIN pg_class c ON c.oid=i.indrelid JOIN pg_namespace n ON n.oid=c.relnamespace
   WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema'),
 'types',(SELECT jsonb_agg(to_jsonb(t) ORDER BY t.oid) FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace
   WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema'),
 'policies',(SELECT jsonb_agg(to_jsonb(p) ORDER BY p.oid) FROM pg_policy p JOIN pg_class c ON c.oid=p.polrelid JOIN pg_namespace n ON n.oid=c.relnamespace
   WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema'),
 'history',(SELECT jsonb_agg(to_jsonb(h) ORDER BY installed_rank) FROM platform_meta.flyway_schema_history h)))"""
OWNER_SQL = """IF current_user <> 'law_schema_migrator' OR
 (SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid='platform_meta.deployment_state'::regclass) <> current_user
 THEN RAISE EXCEPTION 'release owner mismatch'; END IF;"""


def literal(value):
    return "'" + value.replace("'", "''") + "'"


def cas_sql(old, new, schema):
    gate_valid(old)
    gate_valid(new)
    if new['revision'] != old['revision'] + 1 or new['schema_contract_version'] != old['schema_contract_version']:
        raise RuntimeError('invalid gate transition')
    return ("BEGIN; SET LOCAL lock_timeout='5s'; SET LOCAL statement_timeout='30s'; "
            "LOCK TABLE platform_meta.deployment_state IN ACCESS EXCLUSIVE MODE; DO $release$ DECLARE affected integer; BEGIN " + OWNER_SQL +
            " IF " + SCHEMA_SQL + " IS DISTINCT FROM " + literal(encoded(schema).decode()) + "::jsonb THEN RAISE EXCEPTION 'schema changed'; END IF; "
            "UPDATE platform_meta.deployment_state SET active_release_digest=decode(" + literal(new['active_release_digest']) + ",'hex'),"
            "active_manifest_hash=decode(" + literal(new['active_manifest_hash']) + ",'hex'),operating_mode='ACTIVE',revision=revision+1,changed_at=" + literal(new['changed_at']) + "::timestamptz WHERE " +
            GATE_SQL + "=" + literal(encoded(old).decode()) + "::jsonb; GET DIAGNOSTICS affected = ROW_COUNT; "
            "IF affected <> 1 THEN RAISE EXCEPTION 'release CAS conflict'; END IF; END $release$; "
            "COMMIT; SELECT 'RELEASE_CAS_ONE';")


def owned_process(expected, actual):
    if actual is None:
        return
    normalize = lambda value: os.path.normcase(os.path.normpath(value)).replace('\\', '/').lower()
    if (actual['pid'] != expected['pid'] or normalize(actual['executable']) != normalize(expected['executable'])
            or actual['args'] != expected['args']
            or 'created' in expected and actual['created'] != expected['created']):
        raise RuntimeError('unknown or reused PID; nothing stopped')


class RuntimeBoundary:
    """Windows/Docker adapters. Unit tests replace only these external boundaries."""
    def __init__(self, runner):
        self.runner = runner

    def protect(self):
        self.runner.require_protected_runtime()

    def provenance(self, commit):
        run = lambda args: subprocess.run(['git', *args], cwd=self.runner.ROOT, capture_output=True)
        head = run(['rev-parse', 'HEAD'])
        dirty = run(['diff', '--name-only', 'HEAD', '--', 'backend/src/main', 'apps/workbench/src',
                     'database/schema-contract-52-plus-2/generated', 'contracts/openapi', 'deploy/local-login',
                     'deploy/identity', 'pom.xml', 'backend/pom.xml', 'package.json', 'package-lock.json', 'apps/workbench/package.json', 'apps/workbench/vite.config.ts'])
        untracked = run(['ls-files', '--others', '--exclude-standard', '--', 'backend/src/main', 'apps/workbench/src', 'contracts/openapi'])
        if head.returncode or dirty.returncode or untracked.returncode or head.stdout.decode().strip() != commit or dirty.stdout.strip() or untracked.stdout.strip():
            raise RuntimeError('source commit is not clean at artifact inputs')

    def legacy_server(self):
        result = subprocess.run(['git', 'show', '01213b1acfeb164c271c430f6561f87f570250c4:deploy/local-login/server.mjs'],
                                cwd=self.runner.ROOT, capture_output=True)
        if result.returncode:
            raise RuntimeError('baseline host unavailable')
        return result.stdout

    def sql(self, statement):
        self.protect()
        lock = read_json(self.runner.ROOT / 'deploy/identity/identity-toolchain.lock.json')['identityDatabase']
        image = lock['image'] + '@' + lock['digest']
        # Password and CA are file mounts; neither secret nor connection string is
        # exposed on stdout/stderr or supplied as a command-line credential.
        args = ['docker', 'run', '--rm', '--pull=never', '-i', '--network', self.runner.PREFIX + '-business',
                '--mount', 'type=bind,source=' + str(self.runner.RUNTIME / 'secrets/migrator.txt') + ',target=/run/migrator,readonly',
                '--mount', 'type=bind,source=' + str(self.runner.RUNTIME / 'certs/ca.pem') + ',target=/run/ca.pem,readonly',
                '--entrypoint', '/bin/bash', image, '-ec',
                'export PGPASSWORD="$(</run/migrator)"; exec psql -X -qAt -v ON_ERROR_STOP=1 '
                '"host=business-db dbname=law_contract_runtime user=law_schema_migrator sslmode=verify-full sslrootcert=/run/ca.pem"']
        result = subprocess.run(args, input=statement.encode(), capture_output=True, cwd=self.runner.ROOT)
        if result.returncode:
            raise RuntimeError('migration Owner operation failed; gate outcome must be reconciled')
        return result.stdout.decode().strip()

    def read(self):
        statement = ("BEGIN READ ONLY; SET LOCAL statement_timeout='30s'; DO $owner$ BEGIN " + OWNER_SQL +
                     " END $owner$; SELECT jsonb_build_object('gate'," + GATE_SQL + ",'schema'," + SCHEMA_SQL +
                     ") FROM platform_meta.deployment_state WHERE deployment_state_key='PRIMARY'; COMMIT;")
        result = json.loads(self.sql(statement))
        gate_valid(result['gate'])
        return result

    def cas(self, old, new, schema):
        if self.sql(cas_sql(old, new, schema)) != 'RELEASE_CAS_ONE':
            raise RuntimeError('uncertain CAS result; explicit reconciliation required')

    def process(self, pid):
        if type(pid) is not int or pid < 1:
            raise RuntimeError('invalid PID')
        script = f"$p=Get-CimInstance Win32_Process -Filter 'ProcessId={pid}'; if($null -eq $p){{'null'}}else{{$p | Select-Object ProcessId,ExecutablePath,CommandLine,CreationDate | ConvertTo-Json -Compress}}"
        result = subprocess.run(['pwsh', '-NoProfile', '-NonInteractive', '-Command', script], capture_output=True)
        if result.returncode:
            raise RuntimeError('process ownership unavailable')
        data = json.loads(result.stdout)
        if data is None:
            return None
        import ctypes
        from ctypes import wintypes
        parse = ctypes.windll.shell32.CommandLineToArgvW
        parse.argtypes = [wintypes.LPCWSTR, ctypes.POINTER(ctypes.c_int)]
        parse.restype = ctypes.POINTER(wintypes.LPWSTR)
        count = ctypes.c_int()
        parsed = parse(data['CommandLine'], ctypes.byref(count))
        if not parsed:
            raise RuntimeError('process command unavailable')
        try:
            arguments = [parsed[i] for i in range(count.value)]
        finally:
            ctypes.windll.kernel32.LocalFree(ctypes.cast(parsed, ctypes.c_void_p))
        return {'pid': data['ProcessId'], 'executable': data['ExecutablePath'], 'args': arguments[1:],
                'created': data['CreationDate'], 'commandLine': data['CommandLine']}

    def processes(self):
        saved = read_json(self.runner.RUNTIME / 'processes.json')
        if set(saved) != {'api', 'spa'}:
            raise RuntimeError('unknown process registry; coordinate all owned consumers before switch')
        legacy = {'api': [str(self.runner.JAVA), '-Xmx768m', '-jar', str(self.runner.JAR),
                          '--spring.config.additional-location=' + (self.runner.RUNTIME / 'application.properties').as_uri()],
                  'spa': [str(self.runner.TOOLS / 'node-v24.20.0-win-x64/node.exe'), str(self.runner.ROOT / 'deploy/local-login/server.mjs')]}
        result = []
        for name in ('api', 'spa'):
            expected = saved[name]
            if type(expected) is int:
                expected = {'pid': expected, 'executable': legacy[name][0], 'args': legacy[name][1:]}
            else:
                candidates = []
                pointer = self.runner.RUNTIME / 'current-release.json'
                journal = self.runner.RUNTIME / 'release-journal.json'
                states = [read_json(pointer)] if pointer.exists() else []
                if journal.exists():
                    data = read_json(journal)
                    states += [data[key] for key in ('old', 'new', 'recovery') if key in data]
                manager = LocalRelease(self.runner.ROOT, self.runner.RUNTIME, self)
                for state in states:
                    package = manager.package(state['id'])
                    manager.load(state['id'])
                    candidates.append(app_commands(self.runner, package)[name])
                if not any(expected['executable'] == command[0] and expected['args'] == command[1:] for command in candidates):
                    raise RuntimeError('process registry does not name a controlled local artifact')
            actual = self.process(expected['pid'])
            owned_process(expected, actual)
            result.append(actual)
        return result

    def stopped(self):
        self.protect()
        if any(self.processes()):
            raise RuntimeError('owned API/SPA must be stopped before byte switching')
        if (self.runner.RUNTIME / 'apps-start.pending').exists():
            raise RuntimeError('interrupted process launch; reconcile marker and exact owned processes')

    def stop(self):
        self.protect()
        processes = self.processes()  # Validate both before stopping either.
        for process in processes:
            if process is None:
                continue
            quote = lambda value: "'" + value.replace("'", "''") + "'"
            script = (f"$p=Get-CimInstance Win32_Process -Filter 'ProcessId={process['pid']}'; if($null -eq $p){{exit 0}}; "
                      "if($p.ExecutablePath -ne " + quote(process['executable']) + " -or $p.CommandLine -cne " + quote(process['commandLine']) +
                      " -or ($p.CreationDate | ConvertTo-Json -Compress) -ne " + quote(json.dumps(process['created'])) + "){exit 3}; "
                      f"Stop-Process -Id {process['pid']} -ErrorAction Stop; Wait-Process -Id {process['pid']} -Timeout 20 -ErrorAction SilentlyContinue")
            result = subprocess.run(['pwsh', '-NoProfile', '-NonInteractive', '-Command', script], capture_output=True)
            if result.returncode:
                raise RuntimeError('owned process stop failed; inspect without name-based killing')
        self.stopped()


def app_commands(runner, package):
    return {'api': [str(runner.JAVA), '-Xmx768m', '-jar', str(package / 'app.jar'),
                    '--spring.config.additional-location=' + (runner.RUNTIME / 'application.properties').as_uri()],
            'spa': [str(runner.TOOLS / 'node-v24.20.0-win-x64/node.exe'), str(package / 'server.mjs'),
                    str(runner.RUNTIME), str(package / 'dist')]}
