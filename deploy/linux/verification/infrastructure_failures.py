"""Occupied loopback ports fail without taking over the existing listener."""
from pathlib import Path
import tempfile
from ols_linux import config,journal,runtime


def verify(root: Path,image: str):
    resources=runtime.load(root)
    if not resources.get('verification'):raise RuntimeError('Infrastructure probe is verification-only')
    name=resources['name']+'-port-holder'
    if runtime.inspect('container',name):raise RuntimeError('Verification name already occupied')
    resources['containers']['portHolder']=name;runtime.save(root,resources)
    runtime.run(['docker','run','-d','--name',name,'--label','ols.instance='+resources['instanceId'],
        '-p','127.0.0.1::8444','--entrypoint','node',image,'-e',"require('net').createServer().listen(8444,'0.0.0.0')"])
    actual=runtime.owned(root,'container',name);port=int(actual['NetworkSettings']['Ports']['8444/tcp'][0]['HostPort'])
    failed=Path(tempfile.mkdtemp(prefix='ols-port-refusal-'))
    journal.begin(failed,'initialize',config.digest({'probe':'port-refusal','parent':resources['instanceId']}))
    try:
        try:runtime.prepare(failed,{'name':resources['name']+'-busy','repo':resources['repo'],'ports':{'businessDb':port}})
        except RuntimeError:pass
        else:raise AssertionError('Occupied port adopted')
        assert runtime.owned(root,'container',name)['State']['Running']
        pending=runtime.load(failed);pending['verification']=True;runtime.save(failed,pending)
        assert journal.current(failed)['phase']=='PREPARING'
        runtime.cleanup_verification(failed)
    except Exception:
        raise RuntimeError('Port probe failed; original verification resources retained at '+str(failed))
    return {'status':'PASS','occupiedPortRefused':True,'existingListenerPreserved':True}
