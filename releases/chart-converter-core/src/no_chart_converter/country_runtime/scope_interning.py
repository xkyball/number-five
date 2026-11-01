"""Lossless stdlib JSON scope-metadata interning; no geometry or file I/O.

Pool values and expanded scopes have independent mutable ownership. Only the
five explicitly selected metadata fields are moved to canonical-SHA refs.
"""

import copy

import hashlib

import json

POOL_FORMAT = 'no5-scope-value-pool/1'

SCOPE_FORMAT = 'no5-interned-scope/1'

FIELDS = ('globalRank', 'prefix', 'retainedPrefix', 'availabilityBindings',
          'scopeProof.sourceAreaSemantics')

def canonical_json(value):
    """The exact UTF-8 bytes hashed by both writer and independent reader."""
    return json.dumps(value, ensure_ascii=False, sort_keys=True,
                      separators=(',', ':'), allow_nan=False).encode('utf-8')

def canonical_sha(value):
    return hashlib.sha256(canonical_json(value)).hexdigest()

def _json_copy(value):
    encoded = canonical_json(value)
    result = copy.deepcopy(value)
    if json.loads(encoded) != result:
        raise ValueError('JSON_NATIVE_TYPES_REQUIRED')
    return result

def new_pool():
    return {'format': POOL_FORMAT, 'entries': {}}

def _entries(pool, initialize=False):
    if not isinstance(pool, dict):
        raise ValueError('POOL_DICT_REQUIRED')
    if initialize and not pool:
        pool.update(new_pool())
    if set(pool) != {'format', 'entries'} or pool['format'] != POOL_FORMAT or not isinstance(pool['entries'], dict):
        raise ValueError('EXACT_POOL_FORMAT_REQUIRED')
    return pool['entries']

def _digest(value):
    if not isinstance(value, str) or len(value) != 64 or any(c not in '0123456789abcdef' for c in value):
        raise ValueError('CANONICAL_SHA256_REFERENCE_REQUIRED')

def pool_sha(pool):
    """Check every entry and bind the complete pool before publishing/reading."""
    for digest, value in _entries(pool).items():
        _digest(digest)
        if canonical_sha(value) != digest:
            raise ValueError('POOL_VALUE_SHA_MISMATCH')
    return canonical_sha(pool)

def intern_scope(scope, pool):
    if not isinstance(scope, dict):
        raise ValueError('SCOPE_JSON_DICT_REQUIRED')
    owned = _json_copy(scope)
    original_sha = canonical_sha(owned)
    entries = _entries(pool, initialize=True)
    refs = {}
    for path in FIELDS:
        if '.' in path:
            parent, field = path.split('.')
            node = owned.get(parent)
            if not isinstance(node, dict) or field not in node:
                continue
        else:
            node, field = owned, path
            if field not in node:
                continue
        value = node[field]
        digest = canonical_sha(value)
        if digest in entries:
            if canonical_json(entries[digest]) != canonical_json(value):
                raise ValueError('POOL_REUSE_VALUE_MISMATCH')
        else:
            entries[digest] = _json_copy(value)
        refs[path] = digest
        del node[field]
    return {'format': SCOPE_FORMAT, 'originalScopeCanonicalSHA256': original_sha,
            'scope': owned, 'refs': refs}

def expand_scope(compact, pool):
    if not isinstance(compact, dict) or set(compact) != {'format', 'originalScopeCanonicalSHA256', 'scope', 'refs'}:
        raise ValueError('EXACT_COMPACT_SCOPE_FORMAT_REQUIRED')
    if compact['format'] != SCOPE_FORMAT or not isinstance(compact['scope'], dict) or not isinstance(compact['refs'], dict):
        raise ValueError('EXACT_COMPACT_SCOPE_FORMAT_REQUIRED')
    _digest(compact['originalScopeCanonicalSHA256'])
    entries = _entries(pool)
    owned = _json_copy(compact['scope'])
    for path, digest in compact['refs'].items():
        if path not in FIELDS:
            raise ValueError('UNKNOWN_INTERNED_SCOPE_FIELD')
        _digest(digest)
        if digest not in entries:
            raise ValueError('MISSING_POOL_REFERENCE')
        value = entries[digest]
        if canonical_sha(value) != digest:
            raise ValueError('POOL_VALUE_SHA_MISMATCH')
        if '.' in path:
            parent, field = path.split('.')
            node = owned.get(parent)
            if not isinstance(node, dict):
                raise ValueError('NESTED_SCOPE_CONTAINER_REQUIRED')
        else:
            node, field = owned, path
        if field in node:
            raise ValueError('REFERENCE_SHADOWS_DIRECT_SCOPE_FIELD')
        node[field] = _json_copy(value)
    if canonical_sha(owned) != compact['originalScopeCanonicalSHA256']:
        raise ValueError('ORIGINAL_SCOPE_SHA_MISMATCH')
    return owned
