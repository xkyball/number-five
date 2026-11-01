"""Copy saved genuine source presentations, never invent a CS or colour rule.

Inert stdlib module. The caller authenticates source/owner pins and separately
declares which runtime context slots receive the saved source presentation.
This is not a claim that PaperChart getTableItem chose a Simplified source row.
"""

import hashlib, json, re

CATEGORIES = {'DISPLAYBASE': 0, 'STANDARD': 1, 'OTHER': 2}

SOURCE_FAMILIES = {76: ('P', 'SIMPLIFIED'), 78: ('A', 'PLAIN_BOUNDARIES'), 83: ('L', 'LINES')}

KINDS = {1: ('P', 0), 2: ('L', 1), 3: ('A', 2)}

CANONICAL_OPS = frozenset(('AC', 'AP', 'LS', 'LC', 'SY', 'TX', 'TE'))

class Refusal(ValueError): pass

def need(ok, code):
    if not ok: raise Refusal(code)

def sha(raw): return hashlib.sha256(raw).hexdigest()

def canonical_json(x): return json.dumps(x, separators=(',', ':'), sort_keys=True, ensure_ascii=False, allow_nan=False).encode()

def digits(raw):
    m = re.match(rb'[0-9]+', raw); need(m is not None, 'ORIGINAL_DIGIT_PREFIX')
    return raw[m.end():], raw[:m.end()]

def inspect_instruction(text):
    """Conservative syntax guard; it preserves exact input, never repairs it.

Original whole draw splits semicolons, including any inside a source literal.
CA/MP still need their explicitly bound original helper implementation.
"""
    need(type(text) is str and len(text.encode('ascii')) <= 65535, 'BOUNDED_ASCII_INST')
    if not text: return dict(status='no_drawing_command', reason='GENUINE_SOURCE_EMPTY_INST', commands=[])
    if any(ord(c) < 32 for c in text):
        return dict(status='unresolved', reason='ORIGINAL_CONTROL_BYTE_NO_PROVED_CANONICALIZATION', commands=[])
    commands = []
    for token in text.split(';'):
        if not token: continue  # native QString::SkipEmptyParts
        m = re.match(r'^([A-Z]{2})\(', token)
        if m is None: return dict(status='unresolved', reason='ORIGINAL_OPCODE_SYNTAX', commands=commands)
        op = m[1]
        if op == 'CS': return dict(status='unresolved', reason='GENUINE_CONDITIONAL_RESULT_REQUIRED', commands=commands)
        if op not in CANONICAL_OPS:
            return dict(status='unresolved', reason='ORIGINAL_' + op + '_HELPER_REQUIRED', commands=commands,
                exactUnsupportedToken=token, originalTokenClosingParenthesisPresent=token.endswith(')'))
        if not token.endswith(')'):
            return dict(status='unresolved', reason='ORIGINAL_COMMAND_UNCLOSED', commands=commands)
        quoted = False; balance = 0
        for c in token[2:]:
            if c == "'": quoted = not quoted
            elif not quoted and c == '(': balance += 1
            elif not quoted and c == ')':
                balance -= 1
                if balance < 0: return dict(status='unresolved', reason='ORIGINAL_PARENTHESIS_IMBALANCE', commands=commands)
        if quoted or balance != 0:
            return dict(status='unresolved', reason='ORIGINAL_QUOTE_OR_PARENTHESIS_IMBALANCE', commands=commands)
        commands.append(dict(opcode=op, exactToken=token))
    need(commands, 'NONEMPTY_COMMANDS')
    return dict(status='source_commands_ready', commands=commands, nativeSelectionExecuted=False, nativePaintingProven=False)

def parse_effective_tables(common41, registry):
    """Bind every registered effective source style to exact Common41 bytes.

Unregistered SOUNDG expansion rows are deliberately not adopted from this
725-style registry. They have their own original per-point helper lineage.
"""
    need(type(common41) is bytes and len(common41) <= 1 << 20, 'BOUNDED_COMMON41')
    lines = common41.split(b'\n'); need(lines[-1] == b'', 'COMMON41_LF_END'); lines.pop()
    need(len(lines) % 4 == 0, 'FOUR_LINE_FRAMES')
    reg = {r['ownEffectiveStyleID']: r for r in registry}
    need(len(reg) == len(registry), 'DISTINCT_REGISTERED_STYLES')
    rows = []; seen = set(); separate = 0
    for i in range(0, len(lines), 4):
        frame = lines[i:i+4]
        if not frame[0].startswith(b'LUPT  '): continue
        need([x[:6] for x in frame] == [b'LUPT  ', b'ATTC  ', b'INST  ', b'DISC  '], 'LUPT_FRAME_ORDER')
        head, _ = digits(frame[0][6:]); condition, _ = digits(frame[1][6:])
        match = re.fullmatch(rb'NXSTYL([0-9]+)', condition)
        if match is None: continue
        sid = int(match[1])
        if sid not in reg: separate += 1; continue
        need(sid not in seen, 'UNIQUE_REGISTERED_COMMON_ROW'); seen.add(sid)
        need(head[:2] == b'LU' and head[7:10] == b'NIL', 'CURRENT_COMMON_LUPT_LAYOUT')
        inst, prefix = digits(frame[2][6:]); category, _ = digits(frame[3][6:])
        name = head[10:16].decode('ascii'); geometry = head[16:17].decode('ascii'); family = head[23:].decode('ascii')
        original_category = category.decode('ascii'); need(original_category in CATEGORIES, 'ORIGINAL_CATEGORY')
        source = reg[sid]; source_table = source['sourceTable']; need(source_table in SOURCE_FAMILIES, 'DECLARED_SOURCE_FAMILY')
        need(source['classAcronym'] == name and bytes.fromhex(source['effectiveCandidateINSTHex']) == inst, 'EXACT_SOURCE_COMMON_INST')
        need((geometry, family) == SOURCE_FAMILIES[source_table], 'ORIGINAL_SOURCE_FAMILY_GEOMETRY')
        priority = int(head[17:22])
        need(source['actualAfterPriority'] == priority and source['actualAfterCategory'] == ord({'DISPLAYBASE':'D','STANDARD':'S','OTHER':'O'}[original_category]), 'SOURCE_AFTER_STATE')
        text = inst.decode('ascii')
        rows.append(dict(effectiveStyleID=sid, nxStyle=match[1].decode('ascii'), className=name, geometry=geometry,
            sourcePrimitive=source['sourcePrimitive'], originalSourceFamily=family, sourceRCID=source['sourceRCID'], sourceTable=source_table,
            ownNativeTableId=int(head[2:7]), displayPriority=priority, nativeDisplayCategory=CATEGORIES[original_category],
            originalDisplayCategory=original_category, effectiveSourceKeySHA256=source['keySHA256'], canonicalInstruction=text,
            originalStoredINST=frame[2][6:].decode('ascii'), originalNumericPrefix=prefix.decode('ascii'),
            originalCommonOrdinal=i//4, originalFourLineSHA256=sha(b'\n'.join(frame)+b'\n'),
            instructionDisposition=inspect_instruction(text), sourceConditionalExpansionExecutedByThisModule=False))
    need(seen == set(reg), 'ALL_REGISTERED_EFFECTIVE_STYLES_HAVE_EXACT_COMMON_ROW')
    return dict(rows=rows, registeredSourceStyles=len(rows), separateUnregisteredNXRows=separate,
        sourcePointTableFamily='SIMPLIFIED', paperChartSourceStyleRows=0,
        separateSOUNDGHelperRequired=True, nativeGetTableItemExecuted=False)

def attribute_rows_sha256(attributes):
    rows = list(attributes.items()) if type(attributes) is dict else list(attributes)
    typed = [dict(keyType=type(k).__name__, key=k, valueType=type(v).__name__, value=v) for k,v in rows]
    return sha(json.dumps(typed, separators=(',', ':'), ensure_ascii=False, allow_nan=False).encode())

def nxstyle(attributes):
    rows = list(attributes.items()) if type(attributes) is dict else attributes
    need(type(rows) in (list, tuple), 'SERIALIZED_ATTRIBUTE_ROWS')
    values = [v for k,v in rows if k in (65000, '65000', 'NXSTYL')]
    need(len(values) <= 1, 'NO_DUPLICATE_NXSTYL')
    if not values: return None
    value = str(values[0]) if type(values[0]) is int else values[0]
    need(type(value) is str and re.fullmatch(r'[0-9]+', value) is not None, 'UNSIGNED_SOURCE_NX')
    return value

def selected(tables, class_name, kind, style, owner_binding):
    if kind == 8:
        return dict(status='unresolved', reason='ORIGINAL_KIND8_S52_GEOMETRY7_NOT_AREA2', kind=8,
            className=class_name, s52LookupGeometry=7, instruction=None, ownerBinding=owner_binding, newDrawingPacketsAllowed=False)
    if kind not in KINDS:
        return dict(status='unresolved', reason='OTHER_NATIVE_KIND_REQUIRES_ORIGINAL_HELPER', kind=kind,
            className=class_name, instruction=None, ownerBinding=owner_binding)
    rows = [r for r in tables['rows'] if r['nxStyle']==style and r['className']==class_name and
            (r['geometry'],r['sourcePrimitive'])==KINDS[kind]]
    if not rows:
        return dict(status='unresolved', reason='NO_REGISTERED_EXACT_CLASS_KIND_NX_ROW', kind=kind,
            className=class_name, actualNXSTYL=style, instruction=None, ownerBinding=owner_binding)
    need(len(rows)==1, 'EXACT_ONE_SOURCE_PRESENTATION')
    row=rows[0]; disposition=row['instructionDisposition']
    result=dict(className=class_name,kind=kind,selected=dict(row),ownerBinding=owner_binding,
        sourceRCID=row['sourceRCID'],displayPriority=row['displayPriority'],nativeDisplayCategory=row['nativeDisplayCategory'],
        originalSourceFamily=row['originalSourceFamily'],actualNXSTYL=style,nativeGetTableItemExecuted=False,
        sourceCSExecution=False,nativePaintingProven=False,sourceColorGuessed=False,originalPaperChartLookupClaim=False)
    if disposition['status']=='source_commands_ready':
        result.update(status='resolved_saved_source_presentation',instruction=row['canonicalInstruction'])
    else: result.update(status=disposition['status'],reason=disposition['reason'],instruction=None)
    return result

def resolve_actual_source_style(tables, *, class_name, kind, attributes, owner_binding):
    need(type(owner_binding) is dict and bool(owner_binding.get('sourceId')) and bool(owner_binding.get('record32SHA256')) and
        bool(owner_binding.get('additionalFrameSHA256')) and owner_binding.get('attributeRowsSHA256')==attribute_rows_sha256(attributes),
        'AUTHENTIC_ACTUAL_RECORD_QUERY_ATTRIBUTES_REQUIRED')
    style=nxstyle(attributes)
    if style is None: return dict(status='unresolved',reason='NO_ACTUAL_NXSTYL_NO_STYLE_HEURISTIC',kind=kind,className=class_name,instruction=None)
    return selected(tables,class_name,kind,style,owner_binding)

def resolve_saved_source_style(tables, *, class_name, kind, source_style_receipt, owner_binding):
    need(type(source_style_receipt) is dict and type(owner_binding) is dict,'SAVED_SOURCE_RECEIPT')
    s=source_style_receipt
    need(s['sourceId']==owner_binding['sourceId'] and s['canonicalSourceOwnerOrdinal']==owner_binding['sourceOwnerOrdinal'], 'SOURCE_OWNER_IDENTITY')
    need(owner_binding.get('sourceStyleReceiptSHA256')==sha(json.dumps(s,separators=(',',':'),sort_keys=True).encode()), 'SAVED_RECEIPT_DIGEST')
    result=selected(tables,class_name,kind,str(s['ownEffectiveStyleID']),owner_binding)
    if 'selected' not in result: return result
    row=result['selected']
    need(row['effectiveSourceKeySHA256']==s['effectiveStyleKeySHA256'] and row['displayPriority']==s['actualFinalState']['priority'] and
        ord({'DISPLAYBASE':'D','STANDARD':'S','OTHER':'O'}[row['originalDisplayCategory']])==s['actualFinalState']['category'],'SAVED_SOURCE_FINAL_KEY_STATE')
    for cs in s['actualCS']:
        need(cs['actualReturnWasNULL'] is False and bool(cs['actualRecordSHA256']) and bool(cs['originalOutputHex']), 'GENUINE_SAVED_CS_NON_NULL_OUTPUT')
    result.update(actualNXSTYLClaim=False,savedSourceFinalAttrsSHA256=s['actualFinalAttrsSHA256'],savedSourceCS=s['actualCS'])
    return result

def render_packets(resolution, *, context_keys, policy_binding):
    """Explicit own copy policy, distinct from original TableItem mode choice.

Each key must be independently declared by the author. Root's ground compiler
already copies exact ground presentations to both context keys. Other classes
are not silently given a missing PaperChart lookup or a fabricated fallback.
"""
    need(resolution['status']=='resolved_saved_source_presentation' and resolution['kind'] in KINDS,'ONLY_READY_ORIGINAL_SOURCE_PRESENTATION')
    need(type(context_keys) in (tuple,list) and context_keys and len(set(context_keys))==len(context_keys) and
        all(type(k) is int and k in (0x30000,0x30001) for k in context_keys),'EXPLICIT_CONTEXT_KEYS')
    need(type(policy_binding) is dict and policy_binding.get('policy')=='COPY_EXACT_SAVED_SOURCE_PRESENTATION_TO_DECLARED_CONTEXTS' and
        policy_binding.get('contextKeys')==list(context_keys) and bool(policy_binding.get('policyInputSHA256')) and
        policy_binding.get('originalPaperChartLookupClaim') is False,'AUTHORED_COPY_POLICY_REQUIRED')
    raw=resolution['instruction'].encode('ascii')
    need(inspect_instruction(resolution['instruction'])['status']=='source_commands_ready','EXACT_CANONICAL_READY_INSTRUCTION')
    return {k:raw for k in context_keys}
