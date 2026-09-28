"""Exact T09 additive transport projection; retain every preceding frozen pin."""
from copy import deepcopy
import hashlib,json
def digest(value):return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(",",":"),ensure_ascii=True).encode()).hexdigest()
PATHS = {'/api/v1/opportunities/{opportunityId}/contracts/generate': '4751abd99b9a6f5de4546a29a7023cd02ced584319a90420a15f0fceb55ba458'}
SCHEMAS = {'ContractGenerationRequestV1': '0f3a7c44658541e253d9ef7248b9cf53e66da14af33325f8320779a848e98169', 'ContractGeneratedDocumentV1': '45861a8f219680e341b955e8d54547cc5472f0a6b2d5a035cdaeefa464742727'}
CHANGED = {'ContractVersionContentV1': '0b7a756a7055d86655b1e4574910112db36072652724288dc98db2569ec2a538', 'ContractDraftContentV1': '7ecd5bded05e46ec69b8d428993f553977d5fa872d3d60469219a8ffb1d0283c'}
PREVIOUS = {'ContractVersionContentV1': {'type': 'object', 'additionalProperties': False, 'required': ['commercial', 'document', 'signing', 'paymentGate'], 'properties': {'commercial': {'$ref': '#/components/schemas/ContractCommercialV1'}, 'document': {'type': 'object', 'additionalProperties': False, 'required': ['evidenceVersionId', 'bodySha256', 'templateVersionId', 'clauseVersionIds'], 'properties': {'evidenceVersionId': {'$ref': '#/components/schemas/Uuid'}, 'bodySha256': {'type': 'string', 'pattern': '^[a-f0-9]{64}$'}, 'templateVersionId': {'$ref': '#/components/schemas/Uuid'}, 'clauseVersionIds': {'type': 'array', 'items': {'$ref': '#/components/schemas/Uuid'}}}}, 'signing': {'type': 'object', 'additionalProperties': False, 'required': ['partySnapshotDigest', 'requirements'], 'properties': {'partySnapshotDigest': {'type': 'string', 'pattern': '^[a-f0-9]{64}$'}, 'requirements': {'type': 'string', 'maxLength': 4000}}}, 'paymentGate': {'type': 'object', 'additionalProperties': False, 'required': ['receiptRequiredBeforeTransfer', 'requiredMinor'], 'properties': {'receiptRequiredBeforeTransfer': {'type': 'boolean'}, 'requiredMinor': {'oneOf': [{'type': 'integer', 'format': 'int64', 'minimum': 1, 'maximum': 9007199254740991}, {'type': 'null'}]}}}}}, 'ContractDraftContentV1': {'type': 'object', 'additionalProperties': False, 'required': ['commercial'], 'properties': {'commercial': {'$ref': '#/components/schemas/ContractCommercialV1'}, 'document': {'type': 'object', 'additionalProperties': False, 'required': ['evidenceVersionId', 'bodySha256', 'templateVersionId', 'clauseVersionIds'], 'properties': {'evidenceVersionId': {'$ref': '#/components/schemas/Uuid'}, 'bodySha256': {'type': 'string', 'pattern': '^[a-f0-9]{64}$'}, 'templateVersionId': {'$ref': '#/components/schemas/Uuid'}, 'clauseVersionIds': {'type': 'array', 'items': {'$ref': '#/components/schemas/Uuid'}}}}, 'signing': {'type': 'object', 'additionalProperties': False, 'required': ['partySnapshotDigest', 'requirements'], 'properties': {'partySnapshotDigest': {'type': 'string', 'pattern': '^[a-f0-9]{64}$'}, 'requirements': {'type': 'string', 'maxLength': 4000}}}, 'paymentGate': {'type': 'object', 'additionalProperties': False, 'required': ['receiptRequiredBeforeTransfer', 'requiredMinor'], 'properties': {'receiptRequiredBeforeTransfer': {'type': 'boolean'}, 'requiredMinor': {'oneOf': [{'type': 'integer', 'format': 'int64', 'minimum': 1, 'maximum': 9007199254740991}, {'type': 'null'}]}}}}}}
def generation_projection(document):
 try:
  from scripts.baseline.r2_manual_signature_transport_contract import signature_projection
 except ModuleNotFoundError:
  from r2_manual_signature_transport_contract import signature_projection
 result=signature_projection(document);paths=result['paths'];schemas=result['components']['schemas']
 if not any(k in paths for k in PATHS) and not any(k in schemas for k in SCHEMAS):return result
 for section,pins in ((paths,PATHS),(schemas,SCHEMAS)):
  for key,pin in pins.items():
   if digest(section.pop(key,None))!=pin:raise ValueError('T09 exact generation contract required: '+key)
 for key,pin in CHANGED.items():
  if digest(schemas[key])!=pin:raise ValueError('T09 exact generation addition required: '+key)
  schemas[key]=deepcopy(PREVIOUS[key])
 return result
