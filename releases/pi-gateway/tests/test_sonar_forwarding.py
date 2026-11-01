import hashlib
import json
import uuid
import asyncio
from dataclasses import replace
import pytest
from no5_pi_gateway.configuration import GatewayConfig
from no5_pi_gateway.sonar_forwarding import ForwardingLease, ForwardingConflict


def fixture(tmp_path,**changes):
    config=tmp_path/'source.conf';config.write_text('original physical source configuration')
    policy=tmp_path/'policy.json';policy.write_text(json.dumps({
        'schema':'no5.sonar-forwarding-policy/1','target':'pi','local_source_configured':False,
        'protected_sha256':{str(config):hashlib.sha256(config.read_bytes()).hexdigest()},
        'physical_device_globs':[str(tmp_path/'local-pico-*')],**changes}))
    now=[0.];lease=ForwardingLease(policy,clock=lambda:now[0])
    return lease,now,config


def acquire(lease,owner=None):
    owner=owner or str(uuid.uuid4())
    return lease.acquire({'owner':owner,'ttl_s':60,'expected_revision':lease.snapshot()['revision']})


def test_local_configured_source_blocks_even_when_currently_unplugged(tmp_path):
    lease,_,_=fixture(tmp_path,local_source_configured=True)
    with pytest.raises(ForwardingConflict,match='physical source'):acquire(lease)
    assert not lease.snapshot()['active']


def test_owner_expiry_and_no_foreign_takeover(tmp_path):
    lease,now,_=fixture(tmp_path);owner=str(uuid.uuid4());acquire(lease,owner)
    with pytest.raises(ForwardingConflict,match='another owner'):acquire(lease)
    with pytest.raises(ForwardingConflict):lease.release(str(uuid.uuid4()))
    now[0]=59;assert lease.snapshot()['active']
    now[0]=60;assert not lease.snapshot()['active']
    acquire(lease);assert lease.snapshot()['active']


@pytest.mark.parametrize('change',['config','hardware'])
def test_new_local_source_immediately_revokes_forwarding_without_writing_configuration(tmp_path,change):
    lease,_,config=fixture(tmp_path);acquire(lease)
    if change=='config':config.write_text('new own physical source')
    else:(tmp_path/'local-pico-serial').touch()
    expected=config.read_bytes();state=lease.snapshot()
    assert not state['active'] and state['conflict']
    assert config.read_bytes()==expected


def test_stale_revision_or_wrong_ttl_cannot_mutate_route(tmp_path):
    lease,_,_=fixture(tmp_path);owner=str(uuid.uuid4())
    with pytest.raises(ForwardingConflict):lease.acquire({'owner':owner,'ttl_s':61,'expected_revision':0})
    acquire(lease,owner)
    with pytest.raises(ForwardingConflict):lease.acquire({'owner':owner,'ttl_s':60,'expected_revision':0})
    assert lease.snapshot()['owner']==owner


def test_loopback_api_requires_bearer_and_changes_only_sonar_reader(tmp_path,monkeypatch):
    from aiohttp.test_utils import TestClient,TestServer
    from no5_pi_gateway.configuration import SimulationConfig
    from no5_pi_gateway.service import GatewayService,create_app
    lease,_,_=fixture(tmp_path)
    monkeypatch.setenv('NO5_SONAR_FORWARDING_POLICY',str(lease.policy_path))
    monkeypatch.setenv('NO5_GATEWAY_SIMULATION_TOKEN','fixture-forwarding-token')
    config=GatewayConfig(simulation=SimulationConfig(enabled=True,state_file=str(tmp_path/'simulation.json')))
    async def run():
        app=await create_app(config)
        service=app['gateway_service']
        client=TestClient(TestServer(app))
        await client.start_server()
        path='/admin/v1/sonar/forwarding'
        headers={'Authorization':'Bearer fixture-forwarding-token'}
        try:
            response=await client.get(path);assert response.status==401
            response=await client.get(path,headers=headers);state=await response.json()
            owner=str(uuid.uuid4())
            response=await client.put(path,headers=headers,json={'owner':owner,'ttl_s':60,'expected_revision':state['revision']})
            assert response.status==200 and (await response.json())['active']
            assert service.state.config.upstream_base_url==ForwardingLease.UPSTREAM
            assert service.config==config
            assert service.simulation.sonar_mode=='INHERIT'
            response=await client.delete(path,headers={**headers,'X-No5-Forwarding-Owner':owner})
            assert response.status==200 and not(await response.json())['active']
            assert service.state.config==config
        finally:await client.close()
    asyncio.run(run())
