#!/bin/sh
# Verification container: mount one labelled volume at its actual daemon path,
# the checkout at /input (read-only), locked Linux Docker at /input-docker,
# and the local Docker socket. No production resource paths are accepted here.
set -eu
volume_root=$1
run_id=$2
test -d "$volume_root"
cp /input-docker /usr/local/bin/docker
chmod 755 /usr/local/bin/docker
python - <<'PY'
from pathlib import Path
import hashlib,json
lock=json.loads(Path('/input/deploy/linux/verification/toolchain.lock.json').read_text())
assert hashlib.sha256(Path('/usr/local/bin/docker').read_bytes()).hexdigest()==lock['binarySha256']
PY
test "$(docker version --format '{{.Client.Version}}')" = 29.4.3
mkdir -p "$volume_root/tmp"
export TMPDIR="$volume_root/tmp"
export OLS_VERIFICATION_VOLUME="$volume_root"
source_root=$(python - <<'PY'
from pathlib import Path
import os,shutil,tempfile
base=Path(os.environ['OLS_VERIFICATION_VOLUME'])
root=Path(tempfile.mkdtemp(prefix='source-',dir=base))
source=Path('/input')
for name in ['deploy/linux','database/schema-contract-52-plus-2/generated']:
    shutil.copytree(source/name,root/name,ignore=shutil.ignore_patterns('__pycache__','*.pyc'))
for name in ['database/schema-contract-52-plus-2/runtime/toolchain.lock.json','deploy/identity/identity-toolchain.lock.json','deploy/identity/realm-template.json','backend/src/test/resources/db/bootstrap-runtime-logins.sql']:
    (root/name).parent.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(source/name,root/name)
print(root)
PY
)
cd "$source_root"
python -m unittest discover -s deploy/linux/tests -v
if [ "${4:-empty}" = identity ]; then
  python deploy/linux/verification/identity_database.py --run-id "$run_id" --runtime-image "$3"
elif [ "${4:-empty}" = migration ]; then
  python deploy/linux/verification/migration.py --run-id "$run_id"
else
  python deploy/linux/verification/empty_database.py --run-id "$run_id" --runtime-image "$3"
fi
