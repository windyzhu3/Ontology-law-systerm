"""Create clearly synthetic evidence documents for approved business simulation."""
import sys
from pathlib import Path
from datetime import datetime
from reportlab.pdfgen import canvas
from reportlab.lib.pagesizes import A4
from reportlab.lib.utils import simpleSplit
ROOT=Path(__file__).resolve().parents[2]
dest=ROOT/'.superpowers/haihua-uat-runtime/fixtures';dest.mkdir(exist_ok=True)
for i in range(1,6):
 code=f'HH-G{i:02d}-20261001'+('-R2' if '--round2' in sys.argv else '')
 for kind in ['quote-proof','authority']:
  target=dest/f'{code}-{kind}-SYNTHETIC-NOT-LEGAL.pdf'
  if target.exists():raise RuntimeError('preserve existing fixture')
  c=canvas.Canvas(str(target),pagesize=A4);w,h=A4
  c.setTitle(code+' '+kind+' synthetic test fixture');c.setFont('Helvetica-Bold',16)
  c.drawString(35,h-45,'SYNTHETIC TEST SAMPLE - NOT FOR SIGNING')
  c.setFont('Helvetica',11);y=h-85
  texts=[f'Case: {code}',f'Fixture kind: {kind}','Organization: Haihua Law Firm - isolated MVP acceptance test',f'Sales actor: sales{i:02d}',f'Prepared: {datetime.now().isoformat(timespec="seconds")}',
   'This document records a simulated business scenario only. No real client consent, real signature, real bank transfer or legally valid authority is represented.']
  if kind=='quote-proof':texts+=['Simulated quote: fixed consulting fee CNY 10,000.00; exact current approved quote version selected in the product.',
   'Simulated hand delivery to an authorized client representative, followed by an explicit acceptance of the approved quote and stated payment terms.',
   'Client representative: Synthetic Client Signer '+str(i), 'Recipient organization is the independently confirmed party for this case.']
  else:texts+=['Simulated authority: client and firm signers are expressly authorized for the approved exact contract version in this case.',
   'Client signer: Synthetic Client Signer '+str(i),'Firm signer: Synthetic Firm Signer; synthetic seals only.',
   'Identity and authority checks in this run validate the workflow, not legal validity of this fixture.']
  for t in texts:
   for line in simpleSplit(t,'Helvetica',11,w-70):c.drawString(35,y,line);y-=17
   y-=10
  c.setFont('Helvetica-Bold',12);c.drawString(35,55,'TEST ONLY / NO REAL BUSINESS EFFECT');c.save()
print('10 clearly marked synthetic documents created; render review required')
