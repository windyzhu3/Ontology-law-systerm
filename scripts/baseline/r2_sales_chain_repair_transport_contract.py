from copy import deepcopy
import json,hashlib

def digest(v):return hashlib.sha256(json.dumps(v,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest()
PATHS={'/internal/v1/sales-chain-repairs/restore-source-request-task': '1d339ef2ad09a6bff04125dad9d5da8ffb45c386734433d35beafee7d5d65edd', '/internal/v1/sales-chain-repairs/repair-superseded-opportunity-task': '46d5e53f79deb8c9e82e31540bae90ebe636e2cf5bc7d5bd64edf4e43f027f9b'}
SCHEMAS={'R2SalesRepairRevisionSelectorV1': '8c9debcf01371b9cc8459ad7088bf9499a1c1748e4505c175996d844f9ef56d9', 'RestoreSourceRequestTaskV1': '1f4fdcc431ba4e7a9d45b66b0237af9fd26604257e2a53143b7d7fe4a5e55644', 'RepairSupersededOpportunityTaskV1': 'e4b46c91c15857dc5b7be85d58f4bb41dd0cee1ee9a335d6be1c27eb89afd317'}

def sales_chain_repair_projection(document):
 try:
  from scripts.baseline.r2_business_authorities_transport_contract import business_authorities_projection
  from scripts.baseline.r2_transfer_transport_contract import transfer_projection
 except ModuleNotFoundError:
  from r2_business_authorities_transport_contract import business_authorities_projection
  from r2_transfer_transport_contract import transfer_projection
 result=business_authorities_projection(transfer_projection(document));paths=result['paths'];schemas=result['components']['schemas']
 if not any(k in paths for k in PATHS) and not any(k in schemas for k in SCHEMAS):return result
 for section,pins in ((paths,PATHS),(schemas,SCHEMAS)):
  for key,pin in pins.items():
   if digest(section.pop(key,None))!=pin:raise ValueError('F08 exact sales repair addition required: '+key)
 return result
