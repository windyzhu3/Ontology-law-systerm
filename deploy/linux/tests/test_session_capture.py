import importlib
import unittest


class CaptureTests(unittest.TestCase):
    def module(self):
        try:return importlib.import_module('ols_linux.session_capture')
        except ImportError:self.fail('Manual original HUMAN PKCE session capture absent')

    def test_callback_requires_exact_origin_path_state_and_single_code(self):
        module=self.module();origin='https://example.invalid'
        good=origin+'/auth/callback?state=original&code=actual'
        self.assertEqual(module.callback_code(good,origin,'original'),'actual')
        for url in [good.replace(origin,'https://foreign.invalid'),good.replace('/auth/callback','/other'),
                    good.replace('original','foreign'),good+'&code=second',good+'&state=other',good+'&error=denied',good+'#fragment']:
            with self.assertRaises(RuntimeError):module.callback_code(url,origin,'original')

    def test_actual_identity_tokens_must_match_both_original_subject_and_nonce(self):
        module=self.module();import base64,json
        token=lambda value:'x.'+base64.urlsafe_b64encode(json.dumps(value).encode()).decode().rstrip('=')+'.x'
        plan={'issuer':'https://example.invalid/realms/original','spaClient':'client','audience':'api','subjects':{'dingqiming':'original-subject'}}
        access={'iss':plan['issuer'],'sub':'original-subject','aud':['api'],'exp':9999999999}
        idtoken={'iss':plan['issuer'],'sub':'original-subject','aud':'client','nonce':'original-nonce'}
        value={'access_token':token(access),'refresh_token':'private-fixture','id_token':token(idtoken)}
        module.validate_tokens(value,plan,'dingqiming','original-nonce')
        from ols_linux import admin,runtime
        from unittest.mock import patch
        from pathlib import Path
        import tempfile
        session=module.session_value(value,plan,'dingqiming','original-nonce')
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'private-session.json';runtime.private_file(path,json.dumps(session).encode())
            with patch.object(admin.journal,'_read',return_value=plan):
                self.assertEqual(admin.session(Path(directory),path,'dingqiming')['subject'],'original-subject')
        for changed in [dict(idtoken,nonce='other'),dict(idtoken,sub='other'),dict(idtoken,aud='other'),dict(idtoken,iss='https://foreign.invalid')]:
            with self.assertRaises(RuntimeError):module.validate_tokens(dict(value,id_token=token(changed)),plan,'dingqiming','original-nonce')

