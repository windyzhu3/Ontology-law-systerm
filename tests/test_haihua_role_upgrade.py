import importlib.util,sys,tempfile,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'deploy/haihua-uat'))
spec=importlib.util.spec_from_file_location('role_upgrade',ROOT/'deploy/haihua-uat/upgrade-configurable-roles.py')
upgrade=importlib.util.module_from_spec(spec);spec.loader.exec_module(upgrade)
class RoleUpgradeArtifactTest(unittest.TestCase):
 def test_tree_digest_binds_contents_names_and_nested_assets(self):
  with tempfile.TemporaryDirectory() as folder:
   tree=Path(folder);(tree/'index.html').write_text('verified');(tree/'assets').mkdir();asset=tree/'assets/app.js';asset.write_text('one')
   original=upgrade.tree_sha(tree);self.assertEqual(original,upgrade.tree_sha(tree))
   asset.write_text('two');self.assertNotEqual(original,upgrade.tree_sha(tree))
   asset.write_text('one');asset.rename(tree/'assets/other.js');self.assertNotEqual(original,upgrade.tree_sha(tree))
 def test_empty_or_incomplete_build_is_refused(self):
  with tempfile.TemporaryDirectory() as folder:
   with self.assertRaises(RuntimeError):upgrade.tree_sha(Path(folder))
if __name__=='__main__':unittest.main()
