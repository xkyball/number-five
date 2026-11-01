import hashlib
import json

from no_chart_converter.cli import main
from no_chart_converter.navionics.writer import read_header_directory, synthetic_two_header_directory


def test_builtin_persisted_and_no_clobber(tmp_path, capsys):
    output = tmp_path / 'own.bin'
    args = ['write-navionics-directory', '--synthetic-two-header', '--output', str(output)]
    assert main(args) == 0
    result = json.loads(capsys.readouterr().out)
    assert output.read_bytes() == synthetic_two_header_directory()
    assert result['output']['bytes'] == 166
    assert result['nativeReaderExecuted'] is result['geometryWritten'] is False
    assert main(args) == 2
    assert json.loads(capsys.readouterr().out)['reason'] == 'OUTPUT_EXISTS'
    assert output.read_bytes() == synthetic_two_header_directory()


def test_pinned_spec_snapshot(tmp_path, capsys):
    spec = read_header_directory(synthetic_two_header_directory())
    raw = json.dumps({'format': spec['format'], 'headers': spec['headers']}).encode()
    source, output = tmp_path / 'spec.json', tmp_path / 'own.bin'
    source.write_bytes(raw)
    args = ['write-navionics-directory', '--spec', str(source), '--expect-spec-sha256',
            hashlib.sha256(raw).hexdigest(), '--output', str(output)]
    assert main(args) == 0
    capsys.readouterr()
    assert source.read_bytes() == raw
    assert output.read_bytes() == synthetic_two_header_directory()


def test_wrong_sha_duplicate_json_and_symlink_refuse_before_write(tmp_path, capsys):
    source, output = tmp_path / 'spec.json', tmp_path / 'own.bin'
    raw = b'{"format":"one","format":"two","headers":[]}'
    source.write_bytes(raw)
    args = ['write-navionics-directory', '--spec', str(source), '--output', str(output)]
    assert main(args) == 2
    capsys.readouterr()
    for digest in ('0' * 64, hashlib.sha256(raw).hexdigest()):
        assert main(args + ['--expect-spec-sha256', digest]) == 2
        capsys.readouterr()
        assert not output.exists()
    output.symlink_to(tmp_path / 'missing')
    assert main(['write-navionics-directory', '--synthetic-two-header', '--output', str(output)]) == 2
    capsys.readouterr()
    assert not output.exists() and output.is_symlink()


def test_oversize_and_irrelevant_pin_refuse(tmp_path, capsys):
    source, output = tmp_path / 'spec.json', tmp_path / 'own.bin'
    raw = b' ' * 65537
    source.write_bytes(raw)
    assert main(['write-navionics-directory', '--spec', str(source), '--expect-spec-sha256',
                 hashlib.sha256(raw).hexdigest(), '--output', str(output)]) == 2
    assert 'SPEC_EXCEEDS_64_KIB' in capsys.readouterr().out
    assert not output.exists()
    assert main(['write-navionics-directory', '--synthetic-two-header', '--expect-spec-sha256',
                 '0' * 64, '--output', str(output)]) == 2
    capsys.readouterr()
    assert not output.exists()
