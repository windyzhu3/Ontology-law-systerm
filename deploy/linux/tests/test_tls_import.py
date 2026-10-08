import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from ols_linux import journal,runtime,tls_import,tls_rotation,tls_generation
from tls_fixtures import materials,instance,inputs


class TlsImportTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory();cls.fixture=materials(Path(cls.temp.name)/'source')
    @classmethod
    def tearDownClass(cls):cls.temp.cleanup()
    def setUp(self):
        self.temp2=tempfile.TemporaryDirectory();self.addCleanup(self.temp2.cleanup);self.root=instance(Path(self.temp2.name),self.fixture)
        op=journal.current(self.root);journal.record(self.root,op['operationId'],{'phase':'COMPLETE'})
        self.source=inputs(self.fixture)
    def test_manual_import_needs_no_remote_issuer(self):
        with patch('socket.create_connection',side_effect=AssertionError('No issuer request')):
            self.assertEqual(tls_import.normalize(self.root,self.source,now=self.fixture['now']),self.source)
        self.assertEqual(tls_import.issuance_status(self.root,now=self.fixture['now'])['state'],'MANUAL_REQUIRED')
    def hook(self):
        lineage=Path(self.temp2.name)/'lineage';lineage.mkdir(mode=0o700)
        archive=Path(self.temp2.name)/'archive';archive.mkdir(mode=0o700)
        client=Path(self.temp2.name)/'client';runtime.private_file(client,b'fixture-client')
        row={'providerId':'fixture','lineage':str(lineage),'archive':str(archive),'clientPath':str(client),'clientSha256':hashlib.sha256(client.read_bytes()).hexdigest(),'evidenceDigest':'a'*64,'origins':self.source['origins'],'quotaState':'AVAILABLE','ipIssuanceVerified':True,'maintenanceAuthorized':True,'validUntil':self.fixture['now']+86400}
        for stem in ['cert','privkey','chain']:
            runtime.private_file(archive/(stem+'1.pem'),b'fixture');(lineage/(stem+'.pem')).symlink_to(archive/(stem+'1.pem'))
        source=dict(self.source,certificate=str(lineage/'cert.pem'),privateKey=str(lineage/'privkey.pem'),intermediates=[str(lineage/'chain.pem')],provenance={'kind':'certbot-hook','providerId':'fixture','lineage':str(lineage),'evidenceDigest':'a'*64})
        journal._write(self.root,self.root/'tls-test-issuers.json',{'providers':[row]})
        return source,row
    def test_unverified_ip_acme_and_unknown_quota_are_blocked(self):
        source,row=self.hook()
        for changes in [{'quotaState':'UNKNOWN'},{'ipIssuanceVerified':False},{'maintenanceAuthorized':False}]:
            journal._write(self.root,self.root/'tls-test-issuers.json',{'providers':[dict(row,**changes)]})
            with self.assertRaisesRegex(RuntimeError,'ISSUANCE_BLOCKED'):tls_import.normalize(self.root,source,now=self.fixture['now'])
        runtime.save(self.root,dict(runtime.load(self.root),verification=False))
        with self.assertRaisesRegex(RuntimeError,'MANUAL_REQUIRED'):tls_import.normalize(self.root,source,now=self.fixture['now'])
    def test_lineage_change_during_snapshot_refused(self):
        source,row=self.hook();original=tls_import.tls_material.read_private;changed=[]
        def read(path):
            result=original(path)
            if not changed:
                changed.append(True);link=Path(source['certificate']);link.unlink();runtime.private_file(Path(row['archive'])/'cert2.pem',b'different');link.symlink_to(Path(row['archive'])/'cert2.pem')
            return result
        with patch.object(tls_import.tls_material,'read_private',side_effect=read):
            with self.assertRaisesRegex(RuntimeError,'Lineage changed'):tls_import.normalize(self.root,source,now=self.fixture['now'])
        self.assertFalse((self.root/'tls/imports').exists())
    def test_duplicate_delivery_does_not_create_operation(self):
        gate={'operating_mode':'ACTIVE'}
        journal._write(self.root,self.root/'current-release.json',{'descriptor':{}});journal._write(self.root,self.root/'launch.json',{})
        request={'materials':self.source,'proxies':{'version':1,'services':[]},'probeTargets':[]}
        with patch.object(runtime,'validate_tls'),patch.object(tls_rotation.database,'observe',return_value={'gate':gate}),patch.object(tls_rotation.release,'_installed'),patch.object(tls_rotation.tls_proxy,'validate_registration'),patch.object(tls_rotation,'resume',side_effect=lambda root,opid,**kw:{'operationId':opid}):
            result=tls_rotation.begin(self.root,request,now=self.fixture['now'])
        opid=result['operationId'];data=journal._read(self.root,tls_rotation._path(self.root,opid));journal.record(self.root,opid,{'phase':'COMPLETE','outcome':'ROTATED'})
        selected={'version':1,'generationId':'g','operationId':opid,'candidate':data['candidate']}
        before={p.relative_to(self.root).as_posix():p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
        with patch.object(tls_generation,'resolve',return_value=selected),patch.object(tls_rotation.tls_probe,'collect',return_value={'status':'PASS'}),patch.object(journal,'begin',side_effect=AssertionError('Duplicate operation')),patch.object(tls_rotation.database,'observe',side_effect=AssertionError('Duplicate database transition')):
            duplicate=tls_rotation.begin(self.root,request,now=self.fixture['now'])
        self.assertEqual(duplicate['operationId'],opid);self.assertEqual(duplicate['outcome'],'UNCHANGED')
        self.assertEqual(before,{p.relative_to(self.root).as_posix():p.read_bytes() for p in self.root.rglob('*') if p.is_file()})
    def test_new_candidate_cannot_replace_pending_rotation(self):
        op=journal.begin(self.root,'rotate-public-tls','f'*64)
        with patch.object(tls_import,'normalize') as normalize:
            with self.assertRaises(RuntimeError):tls_rotation.begin(self.root,{'materials':self.source,'proxies':{},'probeTargets':[]},now=self.fixture['now'])
            normalize.assert_not_called()
        self.assertEqual(journal.current(self.root)['operationId'],op['operationId'])
    def test_repeated_hook_snapshot_has_no_new_files(self):
        from ols_linux.bundle import inventory
        source,row=self.hook()
        first=tls_import.normalize(self.root,source,now=self.fixture['now']);before=inventory(self.root)
        second=tls_import.normalize(self.root,source,now=self.fixture['now'])
        self.assertEqual(first,second);self.assertEqual(inventory(self.root),before)
