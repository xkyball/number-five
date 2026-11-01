"""Small own JSON/CLI materialization controls; no original runtime."""
import hashlib
import json

from no_chart_converter.cli import main
from test_navionics_layered_batch import document


def source(tmp_path):
    path = tmp_path / 'spec.json'
    path.write_text(json.dumps(document()))
    return path, hashlib.sha256(path.read_bytes()).hexdigest()


def plan(path, digest, capsys):
    assert main(['plan-navionics-layered-batch', '--spec', str(path),
                 '--expect-spec-sha256', digest]) == 0
    return json.loads(capsys.readouterr().out)


def test_real_cli_plans_and_persists_same_two_layer_bytes(tmp_path, capsys):
    path, digest = source(tmp_path)
    ready = plan(path, digest, capsys)
    output = tmp_path / 'own.bin'
    assert main(['write-navionics-layered-batch', '--spec', str(path),
                 '--expect-spec-sha256', digest, '--expect-plan-sha256', ready['planSHA256'],
                 '--output', str(output)]) == 0
    result = json.loads(capsys.readouterr().out)
    assert output.stat().st_size == result['output']['bytes'] == 462
    assert hashlib.sha256(output.read_bytes()).hexdigest() == result['output']['sha256']
    assert result['persistedFileReadback'] is True and result['nativeReaderExecuted'] is False
    assert result['plan'] == ready['plan']


def test_wrong_spec_or_plan_refuses_before_output(tmp_path, capsys):
    path, digest = source(tmp_path)
    output = tmp_path / 'absent.bin'
    for spec_sha in ('0' * 64, digest):
        assert main(['write-navionics-layered-batch', '--spec', str(path),
                     '--expect-spec-sha256', spec_sha, '--expect-plan-sha256', '0' * 64,
                     '--output', str(output)]) != 0
        assert not output.exists()
        capsys.readouterr()


def test_no_clobber_existing_output_or_symlink(tmp_path, capsys):
    path, digest = source(tmp_path)
    ready = plan(path, digest, capsys)
    target = tmp_path / 'keep.bin'; target.write_bytes(b'keep')
    link = tmp_path / 'link.bin'; link.symlink_to(target)
    for output in (target, link):
        assert main(['write-navionics-layered-batch', '--spec', str(path),
                     '--expect-spec-sha256', digest, '--expect-plan-sha256', ready['planSHA256'],
                     '--output', str(output)]) != 0
        assert target.read_bytes() == b'keep'
        capsys.readouterr()
    assert link.is_symlink()


def test_unsupported_layer_unit_or_attribute_refuses_before_write(tmp_path, capsys):
    for field in ('depth_unit', 'opaque_word'):
        doc = document(); doc['layers'][1][field] = 1
        path = tmp_path / 'bad.json'; path.write_text(json.dumps(doc))
        sha = hashlib.sha256(path.read_bytes()).hexdigest()
        output = tmp_path / 'absent.bin'
        assert main(['write-navionics-layered-batch', '--spec', str(path),
                     '--expect-spec-sha256', sha, '--expect-plan-sha256', '0' * 64,
                     '--output', str(output)]) != 0
        assert not output.exists()
        capsys.readouterr()
