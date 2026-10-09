"""Typed systemd 252 replies retain command order and credential sources."""
import json
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from ols_linux import runtime,tls_systemd as sd

EXEC=('ExecStart','ExecStartPre','ExecReload','ExecStartPost','ExecStop','ExecStopPost','ExecCondition')
COMPLEX=(*EXEC,'LoadCredential','EnvironmentFiles','BindPaths','BindReadOnlyPaths')

def command(argv):return [argv[0],argv,False,0,0,0,0,0,0,0]

class BusTests(unittest.TestCase):
    def setUp(self):
        self.values={key:{'type':'a(sasbttttuii)','data':[]} for key in EXEC}
        self.values.update(LoadCredential={'type':'a(ss)','data':[['server.key','/synthetic/server.key'],['issuer.pem','/synthetic/issuer.pem']]},EnvironmentFiles={'type':'a(sb)','data':[]},BindPaths={'type':'a(ssbt)','data':[]},BindReadOnlyPaths={'type':'a(ssbt)','data':[]})
        self.values['ExecReload']['data']=[command(['/usr/sbin/nginx','-t']),command(['/bin/kill','-HUP','$MAINPID'])]
        self.rows=dict.fromkeys((*sd.UNIT_PROPERTIES,*sd.STATE_PROPERTIES),'')
        self.rows['MainPID']='10';self.rows['ControlPID']='0'
    def snapshot(self):
        def run(argv,*args,**kwargs):
            if argv[0]=='systemctl':
                return SimpleNamespace(stdout=''.join(k+'='+v+'\n' for k,v in self.rows.items() if k not in COMPLEX).encode())
            self.assertEqual(argv[0],'busctl')
            self.assertIn('/org/freedesktop/systemd1/unit/fixture_2eservice',argv)
            return SimpleNamespace(stdout=('\n'.join(json.dumps(self.values[key]) for key in COMPLEX)+'\n').encode())
        with patch.object(runtime,'run',side_effect=run):return sd.unit_snapshot('fixture.service',dict.fromkeys(sd.UNIT_PROPERTIES,''))
    def test_ordered_reload_empty_exec_and_typed_credentials(self):
        result=self.snapshot()['properties']
        self.assertEqual(result['ExecReload'],[['/usr/sbin/nginx','-t'],['/bin/kill','-HUP','$MAINPID']])
        self.assertEqual(result['ExecStartPre'],[])
        self.assertEqual(result['LoadCredential'],'issuer.pem:/synthetic/issuer.pem server.key:/synthetic/server.key')
    def test_argument_boundaries_and_sequence_change_identity(self):
        first=self.snapshot()['properties']
        self.values['ExecReload']['data'].reverse()
        self.assertNotEqual(first,self.snapshot()['properties'])
        self.values['ExecStart']['data']=[command(['/bin/test','a b'])];first=self.snapshot()['properties']
        self.values['ExecStart']['data']=[command(['/bin/test','a','b'])]
        self.assertNotEqual(first,self.snapshot()['properties'])
    def test_unprintable_wrong_types_and_duplicate_credential_ids_refused(self):
        for bad in ({'type':'s','data':'[unprintable]'},{'type':'a(ss)','data':[['key','/a'],['key','/b']]},{'type':'a(ss)','data':[['key','relative']]}):
            self.values['LoadCredential']=bad
            with self.subTest(bad=bad),self.assertRaises(RuntimeError):self.snapshot()
    def test_ignored_or_indirect_commands_refused(self):
        for row in (['/bin/false',['/bin/true'],False,0,0,0,0,0,0,0],['/bin/true',['/bin/true'],True,0,0,0,0,0,0,0]):
            self.values['ExecStart']['data']=[row]
            with self.assertRaises(RuntimeError):self.snapshot()
    def test_missing_scalar_cannot_be_fabricated_empty(self):
        del self.rows['FragmentPath']
        with self.assertRaises(RuntimeError):self.snapshot()

    def test_exec_timestamps_do_not_change_loaded_identity(self):
        first=self.snapshot()
        for row in self.values['ExecReload']['data']:row[3:]=[1,2,3,4,5,6,7]
        self.assertEqual(first,self.snapshot())
