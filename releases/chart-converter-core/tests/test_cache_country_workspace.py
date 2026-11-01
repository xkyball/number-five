"""Bounded decode workspace and verified external IR delivery semantics."""
import hashlib
import json
from pathlib import Path

import pytest

from cache_country_fixture import write_package, write_presentation_config
from no_chart_converter import cache_decoder
from no_chart_converter.cache_country import pipeline


def configured(root, **options):
    path=write_presentation_config(root/'profile')
    config=json.loads(path.read_bytes())
    return {**config,'baseDirectory':str(path.parent),**options}


def test_decode_workspace_is_verified_then_removed(tmp_path):
    root=tmp_path.resolve()
    package=write_package(root/'source')
    work=root/'temporary-decode'
    result=pipeline.export_country(package.source,package.catalogs,root/'external-output',
        config=configured(root,decodeWorkDirectory=str(work),maxDecodeWorkBytes=2*1024*1024))
    assert result['status']=='PASS_CACHE_TO_COUNTRY'
    assert not work.exists()
    receipt=json.loads(Path(result['adapterReceipt']['path']).read_bytes())
    delivery=receipt['irDelivery']
    assert delivery['mode']=='SUPPORTED_FILESYSTEM_DECODE_THEN_VERIFIED_COPY'
    assert delivery['atomicDirectoryPublication'] is False
    assert delivery['temporaryCleanupComplete'] is True
    assert delivery['bytes']<=2*1024*1024
    completion=json.loads(Path(delivery['deliveryReceipt']['path']).read_bytes())
    for row in completion['files']:
        data=(root/'external-output/ir'/row['relativePath']).read_bytes()
        assert len(data)==row['bytes']
        assert hashlib.sha256(data).hexdigest()==row['sha256']
    config=json.loads(Path(result['preparedConfig']['path']).read_bytes())
    assert all(not Path(pin['path']).is_relative_to(work)for pin in config['inputs'].values())


def test_unsupported_destination_refuses_before_heavy_decode(tmp_path,monkeypatch):
    root=tmp_path.resolve();package=write_package(root/'source')
    def unavailable(*args,**kwargs):
        raise cache_decoder.DecodeError('ATOMIC_PUBLISH_UNAVAILABLE','simulated unsupported FS')
    def heavy_decode(*args,**kwargs):
        raise AssertionError('Heavy decode must not run before capability check')
    monkeypatch.setattr(pipeline,'atomic_publish',unavailable)
    monkeypatch.setattr(pipeline,'decode',heavy_decode)
    with pytest.raises(ValueError,match='decodeWorkDirectory'):
        pipeline.export_country(package.source,package.catalogs,root/'output',config=configured(root))
    assert not (root/'output/ir').exists()
    assert not list((root/'output').glob('.no5-publish-probe-*'))


def test_temporary_output_budget_refuses_without_publishing_ir(tmp_path):
    root=tmp_path.resolve();package=write_package(root/'source');work=root/'work'
    with pytest.raises(ValueError,match='OUTPUT_BYTE_BUDGET'):
        pipeline.export_country(package.source,package.catalogs,root/'output',
            config=configured(root,decodeWorkDirectory=str(work),maxDecodeWorkBytes=1))
    assert not (work/'ir').exists()
    assert not (root/'output/IR_DELIVERY_RECEIPT.json').exists()
    assert (work/'WORKSPACE_LEASE.json').is_file()
    assert not list(work.glob('.ir-staging-*'))


def test_failed_delivery_retains_completed_owned_work_for_diagnosis(tmp_path,monkeypatch):
    root=tmp_path.resolve();package=write_package(root/'source');work=root/'work'
    def failed_copy(*args,**kwargs):
        raise OSError('simulated external storage failure')
    monkeypatch.setattr(pipeline,'verified_ir_copy',failed_copy)
    with pytest.raises(ValueError,match='external storage failure'):
        pipeline.export_country(package.source,package.catalogs,root/'output',
            config=configured(root,decodeWorkDirectory=str(work)))
    assert (work/'ir/ir-manifest.json').is_file()
    assert (work/'WORKSPACE_LEASE.json').is_file()
    assert not (root/'output/IR_DELIVERY_RECEIPT.json').exists()
    refusal=json.loads((root/'output/ADAPTER_REFUSAL.json').read_bytes())
    assert refusal['successClaim'] is False
