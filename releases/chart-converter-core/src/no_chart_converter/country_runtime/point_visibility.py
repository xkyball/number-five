"""Explicit own point/SOUNDG SCAMIN policy, distinct from native wire20/216."""

import re

import struct

from . import source_winner as sw

POLICY = 'own-source-parity-point-soundg-scamin-with-conservative-base-guard/1'

def common_point_profiles(text):
    """Bind conservatively to every matching original Common41 point LUP.

    No lookup choice or native PAPER/SIMPLIFIED context is inferred. Any base
    category, priority1 or unresolved conditional procedure keeps the point.
    SOUNDG is explicitly handled by the authorized own SCAMIN compiler policy.
    """
    profiles = {}
    blocks = re.split(r'(?=^LUPT )', text, flags=re.M)
    for block in blocks:
        match = re.match(r'LUPT +\d+LU(\d+)NIL(.{6})([PLA])(\d{5})([A-Z])([^\r\n]+)', block)
        if match is None or match[3] != 'P':
            continue
        cat = re.search(r'^DISC +\d+([^\r\n]+)', block, re.M)
        inst = re.search(r'^INST +\d+([^\r\n]*)', block, re.M)
        sw.need(cat is not None and inst is not None, 'COMPLETE_SOURCE_POINT_LUP')
        acronym = match[2]
        entry = dict(lupId=int(match[1]), priority=int(match[4]), family=match[6],
                     category=cat[1].strip(), conditionalProcedure='CS(' in inst[1])
        profiles.setdefault(acronym, []).append(entry)
    sw.need(bool(profiles), 'SOURCE_POINT_LUPS_REQUIRED')
    return profiles

def source_scamin_decision(*, kind, class_acronym, scamin, level, point_profiles):
    sw.need(kind in (1, 4), 'ONLY_POINT_SOUNDING_SCAMIN_POLICY')
    if kind == 4:
        sw.need(class_acronym == 'SOUNDG', 'GENUINE_KIND4_SOUNDG_CLASS_BINDING')
    else:
        candidates = point_profiles.get(class_acronym, ())
        if not candidates:
            return True, 'UNKNOWN_POINT_LUP_KEEP'
        if any(p['category'] == 'DISPLAYBASE' or p['priority'] == 1 for p in candidates):
            return True, 'ORIGINAL_LUP_BASE_OR_PRIORITY1_KEEP'
        if any(p['conditionalProcedure'] for p in candidates):
            return True, 'UNRESOLVED_POINT_CONDITIONAL_OR_SAFETY_KEEP'
    if scamin is None:
        return True, 'SOURCE_SCAMIN_MISSING_KEEP'
    if isinstance(scamin, bool):
        return True, 'SOURCE_SCAMIN_INVALID_KEEP'
    if isinstance(scamin, int):
        value = scamin
    elif isinstance(scamin, str) and re.fullmatch(r'[0-9]+', scamin):
        value = int(scamin)
    else:
        return True, 'SOURCE_SCAMIN_INVALID_KEEP'
    if value == 0:
        return True, 'SOURCE_SCAMIN_ZERO_UNSPECIFIED_KEEP'
    if not 0 < value <= 0xffffffff:
        return True, 'SOURCE_SCAMIN_INVALID_KEEP'
    d = sw.own_display_denominator(level)
    return (False, 'OWN_DISPLAY_DENOMINATOR_GREATER_THAN_SOURCE_SCAMIN') if d > value else (True, 'OWN_DISPLAY_DENOMINATOR_AT_OR_BELOW_SOURCE_SCAMIN')

def check_compact_coordinate_starts(coordinates, index_array, kind):
    """Require complete homogeneous owner elements and exact starts.

    Callers must compact typed per-owner elements first. A full mixed-stride
    chart coordinate stream is deliberately not accepted as this API's input.
    """
    sw.need(kind in (1, 4), 'HOMOGENEOUS_POINT_OWNER_KIND')
    stride = 12 if kind == 4 else 8
    pos = 0
    starts = set()
    while pos < len(coordinates):
        sw.need(pos + 4 <= len(coordinates), 'COMPLETE_ELEMENT_HEADER')
        count = struct.unpack_from('<I', coordinates, pos)[0] & 0xffffff
        sw.need(count > 0, 'NONEMPTY_TYPED_ELEMENT')
        starts.add(pos)
        pos += 4 + count * stride
        sw.need(pos <= len(coordinates), 'COMPLETE_HOMOGENEOUS_ELEMENT')
    sw.need(pos == len(coordinates), 'ELEMENT_STREAM_END')
    sw.need(len(index_array) >= 4, 'SPATIAL_INDEX_HEADER')
    n = struct.unpack_from('<I', index_array)[0]
    sw.need(len(index_array) == 4 + 4*n, 'SPATIAL_INDEX_ARRAY_END')
    sw.need(all((w & 0xffffff) in starts for w in struct.unpack_from('<'+'I'*n, index_array, 4)), 'REFERENCE_EXACT_ELEMENT_START')

def filter_visible_point_owner(coordinates, index_array, stored_record, *,
                               cell_id, class_acronym, source_scamin, level,
                               winner, source_tile, input_root, point_profiles):
    sw.need(len(stored_record) == 32, 'BOUND_STORED_RECORD')
    kind = stored_record[28]
    sw.need(cell_id in winner.by_id, 'BOUND_SOURCE_ID')
    sw.need(input_root in (0,1), 'DECLARED_ROOT_CONTEXT')
    sw.need(input_root == 1 or tuple(source_tile) == (0,0,0), 'GENUINE_L0_ONLY_PROMOTION')
    check_compact_coordinate_starts(coordinates, index_array, kind)
    pos = 0
    while pos < len(coordinates):
        h = struct.unpack_from('<I', coordinates, pos)[0]
        sw.need(((h >> 24) & 1) == input_root, 'UNIFORM_OWNER_ROOT_CONTEXT')
        pos += 4 + (h & 0xffffff) * (12 if kind == 4 else 8)
    eligible, reason = source_scamin_decision(kind=kind, class_acronym=class_acronym,
                                            scamin=source_scamin, level=level,
                                            point_profiles=point_profiles)
    if not eligible:
        return b'', bytes(4), None, dict(policy=POLICY, sourceCell=cell_id,
                                        classAcronym=class_acronym, destinationLevel=level,
                                        sourceSCAMIN=source_scamin, scaleDisposition=reason,
                                        originalUIDPreservedInFullQueryFooter=True,
                                        originalSourceQueryDataMustRemain=True,
                                        nativeWord20Changed=False, nativeHeader216Changed=False,
                                        retainedCoordinateSlots=0)
    co, ix, record, receipt = sw.filter_point_owner(coordinates,index_array,stored_record,
                                                  cell_id=cell_id,level=level,winner=winner,
                                                  source_tile=source_tile,input_root=input_root)
    receipt.update(policy=POLICY,positionWinnerPolicy=sw.POLICY,classAcronym=class_acronym,
                   sourceSCAMIN=source_scamin,scaleDisposition=reason,
                   nativeWord20Changed=False,nativeHeader216Changed=False)
    # This now applies the own Source133 denominator rule. The inner component
    # describes only point ownership and deliberately has no SCAMIN rule.
    receipt['sourceSCAMINApplied'] = reason.startswith('OWN_DISPLAY_')
    return co, ix, record, receipt
