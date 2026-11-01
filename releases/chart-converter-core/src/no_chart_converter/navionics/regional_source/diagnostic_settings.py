"""Pure own retained-IR authoring closure; extracted functions remain source-exact.
No decoder, filesystem, CLI, network, Native or Original execution entry point.
"""
from collections import Counter

def _request_vector_reference(request):
    if 'vectorErrorReference' not in request:
        return None
    value = request['vectorErrorReference']
    if type(value) is not str or value != 'fractional_world_f64':
        raise ValueError('EXACT_OPTIONAL_FRACTIONAL_WORLD_F64_REFERENCE_REQUIRED')
    return value

def diagnostic_settings(cell, *, date_text, semantic, generic, attribute_rows,
                        vector_error_reference=None):
    """Declare observed premises and enumerate failures; never silently drop."""
    if vector_error_reference is not None:
        _request_vector_reference({'vectorErrorReference': vector_error_reference})
    observed = {a.get('acronym') for f in cell['features'] for a in f['attributes']}
    names = {row['acronym']: row for row in attribute_rows.values()}
    numeric = {name: dict(declared_native_units=names[name]['defaultUnitsRaw'],
        maximum_absolute_native_decode_error=0.5 / (abs(names[name]['factorOrEnumStart']) or 1))
        for name in sorted(n for n in observed if isinstance(n, str))
        if name in names and names[name]['storage'] in ('M', 'J', 'H', 'K', 'O')}
    codecs = {name: 'ascii' for name in sorted(n for n in observed if isinstance(n, str))
        if name in names and names[name]['storage'] in ('A', 'S')}
    profile = generic.profile(numeric_premises=numeric, byte_text_codecs=codecs)
    generic.validate_profile(profile)
    # This is the same helper used by _atoms, not a second class translation.
    mappings = semantic._mapping_registry({'attribute_encoding_profile': profile})
    failures = Counter()
    allowed = set()
    for feature in cell['features']:
        mapping = mappings.get(feature.get('classAcronym'))
        if mapping is None or feature['geometry']['kind'] not in mapping[2]:
            continue
        counts = Counter(a.get('acronym') for a in feature['attributes'])
        for attribute in feature['attributes']:
            name = attribute.get('acronym')
            if name == 'catgeo':
                continue
            try:
                if counts[name] != 1:
                    raise ValueError('DUPLICATE_SOURCE_ATTRIBUTE_ACRONYM')
                generic.encode_source_attribute(attribute, mapping[1], profile)
            except (ValueError, ArithmeticError, UnicodeError) as error:
                failures[(str(name), str(error))] += 1
                if isinstance(name, str):
                    allowed.add(name)
    settings = semantic.config(inferred_depth_unit_policy='assume_metres_and_report',
        date_text_1=date_text, date_text_2=date_text,
        allowed_unencoded_attribute_acronyms=sorted(allowed), declared_native_units=2,
        vector_projection_stage='pre_f32_f64', attribute_encoding_profile=profile,
        grid_candidates=[1024, 4096, 16384, 65536], header_flags=4660,
        maps_per_atlas=16, max_depth_error_metres=0.05, max_features_per_layer=63,
        max_map_bytes=65536, max_map_count=1024, max_map_span_source_units=1000000,
        max_metric_error_source_units=2, max_total_map_bytes=67108864,
        **({'vector_error_reference': vector_error_reference}
            if vector_error_reference is not None else {}))
    preflight = dict(format='no5-full-cache-explicit-diagnostic-premises/1',
        explicitDiagnosticAllowedNames=sorted(allowed),
        sourceNumericUnits='UNKNOWN_IN_IR_EXPLICIT_CALLER_PREMISES_ONLY',
        byteCharsets='EXPLICIT_ASCII_PREMISE_NOT_VERIFIED_NATIVE_CHARSET',
        numericErrorPolicy='Half raw input quantum; Decimal*factor exactly integer; no source rounding. Actual native F32 error is retained. Own policy, not a format limit.',
        negativeH='REFUSED_NO_UNSIGNED_WRAP',
        SCAMIN='UNENCODED_NO_DENOMINATOR_TO_NATIVE_LEVEL_INVENTED',
        attributeLossPolicy='Explicit observed acronym allowlist; every omitted occurrence retained and affected emitted feature PARTIAL.',
        failures=[dict(acronym=name, reason=reason, occurrences=count)
            for (name, reason), count in sorted(failures.items())])
    return settings, preflight
