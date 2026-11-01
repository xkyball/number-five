"""No-clobber generation publication and fail-closed OS/filesystem errors."""
import ctypes
import errno
from types import SimpleNamespace

import pytest

from no_chart_converter import cache_decoder as decoder


def test_real_generation_publish_moves_complete_tree(tmp_path):
    stage = tmp_path / 'stage'
    (stage / 'cells').mkdir(parents=True)
    (stage / 'cells' / 'synthetic.json').write_bytes(b'{"complete":true}\n')
    (stage / 'receipt.json').write_bytes(b'{"verified":true}\n')
    destination = tmp_path / 'published'
    decoder.atomic_publish(stage, destination)
    assert not stage.exists()
    assert (destination / 'cells' / 'synthetic.json').read_bytes() == b'{"complete":true}\n'
    assert (destination / 'receipt.json').read_bytes() == b'{"verified":true}\n'


@pytest.mark.parametrize('target_kind', ['empty_directory', 'directory', 'file', 'symlink'])
def test_real_existing_destination_is_never_replaced(tmp_path, target_kind):
    stage = tmp_path / 'stage'
    stage.mkdir()
    (stage / 'complete').write_bytes(b'new generation')
    target = tmp_path / 'existing'
    sentinel = b'existing generation'
    if target_kind in ('empty_directory', 'directory'):
        target.mkdir()
        if target_kind == 'directory':
            (target / 'keep').write_bytes(sentinel)
    elif target_kind == 'file':
        target.write_bytes(sentinel)
    else:
        (tmp_path / 'original').write_bytes(sentinel)
        target.symlink_to('original')
    before = target.lstat()
    with pytest.raises(decoder.DecodeError) as refusal:
        decoder.atomic_publish(stage, target)
    assert refusal.value.code == 'OUTPUT_EXISTS'
    assert 'errno=' in refusal.value.detail
    assert target.lstat().st_ino == before.st_ino
    assert (stage / 'complete').read_bytes() == b'new generation'
    if target_kind == 'directory':
        assert (target / 'keep').read_bytes() == sentinel
    elif target_kind in ('file', 'symlink'):
        assert target.read_bytes() == sentinel
    else:
        assert list(target.iterdir()) == []


@pytest.mark.parametrize('system', ['Darwin', 'Linux'])
@pytest.mark.parametrize('error,expected', [
    (errno.ENOTSUP, 'ATOMIC_PUBLISH_UNAVAILABLE'),
    (errno.EOPNOTSUPP, 'ATOMIC_PUBLISH_UNAVAILABLE'),
    (errno.ENOSYS, 'ATOMIC_PUBLISH_UNAVAILABLE'),
    (errno.EEXIST, 'OUTPUT_EXISTS'),
    (errno.ENOTEMPTY, 'OUTPUT_EXISTS'),
    (errno.EACCES, 'ATOMIC_PUBLISH_FAILED'),
    (errno.EIO, 'ATOMIC_PUBLISH_FAILED'),
    (errno.EXDEV, 'ATOMIC_PUBLISH_FAILED'),
    (errno.EINVAL, 'ATOMIC_PUBLISH_FAILED'),
])
def test_refusal_retains_errno_and_has_no_unsafe_fallback(tmp_path, monkeypatch, system, error, expected):
    calls = []

    def refuse(*args):
        calls.append(args)
        ctypes.set_errno(error)
        return -1

    lib = SimpleNamespace(**{('renamex_np' if system == 'Darwin' else 'renameat2'): refuse})
    monkeypatch.setattr(decoder.ctypes, 'CDLL', lambda *args, **kwargs: lib)
    monkeypatch.setattr(decoder.platform, 'system', lambda: system)
    stage = tmp_path / 'stage'
    stage.mkdir()
    (stage / 'complete').write_bytes(b'complete generation')
    target = tmp_path / 'published'
    with pytest.raises(decoder.DecodeError) as refusal:
        decoder.atomic_publish(stage, target)
    assert refusal.value.code == expected
    assert f'errno={error} {errno.errorcode[error]}' in refusal.value.detail
    assert len(calls) == 1
    assert calls[0][-1] == (4 if system == 'Darwin' else 1)
    assert not target.exists()
    assert (stage / 'complete').read_bytes() == b'complete generation'
    if expected == 'ATOMIC_PUBLISH_UNAVAILABLE':
        assert 'supported filesystem' in refusal.value.detail
        assert 'verified-copy receipt' in refusal.value.detail


@pytest.mark.parametrize('system', ['Darwin', 'Linux', 'Unsupported'])
def test_missing_no_clobber_primitive_refuses_cleanly(tmp_path, monkeypatch, system):
    monkeypatch.setattr(decoder.ctypes, 'CDLL', lambda *args, **kwargs: SimpleNamespace())
    monkeypatch.setattr(decoder.platform, 'system', lambda: system)
    stage = tmp_path / 'stage'
    stage.mkdir()
    with pytest.raises(decoder.DecodeError) as refusal:
        decoder.atomic_publish(stage, tmp_path / 'published')
    assert refusal.value.code == 'ATOMIC_PUBLISH_UNAVAILABLE'
    assert stage.is_dir()
