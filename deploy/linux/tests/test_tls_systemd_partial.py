"""A partial bootstrap can only be amended before any fixture preparation."""
import base64,hashlib,json,os
from unittest.mock import patch
from pathlib import Path
import sys,tempfile,unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'verification'))

class PartialTests(unittest.TestCase):
    def test_original_receipts_and_empty_partial_tree_are_required(self):
        import systemd_partial_resume as resume
        with tempfile.TemporaryDirectory() as directory:
            base=Path(directory)
            original_stat=Path.stat
            def owned(path,*args,**kwargs):
                info=original_stat(path,*args,**kwargs)
                if path==base or base in path.parents:
                    values=list(info);values[4:6]=[0,0];return os.stat_result(values)
                return info
            owner_patch=patch.object(Path,'stat',owned);owner_patch.start();self.addCleanup(owner_patch.stop)
            for name in ('package','inputs','evidence','state'):(base/name).mkdir()
            files={}
            changes={}
            for name in resume.ALLOWED:
                target=base/'package'/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(b'old')
                files[name]=hashlib.sha256(b'old').hexdigest()
                changes[name]={'sha256':hashlib.sha256(b'new').hexdigest(),'data':base64.b64encode(b'new').decode()}
            for index in range(41):
                name=f'ols_linux/unchanged{index}.py';target=base/'package'/name;target.write_bytes(b'old');files[name]=hashlib.sha256(b'old').hexdigest()
            receipt={'archiveSha256':resume.ORIGINAL_ARCHIVE,'files':files}
            inputs={}
            for name,value in [('package-receipt.json',receipt),('operator.json',{}),('resource-admission.json',{})]:
                path=base/'inputs'/name;path.write_text(json.dumps(value));path.chmod(0o600);inputs[name]=hashlib.sha256(path.read_bytes()).hexdigest()
            envelope={'originalInputs':inputs,'replacementFiles':changes}
            for path in base.rglob('*'):
                path.chmod(0o755 if path.is_dir() else 0o600 if path.parent==base/'inputs' else 0o644)
            decoded=resume.validate_partial(base,envelope)
            self.assertEqual(set(decoded),set(resume.ALLOWED))
            changed=base/'package'/sorted(resume.ALLOWED)[1];changed.chmod(0o600)
            with self.assertRaisesRegex(RuntimeError,'metadata'):resume.validate_partial(base,envelope)
            self.assertEqual(list((base/'evidence').iterdir()),[])
            self.assertEqual(changed.read_bytes(),b'old')
            changed.chmod(0o644)
            (base/'materials').mkdir()
            with self.assertRaisesRegex(RuntimeError,'partial'):resume.validate_partial(base,envelope)
            (base/'materials').rmdir()
            (base/'evidence/foreign').write_text('unknown')
            with self.assertRaises(RuntimeError):resume.validate_partial(base,envelope)
            (base/'evidence/foreign').unlink()
            (base/'inputs/operator.json').write_text('changed')
            with self.assertRaisesRegex(RuntimeError,'receipt'):resume.validate_partial(base,envelope)
