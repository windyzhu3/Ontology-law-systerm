"""Preserve generated approved body and append explicit synthetic signing evidence."""
from pathlib import Path
from io import BytesIO
import sys,hashlib,json
from pypdf import PdfReader,PdfWriter
from reportlab.pdfgen import canvas
from reportlab.lib.pagesizes import A4
root=Path(__file__).resolve().parents[2]/'.superpowers/haihua-uat-runtime/fixtures'
code=sys.argv[1]
source=root/f'{code}-generated-approved-body.pdf'
output=root/f'{code}-SYNTHETIC-signed-exact-body.pdf'
if output.exists():raise RuntimeError('preserve existing signed fixture')
buffer=BytesIO();c=canvas.Canvas(buffer,pagesize=A4)
c.setFont('Helvetica-Bold',16);c.drawString(35,800,'SYNTHETIC SIGNATURE EVIDENCE - NOT LEGAL')
c.setFont('Helvetica',11)
lines=[code,'Approved body preserved on preceding pages; no commercial or party changes.',
 'This page simulates signatures and seals solely to exercise the MVP workflow.',
 'Client signer: Synthetic Client Signer '+({'HH-B24-20261001-R2':'4','HH-B22-20261001-R2':'1','HH-B19-20261001-R2':'2'}.get(code,code[5:6])),
 'Client signature: [SYNTHETIC CLIENT SIGNATURE]',
 'Client seal: [SYNTHETIC CLIENT ORGANIZATION SEAL]',
 'Firm signer: Synthetic Firm Signer',
 'Firm signature: [SYNTHETIC FIRM SIGNATURE]',
 'Firm seal: [SYNTHETIC HAIHUA FIRM SEAL]',
 'Signing date: 2026-10-01 (synthetic scenario)',
 'Authority proof: same-case separately received synthetic authority document.',
 'No real signature, seal, authority, or legal effect is represented.']
for i,line in enumerate(lines):c.drawString(35,760-i*30,line)
c.save();buffer.seek(0)
writer=PdfWriter();writer.clone_document_from_reader(PdfReader(source));writer.append(PdfReader(buffer));writer.write(output)
(root/f'{code}-signed-fixture-manifest.json').write_text(json.dumps({'source':source.name,'sourceSha256':hashlib.sha256(source.read_bytes()).hexdigest(),'output':output.name,'outputSha256':hashlib.sha256(output.read_bytes()).hexdigest(),'syntheticOnly':True},indent=2))
print('Exact approved body preserved; explicit synthetic signature page appended')
