from pathlib import Path
import subprocess
import pytest
ROOT=Path(__file__).resolve().parents[1]

def test_continuous_beyond_ten_minutes_preserves_every_abort_path(tmp_path):
    binary=tmp_path/'clip-continuous'
    subprocess.run(['cc','-O2','-std=c11','-Wall','-Wextra','-Werror','-I',str(ROOT/'src/modules'),
        str(ROOT/'tests/clip_continuous_test.c'),str(ROOT/'src/modules/clip_diagnostic.c'),
        str(ROOT/'src/modules/transmit.c'),'-o',str(binary)],check=True)
    subprocess.run([str(binary)],check=True)

def test_depth_rate_variable_windows_and_fail_closed_boundaries(tmp_path):
    binary=tmp_path/'depth-rate'
    subprocess.run(['cc','-std=c11','-Wall','-Wextra','-Werror','-I',str(ROOT/'src/modules'),
        str(ROOT/'tests/depth_rate_test.c'),str(ROOT/'src/modules/clip_diagnostic.c'),
        str(ROOT/'src/modules/transmit.c'),'-o',str(binary)],check=True)
    subprocess.run([str(binary)],check=True)

def test_device_policy_failures_and_repeated_periodic_ping_link(tmp_path):
    binary=tmp_path/'transmit-test'
    subprocess.run(['cc','-std=c11','-Wall','-Wextra','-Werror','-I',str(ROOT/'src/modules'),
                    str(ROOT/'tests/transmit_policy_test.c'),str(ROOT/'src/modules/transmit.c'),'-o',str(binary)],check=True)
    subprocess.run([str(binary)],check=True)

def test_future_marker_survives_a_queued_older_dma_block(tmp_path):
    binary=tmp_path/'marker-index'
    subprocess.run(['cc','-std=c11','-Wall','-Wextra','-Werror','-I',str(ROOT/'tests/include'),
                    str(ROOT/'tests/marker_index_test.c'),'-o',str(binary)],check=True)
    subprocess.run([str(binary)],check=True)


def test_small_internal_ping_blocks_keep_exact_packet_samples_and_time(tmp_path):
    binary=tmp_path/'ping-blocks'
    subprocess.run(['cc','-std=c11','-Wall','-Wextra','-Werror','-I',str(ROOT/'tests/include'),
        str(ROOT/'tests/ping_block_geometry_test.c'),str(ROOT/'src/modules/sonard_ping.c'),
        str(ROOT/'src/modules/capture.c'),str(ROOT/'src/modules/transmit.c'),'-o',str(binary)],check=True)
    subprocess.run([str(binary)],check=True)

@pytest.mark.parametrize('cycles',[1,2,4,8,128])
@pytest.mark.parametrize('program,expected_mask',[('burst.pio',1),('clip_burst.pio',17)])
def test_pio_has_exact_count_high_time_and_finishes_low(cycles,program,expected_mask):
    lines=[];labels={}
    for raw in (ROOT/'src/modules'/program).read_text().splitlines():
        s=raw.strip()
        if not s or s.startswith('.'): continue
        if s.endswith(':'): labels[s[:-1]]=len(lines)
        else: lines.append(s)
    high,low=125,509
    fifo=iter([cycles-1,high-3,low-4]);registers={'x':0,'y':0,'isr':0,'osr':0}
    pc=clock=pin=0; transitions=[]; irq=False
    for _ in range(1000000):
        instruction=lines[pc]; next_pc=pc+1
        if instruction=='pull block': registers['osr']=next(fifo)
        elif instruction.startswith('mov '):
            a,b=instruction[4:].split(', ');registers[a]=registers[b]
        elif instruction.startswith('set pins, '):
            new=int(instruction.rsplit(' ',1)[1])
            if new!=pin: transitions.append((clock,new))
            pin=new
        elif instruction.startswith('jmp '):
            parts=instruction.split()
            if len(parts)==2:
                assert parts[1]=='park' and irq
                break
            reg=parts[1][0];value=registers[reg];registers[reg]=(value-1)&0xffffffff
            if value: next_pc=labels[parts[2]]
        elif instruction=='irq 0': irq=True
        else: raise AssertionError(instruction)
        pc=next_pc;clock+=1
    else: raise AssertionError('PIO never parked')
    assert irq and pin==0 and len(transitions)==cycles*2
    assert all(value==expected_mask for _,value in transitions[::2])
    assert all(transitions[i+1][0]-transitions[i][0]==high for i in range(0,len(transitions),2))
    assert all(transitions[i+1][0]-transitions[i][0]==low for i in range(1,len(transitions)-1,2))

def test_emergency_stop_precedes_adc_stop_and_gp16_stays_input():
    acquisition=(ROOT/'src/base/acquisition.c').read_text()
    assert 'b->safety_abort(b->safety_context); adc_run(false)' in acquisition
    assert 'gpio_set_dir(TX_MARKER_GPIO,false)' in acquisition
    session=(ROOT/'src/base/session.c').read_text()
    assert 'pwm_set_gpio_level' not in session
    assert 'transmit_hw_stop(app,TX_DISCONNECTED)' in session


def test_clip_finite_controller_counterexamples_and_normal_guard(tmp_path):
    binary=tmp_path/'clip-test'
    subprocess.run(['cc','-std=c11','-Wall','-Wextra','-Werror','-I',str(ROOT/'src/modules'),
        str(ROOT/'tests/clip_policy_test.c'),str(ROOT/'src/modules/clip_diagnostic.c'),
        str(ROOT/'src/modules/transmit.c'),'-o',str(binary)],check=True)
    subprocess.run([str(binary)],check=True)


def test_scope_wire_work_does_not_starve_finite_clip_deadlines(tmp_path):
    binary=tmp_path/'clip-stream-checkpoints'
    subprocess.run(['cc','-std=c11','-Wall','-Wextra','-Werror','-I',str(ROOT/'tests/include'),
        str(ROOT/'tests/clip_stream_checkpoint_test.c'),str(ROOT/'src/modules/clip_diagnostic.c'),
        str(ROOT/'src/modules/transmit.c'),'-o',str(binary)],check=True)
    subprocess.run([str(binary)],check=True)

def test_interleaved_endurance_hard_limit_and_physical_ping_failures(tmp_path):
    binary=tmp_path/'clip-endurance'
    subprocess.run(['cc','-std=c11','-Wall','-Wextra','-Werror','-I',str(ROOT/'src/modules'),
        str(ROOT/'tests/clip_endurance_test.c'),str(ROOT/'src/modules/clip_diagnostic.c'),
        str(ROOT/'src/modules/transmit.c'),'-o',str(binary)],check=True)
    subprocess.run([str(binary)],check=True)


def test_recorded_off_phase_marker_preserves_raw_link_and_requires_real_recovery(tmp_path):
    binary=tmp_path/'clip-reference'
    subprocess.run(['cc','-std=c11','-Wall','-Wextra','-Werror','-I',str(ROOT/'src/modules'),
        str(ROOT/'tests/clip_reference_test.c'),str(ROOT/'src/modules/clip_diagnostic.c'),
        str(ROOT/'src/modules/transmit.c'),'-o',str(binary)],check=True)
    subprocess.run([str(binary)],check=True)

def test_marker_refractory_survives_early_dma_consumption(tmp_path):
    source=(ROOT/'src/base/acquisition.c').read_text()
    irq=source[source.index('static void __isr marker_irq'):source.index('void base_trip')]
    code='''#include <stdint.h>
#include <stdbool.h>
#include <assert.h>
#define __isr
#define TX_MARKER_GPIO 16u
#define TX_MARKER_REFRACTORY_US 5000u
#define GPIO_IRQ_EDGE_RISE 1u
typedef unsigned int uint;
typedef struct { struct { bool marker_enabled,marker_pending; uint64_t marker_us,marker_observed_us; unsigned marker_rejected; } acq; } no5_base_t;
static no5_base_t *irq_owner;
static uint64_t now;
static uint64_t time_us_64(void) { return now; }
'''+irq+'''
int main(void) {
 no5_base_t b={0}; irq_owner=&b; b.acq.marker_enabled=true;
 now=1000000; marker_irq(16,1); assert(b.acq.marker_observed_us==now);
 b.acq.marker_pending=false; // DMA consumes first edge before the burst ends.
 now+=20; marker_irq(16,1); assert(b.acq.marker_observed_us==1000000 && !b.acq.marker_pending);
 now=1000512; marker_irq(16,1); assert(b.acq.marker_observed_us==1000000);
 now=1004999; marker_irq(16,1); assert(b.acq.marker_observed_us==1000000);
 now=1086000; marker_irq(16,1); assert(b.acq.marker_observed_us==now);
 assert(b.acq.marker_rejected==3);
 b.acq.marker_pending=false; b.acq.marker_enabled=false;
 now+=100000; marker_irq(16,1); assert(b.acq.marker_observed_us==1086000);
}
'''
    path=tmp_path/'marker-irq.c';path.write_text(code);binary=tmp_path/'marker-irq'
    subprocess.run(['cc','-std=c11','-Wall','-Wextra','-Werror',str(path),'-o',str(binary)],check=True)
    subprocess.run([str(binary)],check=True)
