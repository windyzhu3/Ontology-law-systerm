"""Native packaging of an exact Git archive; never patch a completed JAR."""
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import tarfile
import urllib.request
import zipfile
from . import bundle, runtime
from .config import canonical


def extract(payload: bytes, target: Path):
    with tarfile.open(fileobj=io.BytesIO(payload),mode='r:') as archive:
        members=archive.getmembers()
        if any(PurePosixPath(m.name).is_absolute() or '..' in PurePosixPath(m.name).parts or not (m.isfile() or m.isdir()) for m in members):
            raise RuntimeError('Source archive contains links or unsafe paths')
        archive.extractall(target,members=members,filter='data')


def resources(root: Path, commit: str) -> Path:
    if not re.fullmatch('[0-9a-f]{40}',commit):raise RuntimeError('Exact source commit required')
    manifest=root/bundle.GENERATED/'schema-contract-manifest.json'
    target=root/'.artifacts/linux-build-resources'
    runtime.private_file(target/'schema-contract/schema-contract-manifest.json',manifest.read_bytes())
    runtime.private_file(target/'schema-contract/build-source.json',canonical({'commit':commit,'manifestHash':bundle.sha(manifest)}))
    return target


def completion(root: Path, commit: str, jar_exit: int, spa_exit: int) -> dict:
    if jar_exit!=0 or spa_exit!=0:raise RuntimeError('Failed builds cannot produce a completion proof')
    jar=root/'backend/target/ontology-law-system-0.1.0-SNAPSHOT.jar';spa=root/'apps/workbench/dist'
    proof={'commit':commit,'jarExitCode':jar_exit,'spaExitCode':spa_exit,'jarSha256':bundle.sha(jar),'spaFiles':bundle.inventory(spa)}
    runtime.private_file(root/'.artifacts/linux-build-proof.json',canonical(proof))
    result=bundle.describe(root,jar,spa,commit,include_runtime=True)
    runtime.private_file(root/'release.json',canonical(result))
    return result


def maven(root: Path) -> Path:
    expected='5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce'
    url='https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip'
    wrapper=dict(line.split('=',1) for line in (root/'.mvn/wrapper/maven-wrapper.properties').read_text().splitlines() if '=' in line)
    if wrapper.get('distributionUrl')!=url or wrapper.get('distributionSha256Sum')!=expected:raise RuntimeError('Reviewed exact Maven wrapper required')
    archive=root/'.artifacts/maven.zip'
    if not archive.exists():
        with urllib.request.urlopen(url,timeout=60) as response:runtime.private_file(archive,response.read(32*1024*1024))
    if archive.is_symlink() or bundle.sha(archive)!=expected:raise RuntimeError('Maven archive checksum differs')
    target=root/'.artifacts/maven'
    executable=target/'apache-maven-3.9.16/bin/mvn'
    if not target.exists():
        target.mkdir(mode=0o700)
        with zipfile.ZipFile(archive) as z:
            if any(PurePosixPath(n).is_absolute() or '..' in PurePosixPath(n).parts for n in z.namelist()):raise RuntimeError('Unsafe Maven archive')
            z.extractall(target)
    return executable


def run(repo: Path, target: Path, image: str, oidc: dict) -> dict:
    repo,target=Path(repo).absolute(),Path(target).absolute()
    if os.name=='nt':raise RuntimeError('Native build requires the Linux entry')
    if repo.resolve()!=repo or target.resolve()!=target or target.exists():raise RuntimeError('New unlinked private build target required')
    if not re.fullmatch('sha256:[0-9a-f]{64}',image):raise RuntimeError('Locked Linux toolchain image required')
    commit=runtime.run(['git','-C',repo,'rev-parse','HEAD']).stdout.decode().strip()
    # Git archive selects the exact committed source, including sparse files. Dirty
    # checkout bytes are neither copied nor silently included in this release.
    payload=runtime.run(['git','-C',repo,'archive','--format=tar',commit],timeout=120).stdout
    target.mkdir(mode=0o700,parents=True);extract(payload,target)
    return archived(target,commit,image,oidc)


def archived(target: Path, commit: str, image: str, oidc: dict) -> dict:
    """Consume a controller-exported archive after its Git archive hash was recorded."""
    target=Path(target).absolute()
    if target.resolve()!=target or not re.fullmatch('[0-9a-f]{40}',commit) or not re.fullmatch('sha256:[0-9a-f]{64}',image):raise RuntimeError('Exact native build inputs required')
    if set(oidc)!={'VITE_APP_ORIGIN','VITE_OIDC_ISSUER','VITE_OIDC_CLIENT_ID','VITE_OIDC_AUDIENCE'} or any(not isinstance(v,str) or not v.strip() or '\n' in v for v in oidc.values()):raise ValueError('Explicit public OIDC build configuration required')
    resources(target,commit)
    name='ols-linux-build-'+commit[:12]
    if runtime.inspect('container',name):raise RuntimeError('Existing build result must be reconciled, not restarted')
    env=[arg for k,v in oidc.items() for arg in ['-e',k+'='+v]]
    script='npm ci && npm run openapi:check && npm run typecheck && npm run build'
    result=runtime.run(['docker','run','--rm','--name',name,'--label','ols.build='+commit,'--log-driver','local','--log-opt','max-size=10m','--mount',f'type=bind,source={target},target=/src','--workdir','/src',*env,'--entrypoint','sh',image,'-ec',script],timeout=1800,check=False)
    runtime.private_file(target/'.artifacts/spa-build.log',result.stdout+result.stderr)
    if result.returncode:raise RuntimeError('Native SPA build failed; original source and log retained')
    maven(target)
    script='sh .artifacts/maven/apache-maven-3.9.16/bin/mvn -B -f backend/pom.xml -DskipTests -Dols.build.resources=../.artifacts/linux-build-resources package'
    result=runtime.run(['docker','run','--rm','--name',name,'--label','ols.build='+commit,'--log-driver','local','--log-opt','max-size=10m','--mount',f'type=bind,source={target},target=/src','--workdir','/src','--entrypoint','sh',image,'-ec',script],timeout=1800,check=False)
    runtime.private_file(target/'.artifacts/jar-build.log',result.stdout+result.stderr)
    if result.returncode:raise RuntimeError('Native JAR build failed; original source and log retained')
    result=completion(target,commit,0,0)
    runtime.private_file(target/'.artifacts/linux-bundle.json',canonical(result))
    return result
