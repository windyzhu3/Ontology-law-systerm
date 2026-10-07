"""Exact artifact inventory including the schema embedded by the application build."""
import hashlib
import json
from pathlib import Path
import re
import zipfile
from .config import digest

GENERATED = 'database/schema-contract-52-plus-2/generated'
LOCKS = ('database/schema-contract-52-plus-2/runtime/toolchain.lock.json', 'deploy/identity/identity-toolchain.lock.json')


def sha(path: Path) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def inventory(directory: Path) -> dict:
    directory = Path(directory)
    if not directory.is_dir() or directory.is_symlink(): raise RuntimeError('Artifact directory missing or linked')
    result = {}
    for path in sorted(directory.rglob('*')):
        if path.is_symlink(): raise RuntimeError('Artifact links are forbidden')
        if path.is_file(): result[path.relative_to(directory).as_posix()] = sha(path)
    return result


def describe(repo: Path, jar: Path, spa: Path, commit: str, *, include_runtime=False) -> dict:
    repo, jar, spa = Path(repo).absolute(), Path(jar).absolute(), Path(spa).absolute()
    if not re.fullmatch('[a-f0-9]{40}', commit): raise RuntimeError('Exact source commit required')
    if jar.is_symlink() or not jar.is_file() or not zipfile.is_zipfile(jar): raise RuntimeError('Executable application JAR required')
    manifest = repo / GENERATED / 'schema-contract-manifest.json'
    value = json.loads(manifest.read_text(encoding='utf-8'))
    if value['contractVersion'] != '52-plus-2-r2-v22': raise RuntimeError('This bundle builder requires the reviewed v22 contract')
    with zipfile.ZipFile(jar) as z:
        try:
            embedded = z.read('BOOT-INF/classes/schema-contract/schema-contract-manifest.json')
            boot = z.read('META-INF/MANIFEST.MF')
            build_source = json.loads(z.read('BOOT-INF/classes/schema-contract/build-source.json'))
        except KeyError as error: raise RuntimeError('JAR lacks build-time schema evidence') from error
        if embedded != manifest.read_bytes() or b'JarLauncher' not in boot or build_source != {'commit':commit, 'manifestHash':sha(manifest)}:
            raise RuntimeError('JAR and source schema do not match')
    tree = inventory(spa)
    if 'index.html' not in tree or not tree: raise RuntimeError('Built SPA entry missing')
    proof_path = repo / '.artifacts/linux-build-proof.json'
    try: proof = json.loads(proof_path.read_text(encoding='utf-8'))
    except (OSError, ValueError) as error: raise RuntimeError('Controlled build completion proof missing') from error
    if proof != {'commit':commit, 'jarExitCode':0, 'spaExitCode':0, 'jarSha256':sha(jar), 'spaFiles':tree}:
        raise RuntimeError('Build failed or artifacts differ from their recorded build')
    migrations = inventory(repo / GENERATED / 'db/migration')
    if len(migrations) != 43 or any(value['generatedArtifactSha256'].get('db/migration/' + name) != hash_value for name, hash_value in migrations.items()):
        raise RuntimeError('Generated migration inventory does not match the manifest')
    frozen = json.loads((Path(__file__).resolve().parents[1] / 'config/v20-migration-hashes.json').read_text(encoding='utf-8'))
    if len(frozen) != 41 or any(migrations.get(name) != expected for name, expected in frozen.items()) or set(migrations) - set(frozen) != {'V1070__configurable_appointment_roles.sql','V1080__metadata_comments.sql'}:
        raise RuntimeError('Reviewed v20 prefix or two successor migrations differ')
    paths = [manifest, jar, proof_path, *[repo / lock for lock in LOCKS]]
    config_dir = repo / 'deploy/linux/config'
    paths += [config_dir / name for name in inventory(config_dir)]
    if (repo / 'deploy/linux/runtime/toolchain.lock.json').is_file(): paths += [repo / 'deploy/linux/runtime/toolchain.lock.json']
    if include_runtime:
        for directory in ('deploy/linux/templates','deploy/linux/runtime','deploy/linux/ols_linux'):
            paths += [repo/directory/name for name in inventory(repo/directory)]
        paths += [repo/name for name in ('deploy/identity/realm-template.json','contracts/openapi/ontology-law-api.yaml','backend/src/test/resources/db/bootstrap-runtime-logins.sql')]
        if (repo/'deploy/linux/linux.py').is_file():paths.append(repo/'deploy/linux/linux.py')
    try: files = {path.relative_to(repo).as_posix(): sha(path) for path in paths}
    except ValueError as error: raise RuntimeError('Release bytes must be staged within the bundle root') from error
    result = {'version': 2 if include_runtime else 1, 'commit': commit, 'schemaVersion': value['contractVersion'], 'manifestHash': sha(manifest),
              'jar': jar.relative_to(repo).as_posix(), 'spa': spa.relative_to(repo).as_posix(),
              'files': files, 'spaFiles': tree, 'migrations': migrations}
    result['descriptorDigest'] = digest(result)
    return result


def verify(descriptor: dict, directory: Path) -> None:
    value = dict(descriptor)
    seal = value.pop('descriptorDigest', None)
    if seal != digest(value): raise RuntimeError('Release descriptor was modified')
    if descriptor.get('version') not in {1,2}:raise RuntimeError('Unknown release descriptor version')
    root = Path(directory).absolute()
    for name, expected in descriptor['files'].items():
        path = root / name
        if path.resolve() != path.absolute() or not path.resolve().is_relative_to(root) or not path.is_file() or sha(path) != expected:
            raise RuntimeError('Release file missing, linked or modified')
    actual = describe(root, root / descriptor['jar'], root / descriptor['spa'], descriptor['commit'],include_runtime=descriptor['version']==2)
    if actual != descriptor: raise RuntimeError('Release inventory or contract differs')
