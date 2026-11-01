"""Local output semantics: chart-derived bytes stay private and are never clobbered."""
import os
import stat

import pytest

from no_chart_converter.cli import write_new


def test_new_output_is_private_even_with_permissive_umask(tmp_path):
    target = tmp_path / 'synthetic.rx5'
    previous = os.umask(0)
    try:
        write_new(target, b'OWN SYNTHETIC OUTPUT')
    finally:
        os.umask(previous)
    assert stat.S_IMODE(target.stat().st_mode) == 0o600
    assert target.read_bytes() == b'OWN SYNTHETIC OUTPUT'
    with pytest.raises(FileExistsError):
        write_new(target, b'REPLACEMENT')
    assert target.read_bytes() == b'OWN SYNTHETIC OUTPUT'


def test_new_output_does_not_follow_existing_symlink(tmp_path):
    original = tmp_path / 'original'
    original.write_bytes(b'UNCHANGED')
    target = tmp_path / 'symlink'
    target.symlink_to(original)
    with pytest.raises(FileExistsError):
        write_new(target, b'REPLACEMENT')
    assert original.read_bytes() == b'UNCHANGED'
