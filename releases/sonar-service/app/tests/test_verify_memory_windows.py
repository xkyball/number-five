"""Retain exact endpoint statistics within20 rows;6h has720 resource samples."""
import importlib.util
from pathlib import Path
import statistics
from collections import deque

spec=importlib.util.spec_from_file_location('permanent_memory_windows',Path(__file__).resolve().parents[1]/'tools/verify_permanent_chain.py')
verifier=importlib.util.module_from_spec(spec);spec.loader.exec_module(verifier)


def test_no_duplicate_samples_before_both_windows_are_full():
    windows=verifier.MemoryWindows()
    for count in range(1, 21):
        windows.append({'sample':count})
        assert [v['sample'] for v in windows]==list(range(1,count+1))
        assert len(windows)==count


def test_actual_start_and_end_survive_more_than_the_old_3000_sample_limit():
    windows=verifier.MemoryWindows()
    for count in range(4321):
        windows.append({'sample':count})
        assert len(windows.first)<=10 and len(windows.last)<=10
    assert [v['sample'] for v in windows]==list(range(10))+list(range(4311,4321))
    assert len(windows)==20 and windows.total_samples==4321


def test_fixed_windows_match_legacy_statistics_for_all_720_six_hour_samples():
    windows=verifier.MemoryWindows();legacy=deque(maxlen=3000)
    for count in range(720):
        row={'allocation':count*count%10007}
        windows.append(row);legacy.append(row)
    for collection in [windows,legacy]:
        values=[r['allocation']for r in collection]
        actual=statistics.median(values[-10:])-statistics.median(values[:10])
        if collection is windows:expected=actual
        else:assert actual==expected


def test_early_memory_growth_stays_visible_even_beyond_the_legacy_retention_horizon():
    windows=verifier.MemoryWindows()
    for count in range(4321):
        windows.append({'host_rss_kib':100*1024+(40*1024 if count>=1000 else 0),
                        'emulator_phys_footprint_kib':3*1024**2+(600*1024 if count>=1000 else 0)})
    for field,limit in [('host_rss_kib',32*1024),('emulator_phys_footprint_kib',512*1024)]:
        values=[v[field]for v in windows]
        assert statistics.median(values[-10:])-statistics.median(values[:10])>limit
