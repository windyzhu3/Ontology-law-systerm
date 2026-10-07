"""New resources only; a run ID is never reused as an empty environment."""
from pathlib import Path
import re
import sys
import tempfile
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from ols_linux import config,journal,runtime

REPO=Path(__file__).resolve().parents[3]


def create(run_id: str) -> Path:
    if not re.fullmatch('[a-z0-9][a-z0-9-]{0,38}',run_id):raise ValueError('Safe unique run ID required')
    root=Path(tempfile.mkdtemp(prefix='ols-'+run_id+'-'))
    op=journal.begin(root,'initialize',config.digest({'runId':run_id}))
    resources=runtime.prepare(root,{'name':'ols-'+run_id,'repo':str(REPO)})
    resources['verification']=True;runtime.save(root,resources)
    return root
