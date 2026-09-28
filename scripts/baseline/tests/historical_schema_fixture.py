"""Render exact historical fixtures without changing checked-in generated artifacts."""
import sys
import tempfile
from pathlib import Path
from unittest.mock import patch
from contextlib import contextmanager


def install_historical_schema_fixture(test_class, namespace, through):
    fixture=historical_schema_fixture(through)
    target=fixture.__enter__()
    test_class.addClassCleanup(fixture.__exit__,None,None,None)
    original=namespace['ROOT']
    test_class.addClassCleanup(namespace.__setitem__,'ROOT',original)
    namespace['ROOT']=target


@contextmanager
def historical_schema_fixture(through):
    source=Path(__file__).resolve().parents[3]/'database/schema-contract-52-plus-2'
    sys.path.insert(0,str(source))
    try:
        from contract import schema_contract as contract
        from contract.render import generate_all
    finally:
        sys.path.pop(0)
    evolutions=tuple(e for e in contract.EVOLUTIONS if e.version<=through)
    schemas=contract.BASE_SCHEMAS
    for evolution in evolutions:
        schemas=evolution.apply(schemas)
    with tempfile.TemporaryDirectory() as temporary:
        with patch.object(contract,'SCHEMAS',schemas),patch.object(contract,'EVOLUTIONS',evolutions),patch.object(contract,'CONTRACT_VERSION',evolutions[-1].contract_version):
            generate_all(Path(temporary))
        yield Path(temporary)
