"""Independent numerical checks of the conditional recovery analysis."""
import importlib.util
from pathlib import Path
import shutil
import subprocess

import numpy as np
import pytest

source = Path(__file__).resolve().parents[1]/'tools/analyze_ten_hz_feasibility.py'
spec = importlib.util.spec_from_file_location('ten_hz_analysis', source)
analysis = importlib.util.module_from_spec(spec)
spec.loader.exec_module(analysis)


def test_two_time_constants_recovered_from_known_noisy_reference():
    t = np.arange(820)*.0005-.05
    rng = np.random.default_rng(31)
    y = .646-.07*np.exp(-t/.022)-.175*np.exp(-t/.052)
    y += rng.normal(0, .00005, len(t))
    result = analysis.fit_response(t, y)
    assert abs(result['asymptote_gp26_v']-.646) < .0001
    assert [term['tau_s'] for term in result['terms']] == pytest.approx([.022,.052],rel=.02)
    assert result['rmse_gp26_v'] < .0001


def test_periodic_formula_agrees_with_independent_time_step_charge_decay():
    fit = {'asymptote_gp26_v': .646,
           'terms':[{'amplitude_gp26_v': .24, 'tau_s': .05}]}
    native, period, width = .3863, .1, .0001
    # Integrate d(deficit)/dt=-deficit/tau+I/C, rather than using the closed form.
    dt = .000001
    decay = .05
    kick = .24*(1-np.exp(-native/decay))
    current = kick/(decay*(1-np.exp(-width/decay)))
    deficit = 0.
    steps, pulse_steps = round(period/dt), round(width/dt)
    for _ in range(20):
        before = deficit
        for k in range(steps):
            deficit += dt*((current if k<pulse_steps else 0)-deficit/decay)
    predicted = analysis.periodic_prediction(fit,native,period,pulse_width=width)
    assert predicted == pytest.approx(.646-before,abs=2e-6)
    assert analysis.periodic_prediction(fit,native,period,scale=0)==.646


@pytest.mark.parametrize('native,period,scale,width',[
    (0,.1,1,.0001),(.3863,.00001,1,.0001),(.3863,.1,-1,.0001),
    (.3863,.1,1,0),(.3863,float('nan'),1,.0001)])
def test_invalid_periodic_assumptions_rejected(native,period,scale,width):
    with pytest.raises(ValueError):
        analysis.periodic_prediction({'asymptote_gp26_v':.646,'terms':[]},native,period,scale,width)


@pytest.mark.parametrize('rate,expected',[(250000,6000),(125000,3000)])
def test_actual_capture_module_receives_ten_hz_markers_without_hardware(tmp_path,rate,expected):
    cc = shutil.which('cc')
    if not cc:
        pytest.skip('Native C compiler unavailable')
    module = source.parents[2]/'no5-pico-protocol-prototype/next/shared-pico-tx-marker-v1/src/modules'
    c = tmp_path/'probe.c'
    c.write_text(r'''
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include "capture.h"
static capture_t capture;
int main(int argc,char **argv) {
    assert(argc==2);
    const unsigned rate=(unsigned)atoi(argv[1]), interval=rate/10, count=16384;
    const unsigned requested=6000, block=4096;
    const uint64_t stop=(uint64_t)(requested-1)*interval+count+block;
    unsigned delivered=0;
    assert(capture_configure(&capture,count,0,100,10));
    for(uint64_t first=0;first<stop;first+=block) {
        uint64_t marker=((first+interval-1)/interval)*interval;
        int start=!capture.collecting && !capture.ready && marker<first+block && marker/interval<requested;
        for(unsigned i=0;i<block;i++) {
            uint64_t index=first+i;
            uint16_t value=(uint16_t)(index%4096);
            if(start && index==marker) assert(capture_begin_marker(&capture,value,index));
            else if(capture.collecting) assert(capture_sample(&capture,value,index));
        }
        if(capture.ready) {
            assert(capture.trigger_index%interval==0);
            assert(capture.output[0]==capture.trigger_index%4096);
            assert(capture.output[count-1]==(capture.trigger_index+count-1)%4096);
            delivered++; capture.ready=false;
        }
    }
    printf("%u\n",delivered);
    return 0;
}
''')
    exe=tmp_path/'probe'
    subprocess.run([cc,'-O2','-std=c11','-I',str(module),str(c),str(module/'capture.c'),'-o',str(exe)],
                   check=True,capture_output=True,text=True,timeout=30)
    result=subprocess.run([str(exe),str(rate)],check=True,capture_output=True,text=True,timeout=30)
    assert int(result.stdout)==expected
