import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


class CliTests(unittest.TestCase):
    def test_describe_can_build_exact_native_archive_through_same_entry(self):
        m=self.module()
        args=m.parser().parse_args(['--runtime','/private','describe-bundle','--repo','/source','--build-directory','/private/build','--oidc-settings-file','/private/oidc.json'])
        with patch.object(m,'private_json',return_value={'public':'settings'}),patch.object(m.runtime,'load',return_value={'runtimeImage':'sha256:'+'a'*64}),patch.object(m.build,'run',return_value={'native':'proof'}) as build:
            self.assertEqual(m.dispatch(args),{'native':'proof'});build.assert_called_once_with(Path('/source'),Path('/private/build'),'sha256:'+'a'*64,{'public':'settings'})

    def test_partial_business_configuration_resumes_original_install(self):
        m=self.module()
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)/'runtime';cfg={'config':'original'};op=m.journal.begin(root,'initialize',m.config.digest(cfg))
            candidate={'version':2,'schemaVersion':'52-plus-2-r2-v22'}
            m.journal._write(root,root/'identity/bootstrap.json',{'state':'VERIFIED'})
            m.journal._write(root,root/'business/configuration.json',{'policyState':'UNKNOWN'})
            with patch.object(m.release,'_candidate',return_value=candidate),patch.object(m.assembly,'start_admin'),patch.object(m.initialize,'run') as roster,patch.object(m.business_config,'install') as install,patch.object(m,'sessions',return_value={}),patch.object(m.initialize,'finish',return_value={'phase':'COMPLETE'}):
                m.original_initialization(root,{'config':cfg,'bundleDirectory':directory,'descriptor':candidate},Path('/sessions'))
            roster.assert_called_once();install.assert_called_once_with(root,op['operationId'],cfg)

    def module(self):
        path=Path(__file__).resolve().parents[1]/'linux.py'
        self.assertTrue(path.exists(),'Unique Linux operation entry missing')
        spec=importlib.util.spec_from_file_location('linux_cli',path)
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

    def test_secret_values_and_unknown_subcommands_are_not_accepted(self):
        m=self.module()
        for args in [('--password','not-a-secret'),('unknown',),('initialize','--initial-password','not-a-secret')]:
            with self.assertRaises(SystemExit):m.parser().parse_args(['--runtime','/private',*args])

    def test_read_only_status_dispatch_has_no_initialization_or_mutation(self):
        m=self.module()
        with patch.object(m.release,'status',return_value={'phase':'CREATED'}) as status,patch.object(m.journal,'begin') as begin:
            args=m.parser().parse_args(['--runtime','/private','release-status'])
            self.assertEqual(m.dispatch(args),{'phase':'CREATED'})
            status.assert_called_once_with(Path('/private'));begin.assert_not_called()

    def test_resume_uses_the_explicit_original_operation(self):
        m=self.module()
        with patch.object(m.release,'resume',return_value={'phase':'COMPLETE'}) as resume:
            args=m.parser().parse_args(['--runtime','/private','upgrade-resume','--operation-id','a'*32])
            m.dispatch(args);resume.assert_called_once_with(Path('/private'),'a'*32)

    def test_stop_does_not_delete_or_reinitialize_any_resource(self):
        m=self.module()
        with patch.object(m.release,'stop',return_value={'phase':'STOPPED'}) as stop,patch.object(m.runtime,'cleanup_verification') as delete,patch.object(m.journal,'begin') as begin:
            args=m.parser().parse_args(['--runtime','/private','stop']);m.dispatch(args)
            stop.assert_called_once_with(Path('/private'));delete.assert_not_called();begin.assert_not_called()

    def test_invalid_initialization_config_does_not_seal_an_unusable_original_input(self):
        m=self.module()
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)/'runtime';m.journal.begin(root,'initialize','a'*64)
            args=m.parser().parse_args(['--runtime',str(root),'initialize','--config',str(root/'config.json'),
                '--bundle',str(root/'candidate'),'--initial-password-file',str(root/'password.txt')])
            with patch.object(m.config,'load',return_value={'wrong':'configuration'}),patch.object(m.release,'_candidate',return_value={'schemaVersion':'52-plus-2-r2-v22','version':2}):
                with self.assertRaises(RuntimeError):m.dispatch(args)
            self.assertFalse((root/'cli-initialization.json').exists())

