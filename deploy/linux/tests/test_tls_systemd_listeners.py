import os
import socket
import unittest
from ols_linux import tls_systemd


class ListenerTests(unittest.TestCase):
    def test_real_listener_is_owned_and_foreign_or_closed_claim_is_rejected(self):
        self.assertTrue(callable(getattr(tls_systemd,'verify_listeners',None)),'Kernel listener ownership proof missing')
        with socket.socket() as listener:
            listener.bind(('127.0.0.1',0));listener.listen(1)
            expected=[{'address':'127.0.0.1','port':listener.getsockname()[1]}]
            tls_systemd.verify_listeners(expected,[os.getpid()],True)
            with self.assertRaises(RuntimeError):tls_systemd.verify_listeners(expected,[],True)
            with self.assertRaises(RuntimeError):tls_systemd.verify_listeners(expected,[],False)
        tls_systemd.verify_listeners(expected,[],False)

    def test_wait_retries_only_incomplete_startup(self):
        from unittest.mock import patch
        ready={'running':True,'process':{'pid':1},'credentials':{}}
        with patch.object(tls_systemd,'observe',side_effect=[tls_systemd.Starting('pending'),ready]),patch.object(tls_systemd.time,'sleep'):
            self.assertEqual(tls_systemd.await_started({}),ready)
        with patch.object(tls_systemd,'observe',side_effect=RuntimeError('foreign listener')),patch.object(tls_systemd.time,'sleep') as sleep:
            with self.assertRaisesRegex(RuntimeError,'foreign'):tls_systemd.await_started({})
            sleep.assert_not_called()
        with patch.object(tls_systemd,'observe',side_effect=tls_systemd.Starting('pending')):
            with self.assertRaisesRegex(RuntimeError,'timed out'):tls_systemd.await_started({},timeout=0)
