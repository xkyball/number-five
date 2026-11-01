"""Explicit, source-independent CA -> existing native symbol resource mapping.

OpenCPN CA is private syntax, not a Raymarine instruction. The established
compatibility path replaces only CA with SY and supplies the matching native
definition AND raster atlas. This module reuses such explicitly pinned assets;
it does not rasterize new sectors or silently approximate an unknown recipe.
"""
from __future__ import annotations

from decimal import Decimal, InvalidOperation
from functools import lru_cache
import hashlib
import json
import math
import re
import struct

FORMAT = 'no5-cache-country-light-sector-map/1'
SIZING_POLICY = 'OWN_FIXED_PPMM3_DISPLAY300_ROTATION0_NO_SCAMIN_SIZING/1'
REQUIRED_RESOURCES = (64, 65, 68, 69)


class LightSectorRefusal(ValueError):
    pass


def need(ok, code):
    if not ok:
        raise LightSectorRefusal('LIGHT_SECTOR_' + code)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def parse_ca(token):
    """The frozen source numeric contract: eight values, integral dimensions.

    Preserve exact tokens separately. Numeric equality here is deliberately
    narrower than output equivalence: no modulo, colour or radius inference.
    """
    need(type(token) is str and token.startswith('CA(') and len(token) <= 65535, 'ONE_CA_TOKEN')
    need(not any(c in token for c in (';', '\0', '\x1f')), 'ONE_CA_TOKEN')
    body = token[3:]
    closing = body.endswith(')')
    if closing:
        body = body[:-1]
    parts = body.split(',')
    need(len(parts) == 8 and all(p.strip() for p in parts), 'EIGHT_SOURCE_PARAMETERS')
    need(all(re.fullmatch('[A-Z0-9]{5}', parts[i].strip()) for i in (0, 2)), 'COLOR_NAME5')
    values = []
    for i, text in enumerate(parts):
        if i in (0, 2):
            values.append(text.strip())
            continue
        text = text.strip()
        need(re.fullmatch(r'[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?', text, re.ASCII), 'FINITE_DECIMAL')
        try:
            value = Decimal(text)
        except InvalidOperation as exc:
            raise LightSectorRefusal('LIGHT_SECTOR_FINITE_DECIMAL') from exc
        if i in (1, 3, 6, 7):
            need(value.is_finite() and 0 <= value <= 0x7fffffff and value == value.to_integral_value(),
                 'INTEGRAL_SOURCE_DIMENSION')
            values.append(int(value))
        else:
            number = float(value)
            need(math.isfinite(number), 'FINITE_ANGLE')
            # Keep IEEE negative zero and the exact original double value.
            values.append(struct.pack('<d', number).hex())
    need(values[3] > 0 and values[6] > 0, 'POSITIVE_ARC_RADIUS_WIDTH')
    return dict(parameters=values, exactLexemes=parts, closingPresent=closing)


def _recipe(token):
    return json.dumps(parse_ca(token)['parameters'], separators=(',', ':'), allow_nan=False)


def _native_definition(common, name):
    lines = common.splitlines(keepends=True)
    matches = []
    for at, line in enumerate(lines):
        if not line.startswith(b'SYMB  ') or at + 3 >= len(lines):
            continue
        body = lines[at + 1][6:].rstrip(b'\r\n')
        digits = re.match(rb'[0-9]+', body)
        if lines[at + 1].startswith(b'SYMD  ') and digits and body[len(digits[0]):len(digits[0])+8] == name.encode('ascii'):
            block = lines[at:at + 4]
            need([b[:4] for b in block] == [b'SYMB', b'SYMD', b'SCRF', b'SVCT'], 'NATIVE_FOUR_LINE_DEFINITION')
            matches.append(b''.join(block))
    need(len(matches) == 1, 'UNIQUE_NATIVE_SYMBOL_DEFINITION')
    return matches[0]


def _atlas_row(csv, png, name):
    need(png[:8] == b'\x89PNG\r\n\x1a\n' and len(png) >= 33 and png[12:16] == b'IHDR', 'PNG_IHDR')
    image_width, image_height = struct.unpack_from('>II', png, 16)
    need(image_width > 0 and image_height > 0, 'PNG_DIMENSIONS')
    names, matching = set(), []
    for line in csv.decode('ascii').splitlines():
        fields = line.split(',')
        need(len(fields) == 8 and fields[0] not in names, 'UNIQUE_ATLAS_CSV8')
        names.add(fields[0])
        if fields[0] == name:
            try:
                scale = float(fields[1])
                x, y, w, h = [int(v) for v in fields[2:6]]
                pivots = [float(v) for v in fields[6:8]]
            except ValueError as exc:
                raise LightSectorRefusal('LIGHT_SECTOR_ATLAS_FIELDS') from exc
            need(math.isfinite(scale) and scale > 0 and all(math.isfinite(v) for v in pivots), 'ATLAS_SCALE_PIVOT')
            # Current established CSV uses actual PNG top y; old mirror labels
            # were explicitly corrected by LAYOUT_FIELD_CLARIFICATION.
            need(x >= 0 and y >= 0 and w > 0 and h > 0 and x+w <= image_width and y+h <= image_height,
                 'ATLAS_RECT_INSIDE_PNG')
            matching.append(line.encode('ascii'))
    need(len(matching) == 1, 'NATIVE_SYMBOL_ATLAS_REQUIRED')
    return matching[0]


@lru_cache(maxsize=2)
def _validated_bindings(mapping_json, resource_tuple):
    """Two immutable resource generations; keys contain ALL actual data bytes.

    Validation is per profile generation, not once per light/UID. Retaining only
    two generations bounds this process cache; no input path or object ID key.
    """
    mapping = json.loads(mapping_json)
    resources = dict(zip(REQUIRED_RESOURCES, resource_tuple))
    need(set(mapping) == {'format', 'sizingPolicy', 'resourceSHA256', 'bindings'}, 'GENERIC_MAPPING_KEYS')
    need(mapping.get('format') == FORMAT, 'EXPLICIT_REUSABLE_MAPPING_REQUIRED')
    need(mapping.get('sizingPolicy') == SIZING_POLICY, 'EXPLICIT_ESTABLISHED_SIZING_POLICY')
    resource_hashes = mapping.get('resourceSHA256', {})
    for bid in REQUIRED_RESOURCES:
        need(resource_hashes.get(str(bid)) == sha(resources[bid]), 'SAME_GENERATION_RESOURCE_' + str(bid))
    bindings = {}
    rows = mapping.get('bindings')
    need(isinstance(rows, list), 'BINDINGS_LIST')
    for row in rows:
        need(set(row) == {'sourceCA', 'symbolName', 'definitionSHA256', 'atlasRowSHA256'}, 'GENERIC_RECIPE_KEYS_NO_OWNER_PINS')
        name = row['symbolName']
        need(type(name) is str and re.fullmatch('[A-Z0-9]{8}', name), 'SYMBOL_NAME8')
        key = _recipe(row['sourceCA'])
        need(key not in bindings, 'UNIQUE_RECIPE')
        definition = _native_definition(resources[65], name)
        atlas = _atlas_row(resources[69], resources[68], name)
        need(sha(definition) == row['definitionSHA256'] and sha(atlas) == row['atlasRowSHA256'], 'BOUND_NATIVE_RESOURCE')
        bindings[key] = row
    return bindings


def translate_light_sectors(programme, feature, resources, profile):
    """Return (native programme, receipt), without changing attrs or anchors.

    Profile.lightSectors is explicit reusable DATA, not executable code:
      {format, sizingPolicy, resourceSHA256:{"64":...,"65":...,"68":...,"69":...},
       bindings:[{sourceCA, symbolName, definitionSHA256, atlasRowSHA256}]}

    No cellId/UID appears in the mapping. New unsupported sectors refuse instead
    of borrowing a neighbouring sector or inventing a native CA command.
    """
    need(type(programme) is str, 'PROGRAMME_TEXT')
    if 'CA(' not in programme:
        return programme, dict(format=FORMAT, changed=False, replacements=[])
    # Locate commands before inspecting feature or profile. Quoted text is inert.
    spans, start, quote = [], 0, None
    for at, char in enumerate(programme):
        if char in ("'", '"'):
            quote = None if quote == char else char if quote is None else quote
        if char == ';' and quote is None:
            spans.append((start, at)); start = at + 1
    need(quote is None, 'UNBALANCED_PROGRAMME_QUOTE')
    spans.append((start, len(programme)))
    spans = [(begin, end) for begin, end in spans if programme[begin:end].strip().startswith('CA(')]
    if not spans:
        return programme, dict(format=FORMAT, changed=False, replacements=[])
    need(feature.get('classAcronym') == 'LIGHTS' and feature.get('geometry', {}).get('kind') == 'point',
         'ACTUAL_LIGHTS_POINT_REQUIRED')
    mapping = profile.get('lightSectors')
    need(isinstance(mapping, dict) and mapping.get('format') == FORMAT, 'EXPLICIT_REUSABLE_MAPPING_REQUIRED')
    for bid in REQUIRED_RESOURCES:
        need(type(resources.get(bid)) is bytes, 'IMMUTABLE_RESOURCE_' + str(bid))
    bindings = _validated_bindings(json.dumps(mapping, sort_keys=True, separators=(',', ':'), allow_nan=False),
                                   tuple(resources[bid] for bid in REQUIRED_RESOURCES))
    # Only unquoted standalone CA commands qualify. Text strings containing
    # CA(...) do not become executable instructions or replacement targets.
    replacements = []
    result = programme
    for begin, end in reversed(spans):
        piece = programme[begin:end]
        command = piece.strip()
        if not command.startswith('CA('):
            continue
        key = _recipe(command)
        need(key in bindings, 'UNKNOWN_SECTOR_RECIPE_REQUIRES_NEW_NATIVE_RESOURCE')
        row = bindings[key]
        offset = begin + piece.index(command)
        replacement = 'SY(' + row['symbolName'] + ')'
        result = result[:offset] + replacement + result[offset+len(command):]
        replacements.append(dict(originalToken=command, originalTokenSHA256=sha(command.encode('ascii')),
                                 parameters=parse_ca(command), nativeSymbol=row['symbolName'],
                                 definitionSHA256=row['definitionSHA256'], atlasRowSHA256=row['atlasRowSHA256'],
                                 sourceByteOffset=offset))
    need(replacements, 'CA_NOT_AN_EXECUTABLE_COMMAND')
    return result, dict(format=FORMAT, changed=True, replacements=list(reversed(replacements)),
        originalProgrammeSHA256=sha(programme.encode('ascii')), nativeProgrammeSHA256=sha(result.encode('ascii')),
        sourceFeatureSHA256=sha(json.dumps(feature, sort_keys=True, separators=(',', ':'), ensure_ascii=False, allow_nan=False).encode('utf8')),
        sourceAttributeValuesUnchanged=True, actualSourcePointAnchorUnchanged=True,
        mappingPolicy=SIZING_POLICY, rotationZeroReferenceOnly=True, dynamicDisplaySizingClaim=False,
        newResourceRasterized=False, nativeCACommandEmitted=False, nativeOrA97Execution=False)
