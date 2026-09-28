"""Rebuild the small, synthetic XLSX interoperability fixture with openpyxl 3.1.5.

Tests read the checked-in workbook and do not depend on Python at test time.
No real customer data is included.
"""
from pathlib import Path
from datetime import datetime
from openpyxl import Workbook

book = Workbook()
sheet = book.active
sheet.title = "线索"
sheet.append(["来源记录", "姓名", "电话", "需求"])
sheet.append(["0001", "测试客户甲", "+8613800138000", "确认委托需求"])
for column, value in enumerate(["0002", "测试客户乙", "", "核对联系时间"], 1):
    sheet.cell(8, column, value)
for row in sheet:
    for cell in row:
        cell.number_format = "@"
book.properties.creator = "R2 test fixture"
book.properties.created = datetime(2026, 9, 14)
book.properties.modified = datetime(2026, 9, 14)
book.save(Path(__file__).with_name("lead-intake-openpyxl.xlsx"))
