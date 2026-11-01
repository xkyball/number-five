import asyncio
from dataclasses import replace
from pathlib import Path
import struct
import time
import pytest
from no5_sonard_openecho.depth_rate import DepthRateProfile
from test_depth_rate import fixture


@pytest.mark.parametrize('patch', [12, 13])
def test_new_profile_retains_qualified_geometry_and_requires_explicit_zero(tmp_path, patch):
    p=DepthRateProfile.load(Path(__file__).resolve().parents[1]/f'deployment/clip-depth-qualified-07{patch}.json')
    assert p.firmware==(0,7,patch) and p.continuous_run_supported
    assert p.qualified_min_spacing_us==24300 and p.qualified_max_own_per_native==8
    _,tx,commands=fixture(tmp_path);tx.profile=p;tx.client.hello.fw_patch=patch;tx.client.hello.capabilities=0xC0003FEE
    async def run():
        with pytest.raises(RuntimeError,match='short'):await tx.update({'mode':'periodic','duration_s':0})
        tx.short_verified=True
        await tx.update({'mode':'periodic','duration_s':0})
        config=next(payload for k,payload in commands if k==0x46)
        assert struct.unpack_from('<IIIIII',config,4)==(197000,1000,4,27000,1000,0)
        assert not any(k==0x42 for k,_ in commands) # Explicit FIRE still required.
        tx._first_start=1000000;tx._last_start=1001000;tx._first_host=time.monotonic()-700
        tx.run['state']='RUNNING'
        snap=tx.snapshot()
        assert snap['run']['elapsed_s']>=700 and snap['continuous']
        assert snap['schedule']['hard_max_duration_s'] is None
        assert snap['schedule']['lease_s']==2 and snap['policy']['rate_policy']=='depth_auto'
        await tx.abort();assert tx.mode=='off' and tx.run['state']=='ABORTED'
    asyncio.run(run())


def test_old_firmware_and_unqualified_trials_never_accept_continuous(tmp_path):
    _,tx,_=fixture(tmp_path);tx.short_verified=True
    async def run():
        with pytest.raises(ValueError):await tx.update({'mode':'periodic','duration_s':0})
        tx.profile=replace(tx.profile,firmware=(0,7,12),continuous_run_supported=True)
        tx.trial_spacing_us=21900
        with pytest.raises(ValueError,match='never a trial'):await tx.update({'mode':'periodic','duration_s':0})
        tx.trial_spacing_us=None;tx.diagnostic_source='R17_a'
        with pytest.raises(ValueError):await tx.update({'mode':'periodic','duration_s':0})
    asyncio.run(run())
