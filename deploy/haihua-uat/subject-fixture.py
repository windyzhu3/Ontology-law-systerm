from pathlib import Path
import sys
from reportlab.pdfgen import canvas
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.lib.pagesizes import A4
code=sys.argv[1];revision=sys.argv[2] if len(sys.argv)>2 else ''
if revision not in ('','v2'):raise RuntimeError('Explicit synthetic second version only')
dest=Path(__file__).resolve().parents[2]/'.superpowers/haihua-uat-runtime/fixtures'/(f'{code}-SYNTHETIC-client-identity'+('-v2.pdf' if revision else '.pdf'))
if dest.exists():raise RuntimeError('preserve existing subject proof')
pdfmetrics.registerFont(TTFont('HHTestChinese','C:/Windows/Fonts/simhei.ttf'))
c=canvas.Canvas(str(dest),pagesize=A4);c.setFont('HHTestChinese',16);c.drawString(35,790,'合成委托主体证明 — 纯测试，不用于实际签约')
c.setFont('HHTestChinese',11)
for i,line in enumerate([code,'准确委托主体名称：'+code+' 合成委托组织','组织类型：合成测试组织；仅用于本次隔离验收','合成标识：HH-TEST-CLIENT-'+code,'对应本次已确认客户资料；若用于合同，须另核对准确批准正文。','主体名称及合成标识由人工对照；与本次相对方为独立主体。','本文件不代表任何真实登记证件、组织或法律效力。']+(['合成第2版：补充核对说明，已引用旧版不得自动替换。'] if revision else [])):c.drawString(35,745-i*34,line)
c.save();print('Explicit synthetic accurate client identity proof created')
