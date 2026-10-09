"""Local material delivery. No issuer requests, credentials or purchased quota."""
import json
import os
from pathlib import Path
import re
import hashlib
from .config import digest
from . import journal,runtime,tls_material


def _issuers(root):
    rows=json.loads((Path(__file__).parents[1]/'config/public-tls-issuers.json').read_text())['providers']
    fixture=root/'tls-test-issuers.json'
    if runtime.load(root).get('verification') is True and fixture.exists():rows+=journal._read(root,fixture)['providers']
    return rows


def issuance_status(root: Path,*,now: int) -> dict:
    return {'state':'MANUAL_REQUIRED','reasonCode':'NO_PRODUCTION_IP_ISSUER_ADMITTED'}


def normalize(root: Path,source: dict,*,now: int) -> dict:
    if not isinstance(source,dict) or set(source)!={'certificate','privateKey','intermediates','approvedAnchors','origins','provenance'}:raise RuntimeError('Exact TLS source fields required')
    provenance=source['provenance']
    if not isinstance(provenance,dict):raise RuntimeError('Explicit material provenance required')
    if provenance=={'kind':'manual'}:return dict(source)
    if set(provenance)!={'kind','providerId','lineage','evidenceDigest'} or provenance['kind']!='certbot-hook':raise RuntimeError('MANUAL_REQUIRED: unverified issuer')
    matches=[row for row in _issuers(root) if row['providerId']==provenance['providerId'] and row['lineage']==provenance['lineage'] and row['evidenceDigest']==provenance['evidenceDigest']]
    if len(matches)!=1:raise RuntimeError('MANUAL_REQUIRED: IP issuer has not been admitted')
    row=matches[0]
    if row.get('quotaState')!='AVAILABLE' or not row.get('ipIssuanceVerified') or not row.get('maintenanceAuthorized') or row.get('validUntil',0)<=now or row.get('origins')!=source['origins']:
        raise RuntimeError('ISSUANCE_BLOCKED: admission or quota evidence unavailable')
    if not re.fullmatch('[a-f0-9]{64}',row.get('clientSha256','')):raise RuntimeError('ISSUANCE_BLOCKED: reviewed client unavailable')
    from .bundle import sha
    client=Path(row['clientPath'])
    if client.resolve()!=client.absolute() or sha(client)!=row['clientSha256']:raise RuntimeError('ISSUANCE_BLOCKED: reviewed client differs')
    lineage=Path(row['lineage']);archive=Path(row['archive'])
    if not lineage.is_absolute() or not archive.is_absolute() or lineage.resolve()!=lineage or archive.resolve()!=archive:raise RuntimeError('Registered issuer directories differ')
    links=[lineage/name for name in ['cert.pem','privkey.pem','chain.pem']]
    if [source['certificate'],source['privateKey'],source['intermediates']]!=[str(links[0]),str(links[1]),[str(links[2])]]:raise RuntimeError('Exact registered lineage required')
    captured=[];numbers=[]
    for link,stem in zip(links,['cert','privkey','chain']):
        if not link.is_symlink():raise RuntimeError('Registered Certbot archive link required')
        target=link.resolve();match=re.fullmatch(stem+r'(\d+)\.pem',target.name)
        if target.parent!=archive or not match:raise RuntimeError('Certificate archive outside registered lineage')
        captured.append((link,os.readlink(link),target,tls_material.read_private(target)));numbers.append(match[1])
    if len(set(numbers))!=1 or any(os.readlink(link)!=text or link.resolve()!=target for link,text,target,data in captured):raise RuntimeError('Lineage changed during snapshot')
    directory=root/'tls/imports'/digest({'source':source,'files':{link.name:hashlib.sha256(data).hexdigest() for link,text,target,data in captured}})
    if directory.resolve()!=directory.absolute():raise RuntimeError('Unsafe issuer snapshot directory')
    if not directory.exists():directory.mkdir(mode=0o700,parents=True)
    for link,text,target,data in captured:
        output=directory/link.name
        if output.exists():
            if tls_material.read_private(output)!=data:raise RuntimeError('Issuer snapshot differs')
        else:runtime.private_file(output,data)
    return dict(source,certificate=str(directory/'cert.pem'),privateKey=str(directory/'privkey.pem'),intermediates=[str(directory/'chain.pem')])
