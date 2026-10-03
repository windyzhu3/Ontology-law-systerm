"""Project the HH010 existing payment rejection's missing receipt registration.

Keep historical contract pins unchanged; do not normalize any other enum value.
"""
from copy import deepcopy


def payment_receipt_projection(document):
    result = deepcopy(document)
    schemas = result['components']['schemas']
    values = schemas.get('TerminalRejectionCode', {}).get('enum', [])
    code = 'PAYMENT_ALREADY_RECORDED'
    if code in values:
        existing = schemas.get('ContractProblemV1', {}).get('properties', {}).get('code', {}).get('enum', [])
        if values.count(code) != 1 or code not in existing:
            raise ValueError('HH010 requires exactly one existing payment rejection registration')
        values.remove(code)
    return result
