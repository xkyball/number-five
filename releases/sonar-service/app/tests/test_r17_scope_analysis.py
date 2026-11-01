"""Check recovery timing, honest marker gaps and the native queue fix offline."""
import importlib.util
from pathlib import Path
import shutil
import subprocess
import sys

import numpy as np
import pytest

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
spec=importlib.util.spec_from_file_location('r17_scope_analysis',ROOT/'tools/analyze_r17_scope.py')
analysis=importlib.util.module_from_spec(spec)
spec.loader.exec_module(analysis)


def test_recovery_uses_measured_time_zero_and_does_not_call_a_smooth_ramp_an_RX_gate():
    t=(np.arange(800)-100)*.0005
    row=np.full(len(t),.65)
    row[t>=0]-=.25*np.exp(-t[t>=0]/.05)
    result=analysis.recovery_summary(t,np.tile(row,(12,1)))
    assert result['recovery']['95']['ms_after_GP16_p10_p50_p90'][1]==pytest.approx(-50*np.log(.05),abs=1)
    assert result['recovery']['99']['ms_after_GP16_p10_p50_p90'][1]==pytest.approx(-50*np.log(.01),abs=1)
    assert result['late_window_change_gp26_v_p10_p50_p90'][1]>0
    assert not result['RX_enable_function_proven']


def test_missing_physical_markers_remain_missing_despite_slot_counting():
    markers=np.delete(np.arange(32,dtype=np.int64)*193000,[2,10,19])
    original=markers.copy()
    result=analysis.marker_cadence(markers)
    assert result['fitted_native_interval_ms']==pytest.approx(386,abs=1e-8)
    assert result['missing_slots_assuming_original_cadence']==3
    assert np.array_equal(markers,original)


def test_nonperiodic_B_excursion_is_excluded_without_altering_samples():
    values=np.full(3100000,round(.65/analysis.SCALE),dtype=np.uint16)
    markers=np.arange(300000,2800000,200000)
    suspect=int(markers[5]);values[suspect+10000:suspect+20000]=100
    original=values.copy()
    _,rows,used,rejected=analysis.marked_windows(values,markers,'R17b')
    assert rejected.tolist()==[suspect]
    assert len(used)==len(markers)-1
    assert rows.min()>.6
    assert np.array_equal(values,original)


def test_native_queue_fix_preserves_an_edge_belonging_to_the_next_DMA_block(tmp_path):
    cc=shutil.which('cc')
    if not cc:pytest.skip('Native C compiler unavailable')
    fw=ROOT.parent/'no5-pico-protocol-prototype/next/shared-pico-tx-marker-v1'
    base=fw/'src/base'
    historical=subprocess.run(['git','show','100c0ab:projects/no5-pico-protocol-prototype/next/shared-pico-tx-marker-v1/src/base/acquisition.c'],cwd=ROOT,capture_output=True,text=True)
    if historical.returncode:pytest.skip('Historical source unavailable in this clone')
    # Compare the historical marker function, not its unrelated old DMA
    # memory layout against today's internal acquisition-state structure.
    begin=historical.stdout.index('bool base_take_tx_marker(')
    end=historical.stdout.index('\n}',begin)+2
    old=tmp_path/'old_acquisition.c';old.write_text(historical.stdout[begin:end])
    stubs=(fw/'tests/marker_index_test.c').read_text().split('int main(void)')[0]
    stubs=stubs.replace('"../src/base/internal.h"','"'+str(base/'internal.h')+'"')
    stubs=stubs.replace('"../src/base/api.h"','"'+str(base/'api.h')+'"')
    main='''
int main(void) {
 no5_base_t b={0}; uint32_t index=999; b.acq.marker_enabled=true;
 b.acq.marker_pending=true; b.acq.marker_us=1008692;
 int previous=base_take_tx_marker(&b,1000000,4096,2000,&index);
 int pending=b.acq.marker_pending;
 int following=base_take_tx_marker(&b,1008192,4096,2000,&index);
 printf("%d %d %d %u\\n",previous,pending,following,index);
 return 0;
}
'''
    observed={}
    for name,source in [('historical',old),('prepared',base/'acquisition.c')]:
        c=tmp_path/(name+'.c')
        c.write_text(stubs.replace('"../src/base/acquisition.c"','"'+str(source)+'"')+main)
        exe=tmp_path/name
        subprocess.run([cc,'-std=c11','-I',str(fw/'tests/include'),'-I',str(base),str(c),'-o',str(exe)],check=True,capture_output=True,text=True,timeout=30)
        result=subprocess.run([str(exe)],check=True,capture_output=True,text=True,timeout=10)
        observed[name]=result.stdout.strip()
    assert observed['historical']=='0 0 0 999'
    assert observed['prepared']=='0 1 1 250'
