"""Project only the complete approved U personal-waiting read contract."""
from copy import deepcopy
import hashlib
import json
from pathlib import Path

PATHS = ['/api/v1/workbench/waiting', '/api/v1/workbench/waiting/{taskId}']
SCHEMAS = ['PersonalWaitingDetailV1', 'PersonalWaitingPageV1', 'PersonalWaitingRecordV1', 'PersonalWaitingRowV1']
PARAMETERS = ['OnBehalfAppointmentSelection']
PIN = '12d8c89a282520ecc72016ab53aefdd6bb2f0ff717ef2723ca076f8453db59ab'
PREVIOUS_PIN = 'd7f34b97b4a8058d63150dd5191dad9b5b1390db2f995e9a3d4e4db2c38a73ca'


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode()).hexdigest()


def personal_waiting_projection(document):
    schemas = document['components']['schemas']
    if not any(name in document['paths'] for name in PATHS) and not any(name in schemas for name in SCHEMAS):
        return deepcopy(document)
    fragment = {
        'paths': {name: document['paths'].get(name) for name in PATHS},
        'schemas': {name: schemas.get(name) for name in SCHEMAS},
        'parameters': {name: document['components']['parameters'].get(name) for name in PARAMETERS},
    }
    if digest(fragment) != PIN:
        raise ValueError('PERSONAL_WAITING_V1: exact approved read delta required')
    previous = json.loads(Path(__file__).with_name('personal_waiting_previous_parameters.json').read_text(encoding='utf-8'))
    if digest(previous) != PREVIOUS_PIN:
        raise ValueError('PERSONAL_WAITING_V1: frozen prior selector changed')
    result = deepcopy(document)
    for name in PATHS:
        del result['paths'][name]
    for name in SCHEMAS:
        del result['components']['schemas'][name]
    result['components']['parameters'].update(previous)
    return result
