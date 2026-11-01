"""Fresh invented cache IR can resume without decoding or copying its cells."""
import hashlib
import json
from pathlib import Path

import pytest

from cache_country_fixture import write_package, write_presentation_config
from no_chart_converter import cache_decoder, cli
from no_chart_converter.cache_country import pipeline


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def inputs(root):
    root = root.resolve()
    package = write_package(root / 'source')
    config = write_presentation_config(root / 'profile')
    manifest = cache_decoder.decode(package.source, destination=root / 'retained',
                                    catalog_dir=package.catalogs)
    return package, config, manifest


def resume(root, package, config, manifest, **options):
    return pipeline.export_country(
        package.source, package.catalogs, root.resolve() / 'new-output', config=config,
        expected_sha256=options.pop('expected_sha256', digest(package.source)),
        retained_ir_manifest=manifest,
        expected_ir_sha256=options.pop('expected_ir_sha256', digest(manifest)), **options)


def test_resume_actual_fresh_country_uses_all_retained_pins_without_decoder_or_copy(tmp_path, monkeypatch):
    package, config, manifest = inputs(tmp_path)
    original = {str(p): digest(p) for p in manifest.parent.rglob('*') if p.is_file()}

    def forbidden(*args, **kwargs):
        raise AssertionError('retained resume must not decode, copy IR or probe a decoder workspace')

    for name in ('decode', 'verified_ir_copy', 'publication_probe'):
        monkeypatch.setattr(pipeline, name, forbidden)
    result = resume(tmp_path, package, config, manifest)
    assert result['status'] == 'PASS_CACHE_TO_COUNTRY'
    assert result['country']['uniqueUIDs'] == 8
    assert not (tmp_path / 'new-output' / 'ir').exists()
    receipt = json.loads(Path(result['adapterReceipt']['path']).read_text())
    assert receipt['irManifest']['path'] == str(manifest)
    delivery = receipt['irDelivery']
    assert delivery['mode'] == 'EXPLICIT_PINNED_RETAINED_IR_NO_COPY'
    assert delivery['allCellDescriptorPinsCheckedByBuildContext'] is True
    assert delivery['retainedIRBytesCopied'] == 0
    assert delivery['oldPreparedOwnerInputsUsed'] is False
    assert delivery['retainedGenerationMustRemainAvailable'] is True
    assert original == {str(p): digest(p) for p in manifest.parent.rglob('*') if p.is_file()}


@pytest.mark.parametrize('case, reason', [
    ('missing_source_pin', 'EXPLICIT_RETAINED_SOURCE_SHA256_REQUIRED'),
    ('missing_ir_pin', 'EXPLICIT_RETAINED_IR_SHA256_REQUIRED'),
    ('wrong_ir_pin', 'RETAINED_IR_MANIFEST_PIN_MISMATCH'),
    ('wrong_source_pin', 'RETAINED_IR_EXACT_SOURCE_PACKAGE_REQUIRED'),
    ('changed_cell', 'CACHE_COUNTRY_DATA_PIN_MISMATCH'),
    ('changed_catalog', 'RETAINED_IR_EXACT_DECODER_CATALOGS_REQUIRED'),
    ('missing_receipt', 'No such file'),
    ('cell_filter', 'RETAINED_IR_SELECTION_ALREADY_FROZEN_NO_CELL_FILTER'),
    ('cell_path', 'RETAINED_IR_CELL_PATH_CONTAINMENT'),
    ('symlink_cell', 'RETAINED_IR_CELL_FILE_REQUIRED'),
    ('receipt_manifest', 'RETAINED_IR_COMPLETED_DECODER_RECEIPT_REQUIRED'),
    ('receipt_summary', 'RETAINED_IR_COMPLETE_CELL_DESCRIPTOR_JOIN'),
])
def test_retained_resume_refuses_changed_or_incomplete_inputs(tmp_path, case, reason):
    package, config, manifest = inputs(tmp_path)
    root = tmp_path.resolve()
    document = json.loads(manifest.read_text())
    receipt_path = manifest.parent / 'decode-receipt.json'
    receipt = json.loads(receipt_path.read_text())
    options = {}
    if case == 'missing_source_pin':
        options['expected_sha256'] = None
    elif case == 'missing_ir_pin':
        options['expected_ir_sha256'] = None
    elif case == 'wrong_ir_pin':
        options['expected_ir_sha256'] = '0' * 64
    elif case == 'wrong_source_pin':
        options['expected_sha256'] = '0' * 64
    elif case == 'changed_cell':
        cell = manifest.parent / document['cells'][0]['path']
        cell.write_bytes(cell.read_bytes() + b' ')
    elif case == 'changed_catalog':
        catalog = package.catalogs / 's57attributes.csv'
        catalog.write_bytes(catalog.read_bytes() + b'\n')
    elif case == 'missing_receipt':
        receipt_path.unlink()
    elif case == 'cell_filter':
        options['cell_filter'] = [document['cells'][0]['id']]
    elif case == 'cell_path':
        document['cells'][0]['path'] = '../outside.json'
        manifest.write_text(json.dumps(document))
    elif case == 'symlink_cell':
        cell = manifest.parent / document['cells'][0]['path']
        outside = root / 'outside.json'
        cell.rename(outside)
        cell.symlink_to(outside)
    elif case == 'receipt_manifest':
        receipt['manifestSha256'] = '0' * 64
        receipt_path.write_text(json.dumps(receipt))
    elif case == 'receipt_summary':
        receipt['summary']['features'] += 1
        receipt_path.write_text(json.dumps(receipt))
    with pytest.raises((ValueError, OSError), match=reason):
        resume(root, package, config, manifest, **options)
    refusal = json.loads((root / 'new-output' / 'ADAPTER_REFUSAL.json').read_text())
    assert refusal['status'] == 'REFUSED' and refusal['successClaim'] is False
    assert not (root / 'new-output' / 'prepared').exists()


def test_cli_retained_resume_arguments_reach_actual_country(tmp_path, capsys):
    package, config, manifest = inputs(tmp_path)
    output = tmp_path.resolve() / 'cli-output'
    status = cli.main(['export-country', '--source', str(package.source),
                       '--catalogs', str(package.catalogs), '--config', str(config),
                       '--output', str(output), '--expect-sha256', digest(package.source),
                       '--retained-ir-manifest', str(manifest),
                       '--expect-ir-sha256', digest(manifest)])
    assert status == 0
    result = json.loads(capsys.readouterr().out.splitlines()[-1])
    assert result['status'] == 'PASS_CACHE_TO_COUNTRY'
    assert result['country']['uniqueUIDs'] == 8


@pytest.mark.parametrize('moment,reason', [
    ('after_validation', 'CACHE_COUNTRY_DATA_PIN_MISMATCH'),
    ('after_context', 'IR_MANIFEST_CHANGED_BEFORE_SUCCESS'),
])
def test_retained_manifest_cannot_change_between_validation_context_and_success(
        tmp_path, monkeypatch, moment, reason):
    package, config, manifest = inputs(tmp_path)
    expected = digest(manifest)
    name = 'retained_ir' if moment == 'after_validation' else 'build_context'
    original = getattr(pipeline, name)

    def mutate_after_checked_stage(*args, **kwargs):
        result = original(*args, **kwargs)
        document = json.loads(manifest.read_text())
        document['untrustedAfterValidation'] = True
        manifest.write_text(json.dumps(document))
        return result

    monkeypatch.setattr(pipeline, name, mutate_after_checked_stage)
    with pytest.raises(ValueError, match=reason):
        resume(tmp_path, package, config, manifest, expected_ir_sha256=expected)
    output = tmp_path.resolve() / 'new-output'
    assert not (output / 'adapter-receipt.json').exists()
    refusal = json.loads((output / 'ADAPTER_REFUSAL.json').read_text())
    assert refusal['status'] == 'REFUSED' and refusal['successClaim'] is False
    assert refusal['irManifest']['sha256'] == expected
