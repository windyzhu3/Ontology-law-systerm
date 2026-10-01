"""Accurate synthetic evidence for the approved reply and intake branches."""
from pathlib import Path
import sys
from reportlab.pdfgen import canvas
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.lib.pagesizes import A4
from reportlab.lib.utils import simpleSplit
cases={'HH-B10-20261001-R2':('sales04','quote-proof','客户要求解释服务范围和付款安排，没有明确接受或拒绝。'),
       'HH-B11-20261001-R2':('sales05','quote-proof','客户明确拒绝本版10000元报价，认为超出预算，愿讨论修订可能。'),
       'HH-B22-20261001-R2':('sales01','authority','双方合成签署人已获本版准确合同的签字与盖章授权。'),
       'HH-B24-20261001-R2':('sales04','authority','双方合成签署人已获本版准确合同的签字与盖章授权。'),
       'HH-B19-20261001-R2':('sales02','authority','双方合成签署人已获本版准确先款合同的签字与盖章授权。')}
code=sys.argv[1]
if code not in cases:raise RuntimeError('Approved branch case required')
actor,kind,statement=cases[code]
dest=Path(__file__).resolve().parents[2]/'.superpowers/haihua-uat-runtime/fixtures'/f'{code}-{kind}-SYNTHETIC-NOT-LEGAL.pdf'
if dest.exists():raise RuntimeError('Preserve existing evidence')
pdfmetrics.registerFont(TTFont('HHTestChinese','C:/Windows/Fonts/simhei.ttf'))
c=canvas.Canvas(str(dest),pagesize=A4);c.setTitle(code+' synthetic '+kind);c.setFont('HHTestChinese',16);c.drawString(35,790,'合成业务证明 — 纯测试，不用于实际签约')
lines=[code,'模拟业务机构：海华律师事务所；责任销售：'+actor,'准确委托方：'+code+' 合成委托组织','事实说明：'+statement]
if kind=='quote-proof':lines+=['模拟当面交付本版主管已批准报价：固定咨询费用人民币10000元。','实际产品中人工选择准确报价版本和材料证据；下载不代替交付。','接收人：Synthetic Client Signer '+str(int(actor[-2:])),'本文件只证明上述模拟交付及原回复；绝不记录或推定客户接受。']
else:lines+=['委托方签署人：Synthetic Client Signer '+str(int(actor[-2:]))+'；律所签署人：Synthetic Firm Signer。','双方分别核对准确批准正文的签字与盖章要求，完整核验后才可归档。','合成印章与签字不代表真实授权或任何法律效力。']
lines+=['测试材料不代表真实委托、客户意思表示、资金或法律效力。']
c.setFont('HHTestChinese',11);y=745
for text in lines:
 for line in simpleSplit(text,'HHTestChinese',11,525):c.drawString(35,y,line);y-=22
 y-=12
c.setFont('HHTestChinese',12);c.drawString(35,55,'纯合成样本 / 不产生真实业务效力');c.save()
print('Approved branch synthetic evidence created; render review required')
