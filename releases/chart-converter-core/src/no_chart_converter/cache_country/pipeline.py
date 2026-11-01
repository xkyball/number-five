"""Explicit fresh-cache pipeline; every prepared owner comes from retained IR.

No historical owner manifests or executable source paths are accepted as input.
The presentation profile supplies reusable resources/rules, not chart ownership.
"""
from __future__ import annotations

from datetime import datetime, timezone, timedelta
import hashlib
import json
import os
from pathlib import Path
import time
import tempfile
import shutil
import uuid
import zipfile

from ..cache_decoder import decode, inspect_archive, private_parent, atomic_publish, catalogs
from ..country_runtime import build_country
from ..country_runtime.inputs import REQUIRED


def need(condition, reason):
    if not condition:
        raise ValueError(reason)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def canonical(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2,
                       allow_nan=False) + '\n').encode()


def write_new(path, raw):
    path = Path(path)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'wb') as stream:
        stream.write(raw)
        stream.flush()
        os.fsync(stream.fileno())
    return pin(path)


def pin(path):
    path = Path(path)
    digest = hashlib.sha256()
    count = 0
    with path.open('rb') as stream:
        before = os.fstat(stream.fileno())
        for raw in iter(lambda: stream.read(4 * 1024 * 1024), b''):
            digest.update(raw)
            count += len(raw)
        after = os.fstat(stream.fileno())
    need((before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
         == (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns),
         'PIPELINE_INPUT_CHANGED_DURING_HASH')
    return {'path': str(path), 'bytes': count, 'sha256': digest.hexdigest()}


def read_pin(value, base):
    need(type(value) is dict and {'path', 'bytes', 'sha256'} <= value.keys(),
         'CACHE_COUNTRY_EXPLICIT_DATA_PIN_REQUIRED')
    path = Path(value['path'])
    path = path if path.is_absolute() else Path(base) / path
    actual = pin(path)
    need(type(value['bytes']) is int
         and (actual['bytes'], actual['sha256']) == (value['bytes'], value['sha256']),
         'CACHE_COUNTRY_DATA_PIN_MISMATCH:' + path.name)
    raw = path.read_bytes()
    need((len(raw), sha(raw)) == (actual['bytes'], actual['sha256']),
         'CACHE_COUNTRY_DATA_CHANGED_AFTER_PIN:' + path.name)
    return raw, actual


def _validate_postclip_boundary_runs(cfg):
    """Validate an explicit option without inventing or normalizing its values.

    The default remains absence. All limits are caller work budgets; the
    installed country kernel remains the authority for policy and limit types.
    """
    if 'postclipBoundaryRunAliases' not in cfg:
        return
    from ..country_runtime import postclip_alias
    value = cfg['postclipBoundaryRunAliases']
    need(type(value) is dict and set(value) == {'policy', 'limits'}
         and value['policy'] == postclip_alias.POLICY
         and type(value['limits']) is dict,
         'CACHE_COUNTRY_EXPLICIT_POSTCLIP_ALIAS_POLICY_AND_LIMITS')
    need(set(value['limits']) == set(postclip_alias.Limits.__annotations__),
         'CACHE_COUNTRY_EXACT_POSTCLIP_ALIAS_LIMIT_FIELDS')
    postclip_alias.Limits(**value['limits']).check()


def configuration(value):
    if isinstance(value, (str, Path)):
        path = Path(value).resolve(strict=True)
        raw = path.read_bytes()
        cfg = json.loads(raw)
        config_pin = pin(path)
        need(config_pin['sha256'] == sha(raw), 'CACHE_COUNTRY_CONFIG_CHANGED')
        base = path.parent
    elif type(value) is dict:
        cfg = json.loads(json.dumps(value, allow_nan=False))
        base = Path(cfg.pop('baseDirectory', '.')).resolve()
        raw = canonical(cfg)
        config_pin = {'bytes': len(raw), 'sha256': sha(raw)}
    else:
        raise ValueError('CACHE_COUNTRY_EXPLICIT_CONFIG_REQUIRED')
    need(cfg.get('format') == 'no5-cache-country-config/1', 'CACHE_COUNTRY_CONFIG_FORMAT')
    need('presentationProfile' in cfg, 'CACHE_COUNTRY_PRESENTATION_PROFILE_REQUIRED')
    _validate_postclip_boundary_runs(cfg)
    raw, profile_pin = read_pin(cfg['presentationProfile'], base)
    profile = json.loads(raw)
    need(profile.get('format') == 'no5-cache-country-presentation/1',
         'CACHE_COUNTRY_PRESENTATION_PROFILE_FORMAT')
    for name in ('nativeScaminLevelU32', 'nativeScaminMultiplierU32'):
        need(type(profile.get(name)) is int and 0 <= profile[name] <= 0xffffffff,
             'EXPLICIT_NATIVE_TARGET_POLICY_REQUIRED:' + name)
    cfg['_presentationProfile'] = profile
    cfg['_presentationBaseDirectory'] = str(Path(profile_pin['path']).parent)
    cfg['_presentationProfilePin'] = profile_pin
    cfg['_configurationPin'] = config_pin
    cfg['_configurationBaseDirectory'] = str(base)
    return cfg


def publication_probe(parent):
    """Test the actual no-clobber directory primitive with tiny owned data."""
    parent=Path(parent)
    private_parent(parent)
    source=Path(tempfile.mkdtemp(prefix='.no5-publish-probe-',dir=parent))
    target=parent/('.no5-publish-probe-result-'+uuid.uuid4().hex)
    try:
        write_new(source/'probe',b'OWN TEMPORARY FILESYSTEM CAPABILITY PROBE')
        atomic_publish(source,target)
        need((target/'probe').read_bytes()==b'OWN TEMPORARY FILESYSTEM CAPABILITY PROBE',
             'IR_PUBLICATION_CAPABILITY_READBACK')
    finally:
        if source.is_dir():shutil.rmtree(source)
        if target.is_dir():shutil.rmtree(target)


def verified_ir_copy(source,destination,*,maximum_bytes):
    """Copy completed IR with exact pins; this is not atomic dir publication."""
    source,destination=Path(source),Path(destination)
    need(not destination.exists(),'FRESH_EXTERNAL_IR_DIRECTORY_REQUIRED')
    manifest=json.loads((source/'ir-manifest.json').read_bytes())
    names=['ir-manifest.json','decode-receipt.json']+[row['path']for row in manifest['cells']]
    expected=set(names)
    actual={path.relative_to(source).as_posix()for path in source.rglob('*')if path.is_file()}
    need(expected==actual and all(not path.is_symlink()for path in source.rglob('*')),
         'EXACT_COMPLETE_IR_GENERATION_NO_LINKS')
    source_pins={name:pin(source/name)for name in names}
    need(sum(row['bytes']for row in source_pins.values())<=maximum_bytes,'IR_COPY_BYTE_BUDGET')
    destination.mkdir(mode=0o700)
    (destination/'cells').mkdir(mode=0o700)
    copied=[]
    for name in names:
        original=source/name;target=destination/name
        descriptor=os.open(target,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        digest=hashlib.sha256();count=0
        with original.open('rb')as incoming,os.fdopen(descriptor,'wb')as outgoing:
            for raw in iter(lambda:incoming.read(4*1024*1024),b''):
                outgoing.write(raw);digest.update(raw);count+=len(raw)
            outgoing.flush();os.fsync(outgoing.fileno())
        need((count,digest.hexdigest())==(source_pins[name]['bytes'],source_pins[name]['sha256']),
             'IR_SOURCE_CHANGED_DURING_VERIFIED_COPY')
        copied_pin=pin(target)
        need((copied_pin['bytes'],copied_pin['sha256'])==(count,digest.hexdigest()),
             'IR_EXTERNAL_PERSISTED_COPY_PIN')
        copied.append({'relativePath':name,'bytes':count,'sha256':digest.hexdigest()})
    for row in manifest['cells']:
        read_pin(row,destination)
    return {'format':'no5-verified-ir-delivery/1','status':'PASS_COMPLETE_PERSISTED_IR_COPY',
            'files':copied,'bytes':sum(row['bytes']for row in copied),
            'atomicDirectoryPublication':False,'completionReceiptRequired':True}


def decode_ir(source_zip,catalog_dir,output,cfg,*,expected_sha256,cell_filter):
    """Bounded optional APFS decode workspace, then verified external delivery."""
    maximum=cfg.get('maxDecodeWorkBytes',1024*1024*1024)
    need(type(maximum)is int and maximum>0,'EXPLICIT_POSITIVE_DECODE_BYTE_BUDGET')
    work_value=cfg.get('decodeWorkDirectory')
    if work_value is None:
        try:publication_probe(output)
        except ValueError as error:
            raise ValueError('IR_FILESYSTEM_ATOMIC_PUBLICATION_UNAVAILABLE; '
                             'set an explicit supported decodeWorkDirectory: '+str(error))from error
        result=decode(source_zip,cell_filter,output/'ir',expected_sha256=expected_sha256,
                      catalog_dir=catalog_dir,max_output_bytes=maximum)
        return result,{'mode':'DECODER_ATOMIC_NO_CLOBBER','temporaryWorkspace':None,
                       'maximumDecodedBytes':maximum,'temporaryCleanupComplete':True}
    work=Path(work_value)
    if not work.is_absolute():work=Path(cfg['_configurationBaseDirectory'])/work
    work=work.absolute()
    need(not work.exists()and not work.is_symlink(),'FRESH_EXPLICIT_DECODE_WORK_DIRECTORY_REQUIRED')
    need(not work.is_relative_to(output)and not output.is_relative_to(work),
         'SEPARATE_TEMPORARY_DECODE_WORK_DIRECTORY')
    private_parent(work.parent)
    publication_probe(work.parent)
    work.mkdir(mode=0o700)
    token=uuid.uuid4().hex
    lease={'format':'no5-temporary-decode-workspace/1','token':token,
           'purpose':'Bounded reproducible cache decoding before verified external IR copy',
           'createdUtc':datetime.now(timezone.utc).isoformat(),
           'expiresUtc':(datetime.now(timezone.utc)+timedelta(hours=3)).isoformat(),
           'maximumBytes':maximum,'outputDirectory':str(output)}
    lease_path=work/'WORKSPACE_LEASE.json'
    write_new(lease_path,canonical(lease))
    # Failed deliveries retain this exact owned workspace for diagnosis; no
    # completed decoder generation is discarded as a false external success.
    result=decode(source_zip,cell_filter,work/'ir',expected_sha256=expected_sha256,
                  catalog_dir=catalog_dir,max_output_bytes=maximum)
    delivered=verified_ir_copy(result.parent,output/'ir',maximum_bytes=maximum)
    delivered.update(temporaryWorkspace=str(work),maximumDecodedBytes=maximum,
                     temporaryGenerationManifest=pin(result),temporaryLease=pin(lease_path),
                     mode='SUPPORTED_FILESYSTEM_DECODE_THEN_VERIFIED_COPY')
    receipt=write_new(output/'IR_DELIVERY_RECEIPT.json',canonical(delivered))
    need(json.loads(lease_path.read_bytes())['token']==token,'OWN_TEMPORARY_WORKSPACE_IDENTITY')
    shutil.rmtree(work)
    return output/'ir/ir-manifest.json',{**delivered,'deliveryReceipt':receipt,
                                       'temporaryCleanupComplete':not work.exists()}


def retained_ir(source_zip, catalog_dir, manifest_path, *, expected_sha256,
                expected_ir_sha256, cell_filter):
    """Resume the exact decoded generation; never import prepared owner state.

    Cell descriptor bytes/hashes are checked by ``build_context`` immediately
    after this manifest/receipt/catalog/source join. The retained IR remains in
    place and is an explicit dependency of the new generation's receipt.
    """
    need(cell_filter is None, 'RETAINED_IR_SELECTION_ALREADY_FROZEN_NO_CELL_FILTER')
    for value, label in ((expected_sha256, 'SOURCE'), (expected_ir_sha256, 'IR')):
        need(type(value) is str and len(value) == 64
             and all(char in '0123456789abcdef' for char in value),
             'EXPLICIT_RETAINED_' + label + '_SHA256_REQUIRED')
    path = Path(manifest_path).absolute()
    need(not path.is_symlink() and path.name == 'ir-manifest.json',
         'EXPLICIT_RETAINED_IR_MANIFEST_FILE_REQUIRED')
    actual = pin(path)
    need(actual['sha256'] == expected_ir_sha256, 'RETAINED_IR_MANIFEST_PIN_MISMATCH')
    raw, actual = read_pin(actual, path.parent)
    package = json.loads(raw)
    need(package.get('format') == 'no5-chart-ir/1' and package.get('kind') == 'package',
         'RETAINED_IR_PACKAGE_FORMAT')
    rows = package.get('cells')
    need(type(rows) is list and rows and len({row['id'] for row in rows}) == len(rows),
         'RETAINED_IR_UNIQUE_NONEMPTY_CELLS')
    for row in rows:
        relative = row['path']
        need(relative == 'cells/' + row['id'] + '.json'
             and '/' not in row['id'] and '\\' not in row['id']
             and row['id'] not in ('.', '..'), 'RETAINED_IR_CELL_PATH_CONTAINMENT')
        candidate = path.parent / relative
        need(not candidate.is_symlink() and not candidate.parent.is_symlink()
             and candidate.is_file(), 'RETAINED_IR_CELL_FILE_REQUIRED')
    receipt_path = path.parent / 'decode-receipt.json'
    need(not receipt_path.is_symlink(), 'RETAINED_IR_DECODE_RECEIPT_NO_LINK')
    receipt_pin = pin(receipt_path)
    receipt_raw, receipt_pin = read_pin(receipt_pin, path.parent)
    receipt = json.loads(receipt_raw)
    need(receipt.get('schema') == 'no5-decoder-report/1'
         and receipt.get('manifestSha256') == expected_ir_sha256
         and receipt.get('sourceHashAndStatUnchanged') is True
         and receipt.get('rawDataPreserved') is True,
         'RETAINED_IR_COMPLETED_DECODER_RECEIPT_REQUIRED')
    source = pin(source_zip)
    need(source['sha256'] == expected_sha256 == package['source']['sha256']
         == receipt['source']['sha256']
         and source['bytes'] == package['source']['bytes'] == receipt['source']['bytes'],
         'RETAINED_IR_EXACT_SOURCE_PACKAGE_REQUIRED')
    need(package['summary'] == receipt['summary']
         and package['summary']['cells'] == len(rows)
         and sum(row['featureCount'] for row in rows) == package['summary']['features']
         and sum(row['attributeCount'] for row in rows) == package['summary']['attributes']
         and [row['id'] for row in rows] == [row['id'] for row in receipt['cells']],
         'RETAINED_IR_COMPLETE_CELL_DESCRIPTOR_JOIN')
    _, _, current_catalogs = catalogs(catalog_dir)
    identity = lambda values: sorted((Path(row['path']).name, row['bytes'], row['sha256'])
                                     for row in values)
    need(identity(current_catalogs) == identity(receipt['catalogs']),
         'RETAINED_IR_EXACT_DECODER_CATALOGS_REQUIRED')
    return path, {'mode': 'EXPLICIT_PINNED_RETAINED_IR_NO_COPY',
                  'manifest': actual, 'decoderReceipt': receipt_pin,
                  'sourcePackage': source, 'catalogs': current_catalogs,
                  'allCellDescriptorPinsCheckedByBuildContext': False,
                  'retainedIRBytesCopied': 0, 'oldPreparedOwnerInputsUsed': False,
                  'retainedGenerationMustRemainAvailable': True,
                  'atomicDirectoryPublication': False,
                  'temporaryWorkspace': None, 'temporaryCleanupComplete': True}


def build_context(ir_manifest, source_zip, extracted, *, expected_manifest=None):
    """Load exact decoded descriptors and assign deterministic source-local UIDs."""
    ir_manifest = Path(ir_manifest)
    if expected_manifest is None:
        expected_manifest = pin(ir_manifest)
    need(Path(expected_manifest['path']) == ir_manifest, 'CONTEXT_EXACT_IR_MANIFEST_PATH')
    manifest_raw, _ = read_pin(expected_manifest, ir_manifest.parent)
    package = json.loads(manifest_raw)
    need(package.get('format') == 'no5-chart-ir/1' and package.get('kind') == 'package',
         'CACHE_COUNTRY_IR_PACKAGE_REQUIRED')
    cells, owners, by_cell, source_cells = [], [], {}, {}
    extracted.mkdir(mode=0o700)
    with zipfile.ZipFile(source_zip) as archive:
        manifest = inspect_archive(archive)
        rows = {row['name']: row for row in manifest['cells']}
        for cell_ordinal, descriptor in enumerate(package['cells']):
            raw, _ = read_pin(descriptor, ir_manifest.parent)
            cell = json.loads(raw)
            cid = cell['id']
            need(cid == descriptor['id'] and cid not in by_cell, 'CACHE_COUNTRY_CELL_BIJECTION')
            need(cell['source']['member'] == rows[cid]['base'], 'CACHE_COUNTRY_SOURCE_CELL_BINDING')
            source_raw = archive.read(rows[cid]['base'])
            need(sha(source_raw) == cell['source']['sha256'] == rows[cid]['files'][rows[cid]['base']],
                 'CACHE_COUNTRY_EXTRACTED_SOURCE_CELL_SHA256')
            source_path = extracted / (cid + '.senc')
            source_pin = write_new(source_path, source_raw)
            source_cells[cid] = {'sourceCellPath': str(source_path),
                                 'sourceCellSHA256': source_pin['sha256'], 'pin': source_pin}
            cells.append(cell)
            cell_owners, source_ids = [], set()
            for ordinal, feature in enumerate(cell['features']):
                fid = feature['sourceFeatureId']
                need(type(fid) is int and 0 <= fid <= 65535 and fid not in source_ids,
                     'CACHE_COUNTRY_SOURCE_FEATURE_ID_BIJECTION')
                source_ids.add(fid)
                owner = {'cellId': cid, 'cellOrdinal': cell_ordinal,
                         'sourceOwnerOrdinal': ordinal,
                         'uid': ((cell_ordinal + 1) << 32) | fid, 'feature': feature,
                         'featureSourceDigest': sha(json.dumps(feature, ensure_ascii=False, sort_keys=True,
                                                              separators=(',', ':'), allow_nan=False).encode())}
                owners.append(owner)
                cell_owners.append(owner)
            by_cell[cid] = cell_owners
    current_source = pin(source_zip)
    need((current_source['bytes'], current_source['sha256'])
         == (package['source']['bytes'], package['source']['sha256']),
         'CACHE_COUNTRY_SOURCE_PACKAGE_CHANGED_AFTER_DECODE')
    need(len(owners) == package['summary']['features'], 'CACHE_COUNTRY_ALL_FEATURE_JOIN')
    return {'format': 'no5-cache-country-context/1', 'package': package, 'cells': cells,
            'owners': owners, 'by_cell': by_cell, 'ir_root': ir_manifest.parent,
            'source_package': package['source'], 'source_cells': source_cells}


def validate_scale_semantics(context):
    """Refuse unsupported source visibility inputs instead of silently keeping."""
    for owner in context['owners']:
        feature=owner['feature']
        for attribute in feature['attributes']:
            if attribute['acronym']=='SCAMAX':
                raise ValueError('SOURCE_SCAMAX_VISIBILITY_NOT_IMPLEMENTED:'+feature['sourceId'])
            if attribute['acronym']!='SCAMIN':continue
            value=attribute['value']
            valid=(type(value)is int and 0<=value<=0xffffffff)or(
                type(value)is str and value.isascii()and value.isdigit()and int(value)<=0xffffffff)
            need(valid,'UNSUPPORTED_SOURCE_SCAMIN_VALUE:'+feature['sourceId'])


def merge_pins(*stages):
    result = {}
    for stage in stages:
        for name, value in stage.get('pins', {}).items():
            need(name not in result, 'CACHE_COUNTRY_DUPLICATE_PREPARED_INPUT:' + name)
            result[name] = value
    return result


def export_country(source_zip, catalog_dir, output_dir, *, config,
                   expected_sha256=None, cell_filter=None, prepare_only=False,
                   retained_ir_manifest=None, expected_ir_sha256=None):
    """Decode a fresh cache and author its prepared inputs and country generation.

    A refusal retains the exact IR and writes an explicit private failure report;
    it never publishes a success or silently reuses another chart's owner data.
    """
    started = time.monotonic()
    need(type(prepare_only) is bool, 'CACHE_COUNTRY_PREPARE_BOOL')
    cfg = configuration(config)
    source_zip = Path(source_zip).absolute()
    output = Path(output_dir).absolute()
    private_parent(output.parent)
    need(not output.exists() and not output.is_symlink(), 'CACHE_COUNTRY_FRESH_OUTPUT_REQUIRED')
    output.mkdir(mode=0o700)
    stage = 'decode'
    ir_manifest = None
    ir_manifest_pin = None
    try:
        if retained_ir_manifest is not None:
            stage = 'retained-ir-validation'
            ir_manifest, ir_delivery = retained_ir(
                source_zip, catalog_dir, retained_ir_manifest,
                expected_sha256=expected_sha256, expected_ir_sha256=expected_ir_sha256,
                cell_filter=cell_filter)
        else:
            need(expected_ir_sha256 is None, 'RETAINED_IR_SHA_REQUIRES_EXPLICIT_MANIFEST')
            ir_manifest, ir_delivery = decode_ir(source_zip,catalog_dir,output,cfg,
                                                expected_sha256=expected_sha256,cell_filter=cell_filter)
        ir_manifest_pin = ir_delivery['manifest'] if retained_ir_manifest is not None else pin(ir_manifest)
        stage = 'context'
        context = build_context(ir_manifest, source_zip, output / 'source-cells',
                                expected_manifest=ir_manifest_pin)
        if retained_ir_manifest is not None:
            ir_delivery['allCellDescriptorPinsCheckedByBuildContext'] = True
        validate_scale_semantics(context)
        prepared = output / 'prepared'
        prepared.mkdir(mode=0o700)
        from .metadata import prepare_metadata, finalize_metadata
        from .presentation import prepare_presentation
        from .geometry import prepare_geometry
        stage = 'metadata'
        metadata = prepare_metadata(context, prepared / 'metadata', cfg)
        stage = 'presentation'
        presentation = prepare_presentation(context, metadata, prepared / 'presentation', cfg)
        stage = 'geometry'
        geometry = prepare_geometry(context, metadata, presentation, prepared / 'geometry')
        stage = 'finalize-metadata'
        final_metadata = finalize_metadata(context, metadata, geometry, presentation,
                                           prepared / 'metadata', cfg)
        inputs = merge_pins(geometry, presentation, final_metadata)
        overrides = geometry.get('presentationOverrides', {})
        need(set(overrides) <= {'sourceRegistry'}, 'ONLY_EXPLICIT_DERIVED_REGISTRY_OVERRIDE')
        if overrides:
            need(geometry.get('derivedDrawRecords', geometry.get('counts', {}).get('derivedDrawRecords', 0)) > 0,
                 'DERIVED_REGISTRY_REQUIRES_EXPLICIT_DRAW_COMPANIONS')
            inputs.update(overrides)
        inputs['pixelModelAuthorizations'] = write_new(prepared / 'pixel-authorizations.json',
                                                       canonical({'rows': []}))
        inputs['pixelModelCallInputs'] = write_new(prepared / 'pixel-calls.json',
                                                  canonical({'calls': []}))
        need(set(inputs) == set(REQUIRED), 'CACHE_COUNTRY_COMPLETE_FIFTEEN_INPUTS:'
             + ','.join(sorted(set(REQUIRED) ^ set(inputs))))
        country_config = {'format': 'no5-country-runtime-config/1',
                          'outputDirectory': str(output / 'country'),
                          'title': cfg.get('title', context['package']['source']['product'][:100]),
                          'editionDate': cfg.get('editionDate', datetime.now(timezone.utc).date().isoformat()),
                          'inputs': inputs}
        if 'postclipBoundaryRunAliases' in cfg:
            country_config['postclipBoundaryRunAliases'] = cfg['postclipBoundaryRunAliases']
        if 'expectedSourceCounts' in geometry:
            country_config['expectedSourceCounts'] = geometry['expectedSourceCounts']
        prepared_pin = write_new(prepared / 'country-config.json', canonical(country_config))
        stage = 'country-prepare' if prepare_only else 'country-build'
        country = build_country(Path(prepared_pin['path']), prepare_only=prepare_only)
        need(pin(ir_manifest) == ir_manifest_pin, 'IR_MANIFEST_CHANGED_BEFORE_SUCCESS')
        receipt = {'format': 'no5-cache-country-adapter-receipt/1',
                   'status': 'PASS_CACHE_TO_PREPARED' if prepare_only else 'PASS_CACHE_TO_COUNTRY',
                   'configuration': cfg['_configurationPin'],
                   'presentationProfile': cfg['_presentationProfilePin'],
                   'sourcePackage': context['source_package'], 'irManifest': ir_manifest_pin,
                   'irDelivery': ir_delivery,
                   'sourceCells': len(context['cells']), 'sourceFeatures': len(context['owners']),
                   'primarySourceFeatures': len(context['owners']),
                   'derivedDrawRecords': geometry.get('derivedDrawRecords', geometry.get('counts', {}).get('derivedDrawRecords', 0)),
                   'sourceFeatureIdsReplacedByDerivedIds': False,
                   'uidPolicy': '(selected-IR-cell-ordinal+1)<<32 | original-source-feature-id',
                   'inputKeys': sorted(inputs), 'preparedConfig': prepared_pin,
                   'historicalOwnerManifestsUsed': False, 'newHelperRequests': 0,
                   'nativeTargetPolicy': {name: cfg['_presentationProfile'][name] for name in
                                          ('nativeScaminLevelU32', 'nativeScaminMultiplierU32')},
                   'nativeScaminLevelIsSourceDenominator': False,
                   'coordinatePolicy': 'retain-exported-fromSM-geographic-coordinates; no datum transformation',
                   'sourceDatumAndUnitClaimsUpgraded': False,
                   'country': country, 'prepareOnly': prepare_only,
                   'physicalA97AcceptanceClaim': False,
                   'seconds': round(time.monotonic() - started, 6)}
        for name, value in (('metadata', final_metadata), ('presentation', presentation), ('geometry', geometry)):
            if 'receipt' in value:
                receipt[name] = value['receipt']
        receipt_pin = write_new(output / 'adapter-receipt.json', canonical(receipt))
        return {'status': receipt['status'], 'country': country,
                **({'chart': country['chart']} if 'chart' in country else {}),
                'preparedConfig': prepared_pin, 'irManifest': ir_manifest_pin,
                'adapterReceipt': receipt_pin}
    except Exception as error:
        refusal = {'format': 'no5-cache-country-adapter-refusal/1', 'status': 'REFUSED',
                   'stage': stage, 'errorType': type(error).__name__, 'reason': str(error),
                   'irManifest': ir_manifest_pin,
                   'configuration': cfg['_configurationPin'],
                   'explicitDecodeWorkDirectory': cfg.get('decodeWorkDirectory'),
                   'temporaryWorkspaceMayRequireCleanup': bool(cfg.get('decodeWorkDirectory')),
                   'historicalOwnerManifestsUsed': False, 'successClaim': False,
                   'seconds': round(time.monotonic() - started, 6)}
        failure = write_new(output / 'ADAPTER_REFUSAL.json', canonical(refusal))
        raise ValueError('CACHE_COUNTRY_REFUSED:' + stage + ':' + str(error)
                         + '; receipt=' + failure['path']) from error
