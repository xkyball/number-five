"""Pure own retained-IR authoring closure; extracted functions remain source-exact.
No decoder, filesystem, CLI, network, Native or Original execution entry point.
"""
from decimal import Decimal
from .source_attribute_namespace import validate_source_pair, SOURCE_ATTRIBUTE_CATALOG_SHA256
import math
import re
import struct
from no_chart_converter.navionics.generic_registry import ATTRIBUTES, CLASSES, ATTRIBUTE_CONTRACT_SHA256, ENUM_ALIAS_CONTRACT_SHA256, EXPLICIT_ENUM_ALIASES
from no_chart_converter.navionics.resare_enum_pairing import CATREA28_PAIRING_POLICY, CATREA28_PAIRING_CONTRACT_SHA256
from no_chart_converter.navionics.generic_batch import GenericAttribute

PROFILE_FORMAT = 'no5-semantic-generic-attribute-profile/1'

SUPPORTED_STORAGE = ('E', 'L', 'M', 'J', 'H', 'K', 'O', 'A', 'S', 'B', 'T')

def profile(*, numeric_premises=None, byte_text_codecs=None):
    """Return explicit JSON settings. Empty dictionaries infer no units/codec."""
    return dict(format=PROFILE_FORMAT, numeric_premises=dict(numeric_premises or {}),
        byte_text_codecs=dict(byte_text_codecs or {}), text_resource_language='EN')

def _decimal(value, label):
    if type(value) not in (int, float):
        raise ValueError(label)
    result = Decimal(str(value))
    if not result.is_finite():
        raise ValueError(label)
    return result

def _attribute_by_acronym():
    rows = {}
    for code, row in ATTRIBUTES.items():
        name = row['acronym']
        if name in rows:
            raise ValueError('PARENT_NATIVE_ATTRIBUTE_ACRONYM_NOT_UNIQUE')
        rows[name] = (code, row)
    return rows

def validate_profile(value):
    if (type(value) is not dict or set(value) != {
        'format', 'numeric_premises', 'byte_text_codecs', 'text_resource_language'} or
        value['format'] != PROFILE_FORMAT or value['text_resource_language'] != 'EN'):
        raise ValueError('EXPLICIT_GENERIC_ATTRIBUTE_PROFILE_REQUIRED')
    rows = _attribute_by_acronym()
    for name in ('numeric_premises', 'byte_text_codecs'):
        if type(value[name]) is not dict or len(value[name]) > 264:
            raise ValueError('BOUNDED_PER_ACRONYM_ATTRIBUTE_PREMISES_REQUIRED')
    for name, premise in value['numeric_premises'].items():
        if name not in rows or rows[name][1]['storage'] not in ('M', 'J', 'H', 'K', 'O'):
            raise ValueError('NUMERIC_PREMISE_MUST_NAME_QUALIFIED_FLOAT_OR_UNSIGNED_COUNT')
        if (type(premise) is not dict or set(premise) != {
            'declared_native_units', 'maximum_absolute_native_decode_error'}):
            raise ValueError('EXPLICIT_NUMERIC_UNIT_AND_ERROR_PREMISE_REQUIRED')
        unit = premise['declared_native_units']
        if type(unit) is not int or unit != rows[name][1]['defaultUnitsRaw']:
            raise ValueError('DECLARED_UNIT_MUST_EQUAL_TAG0_DECODED_DICTIONARY_UNIT')
        if _decimal(premise['maximum_absolute_native_decode_error'],
            'FINITE_NONNEGATIVE_NUMERIC_ERROR_PREMISE_REQUIRED') < 0:
            raise ValueError('FINITE_NONNEGATIVE_NUMERIC_ERROR_PREMISE_REQUIRED')
    for name, codec in value['byte_text_codecs'].items():
        if name not in rows or rows[name][1]['storage'] not in ('A', 'S'):
            raise ValueError('BYTE_CODEC_PREMISE_MUST_NAME_QUALIFIED_A_OR_S')
        if codec not in ('ascii', 'utf-8'):
            raise ValueError('EXPLICIT_LOSSLESS_ASCII_OR_UTF8_BYTE_PREMISE_REQUIRED')

def _f32(value):
    return struct.unpack('<f', struct.pack('<f', value))[0]

def _numeric(attr, code, row, settings):
    name = attr['acronym']
    if name not in settings['numeric_premises']:
        raise ValueError('EXPLICIT_PER_ACRONYM_NUMERIC_UNIT_AND_ERROR_PREMISE_REQUIRED')
    if attr.get('type') not in ('uint32', 'float64'):
        raise ValueError('QUALIFIED_SOURCE_NUMERIC_DATATYPE_REQUIRED')
    source = _decimal(attr.get('value'), 'FINITE_SOURCE_NUMBER_REQUIRED')
    if type(attr['value'])is float and attr['value']==0 and math.copysign(1,attr['value'])<0:
        raise ValueError('SOURCE_NEGATIVE_ZERO_SIGN_NOT_REPRESENTABLE')
    if attr['type']=='uint32'and(type(attr['value'])is not int or not 0<=attr['value']<=0xffffffff):
        raise ValueError('SOURCE_UINT32_RANGE_AND_DATATYPE_REQUIRED')
    factor = row['factorOrEnumStart']
    if type(factor) is not int or factor < -(1 << 15) or factor > (1 << 15)-1:
        raise ValueError('QUALIFIED_SIGNED_DICTIONARY_FACTOR_REQUIRED')
    effective = factor if factor else 1
    scaled = source * effective
    if scaled != scaled.to_integral_value():
        raise ValueError('SOURCE_NUMBER_NOT_EXACTLY_REPRESENTABLE_NO_ROUNDING')
    integer = int(scaled)
    storage=row['storage']
    if storage=='H'and source<0:
        raise ValueError('NEGATIVE_SOURCE_H_NOT_REPRESENTABLE_NO_WRAP')
    if storage=='O'and(factor<=0 or source!=source.to_integral_value()):
        raise ValueError('SOURCE_O_COUNT_AND_POSITIVE_FACTOR_REQUIRED')
    premise = settings['numeric_premises'][name]
    scalar = GenericAttribute(code,integer,premise['declared_native_units']if storage!='O'else None)
    if storage=='O':
        decoded=integer//factor
        if decoded!=int(source):raise ValueError('UNSIGNED_O_DIVISION_MUST_PRESERVE_SOURCE_COUNT')
        wire=dict(native_code_u16=code,raw_integer=integer)
        return scalar,wire,dict(sourceDecimal=str(source),exactScaledInteger=integer,
            nativeDictionaryFactor=factor,nativeDecodedUnsignedInteger=decoded,
            nativeIntegerDivision='UNSIGNED_U32_AFTER_U16_READ',absoluteNativeDecodeError='0',
            sourceUnits='EXPLICIT_CALLER_COUNT_PREMISE_NOT_PHYSICAL_UNITS',nativeDecodedUnit=0)
    # The source-bound decoder converts the integer to F32, then performs an
    # F32 division when factor!=0. Retain exact bits and an explicit error cap.
    prediction = _f32(integer)
    if factor:
        prediction = _f32(prediction / _f32(factor))
    error = abs(Decimal.from_float(prediction) - source)
    if error > _decimal(premise['maximum_absolute_native_decode_error'],
        'FINITE_NONNEGATIVE_NUMERIC_ERROR_PREMISE_REQUIRED'):
        raise ValueError('NATIVE_NUMERIC_DECODE_ERROR_PREMISE_EXCEEDED')
    wire = dict(native_code_u16=code, raw_integer=integer,
        declared_native_units=premise['declared_native_units'])
    return scalar, wire, dict(sourceDecimal=str(source), exactScaledInteger=integer,
        nativeDictionaryFactor=factor, predictedNativeF32LEHex=struct.pack('<f', prediction).hex(),
        absoluteNativeDecodeError=str(error), maximumAbsoluteNativeDecodeError=str(
            premise['maximum_absolute_native_decode_error']), sourceUnits='EXPLICIT_CALLER_PREMISE_NOT_VERIFIED',
        nativeDecodedUnit=premise['declared_native_units'], layerDepthUnitTagBits=0)

def encode_source_attribute(attr, native_class, settings):
    """Return (parent scalar, exact JSON input, occurrence qualification)."""
    if type(attr) is not dict or type(attr.get('acronym')) is not str:
        raise ValueError('EXPLICIT_SOURCE_ATTRIBUTE_ACRONYM_REQUIRED')
    export_code=validate_source_pair(attr)
    name = attr['acronym']; found = _attribute_by_acronym().get(name)
    if found is None:
        raise ValueError('NO_EXACT_NATIVE_ATTRIBUTE_ACRONYM')
    code, row = found
    if 'wireType'in attr and attr['wireType']!={'uint32':0,'float64':2,'utf8':4}.get(attr.get('type')):
        raise ValueError('SOURCE_WIRE_TYPE_DATATYPE_MISMATCH')
    # The historical native binder's sourceCode chose later case-folded
    # custom catalog entries for eleven acronyms. It is provenance, not this
    # export namespace. Neither input code nor acronym is replaced here.
    storage = row['storage']
    if storage not in SUPPORTED_STORAGE:
        raise ValueError('NATIVE_ATTRIBUTE_STORAGE_NOT_QUALIFIED:' + str(storage))
    if (native_class not in CLASSES or code not in
        CLASSES[native_class]['nativeAttributeOrder']):
        raise ValueError('ATTRIBUTE_NOT_IN_QUALIFIED_NATIVE_CLASS_DICTIONARY')
    value = attr.get('value'); dtype = attr.get('type')
    qualification = dict(nativeStorage=storage, sourceDatatype=dtype,
        sourceNativeAttributeContractSHA256=ATTRIBUTE_CONTRACT_SHA256,
        exportCatalogSHA256=SOURCE_ATTRIBUTE_CATALOG_SHA256,exportCode=export_code,
        nativeRegistryDeclaredSourceCode=row.get('sourceCode'),
        nativeJoin='EXACT_CASE_SENSITIVE_ACRONYM_NOT_NUMERIC_CODE_IDENTITY')
    if 'source_pairing_policy' in attr:
        policy = attr['source_pairing_policy']
        if (type(policy) is not str or policy != CATREA28_PAIRING_POLICY or
            type(native_class) is not int or native_class != 120 or
            name != 'CATREA' or export_code != 56 or code != 52 or storage != 'L' or
            dtype != 'utf8' or type(value) is not str or value != '28'):
            raise ValueError('EXACT_OPT_IN_RESARE_CATREA28_PAIR_REQUIRED')
        scalar = GenericAttribute(52, [28], source_pairing_policy=policy)
        wire = dict(native_code_u16=52, enum_u8s=[28], source_pairing_policy=policy)
        qualification.update(enumMeaning='EXACT_PUBLISHED_SOURCE_NATIVE_PSSA_PAIR',
            sourcePairingPolicy=policy, sourcePairingContractSHA256=CATREA28_PAIRING_CONTRACT_SHA256,
            sourceAttributeRetainedUnchanged=True, nativePortrayalOrQueryQualified=False)
        return scalar, wire, qualification
    if storage == 'E':
        # Standard CATPIP47 is source L; Native CATPIP46 is E. These three
        # canonical one-member source strings have an exact, already paired
        # meaning. Do not coerce arbitrary text, lists, empty slots or members.
        singleton_catpip = (name == 'CATPIP' and export_code == 47 and code == 46
            and dtype == 'utf8' and type(value) is str and value in ('2', '4', '6'))
        if singleton_catpip:
            value = int(value)
            qualification.update(sourceRepresentation='CATPIP_CANONICAL_SINGLETON_LIST_TEXT',
                sourceListMembers=1, sourceAttributeRetainedUnchanged=True,
                singletonSourceValuesContract='SOURCE47_L_NATIVE46_E_PAIRED_2_4_6')
        elif dtype != 'uint32' or type(value) is not int:
            raise ValueError('SOURCE_UINT32_ENUM_REQUIRED')
        scalar = GenericAttribute(code, value)
        wire = dict(native_code_u16=code, enum_u8=value)
        qualification.update(enumMeaning=('EXPLICIT_FINITE_SOURCE_NATIVE_ALIAS'
            if value in EXPLICIT_ENUM_ALIASES.get(code,())else'EXACT_FINITE_SOURCE_NATIVE_PAIR'),
            explicitAliasContractSHA256=ENUM_ALIAS_CONTRACT_SHA256)
    elif storage == 'L':
        if dtype == 'uint32' and type(value) is int:
            values = [value]
        elif dtype == 'utf8' and type(value) is str and re.fullmatch(r'[1-9][0-9]*(?:,[1-9][0-9]*)*', value):
            values = [int(v) for v in value.split(',')]
        else:
            raise ValueError('SOURCE_ORDERED_COMMA_ENUM_LIST_REQUIRED')
        scalar = GenericAttribute(code, values)
        wire = dict(native_code_u16=code, enum_u8s=list(values))
        qualification.update(enumMeaning='EXACT_FINITE_SOURCE_NATIVE_PAIR',
            orderedListMembers=len(values),sourceListOrderAndDuplicatesPreserved=True,
            explicitAliasValues=[v for v in values if v in EXPLICIT_ENUM_ALIASES.get(code,())],
            explicitAliasContractSHA256=ENUM_ALIAS_CONTRACT_SHA256)
    elif storage in ('M', 'J', 'H', 'K', 'O'):
        scalar, wire, numeric = _numeric(attr, code, row, settings)
        qualification['numericPrediction'] = numeric
    elif storage in ('A', 'S'):
        if dtype != 'utf8' or type(value) is not str:
            raise ValueError('SOURCE_TEXT_FOR_EXPLICIT_BYTE_CODEC_REQUIRED')
        codec = settings['byte_text_codecs'].get(name)
        if codec is None:
            raise ValueError('EXPLICIT_PER_ACRONYM_BYTE_TEXT_CODEC_REQUIRED')
        try:
            data = value.encode(codec, errors='strict')
        except (UnicodeError, LookupError) as error:
            raise ValueError('SOURCE_TEXT_NOT_LOSSLESS_IN_DECLARED_BYTE_CODEC') from error
        if data.decode(codec, errors='strict') != value:
            raise ValueError('SOURCE_TEXT_NOT_LOSSLESS_IN_DECLARED_BYTE_CODEC')
        scalar = GenericAttribute(code, data)
        wire = dict(native_code_u16=code, raw_hex=data.hex())
        qualification.update(sourceByteCodecPremise=codec,
            nativeCharset='EXPLICIT_CALLER_PREMISE_NOT_VERIFIED')
    else:
        if dtype != 'utf8' or type(value) is not str:
            raise ValueError('SOURCE_UTF8_TEXT_RESOURCE_REQUIRED')
        scalar = GenericAttribute(code, value)
        wire = dict(native_code_u16=code, text=value)
        qualification.update(textResourceLanguagePremise='EN',
            sourceTextPreservedExactly=True, nativeTextEncoding='BMP_UTF16LE_RESOURCE')
    return scalar, wire, qualification
