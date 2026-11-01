"""Fresh Cache-IR metadata and a resource-only parent for the country compiler.

No existing chart, owner registry, executable, or binary library is imported.
RX5 attribute maps store lexical strings, so the ordered IR type/value ledger
is a required companion. Empty opaque identity/relations are our compiler's
explicit convention, never reconstructed source FOID/FFPT data.
"""
from __future__ import annotations

from datetime import date
import hashlib
import json
import math
from pathlib import Path, PurePosixPath
import struct
import uuid
import xml.etree.ElementTree as ET

from ..country_runtime.inputs import file_pin
from ..country_runtime import wire
from ..rx5.header import new_plain_vector_header

Q = 2147483649
NAMESPACE = uuid.UUID('934cf818-e3ad-52c9-b786-d45637d8242f')


def need(value, code):
    if not value:
        raise ValueError('CACHE_METADATA_' + code)


def canonical(value):
    return (json.dumps(value, sort_keys=True, ensure_ascii=False,
                       allow_nan=False, separators=(',', ':')) + '\n').encode('utf8')


def write_bytes(directory, name, raw):
    target = Path(directory) / name
    target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    with target.open('xb') as stream:
        stream.write(raw)
    target.chmod(0o600)
    return file_pin(target)


def write_json(directory, name, value):
    return write_bytes(directory, name, canonical(value))


def read_pin(pin, base=Path('.')):
    need(isinstance(pin, dict) and {'path', 'bytes', 'sha256'} <= pin.keys(), 'DATA_PIN_REQUIRED')
    path = Path(pin['path'])
    if not path.is_absolute():
        path = Path(base) / path
    before = path.stat()
    raw = path.read_bytes()
    after = path.stat()
    need(all(getattr(before, f) == getattr(after, f) for f in
             ('st_dev', 'st_ino', 'st_size', 'st_mtime_ns', 'st_ctime_ns')), 'INPUT_CHANGED')
    need(len(raw) == pin['bytes'] and hashlib.sha256(raw).hexdigest() == pin['sha256'], 'DATA_PIN_MISMATCH')
    return raw


def _text(value, code='TEXT'):
    need(isinstance(value, str) and '\0' not in value, code)
    try:
        value.encode('utf8')
    except UnicodeEncodeError as exc:
        raise ValueError('CACHE_METADATA_INVALID_UNICODE') from exc
    return value


def encode_attributes(attributes, *, document_map=None):
    """Encode native regular QString values and retain exact ordered types.

    List-valued/enumeration S57 attributes remain their original UTF8 lexical
    string or unsigned scalar; no CSV expansion, enum guessing or unit change.
    Duplicate native QMap keys cannot be losslessly represented and are refused.
    """
    need(isinstance(attributes, list) and len(attributes) <= 65535, 'ATTRIBUTE_COUNT')
    records, ledger, seen = [], [], set()
    for ordinal, item in enumerate(attributes):
        code, kind, value = item['code'], item['type'], item['value']
        need(type(code) is int and 0 <= code <= 65535, 'ATTRIBUTE_CODE_U16')
        need(code not in seen, 'DUPLICATE_ATTRIBUTE_CODE')
        seen.add(code)
        if kind == 'uint32' and item['wireType'] == 0:
            need(type(value) is int and 0 <= value <= 0xffffffff, 'ATTRIBUTE_UINT32')
            lexical = str(value)
        elif kind == 'float64' and item['wireType'] == 2:
            need(type(value) in (int, float) and math.isfinite(value), 'ATTRIBUTE_FLOAT64')
            lexical = repr(float(value))
            need(struct.pack('<d', float(lexical)) == struct.pack('<d', float(value)), 'FLOAT_ROUNDTRIP')
        elif kind == 'utf8' and item['wireType'] == 4:
            lexical = _text(value)
        else:
            raise ValueError('CACHE_METADATA_UNSUPPORTED_ATTRIBUTE_TYPE')
        document_reference = None
        if item.get('acronym') in ('TXTDSC', 'NTXTDS') and lexical and document_map is not None:
            need(kind == 'utf8' and lexical in document_map, 'UNRESOLVED_DOCUMENT_REFERENCE')
            document_reference = dict(sourceName=lexical, targetName=document_map[lexical])
            lexical = document_reference['targetName']
        _text(lexical)
        records.append(struct.pack('<H', code) + lexical.encode('utf8') + b'\0')
        row = dict(sourceOrdinal=ordinal, **item, targetLexical=lexical)
        if document_reference is not None:
            row['documentReference'] = document_reference
        ledger.append(row)
    # No supplied IR FOID/national/FFPT fields exist; do not invent them.
    return bytes(8) + struct.pack('<H', len(records)) + b''.join(records) + bytes(4), ledger


def decode_attributes(raw, ledger):
    """Read the encoded values back, restoring source types from the ledger."""
    need(type(raw) is bytes and len(raw) >= 14 and raw[:8] == bytes(8), 'ADDITIONAL_HEADER')
    count = struct.unpack_from('<H', raw, 8)[0]
    need(count == len(ledger), 'ATTRIBUTE_LEDGER_COUNT')
    pos, recovered = 10, []
    for item in ledger:
        need(pos + 2 <= len(raw), 'ADDITIONAL_RANGE')
        code = struct.unpack_from('<H', raw, pos)[0]
        pos += 2
        end = raw.find(b'\0', pos)
        need(end >= pos, 'ATTRIBUTE_NUL')
        text = raw[pos:end].decode('utf8')
        need(code == item['code'] and text == item['targetLexical'], 'ATTRIBUTE_LEDGER_VALUE')
        value = int(text) if item['type'] == 'uint32' else float(text) if item['type'] == 'float64' else text
        if 'documentReference' in item:
            need(item['type'] == 'utf8' and text == item['documentReference']['targetName'], 'DOCUMENT_LEDGER_JOIN')
            value = item['documentReference']['sourceName']
        need(value == item['value'] and (item['type'] != 'float64' or
             struct.pack('<d', value) == struct.pack('<d', float(item['value']))), 'SOURCE_VALUE_ROUNDTRIP')
        recovered.append({k: v for k, v in item.items() if k not in ('sourceOrdinal', 'targetLexical', 'documentReference')})
        pos = end + 1
    need(raw[pos:] == bytes(4), 'NATIONAL_RELATION_CONVENTION')
    return recovered


def _context(context):
    cells, owners = context['cells'], context['owners']
    need(cells and len({c['id'] for c in cells}) == len(cells), 'UNIQUE_CELLS')
    expected = []
    for ci, cell in enumerate(cells):
        seen = set()
        for oi, feature in enumerate(cell['features']):
            fid = feature['sourceFeatureId']
            need(type(fid) is int and 0 <= fid <= 65535 and fid not in seen, 'UNIQUE_SOURCE_FEATURE_ID')
            seen.add(fid)
            expected.append((cell['id'], ci, oi, ((ci + 1) << 32) | fid, feature))
    need(len(expected) == len(owners), 'OWNER_BIJECTION')
    for owner, values in zip(owners, expected):
        need(tuple(owner[k] for k in ('cellId', 'cellOrdinal', 'sourceOwnerOrdinal', 'uid', 'feature')) == values,
             'OWNER_ORDER_UID_IR_BIJECTION')
    need(set(context['by_cell']) == {c['id'] for c in cells}, 'BY_CELL_BIJECTION')
    for cell in cells:
        need(context['by_cell'][cell['id']] == [o for o in owners if o['cellId'] == cell['id']], 'BY_CELL_OWNER_ORDER')
    return cells, owners


def prepare_metadata(context, output_dir, config):
    cells, owners = _context(context)
    dictionaries, names, attr_names, class_names = {}, [''], {}, {}
    def intern(text):
        _text(text)
        if text not in names:
            names.append(text)
        return names.index(text)
    documents, document_maps, document_names = [], {}, []
    for ci, cell in enumerate(cells):
        mapping = {}
        for doc in cell['textDocuments']:
            original = _text(doc['name'], 'DOCUMENT_NAME')
            path = PurePosixPath(original)
            need(original and not path.is_absolute() and '..' not in path.parts and '\\' not in original,
                 'DOCUMENT_NAME_PATH')
            need(original not in mapping, 'DUPLICATE_DOCUMENT_NAME')
            # Separate same-named documents from different cells, even if equal.
            name = f'cell-{ci + 1:08d}/' + original
            mapping[original] = name
            content = _text(doc['content'], 'DOCUMENT_CONTENT').encode('utf8')
            ordinal = len(documents)
            need(ordinal < 65536, 'DOCUMENT_ID_U16')
            documents.append(dict(cellId=cell['id'], sourceName=original, name=name,
                                  sourceReference=doc['sourceReference'], ordinal=ordinal,
                                  content=content))
            document_names.append(name)
        document_maps[cell['id']] = mapping
    cell_outputs, typed, source_rows = {}, [], []
    for cell in cells:
        cid = cell['id']
        local_names, local_map = [''], {0: 0}
        additional, offsets, dis = bytearray(), {}, {}
        for owner in context['by_cell'][cid]:
            feature, uid = owner['feature'], owner['uid']
            class_name, class_code = feature['classAcronym'], feature['classCode']
            need(class_name is not None, 'MISSING_CLASS_CATALOG_NAME')
            need(type(class_code) is int and 0 <= class_code <= 65535, 'CLASS_CODE_U16')
            need(class_name and not any(c in class_name for c in '\t\r\n'), 'CLASS_CATALOG_TOKEN')
            need(class_code not in class_names or class_names[class_code] == class_name, 'CLASS_CATALOG_CONFLICT')
            class_names[class_code] = _text(class_name)
            if class_name not in local_names:
                local_names.append(class_name)
            di = local_names.index(class_name)
            local_map[di] = intern(class_name)
            dis[uid] = di
            raw, ledger = encode_attributes(feature['attributes'], document_map=document_maps[cid])
            need(decode_attributes(raw, ledger) == feature['attributes'], 'ALL_TYPED_ATTRIBUTES_EXACT')
            for attr in feature['attributes']:
                acronym = attr['acronym']
                need(acronym is not None, 'MISSING_ATTRIBUTE_CATALOG_NAME')
                need(acronym and not any(c in acronym for c in '\t\r\n'), 'ATTRIBUTE_CATALOG_TOKEN')
                need(attr['code'] not in attr_names or attr_names[attr['code']] == acronym, 'ATTRIBUTE_CATALOG_CONFLICT')
                attr_names[attr['code']] = _text(acronym)
                intern(acronym)
                if acronym in ('TXTDSC', 'NTXTDS') and attr['value']:
                    need(attr['type'] == 'utf8' and attr['value'] in document_maps[cid], 'UNRESOLVED_DOCUMENT_REFERENCE')
            offsets[uid] = len(additional)
            additional.extend(raw)
            typed.append(dict(cellId=cid, uid=uid, sourceId=feature['sourceId'], sourceRecord=feature['sourceRecord'],
                              additionalOffset=offsets[uid], additionalSHA256=hashlib.sha256(raw).hexdigest(),
                              attributes=ledger, displayScale=feature['displayScale']))
        dictionaries[cid] = {str(k): v for k, v in local_map.items()}
        pin = write_bytes(output_dir, f'metadata/cell-{len(cell_outputs):08d}-additional.bin', bytes(additional))
        cell_outputs[cid] = dict(additional=bytes(additional), additionalOffsets=offsets,
                                 dictionaryIndices=dis, originalAdditional=pin)
        source_rows.append({k: cell[k] for k in ('id', 'name', 'source', 'nativeScale', 'datums', 'units', 'bbox')})
    attrmap = ''.join(f'{code}\tOWN\t{name}\n' for code, name in sorted(attr_names.items())).encode('utf8')
    classmap = ''.join(f'{code}\tOWN\t{name}\n' for code, name in sorted(class_names.items())).encode('utf8')
    resources = dict(attributeMap=write_bytes(output_dir, 'metadata/attribute-map.txt', attrmap),
                     classMap=write_bytes(output_dir, 'metadata/class-map.txt', classmap))
    ledger = write_json(output_dir, 'metadata/typed-source-ledger.json', dict(
        format='no5-cache-country-typed-metadata/1', cells=source_rows, owners=typed,
        identityConvention='Own zero Additional header8; source FOID/RCID/GRUP unavailable in IR.',
        relationConvention='Empty native national/FFPT maps; no claim of source absence.',
        sourceTypesRecoveredBySupplementalLedger=True, nativeQueryStoresQStringOnly=True))
    original_document_maps = document_maps
    document_maps = {cid: {name: name for name in mapping.values()} for cid, mapping in original_document_maps.items()}
    return dict(cells=cell_outputs, dictionaryMaps=dictionaries, documentMaps=document_maps,
                originalDocumentMaps=original_document_maps,
                dictionary=names, documents=documents, documentNames=document_names,
                attributeNames=attr_names, classNames=class_names, resourcePins=resources, ledger=ledger, receipt=ledger)


def _date(raw):
    need(isinstance(raw, str) and len(raw) in (8, 10), 'SOURCE_DATE')
    try:
        return date.fromisoformat(raw if '-' in raw else raw[:4] + '-' + raw[4:6] + '-' + raw[6:8]).isoformat()
    except ValueError as exc:
        raise ValueError('CACHE_METADATA_SOURCE_DATE') from exc


def _frame(additional_offset, entries):
    need(len(entries) <= 65535, 'RENDER_COUNT')
    return struct.pack('<HI', len(entries), additional_offset) + b''.join(
        struct.pack('<IH', key, len(value)) + value for key, value in entries.items())


def finalize_metadata(context, metadata, geometry, presentation, output_dir, config):
    """Assemble only fresh global metadata/resources; no cartography is copied."""
    cells, _ = _context(context)
    profile = config['_presentationProfile']
    source_policy = profile.get('sourceInfo', {})
    need(not (set(source_policy) - {'level', 'minimumLevel'}), 'UNSUPPORTED_SOURCEINFO_PARAMETER')
    for value in source_policy.values():
        need(type(value) is int and -1 <= value <= 29, 'EXPLICIT_SOURCEINFO_NATIVE_LEVEL')
    coverage = geometry['coverage']
    need(len(coverage) == len(cells) and {c['cellId'] for c in coverage} == {c['id'] for c in cells}, 'COVERAGE_BIJECTION')
    by_id = {row['cellId']: row for row in coverage}
    source_blocks, coords, indices, render, additional, features = [], bytearray(), bytearray(), bytearray(), bytearray(), bytearray()
    source_ledger = []
    for i, cell in enumerate(cells):
        cid, row = cell['id'], by_id[cell['id']]
        need(row['nativeScale'] == cell['nativeScale'], 'SOURCE_NATIVE_SCALE_EXACT')
        need(not cell['bbox']['crossesAntimeridian'], 'UNSUPPORTED_ANTIMERIDIAN')
        # Identity binds the exact fresh source generation, not a historical cell list.
        source_uuid = uuid.uuid5(NAMESPACE, cid + ':' + cell['source']['sha256'])
        root = ET.Element('rx5', uuid=str(source_uuid))
        info = ET.SubElement(root, 'source_chart_info', name=_text(cell['name']), id=_text(cid))
        ET.SubElement(info, 'edition', source=str(cell['source']['edition']), date=_date(cell['source']['publishDate']))
        if source_policy:
            attrs = {('minimum_level' if k == 'minimumLevel' else k): str(v) for k, v in source_policy.items()}
            ET.SubElement(root, 'render_target', **attrs)
        unit = cell['units']['soundingDepth']
        if unit['state'] == 'verified' and unit['value'] in ('m', 'metres', 'meters'):
            ET.SubElement(info, 'parameters', depth_units='metres')
        xml = ET.tostring(root, encoding='utf-8', xml_declaration=True)
        source_blocks.append(wire.AuthoredBlock('source:' + str(i), 0x30, 0, b'', xml,
                             'source:' + str(i + 1) if i + 1 < len(cells) else 'coverage'))
        index_at, render_at, add_at = len(indices), len(render), len(additional)
        words = []
        need(row['covered'], 'MISSING_AUTHENTIC_COVERAGE')
        for key, usage in (('covered', 18), ('excluded', 14)):
            for ring in row[key]:
                need(4 <= len(ring) < 1 << 24 and ring[0] == ring[-1], 'COVERAGE_CLOSED_RING')
                need(all(len(p) == 2 and all(type(v) is int and 0 <= v <= Q for v in p) for p in ring), 'COVERAGE_ROOT_U32')
                need(len(coords) <= 0xffffff, 'COVERAGE_LOW24')
                words.append(usage << 24 | len(coords))
                coords += struct.pack('<I', len(ring)) + b''.join(struct.pack('<II', *p) for p in ring)
        indices += struct.pack('<I', len(words)) + b''.join(struct.pack('<I', w) for w in words)
        lons = [p[0] for ring in cell['coverage']['covered'] for p in ring['coordinates']]
        need(lons and all(math.isfinite(v) for v in lons), 'COVERED_LONGITUDES')
        render += _frame(add_at, {0x20000: source_uuid.bytes_le,
                                 0x20012: struct.pack('<d', max(lons)), 0x20013: struct.pack('<d', min(lons))})
        additional += bytes(14)
        features += wire.FEATURE.pack(i + 1, index_at, render_at, 0, 0, 0, 8, 0, 0, 0)
        source_ledger.append(dict(cellId=cid, sourceUUID=str(source_uuid), sourceGeneration=cell['source'],
                                  nativeScale=cell['nativeScale'], nativeLevelPolicy=source_policy,
                                  coveredRings=len(row['covered']), excludedRings=len(row['excluded']),
                                  sourceLongitudeBounds=[min(lons), max(lons)]))
    source_blocks.append(wire.AuthoredBlock('coverage', 0x34, 2, bytes(32), bytes(features), references=tuple(
        (8 * i, name) for i, name in enumerate(('coverage:ix', 'coverage:co', 'coverage:ro', 'coverage:ao')))))
    for bid, name, raw in ((0x1200, 'ix', indices), (0x1201, 'co', coords), (0x1202, 'ro', render), (0x1203, 'ao', additional)):
        source_blocks.append(wire.AuthoredBlock('coverage:' + name, bid, 0, b'', bytes(raw)))
    # Resources are opt-in byte-pinned reusable data, never an old chart parent.
    resources = {}
    base = config['_presentationBaseDirectory']
    for item in presentation['resources']:
        bid = item['id']
        need(type(bid) is int and bid in (0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 1075), 'RESOURCE_ID')
        need(bid not in resources, 'DUPLICATE_RESOURCE_ID')
        resources[bid] = read_pin(item['data'], base)
    resources[0x42] = read_pin(metadata['resourcePins']['attributeMap'])
    resources[0x43] = read_pin(metadata['resourcePins']['classMap'])
    name_to_index = {name: i for i, name in enumerate(metadata['dictionary'])}
    for bid, names in ((0x47, metadata['attributeNames']), (0x48, metadata['classNames'])):
        resources[bid] = b''.join(struct.pack('<II', code, name_to_index[name]) for code, name in sorted(names.items()))
    need(0x41 in resources, 'EXPLICIT_COMMON41_REQUIRED')
    resource_rows = []
    for i, (bid, raw) in enumerate(sorted(resources.items())):
        name = 'resource:' + str(bid)
        next_name = 'resource:' + str(sorted(resources)[i + 1]) if i + 1 < len(resources) else None
        source_blocks.append(wire.AuthoredBlock(name, bid, 0, b'', raw, next_name))
        resource_rows.append(dict(id=bid, data=write_bytes(output_dir, f'metadata/resource-{bid}.bin', raw)))
    prefix = bytes(22) + struct.pack('<H', 1)  # Own named-dictionary layout, not publisher identity.
    source_blocks.append(wire.AuthoredBlock('dictionary', 8, 16, prefix, wire.encode_dictionary(metadata['dictionary'])))
    docs = metadata['documents']
    source_blocks.append(wire.AuthoredBlock('documents', 7, 16, prefix, wire.encode_dictionary(metadata['documentNames']),
                                           'document:0' if docs else None))
    doc_receipts = []
    for i, doc in enumerate(docs):
        source_blocks.append(wire.AuthoredBlock('document:' + str(i), i, 64, b'', doc['content'],
                                               'document:' + str(i + 1) if i + 1 < len(docs) else None))
        doc_receipts.append({k: v for k, v in doc.items() if k != 'content'} | dict(bytes=len(doc['content']), sha256=hashlib.sha256(doc['content']).hexdigest()))
    identity = uuid.uuid5(NAMESPACE, hashlib.sha256(canonical(context['package']) + canonical(profile)).hexdigest())
    multiplier = presentation['nativeScaminMultiplierU32']
    need(type(multiplier) is int and 0 <= multiplier <= 0xffffffff, 'EXPLICIT_SCAMIN_MULTIPLIER_U32')
    header = new_plain_vector_header(identity.bytes_le, native_scamin_multiplier=multiplier)
    chart_info = ET.Element('rx5')
    ET.SubElement(chart_info, 'chart_info', name=_text(config.get('title', context['package']['source']['product'])))
    source_blocks += [wire.AuthoredBlock('info', 2, 0, b'', ET.tostring(chart_info, encoding='utf8')),
                      wire.AuthoredBlock('root', 0x6000, 1, bytes(32), b'', references=tuple((8 * i, None) for i in range(4)))]
    raw, _ = wire.assemble(bytes(header), source_blocks, {136: 'info', 144: 'source:0', 160: 'root',
                            168: 'dictionary', 184: 'resource:' + str(min(resources)), 200: 'documents'})
    parsed = wire.blocks(raw)
    need(wire.dictionary(parsed[struct.unpack_from('<Q', raw, 168)[0]].payload) == metadata['dictionary'], 'FRESH_DICTIONARY_READBACK')
    parent = write_bytes(output_dir, 'metadata/presentation-parent.rx5', raw)
    composition = write_json(output_dir, 'metadata/composition.json', dict(cells=[c['id'] for c in cells], chart=parent,
        dictionaryMaps=metadata['dictionaryMaps'], documentMaps=metadata['documentMaps'], documents=doc_receipts))
    container = write_json(output_dir, 'metadata/container-inputs.json', dict(presentation=resource_rows))
    receipt = write_json(output_dir, 'metadata/metadata-receipt.json', dict(format='no5-cache-country-metadata/1',
        typedLedger=metadata['ledger'], sources=source_ledger, resources=resource_rows, documents=doc_receipts,
        parent=parent, dictionaryNames=len(metadata['dictionary']), sourceTypesSupplementOnly=True,
        nativeScaleToLevelMapping=False, sourceSCAMINToWord20Mapping=False, originalChartParentUsed=False,
        nativeCalls=0, sourceLicensedDataAddedToCode=False))
    pins = dict(composition=composition, containerInputs=container, presentationParent=parent)
    return dict(**pins, pins=pins, metadataReceipt=receipt, receipt=receipt)
