"""Exact additive R25_BUSINESS_OVERVIEW_V1 projection; earlier contracts remain frozen."""
from copy import deepcopy
import hashlib,json
PATHS = {'/api/v1/business-overview': '712e8f57dc369f85757e0a4568e20fce70524011b5c9eefe8b46389ed09cb93d', '/api/v1/business-overview/{metric}': '1c41f35c35ab47fcb622de7f2919167c4b9c3e4f77f9a75766e7f4d30bbfb463'}
SCHEMAS = {'BusinessOverviewMetricKeyV1': '0978ed9c97fd50e4dd107d9c6bf0873a8a0eba404b811162e57e14e02a4c95f8', 'BusinessOverviewMetricV1': '0148df6776470a61e4c6a87a70cbdee13cf476a42a5f2b74bcfbb6a840b31884', 'BusinessOverviewSummaryV1': '751f4079494f925f632af7e39e1e69e3668b4644b5ccfb27e7695af59699f4bd', 'BusinessOverviewRowV1': '0701b8a7a766f6bfc787ea5530c4e3d37d053848714fd594358c2bcb9b823f2c', 'BusinessOverviewPageV1': 'dab84b4cd9edc03b0151d59e44f06c3373b3a8183fa5acb0bc0e07eacd5b5d0e'}
ENTRY = {'type': 'boolean', 'description': 'Direct HUMAN overview entry hint; each metric retains its own exact read authority and source audit.'}
def digest(v):return hashlib.sha256(json.dumps(v,sort_keys=True,separators=(',',':'),ensure_ascii=True).encode()).hexdigest()
def overview_projection(document):
 try:
  from scripts.baseline.r25_ai_candidate_contract import ai_projection
 except ImportError:
  from r25_ai_candidate_contract import ai_projection
 result=ai_projection(document);paths=result['paths'];schemas=result['components']['schemas'];session=schemas['SessionContextV1']
 present=any(k.startswith('/api/v1/business-overview') for k in paths) or any(k in schemas for k in SCHEMAS) or 'canReadBusinessOverview' in session['properties']
 if not present:return result
 if any(k.startswith('/api/v1/business-overview') and k not in PATHS for k in paths):raise ValueError('Unregistered overview route')
 for section,pins in ((paths,PATHS),(schemas,SCHEMAS)):
  for name,pin in pins.items():
   if digest(section.pop(name,None))!=pin:raise ValueError('Exact R25 overview addition required: '+name)
 if session['properties'].pop('canReadBusinessOverview',None)!=ENTRY:raise ValueError('Exact overview entry hint required')
 for i in (0,1,3):
  if session['allOf'][i]['then']['properties'].pop('canReadBusinessOverview',None)!={'const':False}:raise ValueError('Direct HUMAN overview boundary required')
 return result
