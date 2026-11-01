"""Invented PNGs, Common resources and XML; no external resource fixtures."""
import builtins
from io import BytesIO
import json

import pytest

from no_chart_converter.cache_country.bitmap_png import decode_png_rgba
from no_chart_converter.cache_country.presentation import (
    PresentationRefusal, bootstrap_public_s52_profile,
)
from no_chart_converter.country_runtime.inputs import file_pin
from no_chart_converter.country_runtime.wire import AuthoredBlock, assemble
from no_chart_converter.rx5.header import new_plain_vector_header


def pillow():
    return pytest.importorskip('PIL.Image')


def png(pixels=b'\x01\x02\x03\xff\x05\x06\x07\x00'):
    image = pillow().frombytes('RGBA', (2, 1), pixels)
    output = BytesIO()
    image.save(output, format='PNG')
    image.close()
    return output.getvalue()


def inputs(tmp_path, *, target_png=None):
    atlas = png() if target_png is None else target_png
    csv = b'FAKE0001,1,0,0,2,1,-2250,-2302\r\nUNKNOWN1,1.0,0,0,1,1,-7.0,8.00\n'
    parent = tmp_path / 'invented-parent.rx5'
    parts = [AuthoredBlock(str(key), key, 0, b'', body) for key, body in
             [(64, b'OWN PALETTE'), (65, b''), (68, atlas), (69, csv)]]
    header = new_plain_vector_header(bytes(range(16)))
    parent.write_bytes(assemble(header, parts, {k: None for k in (136, 144, 160, 168, 184, 200)})[0])
    xml = tmp_path / 'invented-public.xml'
    xml.write_bytes(b'''<chartsymbols><lookups><lookup RCID="7" name="BOYLAT">
      <type>Point</type><disp-prio>Point Symbol</disp-prio><table-name>Paper</table-name>
      <display-cat>STANDARD</display-cat><instruction>SY(FAKE0001);</instruction>
      </lookup></lookups><symbols><symbol><name>FAKE0001</name><definition>R</definition>
      <bitmap width="2" height="1"><graphics-location x="0" y="0"/><pivot x="1" y="0"/>
      <origin x="0" y="0"/><distance min="0" max="0"/></bitmap>
      <color-ref>ACHBLK</color-ref></symbol></symbols></chartsymbols>''')
    bitmap = tmp_path / 'invented-public.png'
    bitmap.write_bytes(atlas)
    return parent, xml, bitmap, csv


def bootstrap(tmp_path, files, name, **kwargs):
    parent, xml, _, _ = files
    return bootstrap_public_s52_profile(file_pin(parent), file_pin(xml), tmp_path / name,
        provider_command=['OWN_PROVIDER'], settings={}, **kwargs)


def document(pin):
    with open(pin['path'], 'rb') as handle:
        return json.load(handle)


def resources(result):
    profile = document(result['profile'])
    output = {}
    for row in profile['resources']:
        with open(row['data']['path'], 'rb') as handle:
            output[row['id']] = handle.read()
    return output


def test_png_adapter_preserves_transparent_rgb_bytes():
    decoded = decode_png_rgba(png())
    assert decoded.width == 2 and decoded.height == 1
    assert decoded.pixels == b'\x01\x02\x03\xff\x05\x06\x07\x00'


def test_bootstrap_normalizes_only_pixel_bound_offsets_and_binds_report(tmp_path):
    files = inputs(tmp_path)
    baseline = bootstrap(tmp_path, files, 'before')
    fixed = bootstrap(tmp_path, files, 'after', public_bitmap_png_pin=file_pin(files[2]))
    old, new = resources(baseline), resources(fixed)
    assert new[69] == b'FAKE0001,1,0,0,2,1,-1,0\r\nUNKNOWN1,1.0,0,0,1,1,-7.0,8.00\n'
    assert {key: new[key] for key in (64, 65, 68)} == {key: old[key] for key in (64, 65, 68)}
    profile = document(fixed['profile'])
    pin = profile['provenance']['bitmapPivotNormalization']
    receipt = document(pin)
    assert document(fixed['receipt'])['bitmapPivotNormalization'] == pin
    assert receipt['counts'] == dict(rows=2, changed=1, unproven=1, alreadyExact=0, protected=0)
    assert receipt['inputs']['publicBitmapPNG'] == file_pin(files[2])
    assert receipt['inputs']['publicLookupXML'] == file_pin(files[1])
    assert receipt['inputs']['targetResourceParent'] == file_pin(files[0])
    assert receipt['publicBitmapRGBA'] == receipt['sourceAtlasRGBA']
    assert receipt['pngDecoder']['name'] == 'Pillow'
    assert receipt['geometryOrProgrammeChanges'] is False


def test_unmatched_png_pixels_remain_exactly_unchanged(tmp_path):
    files = inputs(tmp_path)
    files[2].write_bytes(png(b'\x01\x02\x03\xff\x05\x06\x08\x00'))
    result = bootstrap(tmp_path, files, 'kept', public_bitmap_png_pin=file_pin(files[2]))
    assert resources(result)[69] == files[3]
    report = document(document(result['profile'])['provenance']['bitmapPivotNormalization'])
    assert report['counts']['changed'] == 0 and report['counts']['unproven'] == 2


def test_wrong_bitmap_pin_refuses_before_resource_serialization(tmp_path):
    files = inputs(tmp_path)
    pin = dict(file_pin(files[2]), sha256='0' * 64)
    with pytest.raises(PresentationRefusal, match='PRESENTATION_RESOURCE_PIN_MISMATCH'):
        bootstrap(tmp_path, files, 'wrong', public_bitmap_png_pin=pin)
    assert list((tmp_path / 'wrong').iterdir()) == []


def test_invalid_target_png_refuses_instead_of_inventing_pixels(tmp_path):
    files = inputs(tmp_path, target_png=b'INVENTED INVALID PNG')
    with pytest.raises(PresentationRefusal, match='BITMAP_PIVOT_NORMALIZATION_FAILED'):
        bootstrap(tmp_path, files, 'badpng', public_bitmap_png_pin=file_pin(files[2]))
    assert list((tmp_path / 'badpng').iterdir()) == []


def test_without_opt_in_does_not_import_pillow_or_modify_csv(tmp_path, monkeypatch):
    files = inputs(tmp_path, target_png=b'LEGACY EXPLICIT RESOURCE')
    original = builtins.__import__
    def guarded(name, *args, **kwargs):
        assert name != 'PIL' and not name.startswith('PIL.')
        return original(name, *args, **kwargs)
    monkeypatch.setattr(builtins, '__import__', guarded)
    result = bootstrap(tmp_path, files, 'legacy')
    assert resources(result)[69] == files[3]
    assert 'bitmapPivotNormalization' not in document(result['profile'])['provenance']


def test_missing_pillow_has_explicit_refusal(monkeypatch):
    raw = png()
    original = builtins.__import__
    def missing(name, *args, **kwargs):
        if name == 'PIL':
            raise ImportError('own synthetic missing-dependency control')
        return original(name, *args, **kwargs)
    monkeypatch.setattr(builtins, '__import__', missing)
    with pytest.raises(ValueError, match='BITMAP_PNG_REQUIRES_PILLOW_EXTRA'):
        decode_png_rgba(raw)


def test_animated_png_and_16bit_conversion_refused():
    image = pillow().new('RGBA', (2, 1), (1, 2, 3, 4))
    other = pillow().new('RGBA', (2, 1), (5, 6, 7, 8))
    output = BytesIO()
    image.save(output, format='PNG', save_all=True, append_images=[other], duration=10)
    with pytest.raises(ValueError, match='STATIC_SINGLE_FRAME_PNG_REQUIRED'):
        decode_png_rgba(output.getvalue())
    image.close(); other.close()
    image = pillow().new('I;16', (1, 1), 40000)
    output = BytesIO(); image.save(output, format='PNG'); image.close()
    with pytest.raises(ValueError, match='PNG_BIT_DEPTH_OUTSIDE_RGBA8_CONTRACT'):
        decode_png_rgba(output.getvalue())
