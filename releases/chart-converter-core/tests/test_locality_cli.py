"""Actual CLI round trips with invented source and binding-only approvals."""
import json

from no_chart_converter import cli
from test_locality_apply import TILE, approval, fixture, sha


def test_cli_candidate_then_explicit_reviewed_apply(tmp_path, capsys):
    source = tmp_path / 'source.rx5'
    original = fixture()
    source.write_bytes(original)
    candidate = tmp_path / 'candidate.json'
    assert cli.main(['plan-locality', '--source', str(source), '--expect-sha256', sha(original),
                     '--tile', *map(str, TILE), '--output', str(candidate)]) == 0
    planned = json.loads(candidate.read_text())
    assert planned['status'] == 'CANDIDATE_PLAN_ONLY_NOT_APPROVAL'
    assert source.read_bytes() == original
    assert not list(tmp_path.glob('output*'))
    allowlist, pin = approval(tmp_path, original)
    output, report = tmp_path / 'output.rx5', tmp_path / 'output-report.json'
    assert cli.main(['apply-locality', '--source', str(source), '--allowlist', str(allowlist),
                     '--expect-allowlist-sha256', pin, '--output', str(output),
                     '--report', str(report)]) == 0
    actual = json.loads(report.read_text())
    assert actual['status'] == 'APPLIED_REVIEWED_SCOPED_LOCALITY'
    assert actual['wireReadback']['owners'] == 7
    assert actual['titleChanged'] is False and actual['actualChildPrunes'] == 0
    assert actual['sourcePrefixInverseSHA256'] == sha(original)
    assert source.read_bytes() == original
    assert sha(output.read_bytes()) == actual['outputSHA256']
    assert json.loads(capsys.readouterr().out.splitlines()[-1]) == actual


def test_cli_plan_requires_expected_source_and_never_approves_itself(tmp_path, capsys):
    source = tmp_path / 'source.rx5'
    source.write_bytes(fixture())
    candidate = tmp_path / 'candidate.json'
    args = ['plan-locality', '--source', str(source), '--tile', *map(str, TILE),
            '--output', str(candidate)]
    assert cli.main(args + ['--expect-sha256', '0' * 64]) == 2
    assert not candidate.exists()
    assert cli.main(args) == 0
    output, report = tmp_path / 'output.rx5', tmp_path / 'report.json'
    assert cli.main(['apply-locality', '--source', str(source), '--allowlist', str(candidate),
                     '--expect-allowlist-sha256', sha(candidate.read_bytes()),
                     '--output', str(output), '--report', str(report)]) == 2
    assert not output.exists() and not report.exists()
    assert 'REVIEWED_LOCALITY_APPROVAL_REQUIRED' in capsys.readouterr().out


def test_cli_preserves_existing_report_before_computing_changes(tmp_path, capsys):
    source = tmp_path / 'source.rx5'
    original = fixture()
    source.write_bytes(original)
    allowlist, pin = approval(tmp_path, original)
    output, report = tmp_path / 'output.rx5', tmp_path / 'report.json'
    report.write_bytes(b'EXISTING USER REPORT')
    assert cli.main(['apply-locality', '--source', str(source), '--allowlist', str(allowlist),
                     '--expect-allowlist-sha256', pin, '--output', str(output),
                     '--report', str(report)]) == 2
    assert not output.exists()
    assert report.read_bytes() == b'EXISTING USER REPORT'
    assert source.read_bytes() == original
    assert 'FRESH_DISTINCT_LOCALITY_OUTPUT_AND_REPORT_REQUIRED' in capsys.readouterr().out
