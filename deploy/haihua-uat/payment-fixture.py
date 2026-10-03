from pathlib import Path
import sys
from reportlab.pdfgen import canvas
from reportlab.lib.pagesizes import A4
code,amount,reference=sys.argv[1:4]
dest=Path(__file__).resolve().parents[2]/'.superpowers/haihua-uat-runtime/fixtures'/f'{code}-{reference}-SYNTHETIC-payment.pdf'
if dest.exists():raise RuntimeError('preserve existing proof')
c=canvas.Canvas(str(dest),pagesize=A4);c.setFont('Helvetica-Bold',16);c.drawString(35,790,'SYNTHETIC PAYMENT PROOF - NO REAL TRANSFER')
c.setFont('Helvetica',11)
for i,line in enumerate([code,'Simulated attribution: exact current approved contract and confirmed client.','Receiving account: HH_UAT_TEST_ACCOUNT (synthetic Haihua account).','Currency: CNY; simulated received amount: '+amount,'Synthetic transaction reference: '+reference,'Synthetic received date: 2026-10-01','Test fixture only; no real bank statement or funds represented.']):c.drawString(35,745-i*32,line)
c.save();print('Explicit synthetic payment proof created')
