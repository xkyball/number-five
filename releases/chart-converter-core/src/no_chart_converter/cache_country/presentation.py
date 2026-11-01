"""Compile fresh cache features against an explicitly pinned presentation.

This is a style compiler, not a saved South Africa owner-table adapter. Generic
rules are matched again for every input feature. Public S52 XML and original
Common41 LUP records use the ordering in OpenCPN's CompareLUPObjects/FindBestLUP.
Only closed conditional families are evaluated here. Other conditionals require
the optional executable public-S52 provider; unresolved objects fail the build.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import hashlib
import json
import math
import re
import struct
import subprocess
import xml.etree.ElementTree as ET

from ..country_runtime.inputs import file_pin
from ..country_runtime.render_activation import encode_frame
from ..country_runtime.localize_metadata import Stream, additional_frame
from ..country_runtime.point_packets import regular_attrs
from ..country_runtime.source_presentation_resolver import attribute_rows_sha256

CONTEXTS = (196608, 196609)
TABLES = {'Simplified': 76, 'Paper': 82, 'Lines': 83, 'Plain': 78,
          'Symbolized': 79, 'SIMPLIFIED': 76, 'PAPER_CHART': 82,
          'LINES': 83, 'PLAIN_BOUNDARIES': 78, 'SYMBOLIZED_BOUNDARIES': 79}
CATEGORIES = {'DISPLAYBASE': 68, 'STANDARD': 83, 'OTHER': 79}
CONTEXT_PAINT_POLICY = 'OWN_MAX_PRIORITY_MOST_VISIBLE_CATEGORY/1'
PRIORITIES = {'No data': 0, 'Group 1': 1, 'Area 1': 2, 'Area 2': 3,
              'Point Symbol': 4, 'Line Symbol': 5, 'Area Symbol': 6,
              'Routing': 7, 'Hazards': 8, 'Mariners': 9}
CANONICAL_OPS = frozenset(('AC', 'AP', 'LS', 'LC', 'SY', 'TX', 'TE', 'CA', 'MP', 'CS'))


class PresentationRefusal(ValueError):
    """A missing, unsupported, ambiguous or unbound presentation contract."""

    def __init__(self, code, *, source_id=None, detail=None):
        self.code, self.source_id, self.detail = code, source_id, detail
        super().__init__(code + (':' + str(source_id) if source_id else '') +
                         (':' + str(detail) if detail else ''))


def _need(value, code, **details):
    if not value:
        raise PresentationRefusal(code, **details)


def _canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False,
                      separators=(',', ':'), allow_nan=False).encode()


def feature_digest(feature):
    """Digest the complete fresh IR feature, including typed values/geometry."""
    return hashlib.sha256(_canonical(feature)).hexdigest()


def _sha(raw):
    return hashlib.sha256(raw).hexdigest()


def _u32(value, code):
    _need(type(value) is int and 0 <= value <= 0xffffffff, code)
    return value


def target_owner_paint_state(selections, profile, *, source_id=None):
    """Declare the single target ordering state without changing either LUP.

    A Country owner has one compiled sort/visibility state but two instruction
    packets. The optional target policy keeps the higher numerical priority and
    the most visible category. It is a compiler policy, not source-context paint
    order parity. The exact original context selections remain separate.
    """
    policy = profile.get('contextPaintPolicy')
    _need(policy in (None, CONTEXT_PAINT_POLICY), 'UNSUPPORTED_CONTEXT_PAINT_POLICY')
    _need(set(selections) == {str(k) for k in CONTEXTS}, 'BOTH_CONTEXT_PAINT_STATES_REQUIRED')
    states = {key: {'displayPriority': row['displayPriority'], 'category': row['category']}
              for key, row in selections.items()}
    _need(all(type(row['displayPriority']) is int and 0 <= row['displayPriority'] <= 9
              and row['category'] in CATEGORIES for row in states.values()),
          'VALID_CONTEXT_PAINT_STATES_REQUIRED', source_id=source_id)
    different = len({(row['displayPriority'], row['category']) for row in states.values()}) > 1
    _need(not different or policy == CONTEXT_PAINT_POLICY,
          'EXPLICIT_CONTEXT_PAINT_POLICY_REQUIRED', source_id=source_id, detail=states)
    target = dict(states[str(CONTEXTS[0])])
    if policy == CONTEXT_PAINT_POLICY:
        target['displayPriority'] = max(row['displayPriority'] for row in states.values())
        order = ('DISPLAYBASE', 'STANDARD', 'OTHER')
        target['category'] = min((row['category'] for row in states.values()), key=order.index)
    receipt = {'policy': policy or 'IDENTICAL_CONTEXT_PAINT_STATES/1',
               'sourceContextStates': states, 'contextStatesDiffer': different,
               'targetState': target, 'targetStateDerived': different,
               'selectedSourceLookupContext': CONTEXTS[0],
               'sourceContextPaintOrderParityClaim': not different,
               'drawingProgrammesAltered': False, 'derivedOwnersAdded': 0}
    return target, receipt


def _read_pin(pin, base):
    _need(isinstance(pin, dict) and {'path', 'bytes', 'sha256'} <= pin.keys(),
          'PRESENTATION_DATA_PIN_REQUIRED')
    path = Path(pin['path'])
    path = (path if path.is_absolute() else base / path).resolve(strict=True)
    before = path.stat()
    raw = path.read_bytes()
    after = path.stat()
    fields = ('st_dev', 'st_ino', 'st_size', 'st_mtime_ns', 'st_ctime_ns')
    _need(all(getattr(before, x) == getattr(after, x) for x in fields),
          'PRESENTATION_RESOURCE_CHANGED_DURING_READ')
    _need(type(pin['bytes']) is int and len(raw) == pin['bytes'] and
          _sha(raw) == pin['sha256'], 'PRESENTATION_RESOURCE_PIN_MISMATCH', detail=path.name)
    return raw, {**pin, 'path': str(path)}


def _write_json(directory, name, value):
    path = directory / name
    path.write_bytes(_canonical(value) + b'\n')
    path.chmod(0o600)
    return file_pin(path)


def _attributes(feature):
    result = {}
    for row in feature['attributes']:
        name = row['acronym']
        if name is None:
            continue
        _need(name not in result, 'AMBIGUOUS_DUPLICATE_FEATURE_ATTRIBUTE',
              source_id=feature['sourceId'], detail=name)
        _need(row['type'] in ('uint32', 'float64', 'utf8'), 'UNSUPPORTED_ATTRIBUTE_TYPE',
              source_id=feature['sourceId'], detail=name)
        result[name] = row
    return result


def _float32(value):
    try:
        return struct.unpack('<f', struct.pack('<f', value))[0]
    except (OverflowError, struct.error):
        return math.inf


def _predicate_matches(predicate, attributes):
    name, operator = predicate['acronym'], predicate.get('operator', 'eq')
    row = attributes.get(name)
    if operator == 'present':
        return row is not None
    if operator == 'absent':
        return row is None
    if row is None:
        return False
    if 'type' in predicate and predicate['type'] != row['type']:
        return False
    value = predicate.get('value')
    if operator == 'eq':
        return type(value) is type(row['value']) and value == row['value']
    if operator == 'contains':
        _need(row['type'] == 'utf8' and type(value) is str,
              'LIST_PREDICATE_REQUIRES_DECLARED_STRING_TOKEN')
        return value in row['value'].split(',')
    if operator == 's52':
        # The public port stores S57 list attributes as OGR_STR: exact string
        # matching, not a set intersection. Real attributes compare to the
        # float32 parsed LUP scalar exactly in FindBestLUP.
        if value.startswith(' '):
            return True
        if value.startswith('?'):
            raise PresentationRefusal('UNDEFINED_S52_ATTRIBUTE_REQUIRES_NATIVE_PROVIDER', detail=name)
        if row['type'] == 'uint32':
            match = re.match(r'^[+-]?\d+', value)
            return row['value'] == (int(match[0]) if match else 0)
        if row['type'] == 'float64':
            try:
                expected = _float32(float(value))
            except ValueError:
                expected = 0.0
            return row['value'] == expected
        return row['value'] == value
    raise PresentationRefusal('UNSUPPORTED_ATTRIBUTE_PREDICATE', detail=operator)


@dataclass(frozen=True)
class Lookup:
    identifier: str
    class_name: str
    primitive: int
    table: int
    rcid: int
    priority: int
    category: str
    instruction: str
    attributes: tuple
    ordinal: int
    origin: str
    native_scamin: int | None = None
    contexts: tuple = CONTEXTS


def _validate_lookup(row):
    _need(type(row.primitive) is int and row.primitive in (0, 1, 2), 'LOOKUP_IR_PRIMITIVE')
    _need(row.table in (76, 78, 79, 82, 83), 'LOOKUP_SOURCE_TABLE')
    _u32(row.rcid, 'LOOKUP_SOURCE_RCID_U32')
    _need(type(row.priority) is int and 0 <= row.priority <= 9, 'LOOKUP_PRIORITY')
    _need(row.category in CATEGORIES, 'LOOKUP_CATEGORY', detail=row.category)
    _need(row.class_name and row.class_name != '######', 'GENERIC_UNKNOWN_CLASS_LOOKUP_FORBIDDEN')
    _need(set(row.contexts) <= set(CONTEXTS) and row.contexts, 'LOOKUP_DRAW_CONTEXTS')
    return row


def parse_profile_rules(rules):
    """Explicit reusable rules for an own target presentation, never owners."""
    result, ids = [], set()
    for ordinal, row in enumerate(rules):
        _need(isinstance(row, dict) and not ({'uid', 'featureSHA256', 'sourceId'} & row.keys()),
              'REUSABLE_CLASS_RULE_REQUIRED_NOT_OWNER_PROGRAMME')
        identifier = row['id']
        _need(type(identifier) is str and identifier not in ids, 'UNIQUE_PROFILE_RULE_ID')
        ids.add(identifier)
        predicates = row.get('attributes', [])
        _need(isinstance(predicates, list) and all(isinstance(p, dict) and
              isinstance(p.get('acronym'), str) for p in predicates), 'TYPED_RULE_PREDICATES')
        result.append(_validate_lookup(Lookup(identifier, row['classAcronym'],
            row['sourcePrimitive'], row['sourceTable'], row['sourceRCID'],
            row['priority'], row['category'].upper(), row['instruction'],
            tuple(predicates), ordinal, 'explicit_reusable_target_rule',
            row.get('nativeScaminLevelU32'), tuple(row.get('contextKeys', CONTEXTS)))))
    return result


def parse_s52_xml(raw):
    """Public chartsymbols.xml lookup records in source sequence order.

    Symbols, palette and raster data remain separately pinned target resources;
    parsing a lookup never asserts their presence or native compatibility.
    """
    _need(b'<!DOCTYPE' not in raw.upper() and b'<!ENTITY' not in raw.upper(),
          'S52_XML_EXTERNAL_ENTITIES_FORBIDDEN')
    root = ET.fromstring(raw)
    result = []
    primitive = {'Point': 0, 'Line': 1, 'Area': 2}
    for ordinal, node in enumerate(root.findall('./lookups/lookup')):
        name = node.get('name')
        if name == '######':
            continue
        table = TABLES.get(node.findtext('table-name'))
        category = (node.findtext('display-cat') or '').upper()
        # Mariners and user overlays have no contracted native category here.
        if category not in CATEGORIES:
            continue
        predicates = []
        for attr in node.findall('attrib-code'):
            text = attr.text or ''
            _need(len(text) >= 6, 'S52_ATTRIBUTE_GRAMMAR')
            predicates.append({'acronym': text[:6], 'operator': 's52', 'value': text[6:]})
        row = Lookup('xml:' + str(node.get('RCID')), name,
                     primitive.get(node.findtext('type'), -1), table,
                     int(node.get('RCID')), PRIORITIES.get(node.findtext('disp-prio'), 0),
                     category, node.findtext('instruction') or '', tuple(predicates),
                     ordinal, 'public_opencpn_s52_xml')
        result.append(_validate_lookup(row))
    _need(result, 'S52_XML_HAS_NO_SUPPORTED_LOOKUPS')
    return result


def _field(line):
    data = line[6:]
    match = re.match(r'\d+', data)
    _need(match is not None, 'COMMON_FIELD_LENGTH_PREFIX')
    return data[len(match[0]):]


def parse_common_lups(raw):
    """Original generic Common41 LUP grammar; saved NXSTYL tables are refused."""
    text = raw.decode('ascii')
    result, current = [], None
    for line in text.splitlines():
        if line.startswith('LUPT  '):
            if current is not None:
                result.append(current)
            data = _field(line)
            _need(len(data) >= 24 and data[:2] == 'LU' and data[7:10] == 'NIL',
                  'COMMON_GENERIC_LUP_HEADER')
            current = {'id': int(data[2:7]), 'class': data[10:16],
                       'primitive': {'P': 0, 'L': 1, 'A': 2}.get(data[16]),
                       'priority': int(data[17:22]), 'table': TABLES.get(data[23:]),
                       'attributes': [], 'instruction': None, 'category': None}
        elif current is not None and line.startswith('ATTC  '):
            value = _field(line)
            _need('NXSTYL' not in value, 'SAVED_OWNER_NXSTYL_IS_NOT_A_GENERIC_STYLE_PROFILE')
            for part in value.split('\x1f'):
                if part:
                    _need(len(part) >= 6, 'COMMON_ATTRIBUTE_GRAMMAR')
                    current['attributes'].append({'acronym': part[:6], 'operator': 's52', 'value': part[6:]})
        elif current is not None and line.startswith('INST  '):
            current['instruction'] = _field(line).rstrip('\x1f')
        elif current is not None and line.startswith('DISC  '):
            current['category'] = _field(line).upper()
    if current is not None:
        result.append(current)
    out = []
    for ordinal, row in enumerate(result):
        if row['class'] == '######':
            continue
        _need(row['instruction'] is not None and row['category'] is not None,
              'COMPLETE_COMMON_LUP_FIELDS_REQUIRED')
        out.append(_validate_lookup(Lookup('common:' + str(row['id']), row['class'],
            row['primitive'], row['table'], row['id'], row['priority'], row['category'],
            row['instruction'], tuple(row['attributes']), ordinal, 'original_generic_common41')))
    return out


def select_lookup(lookups, feature, context):
    """Most attributes first, then source sequence; first perfect match.

    If no predicate matches, only an explicit attribute-free class LUP is used.
    Unlike a guessed universal symbol, an unknown class is an explicit refusal.
    """
    attributes = _attributes(feature)
    table = (82 if context == 196608 else 76) if feature['sourcePrimitive'] == 0 else (
        83 if feature['sourcePrimitive'] == 1 else 78)
    matching = [row for row in lookups if row.class_name == feature['classAcronym'] and
                row.primitive == feature['sourcePrimitive'] and context in row.contexts]
    native = [row for row in matching if row.origin != 'explicit_reusable_target_rule' and row.table == table]
    custom = [row for row in matching if row.origin == 'explicit_reusable_target_rule']
    candidates = custom or native
    candidates.sort(key=lambda row: (-len(row.attributes), row.ordinal))
    for row in candidates:
        if row.attributes and all(_predicate_matches(p, attributes) for p in row.attributes):
            return row
    for row in candidates:
        if not row.attributes:
            return row
    raise PresentationRefusal('NO_MATCHING_GENERIC_CLASS_PRESENTATION', source_id=feature['sourceId'],
                              detail={'context': context, 'table': table})


def split_commands(instruction):
    """Split native command separators while retaining text-format literals."""
    _need(type(instruction) is str and '\0' not in instruction and
          all(ord(ch) >= 32 for ch in instruction), 'CANONICAL_ASCII_PROGRAMME_REQUIRED')
    try:
        instruction.encode('ascii')
    except UnicodeEncodeError as error:
        raise PresentationRefusal('CANONICAL_ASCII_PROGRAMME_REQUIRED') from error
    result, start, depth, quote = [], 0, 0, False
    for at, ch in enumerate(instruction):
        if ch == "'":
            quote = not quote
        elif not quote:
            if ch == '(':
                depth += 1
            elif ch == ')':
                depth -= 1
                _need(depth >= 0, 'UNBALANCED_PROGRAMME')
            elif ch == ';' and depth == 0:
                if instruction[start:at].strip():
                    result.append(instruction[start:at].strip())
                start = at + 1
    _need(not quote and depth == 0, 'UNBALANCED_PROGRAMME')
    if instruction[start:].strip():
        result.append(instruction[start:].strip())
    for command in result:
        match = re.fullmatch(r'([A-Z]{2})\(.*\)', command)
        _need(match is not None and match[1] in CANONICAL_OPS,
              'UNSUPPORTED_RENDER_COMMAND', detail=command)
    return result


def _depth_area(feature, settings):
    """Closed DEPARE01/02 from public s52cnsy.cpp:620-695, no associations."""
    _need(feature['classAcronym'] in ('DEPARE', 'DRGARE'), 'DEPARE_CONDITIONAL_CLASS')
    # Canonical provider settings use depths in metres. The contour-named
    # spelling remains accepted for explicit own profiles and earlier fixtures.
    settings = dict(settings)
    for target, source in [('shallowContour', 'shallowDepthMetres'),
                           ('safetyContour', 'safetyDepthMetres'),
                           ('deepContour', 'deepDepthMetres')]:
        if target not in settings and source in settings:
            settings[target] = settings[source]
    settings.setdefault('twoShades', False)  # Same documented public-provider default.
    required = ('twoShades', 'shallowContour', 'safetyContour', 'deepContour')
    _need(all(key in settings for key in required) and type(settings['twoShades']) is bool,
          'EXPLICIT_DEPTH_AREA_MARINER_SETTINGS_REQUIRED')
    _need(all(type(settings[k]) in (int, float) and math.isfinite(settings[k])
              for k in required[1:]), 'FINITE_DEPTH_CONTOURS_REQUIRED')
    attrs = _attributes(feature)
    def number(name, default):
        row = attrs.get(name)
        _need(row is None or row['type'] in ('float64', 'uint32'), 'NUMERIC_DEPTH_ATTRIBUTE_REQUIRED', detail=name)
        return row['value'] if row else default
    low = number('DRVAL1', -1.0)
    high = number('DRVAL2', low + 0.01)
    if high <= low:
        high = low + 0.01
    colour = 'DEPVS' if low >= 0 and high > 0 else 'DEPIT'
    if settings['twoShades']:
        if low >= settings['safetyContour'] and high > settings['safetyContour']:
            colour = 'DEPDW'
    else:
        for key, target in [('shallowContour', 'DEPMS'), ('safetyContour', 'DEPMD'), ('deepContour', 'DEPDW')]:
            if low >= settings[key] and high > settings[key]:
                colour = target
    result = ['AC(' + colour + ')']
    if feature['classAcronym'] == 'DRGARE':
        if 'DRVAL1' not in attrs:
            result[0] = 'AC(DEPMD)'
        result += ['AP(DRGARE01)', 'LS(DASH,1,CHGRF)']
    return result


def compile_closed_programme(lookup, feature, settings):
    """Never leak unresolved CSPs into a supposedly compiled country."""
    output, expansions = [], []
    for command in split_commands(lookup.instruction):
        if command in ('CS(DEPARE01)', 'CS(DEPARE02)'):
            output.extend(_depth_area(feature, settings))
            expansions.append({'family': command[3:-1], 'implementation': 'public_opencpn_depare01_closed'})
        elif command in ('CS(SOUNDG01)', 'CS(SOUNDG02)', 'MP()') and \
                feature['classAcronym'] == 'SOUNDG' and feature['geometry']['kind'] == 'multipoint':
            # Country Kind4 carries the original individual depth samples. The
            # actual RX5 native helper performs per-sample glyph preparation.
            output.append('CS(SOUNDG01)')
            expansions.append({'family': 'SOUNDG', 'implementation': 'native_rx5_kind4_soundg01',
                               'originalSamplesRetained': True, 'perSampleExpansionAtRuntime': True})
        elif command.startswith(('CS(', 'CA(', 'MP(')):
            raise PresentationRefusal('PUBLIC_S52_PROVIDER_REQUIRED', source_id=feature['sourceId'], detail=command)
        else:
            output.append(command)
    programme = (';'.join(output) + (';' if output else '')).encode('ascii')
    _need(len(programme) <= 65535, 'PROGRAMME_U16_LENGTH')
    return programme, expansions


def _symbol_names(common):
    lines = common.splitlines()
    symbols = set()
    for at, line in enumerate(lines):
        if line.startswith(b'SYMB  '):
            _need(at + 1 < len(lines) and lines[at+1].startswith(b'SYMD  '), 'COMPLETE_SYMBOL_DEFINITION')
            data = lines[at+1][6:]
            match = re.match(rb'\d+', data)
            _need(match is not None and len(data) >= len(match[0])+8, 'COMMON_SYMBOL_DEFINITION_NAME')
            symbols.add(data[len(match[0]):len(match[0])+8].decode('ascii'))
    return symbols


def _xml_common_definitions(raw):
    """Serialize reusable public XML definitions to the proven Common frame.

    Header field order/framing follows the already established Source primitive
    serializer (prepare_source_primitives_v3). No native library is executed.
    Bitmap pixels remain separately bound through the explicit target atlas.
    Unrepresentable names/header fields are reported, never renamed or clipped.
    """
    root = ET.fromstring(raw)
    records, admitted, refused = [], [], []
    for family, path in [('SY', './symbols/symbol'), ('LC', './line-styles/line-style'),
                         ('AP', './patterns/pattern')]:
        for node in root.findall(path):
            name = node.findtext('name') or ''
            flag = 'V' if family == 'LC' else node.findtext('definition')
            geometry = node.find('vector' if flag == 'V' else 'bitmap')
            if not re.fullmatch('[A-Z0-9]{8}', name) or flag not in ('R', 'V') or geometry is None:
                refused.append({'family': family, 'name': name, 'reason': 'unsupported_name_definition_or_geometry'})
                continue
            try:
                fields = [int(geometry.find('pivot').get('x')), int(geometry.find('pivot').get('y')),
                          int(geometry.get('width')), int(geometry.get('height')),
                          int(geometry.find('origin').get('x')), int(geometry.find('origin').get('y'))]
                minimum, maximum = [int(geometry.find('distance').get(key)) for key in ('min', 'max')]
            except (AttributeError, TypeError, ValueError):
                refused.append({'family': family, 'name': name, 'reason': 'missing_integral_geometry_header'})
                continue
            if any(value < -9999 or value > 99999 for value in fields) or not (
                    0 <= minimum <= 99999 and 0 <= maximum <= 99999):
                refused.append({'family': family, 'name': name, 'reason': 'outside_established_five_decimal_header'})
                continue
            colour = node.findtext('color-ref') or ''
            hpgl = node.findtext('HPGL') or geometry.findtext('HPGL') or ''
            if any(ch in colour + hpgl for ch in ('\0', '\r', '\n')):
                refused.append({'family': family, 'name': name, 'reason': 'unframed_control_in_definition'})
                continue
            if family == 'SY':
                tag, header, cref, vector = 'SYMB', 'SYMD', 'SCRF', 'SVCT'
                body = name + flag + ''.join('%05d' % value for value in fields)
            elif family == 'LC':
                tag, header, cref, vector = 'LNST', 'LIND', 'LCRF', 'LVCT'
                body = name + ''.join('%05d' % value for value in fields)
            else:
                tag, header, cref, vector = 'PATT', 'PATD', 'PCRF', 'PVCT'
                fill = {'L': 'LIN', 'S': 'STG'}.get(node.findtext('filltype'))
                spacing = {'C': 'CON', 'S': 'SCL'}.get(node.findtext('spacing'))
                if fill is None or spacing is None:
                    refused.append({'family': family, 'name': name, 'reason': 'unsupported_pattern_fill_or_spacing'})
                    continue
                body = name + flag + fill + spacing + ('%05d%05d' % (minimum, maximum)) + ''.join('%05d' % value for value in fields)
            serial = len(admitted) + 1
            records.append('\n'.join([tag + '  10' + family + ('%05d' % serial) + 'NIL',
                header + '  ' + str(len(body)) + body,
                cref + '  ' + str(len(colour)) + colour,
                vector + '  ' + str(len(hpgl) + 1) + hpgl]) + '\n')
            admitted.append({'family': family, 'name': name, 'definition': flag})
    return ''.join(records).encode('ascii'), admitted, refused


def _xml_common_lups(lookups):
    families = {76: 'SIMPLIFIED', 82: 'PAPER_CHART', 83: 'LINES',
                78: 'PLAIN_BOUNDARIES', 79: 'SYMBOLIZED_BOUNDARIES'}
    records = []
    for serial, row in enumerate(lookups, 1):
        _need(len(row.class_name) == 6, 'S52_CLASS_ACRONYM_SIX_BYTES')
        header = 'LU%05dNIL%s%s%05dO%s' % (serial, row.class_name,
            {0: 'P', 1: 'L', 2: 'A'}[row.primitive], row.priority, families[row.table])
        attrs = '\x1f'.join(predicate['acronym'] + predicate['value'] for predicate in row.attributes)
        for tag, body in [('LUPT', header), ('ATTC', attrs),
                          ('INST', row.instruction), ('DISC', row.category)]:
            records.append(tag + '  ' + str(len(body) + 1) + body + '\n')
    return ''.join(records).encode('ascii')


def bootstrap_public_s52_profile(parent_pin, lookup_xml_pin, output_dir, *,
                                 provider_command, settings,
                                 native_scamin_level=0, native_scamin_multiplier=1,
                                 public_bitmap_png_pin=None):
    """Create a private reusable resource/profile package, without chart owners.

    Only Common palette, glyph atlas and definition resources are reused from
    the explicit own target parent. All owner-selected LUP/NXSTYL rows are
    removed. Generic LUPs and missing standard definitions come from the pinned
    public S52 XML. Geometry, query owners and historical registries are absent.
    This makes resources usable for a new cache rather than requiring manually
    prepared owner/CSP manifests. It does not assert physical a97 acceptance.

    An optional public bitmap PNG pin enables exact pixel-bound Common69 pivot
    normalization. Only offsets with a unique public bitmap pivot and identical
    name, dimensions and complete RGBA8 bytes change. Other rows are preserved.
    The option uses Pillow from the bitmap extra; it is never imported otherwise.
    """
    from ..country_runtime.wire import blocks
    output_dir = Path(output_dir)
    output_dir.mkdir(mode=0o700, parents=True, exist_ok=False)
    parent, actual_parent = _read_pin(parent_pin, Path('.'))
    xml, actual_xml = _read_pin(lookup_xml_pin, Path('.'))
    if public_bitmap_png_pin is not None:
        from .bitmap_pivots import parse_bitmap_xml
        try:
            parse_bitmap_xml(xml)
        except (ValueError, SyntaxError) as error:
            raise PresentationRefusal('BITMAP_PIVOT_NORMALIZATION_FAILED', detail=str(error)) from error
    lookups = parse_s52_xml(xml)
    resources = {}
    for block in blocks(parent).values():
        if block.flags == 0 and block.disk_id in (64, 65, 68, 69):
            _need(block.disk_id not in resources, 'UNIQUE_TARGET_PRESENTATION_RESOURCE')
            resources[block.disk_id] = block.payload
    _need(set(resources) == {64, 65, 68, 69}, 'COMPLETE_TARGET_PALETTE_COMMON_ATLAS_RESOURCES')
    # Retain only definition groups. No old LUP, NXSTYL, owner or chart bounds
    # participate in the new generic profile.
    original_lines = resources[65].splitlines(keepends=True)
    definitions, names = [], set()
    for at, line in enumerate(original_lines):
        if line.startswith((b'SYMB  ', b'LNST  ', b'PATT  ')):
            _need(at + 3 < len(original_lines), 'COMPLETE_TARGET_COMMON_DEFINITION')
            block = b''.join(original_lines[at:at+4])
            tag = {b'SYMB': b'SYMD', b'LNST': b'LIND', b'PATT': b'PATD'}[line[:4]]
            _need(original_lines[at+1].startswith(tag + b'  '), 'TARGET_COMMON_DEFINITION_TAG')
            name = _field(original_lines[at+1].decode('ascii').rstrip('\r\n'))[:8]
            _need((line[:4], name) not in names, 'UNIQUE_TARGET_COMMON_DEFINITION_NAME')
            names.add((line[:4], name))
            definitions.append(block)
    xml_defs, xml_admitted, xml_refused = _xml_common_definitions(xml)
    xml_lines = xml_defs.splitlines(keepends=True)
    additions = []
    for at in range(0, len(xml_lines), 4):
        family = xml_lines[at][:4]
        name = _field(xml_lines[at+1].decode('ascii').rstrip('\r\n'))[:8]
        if (family, name) not in names:
            definitions.append(b''.join(xml_lines[at:at+4]))
            names.add((family, name))
            additions.append({'family': family.decode('ascii'), 'name': name})
    renumbered = []
    for serial, raw in enumerate(definitions, 1):
        lines = raw.splitlines(keepends=True)
        family = {b'SYMB': b'SY', b'LNST': b'LC', b'PATT': b'AP'}[lines[0][:4]]
        lines[0] = lines[0][:4] + b'  10' + family + ('%05d' % serial).encode() + b'NIL\n'
        renumbered.append(b''.join(lines))
    wire_lookups = [row for row in lookups if len(row.class_name) == 6]
    resources[65] = _xml_common_lups(wire_lookups) + b''.join(renumbered)
    _need(b'NXSTYL' not in resources[65], 'BOOTSTRAP_REMOVED_OWNER_SELECTED_STYLES')
    pivot_receipt_pin = None
    if public_bitmap_png_pin is not None:
        from .bitmap_png import decode_png_rgba
        from .bitmap_pivots import normalize_bitmap_pivot_rows
        bitmap, actual_bitmap = _read_pin(public_bitmap_png_pin, Path('.'))
        try:
            resources[69], pivot_receipt = normalize_bitmap_pivot_rows(
                resources[69], decode_png_rgba(resources[68]), xml, decode_png_rgba(bitmap))
        except (ValueError, OSError, SyntaxError) as error:
            raise PresentationRefusal('BITMAP_PIVOT_NORMALIZATION_FAILED', detail=str(error)) from error
        pivot_receipt['inputs'] = {
            'targetResourceParent': actual_parent, 'publicLookupXML': actual_xml,
            'publicBitmapPNG': actual_bitmap,
            'targetAtlasPNG': {'resourceId': 68, 'bytes': len(resources[68]),
                               'sha256': _sha(resources[68])}}
        from PIL import __version__ as pillow_version
        pivot_receipt['pngDecoder'] = {'name': 'Pillow', 'version': pillow_version,
                                       'output': 'straight RGBA8; no ICC transform, resizing or compositing'}
        pivot_receipt_pin = _write_json(output_dir, 'bitmap-pivot-receipt.json', pivot_receipt)
    profile_resources = []
    for identifier, raw in sorted(resources.items()):
        path = output_dir / ('resource-%02x.bin' % identifier)
        path.write_bytes(raw)
        path.chmod(0o600)
        profile_resources.append({'id': identifier, 'data': file_pin(path)})
    profile = {'format': 'no5-cache-country-presentation/1',
               'nativeScaminLevelU32': _u32(native_scamin_level, 'NATIVE_SCAMIN_LEVEL_U32'),
               'nativeScaminMultiplierU32': _u32(native_scamin_multiplier, 'NATIVE_SCAMIN_MULTIPLIER_U32'),
               'resources': profile_resources, 'lookupXml': actual_xml,
               'settings': settings, 'provider': {'command': list(provider_command)},
               'requireAtlasBindings': True,
               'provenance': {'targetResourceParent': actual_parent, 'publicLookupXML': actual_xml,
                              'ownerManifestOrRegistryReused': False,
                              'oldLUPAndNXSTYLRowsDiscarded': True}}
    if pivot_receipt_pin is not None:
        profile['provenance']['bitmapPivotNormalization'] = pivot_receipt_pin
    profile_pin = _write_json(output_dir, 'presentation-profile.json', profile)
    receipt = {'format': 'no5-public-s52-resource-bootstrap/1', 'profile': profile_pin,
               'genericLUPs': len(wire_lookups),
               'nonS57LookupRefusals': [{'className': row.class_name, 'sourceRCID': row.rcid,
                                       'reason': 'native_fixed_six_byte_class_acronym'}
                                      for row in lookups if len(row.class_name) != 6],
               'targetDefinitionsReused': len(names)-len(additions),
               'publicDefinitionsAdded': additions, 'publicDefinitionsRepresentable': len(xml_admitted),
               'publicDefinitionRefusals': xml_refused, 'allOwnersNeedFreshCompile': True,
               'ownerManifestOrRegistryReused': False, 'physicalA97AcceptanceClaim': False}
    if pivot_receipt_pin is not None:
        receipt['bitmapPivotNormalization'] = pivot_receipt_pin
    return {'profile': profile_pin,
            'receipt': _write_json(output_dir, 'resource-bootstrap-receipt.json', receipt)}


def _validate_atlas_programme(programme, resources, *, source_id, atlas_names=None):
    """Bind actual symbol requests to the same explicit target Common/atlas."""
    if atlas_names is None:
        _need(68 in resources and 69 in resources, 'EXPLICIT_NATIVE_ATLAS_PAIR_REQUIRED', source_id=source_id)
        png = resources[68]
        _need(png[:16] == b'\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR' and len(png) >= 24,
              'NATIVE_ATLAS_PNG_IHDR', source_id=source_id)
        width, height = struct.unpack_from('>II', png, 16)
        _need(height == 512 and width > 0, 'NATIVE_ATLAS_FIXED_HEIGHT512', source_id=source_id)
        names = set()
        for line in resources[69].decode('ascii').splitlines():
            row = line.split(',')
            _need(len(row) == 8 and row[0] not in names, 'NATIVE_ATLAS_UNIQUE_EIGHT_FIELD_ROWS')
            name = row[0]
            try:
                x, y, w, h = map(int, row[2:6])
                scale, ox, oy = (float(row[i]) for i in (1, 6, 7))
            except ValueError as error:
                raise PresentationRefusal('NATIVE_ATLAS_TYPED_RECT_SCALE_PIVOT') from error
            _need(re.fullmatch(r'[A-Z0-9]{8}', name) and scale > 0 and
                  all(math.isfinite(v) for v in (scale, ox, oy)) and
                  0 <= x < width and 0 <= y < height and 0 < w <= width-x and 0 < h <= height-y,
                  'NATIVE_ATLAS_RECT_OR_SCALE_OUTSIDE_RESOURCE', source_id=source_id, detail=name)
            names.add(name)
    else:
        names = atlas_names
    requested = {value.decode('ascii') for value in re.findall(rb'SY\(([A-Z0-9]{8})', programme)}
    _need(requested <= names, 'REQUIRED_NATIVE_ATLAS_SYMBOL_BINDING_MISSING',
          source_id=source_id, detail=sorted(requested - names))
    return frozenset(names)


def _profile(config):
    profile = config.get('_presentationProfile', config.get('presentationProfileDocument'))
    base = Path(config.get('_presentationBaseDirectory', config.get('presentationBaseDirectory', '.'))).resolve()
    if profile is None:
        raw, pin = _read_pin(config['presentationProfile'], Path(config.get('baseDirectory', '.')).resolve())
        profile, base = json.loads(raw), Path(pin['path']).parent
        digest = pin['sha256']
    else:
        digest = config.get('_presentationProfileSHA256', config.get('_presentationProfilePin',
            config.get('presentationProfile', {})).get('sha256', _sha(_canonical(profile))))
    _need(profile.get('format') == 'no5-cache-country-presentation/1', 'CACHE_COUNTRY_PRESENTATION_PROFILE_FORMAT')
    _u32(profile.get('nativeScaminLevelU32'), 'EXPLICIT_NATIVE_SCAMIN_LEVEL_U32_REQUIRED')
    _u32(profile.get('nativeScaminMultiplierU32'), 'EXPLICIT_NATIVE_SCAMIN_MULTIPLIER_U32_REQUIRED')
    _need(profile.get('contextPaintPolicy') in (None, CONTEXT_PAINT_POLICY),
          'UNSUPPORTED_CONTEXT_PAINT_POLICY')
    return profile, base, digest


def _external_programmes(context, profile, profile_digest, output_dir):
    """Optional bounded executable provider; programme rows must bind fresh IR."""
    provider = profile.get('provider')
    if provider is None:
        return {}, None
    command = provider.get('command')
    _need(isinstance(command, list) and command and all(type(x) is str and x for x in command),
          'EXPLICIT_PUBLIC_S52_PROVIDER_COMMAND_REQUIRED')
    request = {'format': 'no5-cache-country-s52-request/1', 'profileSHA256': profile_digest,
               'settings': profile.get('settings', {}), 'sourcePackage': context['source_package'],
               'cells': []}
    for cell in context['cells']:
        source_cell = context.get('source_cells', {}).get(cell['id'], {})
        request['cells'].append({'cellId': cell['id'], 'source': cell['source'],
            **{key: source_cell[key] for key in ('sourceCellPath', 'sourceCellSHA256') if key in source_cell},
            'owners': [{'sourceFeatureId': owner['feature']['sourceFeatureId'],
                        'featureSHA256': feature_digest(owner['feature']), 'feature': owner['feature']}
                       for owner in context['by_cell'][cell['id']]]})
    # The main pipeline may supply the verified source archive path, while the
    # complete manifest digest above remains the required immutable binding.
    for name in ('source_archive', 'sourceArchive', 'source_package_pin'):
        if name in context:
            request['sourceArchive'] = context[name]
            break
    request_path = output_dir / 's52-provider-request.json'
    response_path = output_dir / 's52-provider-response.json'
    request_path.write_bytes(_canonical(request) + b'\n')
    request_path.chmod(0o600)
    completed = subprocess.run(command + ['--request', str(request_path), '--output', str(response_path)],
                               check=False, capture_output=True, timeout=provider.get('timeoutSeconds', 300))
    _need(completed.returncode == 0, 'PUBLIC_S52_PROVIDER_FAILED',
          detail={'returncode': completed.returncode,
                  # The public CLI emits structured refusals on stdout. Keep
                  # both bounded channels or a real semantic error is hidden
                  # behind an otherwise empty stderr/exit-code diagnostic.
                  'stdout': completed.stdout[-2048:].decode('utf8', 'replace'),
                  'stderr': completed.stderr[-2048:].decode('utf8', 'replace')})
    _need(response_path.is_file() and response_path.stat().st_size <= 64 * 1024 * 1024,
          'BOUNDED_PUBLIC_S52_PROVIDER_RESPONSE_REQUIRED')
    response = json.loads(response_path.read_bytes())
    _need(response.get('format') == 'no5-cache-country-s52-response/1' and
          response.get('profileSHA256') == profile_digest, 'PUBLIC_S52_PROVIDER_RESPONSE_BINDING')
    expected_settings = {'safetyDepthMetres': 3.0, 'shallowDepthMetres': 2.0,
                         'deepDepthMetres': 6.0, 'depthUnit': 'metres', 'twoShades': False,
                         **profile.get('settings', {})}
    _need(response.get('settings') == expected_settings, 'PUBLIC_S52_PROVIDER_SETTINGS_BINDING')
    result = {}
    for cell in response.get('cells', []):
        for row in cell['owners']:
            key = (cell['cellId'], row['sourceFeatureId'])
            _need(key not in result, 'DUPLICATE_PROVIDER_OWNER')
            result[key] = row
    expected = {(o['cellId'], o['feature']['sourceFeatureId']): o for o in context['owners']}
    _need(set(result) == set(expected), 'PUBLIC_S52_PROVIDER_COMPLETE_OWNER_BIJECTION')
    for key, row in result.items():
        _need(row.get('featureSHA256') == feature_digest(expected[key]['feature']),
              'PUBLIC_S52_PROVIDER_FRESH_FEATURE_DIGEST', detail=key)
    return result, {'request': file_pin(request_path), 'response': file_pin(response_path),
                    'resolver': response.get('resolver'), 'settings': response['settings']}


def prepare_presentation(context, metadata, output_dir, config):
    """Produce Country programme streams and new hash-bound owner/style tables."""
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    profile, base, profile_digest = _profile(config)
    resources, resource_bytes = [], {}
    for resource in profile['resources']:
        identifier = resource['id']
        _need(type(identifier) is int and identifier not in resource_bytes,
              'UNIQUE_PRESENTATION_RESOURCE_ID')
        raw, pin = _read_pin(resource['data'], base)
        resources.append({'id': identifier, 'data': pin})
        resource_bytes[identifier] = raw
    _need(65 in resource_bytes, 'PINNED_COMMON41_TARGET_RESOURCE_REQUIRED')
    common_pin = next(row['data'] for row in resources if row['id'] == 65)
    lookups = parse_profile_rules(profile.get('rules', []))
    if 'lookupXml' in profile:
        xml, xml_pin = _read_pin(profile['lookupXml'], base)
        lookups.extend(parse_s52_xml(xml))
    elif profile.get('genericCommonLookups', False):
        lookups.extend(parse_common_lups(resource_bytes[65]))
    _need(lookups or profile.get('provider'), 'GENERIC_LOOKUPS_OR_PUBLIC_PROVIDER_REQUIRED')
    lookup_groups = {}
    for lookup in lookups:
        lookup_groups.setdefault((lookup.class_name, lookup.primitive), []).append(lookup)
    provider_rows, provider_receipt = _external_programmes(context, profile, profile_digest, output_dir)
    symbols = _symbol_names(resource_bytes[65])
    atlas_names = (_validate_atlas_programme(b'', resource_bytes, source_id=None)
                   if profile.get('requireAtlasBindings', False) else None)
    cells, owners, registry, point_bindings, all_receipts = {}, {}, [], [], []
    styles = {}
    paint_policy_changed_owners = 0
    for cell in context['cells']:
        cid = cell['id']
        compiled, offsets, dedup = bytearray(), {}, {}
        meta = metadata['cells'][cid]
        add = meta['additional']
        stream = Stream(len(add), lambda start, count: add[start:start+count])
        for owner in context['by_cell'][cid]:
            feature, uid = owner['feature'], owner['uid']
            geometry = feature.get('geometry')
            _need(geometry and geometry['kind'] in ('point', 'multipoint', 'line', 'area'),
                  'PRESENTABLE_GENUINE_GEOMETRY_REQUIRED', source_id=feature['sourceId'])
            _need(feature['classAcronym'], 'UNRESOLVED_SOURCE_CLASS', source_id=feature['sourceId'])
            attributes = _attributes(feature)
            kind = {'point': 1, 'multipoint': 4, 'line': 2, 'area': 3}[geometry['kind']]
            _need(kind != 4 or feature['classAcronym'] == 'SOUNDG',
                  'NON_SOUNDING_MULTIPOINT_PRESENTATION_UNCONTRACTED', source_id=feature['sourceId'])
            entries, selected_by_context, expansions, native_levels = {}, {}, [], set()
            provided = provider_rows.get((cid, feature['sourceFeatureId']))
            for draw_context in CONTEXTS:
                if provided is None:
                    lookup = select_lookup(lookup_groups.get((feature['classAcronym'], feature['sourcePrimitive']), ()),
                                           feature, draw_context)
                    programme, expanded = compile_closed_programme(lookup, feature, profile.get('settings', {}))
                    native_scamin = lookup.native_scamin
                    selected = {'ruleId': lookup.identifier, 'sourceTable': lookup.table,
                                'sourceRCID': lookup.rcid, 'displayPriority': lookup.priority,
                                'category': lookup.category, 'source': lookup.origin,
                                'originalInstruction': lookup.instruction}
                    expansions.extend(expanded)
                else:
                    key = str(draw_context)
                    _need(key in provided.get('programmes', {}) and key in provided.get('selections', {}),
                          'PROVIDER_BOTH_REAL_DRAW_CONTEXTS_REQUIRED', source_id=feature['sourceId'])
                    programme = bytes.fromhex(provided['programmes'][key])
                    text = programme.decode('ascii')
                    if 'CA(' in text:
                        from .light_sectors import translate_light_sectors
                        text, light_receipt = translate_light_sectors(text, feature, resource_bytes, profile)
                        programme = text.encode('ascii')
                        expansions.append(light_receipt)
                    # The provider must expand CS and convert special private
                    # commands to contracted native programmes. SOUNDG remains
                    # the one explicit per-sample native-helper exception.
                    commands = split_commands(text)
                    _need(all(not command.startswith(('CS(', 'MP(', 'CA(')) or
                              (kind == 4 and command == 'CS(SOUNDG01)') for command in commands),
                          'PROVIDER_LEFT_UNSUPPORTED_CONDITIONAL_OR_PRIVATE_ARC', source_id=feature['sourceId'])
                    p = provided['selections'][key]
                    selected = {'ruleId': 'provider:' + str(p['sourceRCID']),
                                'sourceTable': p['sourceTable'], 'sourceRCID': p['sourceRCID'],
                                'displayPriority': p['priority'], 'category': p['category'],
                                'source': 'public_opencpn_s52_executable',
                                'originalInstruction': p.get('originalInstruction', p.get('instruction', '')),
                                'expandedInstruction': p.get('instruction', ''),
                                'conditionals': p.get('conditionals', []),
                                'actualSourceSymbolResolutions': p.get('symbolResolutions', [])}
                    native_scamin = None
                    _need(selected['category'] in CATEGORIES and type(selected['displayPriority']) is int,
                          'PROVIDER_EFFECTIVE_CATEGORY_PRIORITY_REQUIRED')
                for command in split_commands(programme.decode('ascii')):
                    if command.startswith('SY('):
                        _need(re.match(r'^SY\([A-Z0-9]{8}(?:,|\))', command),
                              'LITERAL_NATIVE_SYMBOL_NAME_REQUIRED', source_id=feature['sourceId'])
                missing = set(re.findall(rb'SY\(([A-Z0-9]{8})', programme)) - {s.encode() for s in symbols}
                _need(not missing, 'REQUIRED_SYMBOL_DEFINITION_MISSING', source_id=feature['sourceId'],
                      detail=sorted(s.decode() for s in missing))
                if profile.get('requireAtlasBindings', False):
                    _validate_atlas_programme(programme, resource_bytes, source_id=feature['sourceId'], atlas_names=atlas_names)
                _need(len(programme) <= 65535, 'PROGRAMME_U16_LENGTH')
                native_levels.add(_u32(native_scamin if native_scamin is not None else profile['nativeScaminLevelU32'],
                                       'EXPLICIT_NATIVE_SCAMIN_LEVEL_U32_REQUIRED'))
                entries[draw_context] = programme
                selected_by_context[str(draw_context)] = selected
            native_scamin = _u32(native_scamin if native_scamin is not None else profile['nativeScaminLevelU32'],
                                  'EXPLICIT_NATIVE_SCAMIN_LEVEL_U32_REQUIRED')
            _need(len(native_levels) == 1, 'CONTEXT_DEPENDENT_NATIVE_SCAMIN_LEVEL_UNCONTRACTED',
                  source_id=feature['sourceId'])
            target_paint, paint_receipt = target_owner_paint_state(
                selected_by_context, profile, source_id=feature['sourceId'])
            paint_policy_changed_owners += int(paint_receipt['contextStatesDiffer'])
            # Lookup identity and its effective original state stay exact.
            # The writer's shared ordering state is an explicit separate value.
            selected = dict(selected_by_context[str(CONTEXTS[0])])
            effective_key = _sha(_canonical({'profileSHA256': profile_digest,
                'className': feature['classAcronym'], 'kind': kind,
                'contexts': selected_by_context,
                'programmes': {str(k): v.hex() for k, v in entries.items()}}))
            if effective_key not in styles:
                style_id = len(styles) + 1
                styles[effective_key] = style_id
                registry.append({'keySHA256': effective_key, 'ownEffectiveStyleID': style_id,
                    'classAcronym': feature['classAcronym'], 'sourceTable': selected['sourceTable'],
                    'sourceRCID': selected['sourceRCID'], 'sourcePrimitive': 1 if kind in (1, 4) else kind,
                    'irSourcePrimitive': feature['sourcePrimitive'],
                    'actualAfterPriority': target_paint['displayPriority'],
                    'actualAfterCategory': CATEGORIES[target_paint['category']],
                    'provenance': 'fresh_ir_generic_presentation_compile',
                    'targetPaintStatePolicy': paint_receipt,
                    'actualAfterFieldsMeaning': 'declared_own_target_state_with_original_contexts_retained',
                    'profileSHA256': profile_digest, 'contexts': selected_by_context,
                    'effectiveCandidateINSTHex': entries[CONTEXTS[0]].hex()})
            selected.update(effectiveSourceKeySHA256=effective_key, effectiveStyleID=styles[effective_key],
                            sourcePrimitive=1 if kind in (1, 4) else kind,
                            irSourcePrimitive=feature['sourcePrimitive'], className=feature['classAcronym'])
            command_ops = {c[:2] for value in entries.values() for c in split_commands(value.decode('ascii'))}
            role = 'point' if kind in (1, 4) else ('ground' if kind == 3 and command_ops and command_ops <= {'AC', 'AP'} else 'detail')
            mixed = kind == 3 and bool(command_ops & {'AC', 'AP'}) and bool(command_ops - {'AC', 'AP'})
            source_scamin = [str(attributes['SCAMIN']['value'])] if 'SCAMIN' in attributes else []
            resolution = {'status': 'resolved_fresh_cache_presentation',
                          'instruction': entries[CONTEXTS[0]].decode('ascii'),
                          'displayPriority': target_paint['displayPriority'], 'selected': selected,
                          'contextSelections': selected_by_context, 'conditionalExpansions': expansions,
                          'targetPaintStatePolicy': paint_receipt,
                          'profileSHA256': profile_digest,
                          'featureSourceDigest': feature_digest(feature),
                          'originalNativeOEMLookupExecutionClaim': False}
            owners[uid] = {'role': role, 'kind': kind, 'resolution': resolution,
                           'sourceSCAMIN': source_scamin, 'paintPriority': target_paint['displayPriority'],
                           'nativeScaminLevel': native_scamin, 'mixedProgramme': mixed,
                           'commandOpcodes': sorted(command_ops)}
            additional_offset = meta['additionalOffsets'][uid]
            query = additional_frame(stream, additional_offset)
            frame = encode_frame(additional_offset, entries)
            if frame not in dedup:
                dedup[frame] = len(compiled)
                compiled += frame
            offsets[uid] = dedup[frame]
            receipt = {'uid': uid, 'cellId': cid, 'sourceOwnerOrdinal': owner['sourceOwnerOrdinal'],
                       'className': feature['classAcronym'], 'additionalFrameSHA256': _sha(query),
                       'attributeRowsSHA256': attribute_rows_sha256(regular_attrs(query)),
                       'programmes': {str(k): v.hex() for k, v in entries.items()},
                       'programmeSHA256': {str(k): _sha(v) for k, v in entries.items()},
                       'featureSourceDigest': feature_digest(feature), 'profileSHA256': profile_digest,
                       'resolution': resolution}
            all_receipts.append(receipt)
            if kind == 1:
                point_bindings.append(receipt)
        cells[cid] = {'render': bytes(compiled), 'renderOffsets': offsets}
    index = {'format': 'no5-cache-country-source-presentation/1', 'originalCommon': common_pin,
             'profileSHA256': profile_digest, 'pointProgramBindings': point_bindings,
             'owners': all_receipts, 'freshFeatureCompilation': True,
             'savedOwnerManifestReused': False}
    pins = {'sourceRegistry': _write_json(output_dir, 'fresh-source-registry.json', registry),
            'sourcePresentationIndex': _write_json(output_dir, 'fresh-source-presentation.json', index),
            'caOwnerProgramBindings': _write_json(output_dir, 'fresh-ca-bindings.json', {'owners': []})}
    report = {'format': 'no5-cache-country-presentation-report/1', 'profileSHA256': profile_digest,
              'features': len(owners), 'uniqueEffectiveStyles': len(registry),
              'publicProviderUsed': bool(provider_rows), 'savedOwnerManifestReused': False,
              'originalNativeOEMLookupExecutionClaim': False, 'allOwnersCompiled': True,
              'nativeScaminLevelMeaning': 'explicit_own_target_level_not_source_denominator',
              'sourceSCAMINPreservedSeparately': True, 'contextKeys': list(CONTEXTS)}
    report['contextPaintPolicy'] = profile.get('contextPaintPolicy', 'IDENTICAL_CONTEXT_PAINT_STATES/1')
    report['contextPaintStatesDifferOwners'] = paint_policy_changed_owners
    report['sourceContextPaintOrderParityClaim'] = paint_policy_changed_owners == 0
    if provider_receipt is not None:
        report['publicS52Provider'] = provider_receipt
    report_pin = _write_json(output_dir, 'presentation-report.json', report)
    return {'cells': cells, 'owners': owners, 'pins': pins, 'resources': resources,
            'nativeScaminMultiplierU32': profile['nativeScaminMultiplierU32'],
            'sourceInfo': profile.get('sourceInfo', {}), 'profileSHA256': profile_digest,
            'report': report, 'receipt': {**report, 'reportPin': report_pin}}
