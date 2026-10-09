"""Real private journal/config writes with a simulated systemd boundary."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from ols_linux import journal,tls_maintenance,tls_proxy,tls_systemd


class MaintenanceTests(unittest.TestCase):
    def test_response_loss_resumes_original_start_without_a_second_start(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)/'runtime';op=journal.begin(root,'rotate-public-tls','a'*64);oid=op['operationId']
            journal.record(root,oid,{'phase':'ACTIVATING'})
            config=root/'https.conf';config.write_text('original')
            st=config.stat();record={'path':str(config),'sha256':'a'*64,'uid':st.st_uid,'gid':st.st_gid,'mode':st.st_mode&0o777}
            service={'transport':'systemd','role':'nginx','config':str(config),'systemd':{'immutableFiles':[record]}}
            row={'service':service,'before':'original','after':'candidate'}
            value={'service':service,'configuration':'maintenance'}
            journal._write(root,root/'operations'/(oid+'-issuer-maintenance.json'),value)
            original={'running':True,'process':{'pid':10,'startTicks':1},'credentials':{}}
            stopped={'running':False,'process':None,'credentials':{}}
            fresh={'running':True,'process':{'pid':11,'startTicks':2},'credentials':{}}
            with patch.object(tls_maintenance,'prepare',return_value=value),patch.object(tls_proxy,'_state',side_effect=[original,stopped]),patch.object(tls_proxy,'_action',side_effect=[None,RuntimeError('response lost')]):
                with self.assertRaisesRegex(RuntimeError,'response lost'):tls_maintenance.start(root,oid,[row],[],{})
            self.assertEqual(config.read_text(),'maintenance')
            self.assertEqual(config.stat().st_mode&0o777,record['mode'])
            with patch.object(tls_maintenance,'prepare',return_value=value),patch.object(tls_proxy,'_state',return_value=fresh),patch.object(tls_proxy,'_action') as action:
                self.assertEqual(tls_maintenance.start(root,oid,[row],[],{}),value);action.assert_not_called()
            self.assertEqual(journal.current(root)['operationId'],oid)
