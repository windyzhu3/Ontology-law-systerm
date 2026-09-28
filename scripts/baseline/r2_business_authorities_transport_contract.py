from copy import deepcopy
ADDITIONS=['CONTRACT_TERMINATION_REVIEW','PAYMENT_SUBMIT','PAYMENT_CONFIRM','TRANSFER_SUBMIT','TRANSFER_REVIEW','TRANSFER_ACCEPT','MATTER_CLASSIFY','MATTER_RECEIVE']
def business_authorities_projection(document):
 try:
  from scripts.baseline.r2_management_transport_contract import management_projection
 except ModuleNotFoundError:
  from r2_management_transport_contract import management_projection
 result=management_projection(document);schemas=result['components']['schemas']
 arrays=[schemas['GrantableAuthorityCodeV1']['enum'],schemas['AuthorityGrantV1']['properties']['authorityCode']['enum']]
 if not any(code in values for values in arrays for code in ADDITIONS):return result
 for values in arrays:
  if values[-len(ADDITIONS):]!=ADDITIONS or len(values)!=len(set(values)) or not all(code in values for code in ['CONTRACT_READ','CONTRACT_SIGNATURE_VERIFY','CONTRACT_EXECUTION_VERIFY']):raise ValueError('Exact R2 business management authorities required')
  del values[-len(ADDITIONS):]
 return result
