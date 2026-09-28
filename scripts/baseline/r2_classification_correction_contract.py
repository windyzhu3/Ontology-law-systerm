from copy import deepcopy
import hashlib,json
def digest(v):return hashlib.sha256(json.dumps(v,sort_keys=True,separators=(",",":"),ensure_ascii=True).encode()).hexdigest()
PATH="/api/v1/opportunities/{opportunityId}/transfers/classification-context"
SCHEMA="MatterClassificationCorrectionContextV1"
PATH_HASH="3fbaf1aed97b425b13b564c262bc2f633c9dcf7af2890890bb19decbb8b39880"
SCHEMA_HASH="738511162965ca558e577e59efc45049d01a0fdebe4d2416c337a0f8d40c9389"
def correction_projection(document):
 result=deepcopy(document);paths=result['paths'];schemas=result['components']['schemas'];transfer=schemas.get('ContractContextV1',{}).get('properties',{}).get('transfer',{}).get('properties',{})
 if PATH not in paths and SCHEMA not in schemas and 'canCorrectClassification' not in transfer:return result
 if digest(paths.pop(PATH,None))!=PATH_HASH or digest(schemas.pop(SCHEMA,None))!=SCHEMA_HASH or transfer.pop('canCorrectClassification',None)!={'type':'boolean'}:raise ValueError('Exact approved Q1 correction addition required')
 return result
