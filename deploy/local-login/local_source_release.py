"""The one approved fixed local source delta; never builds or modifies business facts."""
import copy
import re
import shutil
import uuid

from local_release import change_config, digest, encoded, files, read_json, regular

PROFILE = 'LOCAL_SYNTHETIC_SOURCE_RELEASE_V1'
AUTO_SOURCE_LINES = (
    b'ols.api.sources[LOCAL_SYNTHETIC_AUTO].assignment-mode=AUTOMATIC\n'
    b'ols.api.sources[LOCAL_SYNTHETIC_AUTO].routing-organization-root-codes[0]=ROOT\n'
    b'ols.api.sources[LOCAL_SYNTHETIC_AUTO].routing-supervisor-root-code=ROOT\n'
    b'ols.api.sources[LOCAL_SYNTHETIC_AUTO].source-intake-root-code=ROOT\n'
    b'ols.api.sources[LOCAL_SYNTHETIC_AUTO].business-timezone=Asia/Shanghai\n')

# Closed inventory from the original local application-config generator. Values
# remain opaque; no secret or extra caller-supplied configuration is accepted.
BASE_KEYS = set('''ols.runtime-role server.address server.port spring.main.banner-mode logging.level.root
server.ssl.client-auth server.ssl.key-store server.ssl.key-store-password server.ssl.key-store-type
server.ssl.trust-store server.ssl.trust-store-password server.ssl.trust-store-type
ols.api.semantic-baseline ols.api.node ols.api.cursor-key ols.api.identity-trust-store-path
ols.api.identity-trust-store-password-path'''.split())
for prefix, fields in {
    'database': 'url username password schema-version release-digest manifest-hash',
    'trusts[0]': 'issuer audience verification-key-path',
    'human-trusts[0]': 'issuer audience identity-provider-code tenant-id introspection-client-id introspection-secret-path directory-client-id directory-secret-path',
    'registrations[0]': 'issuer audience identity-provider-code tenant-id principal-id appointment-id principal-kind source-account-codes[0]',
    'certificates[0]': 'sha256 identity-provider-code tenant-id principal-id appointment-id',
    'identity-administration': 'active-candidate-key-id candidate-keys[local-online-v1] etag-key cursor-key',
}.items():
    BASE_KEYS.update('ols.api.' + prefix + '.' + field for field in fields.split())


def properties(data):
    """Accept the generator's unescaped key=value form; ambiguity fails closed."""
    try:
        text = data.decode('utf-8')
    except UnicodeError:
        raise RuntimeError('invalid saved API configuration') from None
    if not text.endswith('\n') or '\\' in text or '\x00' in text:
        raise RuntimeError('ambiguous saved API configuration')
    result = {}
    for line in text.splitlines():
        if not line or line.startswith(('#', '!')):
            continue
        key, separator, value = line.partition('=')
        if not separator or not re.fullmatch(r'[A-Za-z0-9_.\[\]-]+', key) or key in result:
            raise RuntimeError('ambiguous saved API configuration')
        result[key] = value
    return result


def auto_policy(data):
    values = properties(data)
    selected = {key: value for key, value in values.items()
                if 'localsyntheticauto' in re.sub(r'[^a-z0-9]', '', key.lower())}
    if selected and selected != properties(AUTO_SOURCE_LINES):
        raise RuntimeError('malformed or changed fixed AUTO source configuration')
    return selected


def add_fixed_auto_source(data):
    values = properties(data)
    manual = properties(AUTO_SOURCE_LINES.replace(b'LOCAL_SYNTHETIC_AUTO', b'LOCAL_SYNTHETIC')
                        .replace(b'=AUTOMATIC', b'=MANUAL'))
    if any(values.get(key) != value for key, value in manual.items()):
        raise RuntimeError('original fixed MANUAL source policy mismatch')
    for key in values:
        if key not in BASE_KEYS and key not in manual and not re.fullmatch(
                r'ols\.api\.tenant-keys\[[0-9a-f-]{36}\]\.(encryption|phone-hmac|email-hmac|source-hmac|credential-subject-hmac|actor-scope-hmac)', key):
            raise RuntimeError('unknown or already configured source input')
    if values.get('ols.api.registrations[0].source-account-codes[0]') != 'LOCAL_SYNTHETIC':
        raise RuntimeError('original SERVICE source binding mismatch')
    return data + AUTO_SOURCE_LINES


def stage_local_auto_source(release, operator_commit):
    release.check()
    if not isinstance(operator_commit, str) or not re.fullmatch(r'[0-9a-f]{40}', operator_commit):
        raise RuntimeError('exact operator commit required')
    release.boundary.operator_provenance(operator_commit)
    current = release.current()
    parent = release.stable(current)
    package = release.package(current['id'])
    config = regular(package / 'application.properties').read_bytes()
    updated = add_fixed_auto_source(config)
    deployment = read_json(regular(package / 'deployment.json'))
    values = properties(config)
    if (values.get('ols.api.database.release-digest') != current['gate']['active_release_digest']
            or values.get('ols.api.database.manifest-hash') != current['gate']['active_manifest_hash']
            or deployment.get('releaseDigest') != current['gate']['active_release_digest']
            or deployment.get('manifestHash') != current['gate']['active_manifest_hash']):
        raise RuntimeError('parent configuration gate mismatch')
    if parent['kind'] != 'controlled-local-release':
        raise RuntimeError('verified business release provenance required')
    provenance = parent['provenance']
    if (read_json(regular(package / 'release-manifest.json')) != provenance
            or digest(encoded(provenance)) != current['gate']['active_manifest_hash']
            or provenance['jarSha256'] != current['gate']['active_release_digest']
            or files(release.root / 'database/schema-contract-52-plus-2/generated') != parent['schemaSources']):
        raise RuntimeError('parent manifest or schema source mismatch')
    artifact_hashes = {'jarSha256': digest(regular(package / 'app.jar').read_bytes()),
                       'spaFiles': files(package / 'dist'),
                       'serverSha256': digest(regular(package / 'server.mjs').read_bytes())}
    artifact_hashes['spaSha256'] = digest(encoded(artifact_hashes['spaFiles']))
    if any(provenance.get(key) != value for key, value in artifact_hashes.items()):
        raise RuntimeError('parent binary provenance mismatch')
    manifest = {'profile': PROFILE,
                'parent': {'id': current['id'], 'descriptorHash': current['descriptorHash'],
                           'manifestHash': current['gate']['active_manifest_hash']},
                'originalConfigSha256': digest(config), 'sourceDelta': AUTO_SOURCE_LINES.decode().splitlines(),
                'operatorCommit': operator_commit, 'binaryProvenance': copy.deepcopy(provenance),
                **artifact_hashes}
    manifest_hash = digest(encoded(manifest))
    name = uuid.uuid4().hex
    pending = release.store / (name + '.pending')
    pending.mkdir()
    for file in ('app.jar', 'server.mjs'):
        shutil.copy2(regular(package / file), pending / file)
    shutil.copytree(package / 'dist', pending / 'dist')
    if (digest((pending / 'app.jar').read_bytes()) != artifact_hashes['jarSha256']
            or files(pending / 'dist') != artifact_hashes['spaFiles']
            or digest((pending / 'server.mjs').read_bytes()) != artifact_hashes['serverSha256']):
        raise RuntimeError('saved artifact copy drift')
    (pending / 'release-manifest.json').write_bytes(encoded(manifest))
    (pending / 'application.properties').write_bytes(change_config(updated, current['gate']['active_release_digest'], manifest_hash))
    deployment.update(releaseDigest=current['gate']['active_release_digest'], manifestHash=manifest_hash)
    (pending / 'deployment.json').write_bytes(encoded(deployment))
    record = {'id': name, 'kind': 'controlled-local-source-release', 'previous': current,
              'schema': parent['schema'], 'materials': parent['materials'], 'schemaSources': parent['schemaSources'],
              'jarSha256': artifact_hashes['jarSha256'], 'manifestHash': manifest_hash,
              'provenance': copy.deepcopy(provenance), 'sourceRelease': manifest}
    release.stable(current)
    if release.current() != current:
        raise RuntimeError('current parent changed during source staging')
    release.boundary.operator_provenance(operator_commit)
    release.seal(pending, record)
    return name


def guard_source_removal(release, states, desired):
    """Called only after exact consumers stopped, before journal/CAS/install writes.

    Recovery includes both journal sides and live bytes: either package may have
    been exposed before interruption, irrespective of the current gate relation.
    """
    target = release.package(desired['id'])
    target_policy = auto_policy(regular(target / 'application.properties').read_bytes())
    policies = [auto_policy(regular(release.package(state['id']) / 'application.properties').read_bytes())
                for state in states]
    policies.append(auto_policy(regular(release.runtime / 'application.properties').read_bytes()))
    if not any(policy and policy != target_policy for policy in policies):
        return
    tenant = read_json(regular(target / 'deployment.json'))['tenantId']
    if any(read_json(regular(release.package(state['id']) / 'deployment.json'))['tenantId'] != tenant for state in states):
        raise RuntimeError('source removal tenant mismatch')
    if release.boundary.source_facts(tenant) is not False:
        raise RuntimeError('AUTO source facts exist or are unknown; source removal forbidden')
