"""Strict public configuration; identities are resolved only during initialization."""
import hashlib
import json
from pathlib import Path
import re


def digest(value: dict) -> str:
    return hashlib.sha256(canonical(value)).hexdigest()


def canonical(value) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('utf-8')


def _validate(value, schema, root, location='$'):
    if '$ref' in schema:
        target = root
        for key in schema['$ref'].removeprefix('#/').split('/'):
            target = target[key]
        return _validate(value, target, root, location)
    kind = schema.get('type')
    types = {'object': dict, 'array': list, 'string': str, 'integer': int, 'boolean': bool, 'null': type(None)}
    kinds = kind if isinstance(kind, list) else [kind]
    if kind and not any(type(value) is types[k] for k in kinds):
        raise ValueError(f'Invalid configuration type at {location}')
    if 'const' in schema and value != schema['const'] or 'enum' in schema and value not in schema['enum']:
        raise ValueError(f'Invalid configuration value at {location}')
    if isinstance(value, dict):
        properties = schema.get('properties', {})
        if set(schema.get('required', [])) - value.keys() or schema.get('additionalProperties') is False and value.keys() - properties.keys():
            raise ValueError(f'Unknown or missing configuration field at {location}')
        for key, item in value.items():
            if key in properties: _validate(item, properties[key], root, location + '.' + key)
    elif isinstance(value, list):
        if not schema.get('minItems', 0) <= len(value) <= schema.get('maxItems', 10000):
            raise ValueError(f'Invalid configuration collection at {location}')
        if schema.get('uniqueItems') and len({canonical(item) for item in value}) != len(value):
            raise ValueError(f'Duplicate configuration entry at {location}')
        for item in value: _validate(item, schema.get('items', {}), root, location + '[]')
    elif isinstance(value, str):
        if not schema.get('minLength', 0) <= len(value) <= schema.get('maxLength', 10000) or 'pattern' in schema and not re.fullmatch(schema['pattern'], value):
            raise ValueError(f'Invalid configuration string at {location}')


def load(path: Path) -> dict:
    def unique_pairs(pairs):
        result = {}
        for key, value in pairs:
            if key in result: raise ValueError('Duplicate JSON property')
            result[key] = value
        return result
    value = json.loads(Path(path).read_text(encoding='utf-8'), object_pairs_hook=unique_pairs)
    schema = json.loads((Path(__file__).resolve().parents[1] / 'config/haihua.schema.json').read_text(encoding='utf-8'))
    _validate(value, schema, schema)
    organizations = {o['code']: o for o in value['organizations']}
    if len(organizations) != len(value['organizations']) or organizations.get('ROOT', {}).get('parent') is not None:
        raise ValueError('One ROOT and distinct organizations required')
    if any(o['parent'] != 'ROOT' for o in value['organizations'] if o['code'] != 'ROOT'):
        raise ValueError('Haihua departments must be direct ROOT children')
    roles = {r['code'] for r in value['roles']}
    if len(roles) != len(value['roles']): raise ValueError('Duplicate appointment role')
    people = {p['username']: p for p in value['people']}
    if len(people) != len(value['people']): raise ValueError('Duplicate username')
    appointments = {}
    for person in value['people']:
        for appointment in person['appointments']:
            if appointment['key'] in appointments or appointment['organization'] not in organizations or appointment['role'] not in roles:
                raise ValueError('Invalid appointment reference')
            appointments[appointment['key']] = appointment
            for grant in appointment['grants']:
                if grant['scope'] not in organizations: raise ValueError('Unknown authority scope')
                if grant['authority'].startswith('IDENTITY_') and appointment['organization'] != 'ROOT':
                    raise ValueError('Identity administration requires ROOT appointment')
    for route in value['responsibilityRoutes']:
        if route['sourceOrganization'] not in organizations or route['appointment'] not in appointments:
            raise ValueError('Invalid responsibility reference')
    route_keys = [(r['sourceOrganization'], r['stage']) for r in value['responsibilityRoutes']]
    if len(route_keys) != len(set(route_keys)): raise ValueError('Duplicate responsibility route')
    for policy in value['approvalPolicies']:
        if policy['organization'] not in organizations or any(a not in appointments for a in policy['approvers']):
            raise ValueError('Invalid approval member')
    if value['defaults']['representative'] not in people: raise ValueError('Unknown default representative')
    source_people = [source['username'] for source in value['intakeSources']]
    source_codes = [source['account'] for source in value['intakeSources']]
    if set(source_people) != set(people) or len(source_codes) != len(set(source_codes)) or len(source_people) != len(set(source_people)):
        raise ValueError('One distinct intake source per principal required')
    if any(source['organization'] not in organizations for source in value['intakeSources']):
        raise ValueError('Unknown intake organization')
    return value
