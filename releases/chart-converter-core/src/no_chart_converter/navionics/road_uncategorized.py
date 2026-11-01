"""Default-off missing-CATROD source recipe; no projection, decode, IO or native."""
from copy import deepcopy
import hashlib
import json

from .road_authoring_registry import ROAD_UNCATEGORIZED_POLICY


def _hash(value):
    return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':'),
        ensure_ascii=False,allow_nan=False).encode()).hexdigest()


def prepare_uncategorized_road(feature,*,policy=None):
    """Native recipe only; caller retains geometry/caps and the complete ledger.

    Present or malformed CATROD never becomes an absent native category.
    This deliberately does not infer native encoding for other attributes.
    """
    if type(policy) is not str or policy != ROAD_UNCATEGORIZED_POLICY:
        raise ValueError('EXPLICIT_UNCATEGORIZED_ROAD_POLICY_REQUIRED')
    if (type(feature) is not dict or type(feature.get('classCode')) is not int or
            feature['classCode'] != 116 or feature.get('classAcronym') != 'ROADWY' or
            type(feature.get('sourcePrimitive')) is not int or feature['sourcePrimitive'] != 1 or
            feature.get('geometry',{}).get('kind') != 'line' or type(feature.get('sourceId')) is not str or
            not feature['sourceId'] or type(feature.get('attributes')) is not list):
        raise ValueError('WHOLE_SOURCE_ROADWY_LINE_REQUIRED')
    attrs = feature['attributes']
    if any(type(a) is not dict or type(a.get('code')) is not int or
            type(a.get('acronym')) is not str or 'type' not in a or 'value' not in a for a in attrs):
        raise ValueError('COMPLETE_TYPED_SOURCE_ATTRIBUTE_OBJECTS_REQUIRED')
    if any(a['code'] == 57 or a['acronym'] == 'CATROD' for a in attrs):
        raise ValueError('CATROD_MUST_BE_ABSENT_NOT_RELABELED')
    ledger = []
    for i,a in enumerate(attrs):
        structural = (a['code'] == 50000 and a['acronym'] == 'catgeo' and
            a['type'] == 'uint32' and type(a['value']) is int and a['value'] == 2)
        if (a['code'] == 50000 or a['acronym'] == 'catgeo') and not structural:
            raise ValueError('EXACT_SOURCE_LINE_CATGEO2_REQUIRED')
        ledger.append(dict(sourceAttributeOrdinal=i,sourceAttribute=deepcopy(a),
            sourceAttributeSHA256=_hash(a),encodedNativeAttribute=None,
            disposition='STRUCTURAL_LINE32_NOT_NATIVE_ATTRIBUTE' if structural else 'EXTERNAL_UNENCODED_ORIGINAL_VALUE',
            semanticsClaim='SCAMIN_IS_NOT_NATIVE_LOD; TXTDSC_IS_NOT_AN_ATTACHED_FILE; NO_MISSING_CATEGORY_VALUE_INFERRED'))
    return dict(format='no5-uncategorized-road-authoring-recipe/1',policy=policy,
        sourceId=feature['sourceId'],sourceGeometrySHA256=_hash(feature['geometry']),sourceAttributesSHA256=_hash(attrs),
        sourceRecord=deepcopy(feature.get('sourceRecord')),sourceDisplayScale=deepcopy(feature.get('displayScale',{})),
        nativeClass=208,lineLayer=121,kind=32,attributes=[],attributeBytes=0,presenceMask=[0,0],
        nativeCategory='ABSENT_NOT_MAJOR2',sourceAttributeLedger=ledger,
        originalSourceAttributeCount=len(attrs),structuralSourceOccurrences=sum(r['disposition'].startswith('STRUCTURAL')for r in ledger),
        externalSourceOccurrences=sum(r['disposition'].startswith('EXTERNAL')for r in ledger),
        geometryReencoded=False,geometryIncluded=False,nativeExecuted=False,
        notClaimed=['Exact ROADWY/native class identity','Native admission/selection/portrayal/query parity',
            'Source physical units','SCAMIN/product LoD','Complete source attribute native encoding'])
