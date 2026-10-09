"""Real proc-like files prove explicit PSI absence and conservative fallback gates."""
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'verification/systemd_qualification'))
import bootstrap,guard


class ResourceTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.proc=Path(self.tmp.name);(self.proc/'meminfo').write_text('MemAvailable: 2097152 kB\n')
        (self.proc/'vmstat').write_text('pswpin 5\npswpout 7\npgscan_direct 0\nallocstall_normal 0\n')
    def sample(self):
        self.assertTrue(callable(getattr(bootstrap,'resource_sample',None)))
        return bootstrap.resource_sample(self.proc)
    def test_missing_psi_is_explicit_and_fallback_requires_direct_reclaim_counters(self):
        value=self.sample();self.assertIsNone(value['psi']);self.assertEqual(value['pressureMode'],'vmstat-fallback')
        (self.proc/'vmstat').write_text('pswpin 0\npswpout 0\n')
        with self.assertRaisesRegex(RuntimeError,'direct-reclaim'):self.sample()
    def test_available_psi_is_parsed_but_malformed_or_unreadable_is_not_absence(self):
        (self.proc/'pressure').mkdir();file=self.proc/'pressure/memory'
        file.write_text('some avg10=4.00 avg60=1.00 avg300=0.00 total=100\nfull avg10=3.00 avg60=0.00 avg300=0.00 total=1\n')
        self.assertEqual(self.sample()['psi'],3)
        file.write_text('full avg10=nan\n')
        with self.assertRaises(RuntimeError):self.sample()
    def test_fallback_rejects_any_swap_or_direct_reclaim_growth_at_admission(self):
        before=dict(self.sample(),time=1);quiet=dict(before,time=6)
        self.assertFalse(bootstrap.resource_pressure(before,quiet))
        for field in ('swapPages','directReclaim','allocationStalls'):
            with self.subTest(field=field):
                self.assertTrue(bootstrap.resource_pressure(before,dict(quiet,**{field:before[field]+1})))
        with self.assertRaises(RuntimeError):bootstrap.resource_pressure(before,dict(quiet,swapPages=0))
    def test_existing_psi_policy_and_counter_reset_fail_closed(self):
        before=dict(self.sample(),time=1,psi=0,pressureMode='psi');after=dict(before,time=6,psi=2.1)
        self.assertTrue(bootstrap.resource_pressure(before,after))
        with self.assertRaises(RuntimeError):bootstrap.resource_pressure(before,dict(after,psi=None,pressureMode='vmstat-fallback'))
    def test_continuous_fallback_stops_for_sustained_growth_and_immediate_low_memory(self):
        self.assertTrue(callable(getattr(guard,'resource_step',None)))
        previous=dict(self.sample(),time=1);events={'max':0,'oom':0,'oom_kill':0,'high':0}
        streak=0;overload=0
        for index in range(1,3):
            current=dict(previous,time=previous['time']+5,swapPages=previous['swapPages']+1)
            streak,overload=guard.resource_step(previous,current,events,events,streak,overload,100,None)
            previous=current
        with self.assertRaisesRegex(RuntimeError,'pressure'):guard.resource_step(previous,dict(previous,time=16,swapPages=previous['swapPages']+1),events,events,streak,overload,100,None)
        with self.assertRaisesRegex(RuntimeError,'memory'):guard.resource_step(previous,dict(previous,time=16,available=511*1024**2),events,events,0,0,100,None)
        for key in ('max','oom','oom_kill'):
            with self.subTest(key=key),self.assertRaisesRegex(RuntimeError,'cgroup'):guard.resource_step(previous,dict(previous,time=16),dict(events,**{key:1}),events,0,0,100,None)

    def test_cgroup_limits_and_events_are_mandatory_but_psi_absence_is_explicit(self):
        group=self.proc/'cgroup';group.mkdir()
        values={'memory.max':'536870912','memory.high':'268435456','memory.swap.max':'0','pids.max':'128','cpu.max':'50000 100000','memory.events':'low 0\nhigh 0\nmax 0\noom 0\noom_kill 0\n'}
        for name,value in values.items():(group/name).write_text(value)
        self.assertIsNone(guard.cgroup_sample(group)['psi'])
        for name in ('memory.max','memory.high','memory.swap.max','pids.max','cpu.max'):
            (group/name).write_text('max' if name!='cpu.max' else 'max 100000')
            with self.subTest(name=name),self.assertRaises(RuntimeError):guard.cgroup_sample(group)
            (group/name).write_text(values[name])
        (group/'memory.events').write_text('high 0\nmax 0\n')
        with self.assertRaisesRegex(RuntimeError,'events'):guard.cgroup_sample(group)

    def test_pressure_permission_error_is_not_converted_to_zero_or_fallback(self):
        original=Path.read_text
        def read(path,*args,**kwargs):
            if path==self.proc/'pressure/memory':raise PermissionError('denied')
            return original(path,*args,**kwargs)
        with patch.object(Path,'read_text',read),self.assertRaises(PermissionError):self.sample()

    def test_read_only_host_capabilities_fail_closed_for_unsupported_hosts(self):
        import subprocess
        from types import SimpleNamespace
        files={
            '/sys/fs/cgroup/cgroup.controllers':'cpu memory pids',
            '/proc/self/status':'CapEff: ffffffffffffffff\n',
            '/sys/fs/selinux/enforce':'0',
        }
        def read(path,*args,**kwargs):return files[str(path)]
        def command(args,**kwargs):
            return subprocess.CompletedProcess(args,0,stdout=b'systemd 252\n' if args[0]=='systemctl' else b'OpenSSL 3.0\n')
        with patch.object(Path,'read_text',read),patch.object(Path,'exists',return_value=True),patch.object(Path,'is_file',return_value=True),patch.object(bootstrap,'command',command),patch.object(bootstrap.ssl,'HAS_TLSv1_3',True),patch.object(bootstrap.os,'statvfs',return_value=SimpleNamespace(f_bavail=1000000,f_frsize=4096)):
            value=bootstrap.host_capabilities()
            self.assertFalse(value['selinuxEnforcing']);self.assertTrue(value['requiredCapabilitiesPresent'])
            for path,bad,reason in [('/sys/fs/cgroup/cgroup.controllers','cpu pids','controllers'),('/proc/self/status','CapEff: 0\n','capabilities'),('/sys/fs/selinux/enforce','1','SELinux')]:
                old=files[path];files[path]=bad
                with self.subTest(path=path),self.assertRaisesRegex(RuntimeError,reason):bootstrap.host_capabilities()
                files[path]=old
            with patch.object(bootstrap.ssl,'HAS_TLSv1_3',False),self.assertRaisesRegex(RuntimeError,'TLS1.3'):bootstrap.host_capabilities()
