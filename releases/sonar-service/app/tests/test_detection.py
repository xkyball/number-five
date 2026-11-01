from __future__ import annotations

import numpy as np
from fastapi.testclient import TestClient

from no5_sonard_openecho.detection import DetectionPipeline, DetectionPolicy, detect_echo_blocks
from no5_sonard_openecho.__main__ import create_host_app
from no5_sonard_openecho.service import HostService
from no5_sonard.config import ServiceConfig


PERIOD_NS = 4_000


def test_compact_echo_uses_adc_geometry_and_rejects_single_sample_outliers():
    raw = np.full(8192, 190.0)
    index = np.arange(raw.size)
    raw += 60*np.exp(-.5*((index-754)/12)**2)
    raw[[480, 610, 850, 1110]] += 900
    raw = raw.astype(np.uint16)
    before = raw.copy()
    blocks = detect_echo_blocks(raw, tx_zero_sample_index=0, sample_period_ns=2000,
                                sound_speed_mps=1500, policy=DetectionPolicy())
    compact = [b for b in blocks if b.qualification_method == 'median_gaussian_prominence_v1']
    assert len(compact)==1 and abs(compact[0].depth_m-1.131)<.006
    assert compact[0].width_m > .02 and compact[0].snr_db > 20
    np.testing.assert_array_equal(raw,before)


def test_compact_gate_does_not_promote_noise_or_fill_a_missing_echo():
    rng=np.random.default_rng(41)
    raw=(190+rng.normal(0,1,8192)).astype(np.uint16)
    raw[[460, 610, 850, 1110, 1300, 2400]] = 2000
    blocks = detect_echo_blocks(raw, tx_zero_sample_index=0, sample_period_ns=2000,
                                sound_speed_mps=1500, policy=DetectionPolicy())
    assert not any(b.qualification_method=='median_gaussian_prominence_v1' for b in blocks)


def test_near_echo_does_not_raise_its_own_noise_threshold():
    x=np.arange(2048);raw=(190+150*np.exp(-.5*((x-520)/55)**2)).astype(np.uint16)
    kwargs=dict(tx_zero_sample_index=0,sample_period_ns=2000,sound_speed_mps=1500)
    old=detect_echo_blocks(raw,policy=DetectionPolicy(compact_reference_windows=1),**kwargs)
    new=detect_echo_blocks(raw,policy=DetectionPolicy(),**kwargs)
    assert not any(b.qualification_method=='median_gaussian_prominence_v1' for b in old)
    assert any(b.qualification_method=='median_gaussian_prominence_v1' and abs(b.depth_m-.78)<.01 for b in new)


def test_same_filtered_template_recovers_pulse_but_rejects_ripple_alone():
    from no5_sonard_openecho.detection import _local_template_block
    axis=np.arange(2048);pulse=np.exp(-.5*((axis-754)/25)**2)
    pipeline=DetectionPipeline()
    for pid in range(8):
        result=pipeline.process(ping_id=pid,samples=(180+150*pulse).astype(np.uint16),
            tx_zero_sample_index=0,sample_period_ns=2000,time_utc_ns=pid*80_000_000,
            valid_tx=True,acquisition_source='own')
    template=pipeline._templates['own'][result.selected.track_id][-1]
    rng=np.random.default_rng(651)
    ripple=250+90*np.sin(2*np.pi*axis/9)+rng.normal(0,2,len(axis))
    raw=(ripple+110*pulse).astype(np.uint16);before=raw.copy()
    kwargs=dict(template=template,expected_depth_m=1.131,policy=DetectionPolicy(),
                tx_zero_sample_index=0,sample_period_ns=2000,sound_speed_mps=1500)
    block=_local_template_block(raw,**kwargs)
    assert block is not None and block.template_fit_preprocessing=='median_gaussian_v1'
    assert block.template_score>=6 and block.template_correlation>=.8
    assert abs(block.depth_m-1.131)<.015
    np.testing.assert_array_equal(raw,before)
    assert _local_template_block(ripple.astype(np.uint16),**kwargs) is None


def test_compact_envelope_can_be_disabled_without_changing_raw_cfar():
    raw = np.full(8192,190,dtype=np.uint16)
    blocks = detect_echo_blocks(raw, tx_zero_sample_index=0, sample_period_ns=2000,
        sound_speed_mps=1500, policy=DetectionPolicy(compact_envelope=False))
    assert not any(b.qualification_method=='median_gaussian_prominence_v1' for b in blocks)


def profile(*returns: tuple[int, int], size=1800) -> np.ndarray:
    """A deterministic envelope capture with a modest ADC baseline/noise."""
    rng = np.random.default_rng(7)
    values = 100.0 + rng.normal(0.0, 0.7, size)
    for centre, amplitude in returns:
        values[centre - 4:centre + 5] += amplitude
    return np.clip(values, 0, 4095).astype(np.uint16)


def test_cfar_emits_blocks_and_respects_blanking():
    policy = DetectionPolicy(blanking_m=0.5, training_cells=24, guard_cells=6,
                             min_block_cells=4)
    # 4 us maps to 3 mm/sample.  The early pulse is inside 0.5 m blanking.
    blocks = detect_echo_blocks(profile((100, 150), (350, 60), (700, 80)),
                                tx_zero_sample_index=0, sample_period_ns=PERIOD_NS,
                                sound_speed_mps=1500.0, policy=policy)
    assert len(blocks) == 2
    assert 1.0 < blocks[0].depth_m < 1.1
    assert blocks[0].width_m > 0
    assert blocks[0].snr_db > 12


def test_wide_direct_return_uses_post_tx_envelope_when_cfar_has_no_contrast():
    """A broad wall return must not be hidden by transmitter ring-down."""
    raw = np.full(10_000, 90, dtype=np.uint16)
    raw[:167] = np.linspace(410, 55, 167, dtype=np.uint16)
    raw[300:380] = np.linspace(110, 230, 80, dtype=np.uint16)
    raw[380:1_300] = 230
    blocks = detect_echo_blocks(raw, tx_zero_sample_index=0, sample_period_ns=PERIOD_NS,
                                sound_speed_mps=1500.0, policy=DetectionPolicy())
    assert len(blocks) == 1
    assert blocks[0].detector == "direct_envelope"
    assert 1.0 < blocks[0].depth_m < 1.2
    assert blocks[0].snr_db > 12


def test_direct_envelope_provenance_survives_tracking():
    raw = np.full(10_000, 90, dtype=np.uint16)
    raw[:167] = np.linspace(410, 55, 167, dtype=np.uint16)
    raw[300:380] = np.linspace(110, 230, 80, dtype=np.uint16)
    raw[380:1_300] = 230
    pipeline = DetectionPipeline(DetectionPolicy())
    for ping in range(1, 4):
        result = pipeline.process(ping_id=ping, samples=raw, tx_zero_sample_index=0,
                                  sample_period_ns=PERIOD_NS,
                                  time_utc_ns=ping * 1_000_000_000, valid_tx=True)
    assert result.selected is not None
    assert result.selected.detector == "direct_envelope"


def test_unrelated_cfar_hit_cannot_hide_wide_direct_return():
    raw = np.full(10_000, 90, dtype=np.uint16)
    raw[300:380] = np.linspace(110, 230, 80, dtype=np.uint16)
    raw[380:1_300] = 230
    raw[550:560] = 500  # unrelated narrow return inside the broad search interval
    raw[2200:2210] = 500
    blocks = detect_echo_blocks(raw, tx_zero_sample_index=0, sample_period_ns=PERIOD_NS,
                                sound_speed_mps=1500.0, policy=DetectionPolicy())
    assert any(block.detector == 'cfar' and block.depth_m > 6 for block in blocks)
    assert any(block.detector == 'direct_envelope' and 1 < block.depth_m < 1.2 for block in blocks)


def test_track_identity_survives_rolling_window_and_native_own_interleave():
    from no5_sonard_openecho.detection import EchoBlock, EchoTracker
    def block(depth):
        return EchoBlock(350, 370, 360, depth, .02, 1000, 70, 1, 25, 60)
    tracker = EchoTracker(DetectionPolicy(track_window_pings=5))
    old_id = None
    for frame in range(18):
        # The shallow transient falls out of the window and renumbers the
        # remaining trackpy particle. Its persistent identity must survive.
        observations = [block(1.13)]
        if frame < 2: observations.insert(0, block(.7))
        tracks = tracker.update(observations, time_utc_ns=frame * 100_000_000)
        target = next(track for track in tracks if abs(track.depth_m-1.13) < .01)
        if old_id is None: old_id = target.track_id
        assert target.track_id == old_id
        assert target.hits == frame+1
    pipeline = DetectionPipeline()
    for frame in range(12):
        for source, centre in [('own', 375), ('native', 460)]:
            result = pipeline.process(ping_id=frame*2+(source=='native'),
                samples=profile((centre, 80)), tx_zero_sample_index=0,
                sample_period_ns=PERIOD_NS, time_utc_ns=frame*400_000_000,
                valid_tx=True, acquisition_source=source)
            if frame > 3:
                assert result.selected is not None
                assert result.selected.hits == frame+1
            assert result.public()['acquisition_source'] == source


def test_earliest_persistent_return_beats_later_louder_multipath():
    pipeline = DetectionPipeline(DetectionPolicy(training_cells=24, guard_cells=6,
                                                 min_track_hits=3))
    for ping in range(1, 4):
        result = pipeline.process(ping_id=ping, samples=profile((350 + ping, 55), (720, 150)),
                                  tx_zero_sample_index=0, sample_period_ns=PERIOD_NS,
                                  time_utc_ns=ping * 1_000_000_000, valid_tx=True)
    assert result.selected is not None
    assert result.selected.state == "confirmed"
    assert 1.0 < result.selected.depth_m < 1.1
    assert len([track for track in result.tracks if track.state == "confirmed"]) == 2


def test_track_needs_repeated_observations_and_expires_noise():
    pipeline = DetectionPipeline(DetectionPolicy(training_cells=24, guard_cells=6,
                                                 min_track_hits=3, max_track_misses=1))
    first = pipeline.process(ping_id=1, samples=profile((400, 70)), tx_zero_sample_index=0,
                             sample_period_ns=PERIOD_NS, time_utc_ns=1, valid_tx=True)
    assert first.selected is None
    pipeline.process(ping_id=2, samples=profile((401, 70)), tx_zero_sample_index=0,
                     sample_period_ns=PERIOD_NS, time_utc_ns=2, valid_tx=True)
    third = pipeline.process(ping_id=3, samples=profile((399, 70)), tx_zero_sample_index=0,
                             sample_period_ns=PERIOD_NS, time_utc_ns=3, valid_tx=True)
    assert third.selected is not None
    pipeline.process(ping_id=4, samples=profile(), tx_zero_sample_index=0,
                     sample_period_ns=PERIOD_NS, time_utc_ns=4, valid_tx=True)
    expired = pipeline.process(ping_id=5, samples=profile(), tx_zero_sample_index=0,
                               sample_period_ns=PERIOD_NS, time_utc_ns=5, valid_tx=True)
    assert expired.tracks == ()


def test_off_preserves_raw_contract_but_disables_derived_output():
    pipeline = DetectionPipeline(DetectionPolicy(mode="off", training_cells=24, guard_cells=6))
    result = pipeline.process(ping_id=1, samples=profile((400, 70)), tx_zero_sample_index=0,
                              sample_period_ns=PERIOD_NS, time_utc_ns=1, valid_tx=True)
    assert result.state == "off"
    assert result.blocks == ()
    assert result.selected is None


def test_unverified_tx_cannot_become_range_or_track():
    pipeline = DetectionPipeline(DetectionPolicy(training_cells=24, guard_cells=6))
    result = pipeline.process(ping_id=1, samples=profile((400, 70)), tx_zero_sample_index=0,
                              sample_period_ns=PERIOD_NS, time_utc_ns=1, valid_tx=False)
    assert result.state == "not_applicable"
    assert result.blocks == ()
    assert "TX reference" in result.reason


def test_runtime_api_switches_policy_and_records_boat_context(tmp_path):
    service = HostService(ServiceConfig(device=str(tmp_path / "no-pico"), storage_root=tmp_path), archive_enabled=True)
    with TestClient(create_host_app(service)) as client:
        assert client.get("/api/v1/detection/filter").json()["policy"]["mode"] == "auto"
        assert client.put("/api/v1/detection/filter", json={"mode": "on"}).json()["policy"]["mode"] == "on"
        context = client.patch("/api/v1/acquisition/context", json={
            "mount": {"draft_m": 0.4}, "gps": {"lat": -33.9, "lon": 18.4},
            "course_deg": 90.0, "speed_mps": 2.1,
            "water": {"temperature_c": 18.0},
        }).json()["context"]
        assert context["mount"]["draft_m"] == 0.4
        assert context["gps"]["lat"] == -33.9
        assert client.put("/api/v1/detection/filter", json={"mode": "on", "extra": 1}).status_code == 422
    assert (tmp_path / "sessions" / service.status.session_id / "acquisition-context.json").exists()


def test_auto_uses_track_only_when_raw_bottom_is_not_credible(tmp_path):
    service = HostService(ServiceConfig(device=str(tmp_path / "no-pico"), storage_root=tmp_path), archive_enabled=True)
    for ping in range(1, 4):
        service._last_detection = service.detection.process(
            ping_id=ping, samples=profile((350, 75)), tx_zero_sample_index=0,
            sample_period_ns=PERIOD_NS, time_utc_ns=ping * 1_000_000_000, valid_tx=True)
    service.status.last_bottom_depth_m = 1.05
    service.status.last_bottom_confidence = 0.9
    assert service._detection_decision()["effective_mode"] == "auto_raw"
    service.status.last_bottom_depth_m = None
    service.status.last_bottom_confidence = 0.0
    fallback = service._detection_decision()
    assert fallback["effective_mode"] == "auto_filter"
    assert fallback["active"]


def test_rules_mark_late_two_x_return_as_multipath():
    pipeline = DetectionPipeline(DetectionPolicy(training_cells=24, guard_cells=6,
                                                 min_track_hits=3))
    for ping in range(1, 4):
        result = pipeline.process(
            ping_id=ping, samples=profile((350, 100), (700, 135)),
            tx_zero_sample_index=0, sample_period_ns=PERIOD_NS,
            time_utc_ns=ping * 1_000_000_000, valid_tx=True)
    confirmed = {round(track.depth_m, 1): track for track in result.tracks
                 if track.state == "confirmed"}
    assert any(track.classification == "multipath_like" for track in confirmed.values())
    assert result.selected is not None
    assert result.selected.classification != "multipath_like"
    assert result.selected.classification_source == "rules_v1"


def test_scipy_geometry_is_persisted_on_blocks():
    policy = DetectionPolicy(training_cells=24, guard_cells=6, min_block_cells=4)
    block = detect_echo_blocks(profile((420, 100)), tx_zero_sample_index=0,
                               sample_period_ns=PERIOD_NS, sound_speed_mps=1500.0,
                               policy=policy)[0]
    assert block.prominence > 0
    assert block.width_m > 0


def test_label_api_is_append_only_and_dataset_export(tmp_path):
    from no5_sonard_openecho.dataset import build, evaluate
    service = HostService(ServiceConfig(device=str(tmp_path / "no-pico"), storage_root=tmp_path), archive_enabled=True)
    service._ensure_session()
    app = create_host_app(service)
    with TestClient(app) as client:
        response = client.post("/api/v1/labels", json={
            "track_id": 1, "ping_id": 17, "label": "fish_like",
            "reviewer": "operator", "note": "known target",
        })
        assert response.status_code == 201
        assert client.get("/api/v1/labels").json()["labels"][0]["label"] == "fish_like"
        assert client.post("/api/v1/labels", json={
            "track_id": 0, "ping_id": 17, "label": "fish_like",
        }).status_code == 422
    derived = tmp_path / "sessions" / service.status.session_id / "derived" / "segment-a"
    derived.mkdir(parents=True)
    (derived / "detections.jsonl").write_text(__import__("json").dumps({
        "ping_id": 17, "time_utc_ns": 1, "valid_tx": True, "policy": {},
        "tracks": [{"track_id": 1, "classification": "fish_like"}],
    }) + "\n")
    output = tmp_path / "dataset.jsonl"
    result = build(tmp_path, output)
    assert result["rows"] == 1
    report = evaluate(tmp_path)
    assert report["labeled_rows"] == 1
    assert report["agreement"] == 1.0


def test_presentation_is_the_only_bottom_decision_and_recommends_hold_on_loss(tmp_path):
    service = HostService(ServiceConfig(device=str(tmp_path / "no-pico"), storage_root=tmp_path), archive_enabled=True)
    for ping in range(1, 4):
        service._last_detection = service.detection.process(
            ping_id=ping, samples=profile((350, 75)), tx_zero_sample_index=0,
            sample_period_ns=PERIOD_NS, time_utc_ns=ping * 1_000_000_000, valid_tx=True)
    service.status.last_sample_count = 10_000
    service.status.last_sample_period_ns = PERIOD_NS
    service.status.last_tx_reference_valid = True
    service.status.last_bottom_depth_m = None
    service.status.last_bottom_confidence = 0.0
    service._refresh_presentation()
    locked = service.presentation()
    assert locked["schema"] == "no5.sonard-presentation/1"
    assert locked["quality"] == "LOCKED"
    assert locked["bottom"]["source"] == "cfar_track_rules_v1"
    assert locked["bottom"]["classification_source"] == "rules_v1"
    assert 0.0 < locked["bottom"]["display_bin"] < 1023.0
    assert locked["recommendations"]["range"] == "FOLLOW_BOTTOM"

    service._last_detection = service.detection.process(
        ping_id=4, samples=profile(), tx_zero_sample_index=0,
        sample_period_ns=PERIOD_NS, time_utc_ns=4_000_000_000, valid_tx=True)
    service._refresh_presentation()
    unstable = service.presentation()
    assert unstable["quality"] == "UNSTABLE"
    assert unstable["bottom"] is None
    assert unstable["recommendations"] == {"range": "HOLD", "range_end_bin": None, "freeze": True}


def test_presentation_endpoint_is_additive_to_existing_detection_api(tmp_path):
    service = HostService(ServiceConfig(device=str(tmp_path / "no-pico"), storage_root=tmp_path), archive_enabled=True)
    with TestClient(create_host_app(service)) as client:
        payload = client.get("/api/v1/sonar/presentation").json()
        assert payload["schema"] == "no5.sonard-presentation/1"
        latest = client.get("/api/v1/detection/latest").json()
        assert latest["presentation"]["schema"] == "no5.sonard-presentation/1"


def test_auto_rejects_loud_unsupported_late_peak_and_keeps_measured_track(tmp_path):
    service = HostService(ServiceConfig(device=str(tmp_path / "no-pico"), storage_root=tmp_path), archive_enabled=True)
    service.status.last_sample_count = 1800
    service.status.last_sample_period_ns = PERIOD_NS
    service.status.last_tx_reference_valid = True
    raw = profile((375, 80))
    for ping in range(1, 8):
        service._last_detection = service.detection.process(
            ping_id=ping, samples=raw, tx_zero_sample_index=0,
            sample_period_ns=PERIOD_NS, time_utc_ns=ping*100_000_000,
            valid_tx=True, acquisition_source="own")
        # Saturating single-cell noise can fool the independent M1 peak picker.
        service.status.last_bottom_depth_m = 4.9
        service.status.last_bottom_confidence = 1.0
        service._refresh_presentation()
    p = service.presentation()
    assert p["quality"] == "LOCKED"
    assert abs(p["bottom"]["depth_m"]-1.125) < .02
    assert p["filter"]["effective_mode"] == "auto_filter"
    # Remove the current echo. A stale track cannot manufacture useful pings.
    service._last_detection = service.detection.process(
        ping_id=8, samples=profile(), tx_zero_sample_index=0,
        sample_period_ns=PERIOD_NS, time_utc_ns=800_000_000,
        valid_tx=True, acquisition_source="own")
    service._refresh_presentation()
    assert service.presentation()["bottom"] is None


def test_auto_preserves_supported_deep_bottom_below_shallow_target(tmp_path):
    service = HostService(ServiceConfig(device=str(tmp_path / "no-pico"), storage_root=tmp_path), archive_enabled=True)
    service.status.last_sample_count = 1800
    service.status.last_sample_period_ns = PERIOD_NS
    service.status.last_tx_reference_valid = True
    for ping in range(1, 8):
        service._last_detection = service.detection.process(
            ping_id=ping, samples=profile((375, 80), (910, 150)),
            tx_zero_sample_index=0, sample_period_ns=PERIOD_NS,
            time_utc_ns=ping*100_000_000, valid_tx=True)
    service.status.last_bottom_depth_m = 2.73
    service.status.last_bottom_confidence = .9
    service._refresh_presentation()
    assert service.presentation()["filter"]["effective_mode"] == "auto_raw"
    assert service.presentation()["bottom"]["depth_m"] == 2.73


def test_new_shallow_reflection_does_not_steal_an_established_measured_track():
    pipeline = DetectionPipeline()
    identity = None
    for ping in range(1, 13):
        returns = [(400, 90)]
        if ping > 6:
            returns.append((230, 130))
        result = pipeline.process(ping_id=ping, samples=profile(*returns),
            tx_zero_sample_index=0, sample_period_ns=PERIOD_NS,
            time_utc_ns=ping*100_000_000, valid_tx=True)
        if result.selected is not None:
            if identity is None: identity = result.selected.track_id
            assert result.selected.track_id == identity
            assert abs(result.selected.depth_m-1.2) < .02


def test_measured_template_recovers_current_weak_echo_without_filling_absence():
    rng = np.random.default_rng(128)
    axis = np.arange(8192,dtype=float)
    pulse = np.where(axis<754,np.exp(-((axis-754)/18)**2),np.exp(-np.maximum(axis-754,0)/60))
    def raw(echo=True, disturbed=False):
        values = 180+rng.normal(0,.6,len(axis))
        if echo: values += 150*pulse
        if disturbed: values += 200*np.exp(-.5*((axis-480)/95)**2)
        return np.clip(values,0,4095).astype(np.uint16)
    pipeline = DetectionPipeline()
    for ping in range(1,9):
        r = pipeline.process(ping_id=ping,samples=raw(),tx_zero_sample_index=0,
            sample_period_ns=2000,time_utc_ns=ping*80_000_000,valid_tx=True,acquisition_source='own')
    assert r.selected is not None and pipeline._templates['own']
    weak = raw(disturbed=True);before = weak.copy()
    recovered = pipeline.process(ping_id=9,samples=weak,tx_zero_sample_index=0,
        sample_period_ns=2000,time_utc_ns=720_000_000,valid_tx=True,acquisition_source='own')
    fits = [b for b in recovered.blocks if b.qualification_method=='track_local_measured_template_v1']
    assert fits and fits[0].template_score >= 6 and fits[0].template_correlation >= .8
    assert fits[0].detector == 'direct_envelope'  # existing gateway enum stays compatible
    assert abs(fits[0].depth_m-1.131)<.03 and fits[0].template_ping_id<9
    np.testing.assert_array_equal(weak,before)
    missing = pipeline.process(ping_id=10,samples=raw(echo=False,disturbed=True),
        tx_zero_sample_index=0,sample_period_ns=2000,time_utc_ns=800_000_000,
        valid_tx=True,acquisition_source='own')
    assert missing.selected is None
    assert not any(b.qualification_method=='track_local_measured_template_v1' for b in missing.blocks)
    # A template from owned emissions is never borrowed for a native ping.
    native = pipeline.process(ping_id=11,samples=weak,tx_zero_sample_index=0,
        sample_period_ns=2000,time_utc_ns=850_000_000,valid_tx=True,acquisition_source='native')
    assert not any(b.qualification_method=='track_local_measured_template_v1' for b in native.blocks)


def test_recent_template_bank_is_bounded_and_rejects_noise_without_an_echo():
    rng = np.random.default_rng(651)
    axis = np.arange(8192, dtype=float)
    pipeline = DetectionPipeline()
    for ping in range(1, 21):
        tail = (35, 55, 75)[ping % 3]
        pulse = np.where(axis < 754, np.exp(-((axis-754)/18)**2),
                         np.exp(-np.maximum(axis-754, 0)/tail))
        raw = (180+rng.normal(0, .6, len(axis))+150*pulse).astype(np.uint16)
        result = pipeline.process(ping_id=ping, samples=raw, tx_zero_sample_index=0,
            sample_period_ns=2000, time_utc_ns=ping*80_000_000,
            valid_tx=True, acquisition_source='own')
    assert result.selected is not None
    bank = pipeline._templates['own'][result.selected.track_id]
    assert len(bank) == 8 and len({t['ping_id'] for t in bank}) == 8
    from no5_sonard_openecho.detection import _local_template_block
    for _ in range(30):
        # Correlated background, independent ADC noise, and unrelated spikes.
        raw = 180+rng.normal(0, 2, len(axis))
        raw += 25*np.sin(axis/rng.uniform(100, 220)+rng.uniform(0, 6))
        raw[rng.integers(340, 1200, 8)] += 600
        before = raw.copy()
        assert all(_local_template_block(raw, template=t, expected_depth_m=1.131,
            policy=DetectionPolicy(), tx_zero_sample_index=0,
            sample_period_ns=2000, sound_speed_mps=1500) is None for t in bank)
        np.testing.assert_array_equal(raw, before)


def test_single_shallow_spike_cannot_reclassify_an_established_bottom_as_multipath():
    pipeline = DetectionPipeline()
    for ping in range(1, 10):
        returns = [(400, 80)]
        if ping == 9:
            returns.append((200, 200))
        result = pipeline.process(ping_id=ping, samples=profile(*returns),
            tx_zero_sample_index=0, sample_period_ns=PERIOD_NS,
            time_utc_ns=ping*100_000_000, valid_tx=True)
    assert result.selected is not None and abs(result.selected.depth_m-1.2) < .03
    assert result.selected.classification != 'multipath_like'


def test_small_early_ripple_does_not_hide_the_wider_energetic_bottom():
    axis = np.arange(8192, dtype=float)
    raw = (180+35*np.exp(-.5*((axis-681)/5)**2)
           +150*np.exp(-.5*((axis-754)/20)**2)).astype(np.uint16)
    blocks = detect_echo_blocks(raw, tx_zero_sample_index=0, sample_period_ns=2000,
                                sound_speed_mps=1500, policy=DetectionPolicy())
    peaks = [b.depth_m for b in blocks if b.qualification_method == 'median_gaussian_prominence_v1']
    assert any(abs(b.depth_m-1.0215) < .006 for b in blocks)
    assert any(abs(d-1.131) < .006 for d in peaks)
    pipeline = DetectionPipeline()
    for ping in range(1, 10):
        result = pipeline.process(ping_id=ping, samples=raw, tx_zero_sample_index=0,
            sample_period_ns=2000, time_utc_ns=ping*80_000_000,
            valid_tx=True, acquisition_source='own')
    assert result.selected is not None and abs(result.selected.depth_m-1.131) < .006


def test_raw_peak_inside_association_gate_still_needs_close_depth_agreement(tmp_path):
    service = HostService(ServiceConfig(device='/offline-no-usb', storage_root=tmp_path), archive_enabled=True)
    for ping in range(1, 7):
        service._last_detection = service.detection.process(ping_id=ping,
            samples=profile((400, 90)), tx_zero_sample_index=0,
            sample_period_ns=PERIOD_NS, time_utc_ns=ping*100_000_000, valid_tx=True)
    service.status.last_bottom_depth_m = 1.32
    service.status.last_bottom_confidence = .95
    service.status.last_tx_reference_valid = True
    service.status.last_sample_count = 1800
    service.status.last_sample_period_ns = PERIOD_NS
    service._refresh_presentation()
    assert service.presentation()['filter']['effective_mode'] == 'auto_filter'
    assert abs(service.presentation()['bottom']['depth_m']-1.2) < .02


def test_presentation_allows_the_local_reference_page_to_read_it(tmp_path):
    service = HostService(ServiceConfig(device=str(tmp_path / "no-pico"), storage_root=tmp_path), archive_enabled=True)
    with TestClient(create_host_app(service)) as client:
        response = client.get("/api/v1/sonar/presentation",
                              headers={"Origin": "http://127.0.0.1:8788"})
    assert response.headers["access-control-allow-origin"] == "http://127.0.0.1:8788"


def test_coarse_search_peak_cannot_steal_resolved_track_or_hide_current_template():
    from no5_sonard_openecho.detection import EchoBlock, EchoTracker

    def block(depth, coarse=False, template=False):
        return EchoBlock(720, 1224 if coarse else 805, round(depth/.0015),
                         depth, .756 if coarse else .11, 9000, 150, 1, 35, 150,
                         detector='direct_envelope', qualification_method=(
                             'coarse_sustained_v1' if coarse else
                             'track_local_measured_template_v1' if template else
                             'median_gaussian_prominence_v1'))

    tracker = EchoTracker(DetectionPolicy())
    for frame in range(8):
        tracks = tracker.update([block(1.1325)], time_utc_ns=frame*27_000_000)
    identity = tracks[0].track_id
    # Recorded failure geometry: a broad max-pool envelope straddles the
    # resolved pulse, then wanders past it while the actual echo stays put.
    for frame, coarse_depth in enumerate([1.008, 1.164, 1.224], start=8):
        tracks = tracker.update([block(1.1325), block(coarse_depth, coarse=True)],
                                time_utc_ns=frame*27_000_000)
        measured = next(t for t in tracks if t.track_id == identity)
        assert measured.state == 'confirmed' and abs(measured.depth_m-1.1325) < .002
        assert any(t.track_id != identity for t in tracks)  # broad evidence retained
    tracks = tracker.update([block(1.134, template=True)], time_utc_ns=330_000_000)
    measured = next(t for t in tracks if t.track_id == identity)
    assert measured.state == 'confirmed' and measured.hits == 12 and measured.misses == 0
    # A coarse-only observation must not be promoted into a fresh compact hit.
    tracks = tracker.update([block(1.134, coarse=True)], time_utc_ns=357_000_000)
    measured = next(t for t in tracks if t.track_id == identity)
    assert measured.state == 'stale' and measured.hits == 12 and measured.misses == 1


def test_measured_core_recovers_current_pulse_with_changed_tail_not_an_absent_echo():
    from no5_sonard_openecho.detection import _local_template_block

    axis = np.arange(8192, dtype=float)
    pulse = np.where(axis < 754, np.exp(-((axis-754)/18)**2),
                     np.exp(-np.maximum(axis-754, 0)/60))
    clean = (180+150*pulse).astype(np.uint16)
    pipeline = DetectionPipeline()
    for ping in range(1, 9):
        result = pipeline.process(ping_id=ping, samples=clean, tx_zero_sample_index=0,
            sample_period_ns=2000, time_utc_ns=ping*80_000_000,
            valid_tx=True, acquisition_source='own')
    reference = pipeline._templates['own'][result.selected.track_id][-1]
    tail = 150*np.exp(-.5*((axis-845)/16)**2)
    changed = (180+150*pulse+tail).astype(np.uint16)
    before = changed.copy()
    kwargs = dict(template=reference, expected_depth_m=1.131,
                  tx_zero_sample_index=0, sample_period_ns=2000, sound_speed_mps=1500)
    assert _local_template_block(changed,
        policy=DetectionPolicy(template_core_window_us=None), **kwargs) is None
    current = _local_template_block(changed, policy=DetectionPolicy(), **kwargs)
    assert current is not None and current.template_fit_window == 'measured_core_v1'
    assert current.template_fit_window_samples == 101  # 200us at the ADC spacing
    assert current.template_score >= 6 and current.template_correlation >= .8
    assert abs(current.depth_m-1.131) < .006
    np.testing.assert_array_equal(changed, before)
    missing = (180+tail).astype(np.uint16)
    assert _local_template_block(missing, policy=DetectionPolicy(), **kwargs) is None


def test_bounded_template_alignment_recovers_a_measured_pulse_under_phase_noise():
    from no5_sonard_openecho.detection import _local_template_block

    axis = np.arange(8192, dtype=float)
    pulse = np.where(axis < 754, np.exp(-((axis-754)/18)**2),
                     np.exp(-np.maximum(axis-754, 0)/60))
    clean = (180+150*pulse).astype(np.uint16)
    pipeline = DetectionPipeline()
    for ping in range(1, 9):
        result = pipeline.process(ping_id=ping, samples=clean, tx_zero_sample_index=0,
            sample_period_ns=2000, time_utc_ns=ping*80_000_000,
            valid_tx=True, acquisition_source='own')
    reference = pipeline._templates['own'][result.selected.track_id][-1]
    disturbed = (180+150*pulse+20*np.sin((axis-754)/20+2.1)).astype(np.uint16)
    before = disturbed.copy()
    kwargs = dict(template=reference, expected_depth_m=1.131, tx_zero_sample_index=0,
                  sample_period_ns=2000, sound_speed_mps=1500)
    assert _local_template_block(disturbed, policy=DetectionPolicy(
        template_core_window_us=None, template_alignment_us=0), **kwargs) is None
    fitted = _local_template_block(disturbed,
        policy=DetectionPolicy(template_core_window_us=None), **kwargs)
    assert fitted is not None and fitted.template_fit_window == 'measured_full_v1'
    assert 0 < abs(fitted.template_fit_offset_samples)*2 <= 10
    assert fitted.peak_sample == fitted.template_observed_peak_sample+fitted.template_fit_offset_samples
    assert fitted.start_sample <= fitted.peak_sample <= fitted.end_sample
    assert fitted.template_score >= 6 and fitted.template_correlation >= .8
    assert abs(fitted.depth_m-1.131) < .015
    np.testing.assert_array_equal(disturbed, before)
