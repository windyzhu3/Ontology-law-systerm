"""Deterministic source-only archive builder. Does not access any runtime/evidence."""
import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import tarfile
import zlib


def build(base):
    files=sorted((base/'ols_linux').glob('*.py'))+sorted(path for path in (base/'verification/systemd_qualification').rglob('*') if path.is_file() and path.suffix in {'.py','.conf','.Caddyfile','.md'})
    if not files or not (base/'verification/systemd_qualification/bootstrap.py').is_file():raise RuntimeError('Complete checked-out source tree required')
    buffer=io.BytesIO();content={}
    with tarfile.open(fileobj=buffer,mode='w') as archive:
        for path in files:
            if path.is_symlink() or path.resolve()!=path.absolute():raise RuntimeError('Linked source file is forbidden')
            data=path.read_bytes();name=str(path.relative_to(base));content[name]=hashlib.sha256(data).hexdigest()
            info=tarfile.TarInfo(name);info.size=len(data);info.mode=0o644;info.mtime=0
            archive.addfile(info,io.BytesIO(data))
    raw=buffer.getvalue();compressed=gzip.compress(raw,mtime=0)
    return compressed,{'archiveBytes':len(compressed),'archiveSha256':hashlib.sha256(compressed).hexdigest(),'uncompressedTarSha256':hashlib.sha256(raw).hexdigest(),'zlibRuntime':zlib.ZLIB_RUNTIME_VERSION,'files':content}


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--source-root',required=True,type=Path,help='Verified deploy/linux source directory');parser.add_argument('--output',required=True,type=Path);args=parser.parse_args()
    data,manifest=build(args.source_root.absolute())
    with args.output.open('xb') as target:target.write(data)
    args.output.chmod(0o600)
    print(json.dumps(manifest,sort_keys=True,indent=2))


if __name__=='__main__':main()
