import json
import os
from pathlib import Path
import sys
import threading
import time

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from archive_wait_observer import ArchiveWaitSteps
from archive_step_observer import ArchiveSteps
from launch_archive_wait_diagnostic import claim_once


def recorder():
    # Retention tests do not acquire a real monitoring slot or start a source.
    value = object.__new__(ArchiveWaitSteps)
    value.active = {}
    value.slow = []
    value.slow_s = .2
    value._retained = {}
    value._capture_ages = {}
    return value


def test_once_only_launch_claim_preserves_original_receipt(tmp_path):
    assert claim_once(tmp_path) is True
    path = tmp_path / 'inprocess-wait-claimed.json'
    before = path.read_bytes()
    assert claim_once(tmp_path) is False
    assert path.read_bytes() == before
    assert path.stat().st_mode & 0o777 == 0o600


def capture(value, tid, label, started, now):
    value.active = {tid: [(None, label, started, 0.)]}
    value._capture(now=now, frames_provider=lambda: {})


def test_late_long_wait_replaces_short_stack_after_capacity_is_full():
    value = recorder()
    for n in range(128):
        capture(value, 1, 'background', float(n), n + .25)
    assert len(value.slow) == 128
    capture(value, 2, 'RAW:C.write', 200., 203.5)
    assert len(value.slow) == 128
    assert any(row['active_operation'] == 'RAW:C.write' and row['age_s'] == 3.5
               for row in value.slow)
    capture(value, 3, 'shorter', 300., 300.21)
    assert not any(row['active_operation'] == 'shorter' for row in value.slow)


def test_still_running_call_is_resampled_at_two_seconds():
    value = recorder()
    capture(value, 1, 'RAW:C.fsync', 10., 10.21)
    capture(value, 1, 'RAW:C.fsync', 10., 11.6)
    capture(value, 1, 'RAW:C.fsync', 10., 12.01)
    assert len(value.slow) == 1
    assert value.slow[0]['age_s'] > 2
    assert value.slow[0]['capture_policy'] == 'longest_waits_with_resampling'


def test_completed_call_history_and_frames_remain_bounded():
    value = recorder()
    for n in range(500):
        capture(value, 1, 'RAW', float(n), n + .3)
        assert len(value._capture_ages) <= 1
        assert len(value.slow) <= 128
    value.active = {}
    value._capture(now=1000., frames_provider=lambda: {})
    assert value._capture_ages == {}
    assert len(value._retained) <= 128


def test_real_monitoring_lifecycle_records_a_wait_and_releases_its_slot(tmp_path):
    event = threading.Event()

    def wait_in_archive_fixture():
        event.wait(.08)

    observer = ArchiveWaitSteps([wait_in_archive_fixture], tmp_path / 'report.json',
                                duration_s=1, slow_s=.005).start()
    try:
        wait_in_archive_fixture()
    finally:
        observer.close()
    report = json.loads((tmp_path / 'report.json').read_text())
    assert report['error_count'] == 0
    assert report['monitoring_removed'] is True
    assert report['qualifying'] is False
    assert report['source_commands_sent'] == 0
    assert report['totals'][wait_in_archive_fixture.__qualname__]['calls'] == 1
    assert report['slow_stacks']
    assert all(len(row['python_stack_no_locals']) <= 16 for row in report['slow_stacks'])
    assert sys.monitoring.get_tool(observer.tool) is None


@pytest.mark.parametrize('observer_class,expected_long_stack',
                         [(ArchiveSteps, False), (ArchiveWaitSteps, True)])
def test_real_native_wait_after_full_stack_capacity(tmp_path, observer_class, expected_long_stack):
    read_fd, write_fd = os.pipe()
    values = []

    def blocked_archive_fixture():
        values.append(os.read(read_fd, 1))

    observer = observer_class([blocked_archive_fixture], tmp_path / 'native.json',
                              duration_s=4, slow_s=.2)
    for n in range(128):
        row = {'epoch_s': 0., 'thread_ident': 0, 'active_operation': 'older_short_wait',
               'age_s': .25, 'python_stack_no_locals': []}
        observer.slow.append(row)
        if isinstance(observer, ArchiveWaitSteps):
            observer._retained[(0, float(n))] = row
    observer.start()
    worker = threading.Thread(target=blocked_archive_fixture)
    worker.start()
    try:
        deadline = time.monotonic() + 2.2
        while time.monotonic() < deadline:
            if any('C.read' in row['active_operation'] and row['age_s'] >= 2
                   for row in observer.slow):
                break
            time.sleep(.01)
    finally:
        os.write(write_fd, b'x')
        worker.join(1)
        os.close(read_fd)
        os.close(write_fd)
        observer.close()
    assert not worker.is_alive() and values == [b'x']
    report = json.loads((tmp_path / 'native.json').read_text())
    assert report['error_count'] == 0 and report['monitoring_removed']
    assert len(report['slow_stacks']) == 128
    assert any('C.read' in label and row['maximum_wall_s'] >= 2
               for label, row in report['totals'].items())
    assert any('C.read' in row['active_operation'] and row['age_s'] >= 2
               for row in report['slow_stacks']) is expected_long_stack
