"""Structural release fixtures deliberately lack executable Java classes.

They can prove migration preservation and closed activation, never readiness.
The final Linux application verification uses genuinely built artifacts.
"""
import gzip
import json
from pathlib import Path
import shutil
import zipfile
from ols_linux import bundle,config,runtime


def create(repo: Path,directory: Path,version: str) -> dict:
    if version not in {'v20','v22'}:raise ValueError('Reviewed schema fixture only')
    directory.mkdir(mode=0o700,parents=True)
    generated=directory/bundle.GENERATED;generated.mkdir(parents=True)
    if version=='v20':
        metadata=json.loads((repo/'deploy/linux/verification/fixtures/v20-source.json').read_text(encoding='utf-8'))
        manifest=gzip.decompress((repo/'deploy/linux/verification/fixtures/v20-manifest.json.gz').read_bytes())
        if __import__('hashlib').sha256(manifest).hexdigest()!=metadata['sha256']:raise RuntimeError('Frozen manifest differs')
        commit=metadata['commit']
    else:
        manifest=(repo/bundle.GENERATED/'schema-contract-manifest.json').read_bytes();commit='a'*40
    runtime.private_file(generated/'schema-contract-manifest.json',manifest)
    migrations=json.loads((repo/'deploy/linux/config/v20-migration-hashes.json').read_text(encoding='utf-8')) if version=='v20' else bundle.inventory(repo/bundle.GENERATED/'db/migration')
    for name,h in migrations.items():
        source=repo/bundle.GENERATED/'db/migration'/name
        if bundle.sha(source)!=h:raise RuntimeError('Frozen fixture migration differs')
        runtime.private_file(generated/'db/migration'/name,source.read_bytes())
    for name in bundle.LOCKS:
        (directory/name).parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(repo/name,directory/name)
    shutil.copytree(repo/'deploy/linux/config',directory/'deploy/linux/config')
    shutil.copytree(repo/'deploy/linux/runtime',directory/'deploy/linux/runtime')
    jar=directory/'app.jar'
    with zipfile.ZipFile(jar,'w') as archive:
        archive.writestr('META-INF/MANIFEST.MF','Main-Class: org.springframework.boot.loader.launch.JarLauncher\n')
        archive.writestr('BOOT-INF/classes/schema-contract/schema-contract-manifest.json',manifest)
        archive.writestr('BOOT-INF/classes/schema-contract/build-source.json',config.canonical({'commit':commit,'manifestHash':bundle.sha(generated/'schema-contract-manifest.json')}))
    runtime.private_file(directory/'dist/index.html',b'<html>Non-executable migration artifact fixture</html>')
    tree=bundle.inventory(directory/'dist')
    proof={'commit':commit,'jarExitCode':0,'spaExitCode':0,'jarSha256':bundle.sha(jar),'spaFiles':tree}
    # These are fixture descriptors, not a record of a production application build.
    runtime.private_file(directory/'.artifacts/linux-build-proof.json',config.canonical(proof))
    if version=='v22':descriptor=bundle.describe(directory,jar,directory/'dist',commit)
    else:
        paths=[bundle.GENERATED+'/schema-contract-manifest.json','app.jar','.artifacts/linux-build-proof.json',*bundle.LOCKS]
        paths += ['deploy/linux/config/'+name for name in bundle.inventory(directory/'deploy/linux/config')]
        paths += ['deploy/linux/runtime/toolchain.lock.json']
        descriptor={'version':1,'commit':commit,'schemaVersion':'52-plus-2-r2-v20','manifestHash':bundle.sha(generated/'schema-contract-manifest.json'),'jar':'app.jar','spa':'dist','files':{name:bundle.sha(directory/name) for name in paths},'spaFiles':tree,'migrations':migrations}
        descriptor['descriptorDigest']=config.digest(descriptor)
    runtime.private_file(directory/'release.json',config.canonical(descriptor))
    return descriptor
