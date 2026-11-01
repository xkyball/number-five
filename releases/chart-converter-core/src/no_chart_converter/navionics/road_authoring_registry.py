"""Opt-in own registry recipe for the native road consumer; no OEM mutation.

The source ROADWY→native roaedg relation is an explicit authoring policy.
It does not add a fake exact acronym or source code to the ordinary registry.
The default registry and enum domains stay unchanged.
"""
ROAD_AUTHORING_POLICY = 'source_roadwy_major_or_motorway_to_native_roaedg_v1'
ROAD_UNCATEGORIZED_POLICY = 'source_roadwy_line_without_catrod_to_native_roaedg_unclassified_v1'
ROAD_UNCATEGORIZED_CONSUMER_SHA256 = '4632dafb838995fa26a854d2c7d3020b2fe0b0bc20d273e329b24979be359052'
ROAD_CONSUMER_CONTRACT_SHA256 = 'b2211a3ef196bf5ce868bb4de41411a2b85baf5d69ffa7132a6038f956d35dba'
_ORDER = (191, 98, 109, 188, 158, 192, 179, 177, 163, 162, 189, 171,
          182, 181, 190, 155, 180, 170, 169, 168, 178, 172, 131, 227,
          228, 229, 230, 231, 232, 233, 234, 235, 236, 237, 238, 239,
          240, 243, 242, 136, 247, 260, 261, 193, 194)


def _policy(value):
    if type(value) is not str or value not in (ROAD_AUTHORING_POLICY, ROAD_UNCATEGORIZED_POLICY):
        raise ValueError('explicit exact road authoring policy required')


def road_class_row(native_class, policy):
    _policy(policy)
    if type(native_class) is not int or native_class != 208:
        raise ValueError('road authoring policy is only native class208')
    return {'acronym': 'roaedg', 'sourceCode': None, 'layerBase': 120,
            'allowedKinds': (32,), 'nativeAttributeOrder': list(_ORDER),
            'nativeSourceClassIdentity': 'NOT_INFERRED_EXPLICIT_EXTERNAL_LEDGER'}


def road_enum_values(native_code, policy):
    _policy(policy)
    if policy == ROAD_UNCATEGORIZED_POLICY:
        raise ValueError('uncategorized road policy forbids native category attributes')
    if type(native_code) is not int or native_code != 158:
        raise ValueError('road authoring enum scope is only catroa158')
    return (1, 2)
