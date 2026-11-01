from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import subprocess
import uvicorn
from fastapi import HTTPException, WebSocket
from fastapi.middleware.cors import CORSMiddleware
from no5_sonard.app import create_app
from no5_sonard.config import ServiceConfig
from . import __version__
from .detection import DetectionPolicy
from .service import HostService
from .transmit import TransmitProfile


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description="OpenEcho Sonard host, existing Pico PING mode")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument('--cobs-decoder', choices=['auto', 'python', 'native'], default='auto',
                        help='Process COBS decode backend; native requires reviewed C extension')
    parser.add_argument('--depth-rate-trial-spacing-us', type=int,
                        help='Local bounded experimental candidate; never a qualified normal rate')
    parser.add_argument('--depth-rate-trial-evidence', type=Path,
                        help='Passed short/30s/R17/A-B-A receipts for the one final endurance candidate')
    parser.add_argument('--depth-rate-trial-probe-source', choices=['ECHO_AD','R17_a'],default='ECHO_AD',
                        help='Explicit 2s recovery diagnostic; R17 never supplies a sonar depth')
    parser.add_argument("--device", required=True)
    parser.add_argument("--expected-serial", required=True)
    parser.add_argument("--test-archive", action="store_true", help="Optional lossy ringbuffer test recorder; default off")
    parser.add_argument("--storage-root", type=Path, required=True)
    parser.add_argument('--control-root',type=Path,help='Persistent internal control/intent state for managed service restarts')
    parser.add_argument("--pid-file", type=Path,
                        help="Exclusive new PID receipt for a matching graceful stop command")
    parser.add_argument("--trigger-threshold", type=int, required=True,
                        help="Explicit 12-bit ADC code; no historical hardware default")
    parser.add_argument("--trigger-hysteresis", type=int, required=True)
    parser.add_argument("--sample-period-us", type=int, choices=[2, 4, 8], default=4)
    parser.add_argument("--sample-count", type=int, default=10000)
    parser.add_argument("--pretrigger", type=int, default=256)
    parser.add_argument("--capture-timeout-us", type=int, default=1000000)
    parser.add_argument("--origin", choices=["unknown", "venterior_normal", "venterior_demo"],
                        default="unknown")
    parser.add_argument("--empirical-marker-aba", action="store_true",
                        help="Use the recorded 2026-10-12 marker A-B-A evidence as an empirical TX origin")
    parser.add_argument("--detection-filter-mode", choices=["off", "auto", "on"], default="auto",
                        help="Persisted CFAR/block/track detector policy; RAW archive only with --test-archive")
    parser.add_argument("--capture-journal-root",type=Path,default=None,
                        help="Bounded durable SSD journal; requires the identified-volume launcher")
    parser.add_argument("--archive-chunk-pings", type=int, choices=(16,64,256), default=16,
                        help="Raw Zarr chunk rows; each PING remains fsync'd in its original WAL")
    parser.add_argument("--noise-reduction-mode", choices=["off", "auto", "on"], default=None,
                        help="Display noise reduction; saved policy or auto by default")
    parser.add_argument("--log-level", default="info")
    parser.add_argument("--display-ping-source", choices=["all", "own", "native"], default="all",
                        help="Echogram/presentation selection only; archive all PINGs only with --test-archive")
    parser.add_argument("--firmware-contract", choices=["marker-05", "owned-tx-07", "clip-076", "clip-077", "clip-078", "clip-079", "clip-0710", "clip-0711", "clip-0712", "clip-0713"], default="marker-05")
    parser.add_argument("--clip-endurance-profile", type=Path)
    parser.add_argument("--physical-marker", action="store_true", help="Capture GP16 without empirical analog-trigger fallback")
    parser.add_argument("--hardware-tx-marker", action="store_true", help="Use real GP16 transducer markers without empirical fallback")
    parser.add_argument("--transmit-profile", type=Path, help="Qualified, hash-bound hardware profile; never auto-arms TX")
    args = parser.parse_args(argv)
    if args.depth_rate_trial_spacing_us is not None and (args.firmware_contract not in {'clip-0711','clip-0712','clip-0713'} or
            not 6000 <= args.depth_rate_trial_spacing_us <= 27000):
        parser.error('depth-rate trials require clip-0711 and spacing 6000..27000us')
    if args.depth_rate_trial_evidence and args.depth_rate_trial_spacing_us is None:
        parser.error('trial evidence requires an explicit trial spacing')
    if args.depth_rate_trial_probe_source!='ECHO_AD' and args.depth_rate_trial_spacing_us is None:
        parser.error('R17 requires an explicit bounded depth-rate trial')
    if args.clip_endurance_profile and (args.firmware_contract not in {'clip-076','clip-077','clip-078','clip-079','clip-0710','clip-0711','clip-0712','clip-0713'} or args.transmit_profile or
            not args.hardware_tx_marker or args.sample_period_us!=2 or args.sample_count!=8192 or args.pretrigger!=0):
        parser.error('clip profile requires clip-076, hardware-tx-marker, 2us/8192/zero pretrigger; no normal TX profile')
    if args.firmware_contract in {'clip-076','clip-077','clip-078','clip-079','clip-0710','clip-0711','clip-0712','clip-0713'} and not args.clip_endurance_profile:
        parser.error('clip-076 requires a bound experimental hardware profile')
    if args.hardware_tx_marker and args.empirical_marker_aba:
        parser.error("hardware-tx-marker and empirical-marker-aba are mutually exclusive")
    if args.transmit_profile and args.firmware_contract != "owned-tx-07":
        parser.error("transmit-profile requires firmware-contract owned-tx-07")
    if not 32 <= args.sample_count <= 16384:
        parser.error("sample-count must be 32..16384")
    if not 0 <= args.pretrigger <= min(8192, args.sample_count - 1):
        parser.error("pretrigger outside sample window")
    if not 0 < args.trigger_hysteresis <= args.trigger_threshold <= 4095:
        parser.error("require 0 < hysteresis <= threshold <= 4095")
    if args.capture_timeout_us < 1000:
        parser.error("capture-timeout-us must be at least 1000")
    return args


def create_host_app(service):
    """Expose runtime policy and boat-context without a service restart."""
    app = create_app(service)
    # The preserved handler only awaits queue.get(); a silent own-only stream
    # therefore hid disconnects and blocked Uvicorn's graceful shutdown.
    from .display_socket import serve_display
    routes=[route for route in app.router.routes if getattr(route,'path',None)=='/ws/v1/echogram']
    if len(routes)!=1:
        raise RuntimeError('preserved display socket contract changed')
    app.router.routes.remove(routes[0])

    @app.websocket('/ws/v1/echogram')
    async def selected_echogram(websocket: WebSocket):
        await serve_display(websocket, service.hub)

    @app.websocket('/ws/v1/echogram-context')
    async def contextual_echogram(websocket: WebSocket):
        await serve_display(websocket, service.context_hub)
    # The historical local reference page is served on port 8788 while the
    # host runs on 8787.  Limit cross-origin reads to those two loopback
    # origins; the Android app continues through its gateway contract.
    app.add_middleware(CORSMiddleware,
                       allow_origins=["http://127.0.0.1:8788", "http://localhost:8788"],
                       allow_methods=["GET"], allow_headers=[])

    @app.get("/api/v1/detection/filter")
    async def detection_filter():
        latest = None if service._last_detection is None else service._last_detection.public()
        return {"policy": service.detection.policy.public(), "selection": service._detection_decision(),
                "latest": latest}

    @app.put("/api/v1/detection/filter")
    async def set_detection_filter(payload: dict):
        if set(payload) != {"mode"}:
            raise HTTPException(status_code=422, detail="body must contain only mode")
        try:
            async with service._analysis_lock:
                policy = service.set_detection_mode(payload["mode"])
        except (TypeError, ValueError) as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        return {"policy": policy}

    @app.get("/api/v1/sonar/noise-reduction")
    async def noise_reduction():
        return service.noise_reduction_status()

    @app.put("/api/v1/sonar/noise-reduction")
    async def set_noise_reduction(payload: dict):
        if not payload or set(payload)-{"mode", "stripe_mode", "impulse_mode", "near_field_m"}:
            raise HTTPException(status_code=422, detail="body accepts mode, stripe_mode, impulse_mode and near_field_m")
        if "near_field_m" in payload:
            from .near_field import validate_distance
            try:
                validate_distance(payload["near_field_m"])
            except ValueError as exc:
                raise HTTPException(status_code=422, detail=str(exc)) from exc
        if "stripe_mode" in payload and not isinstance(payload["stripe_mode"], str):
            raise HTTPException(status_code=422, detail="stripe_mode must be off, auto or on")
        if "impulse_mode" in payload and not isinstance(payload["impulse_mode"], str):
            raise HTTPException(status_code=422, detail="impulse_mode must be off, auto or on")
        try:
            before = service.noise_reduction.revision
            async with service._analysis_lock:
                status = service.set_noise_reduction_mode(payload.get("mode", service.noise_reduction.mode),
                                                           stripe_mode=payload.get("stripe_mode"),
                                                           impulse_mode=payload.get("impulse_mode"),
                                                           near_field_m=payload.get("near_field_m"))
            return {**status, "clear_client_history": before != service.noise_reduction.revision}
        except (TypeError, ValueError) as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc

    @app.get("/api/v1/acquisition/context")
    async def acquisition_context():
        return {"context": dict(service._acquisition_context)}

    @app.patch("/api/v1/acquisition/context")
    async def update_acquisition_context(payload: dict):
        try:
            return {"context": service.update_acquisition_context(payload)}
        except (TypeError, ValueError) as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc

    @app.get("/api/v1/detection/latest")
    async def latest_detection():
        return {"detection": service.latest_detection(),
                "presentation": service.presentation(),
                "policy": service.detection.policy.public()}

    @app.get("/api/v1/sonar/presentation")
    async def sonar_presentation():
        return service.presentation()

    @app.get("/api/v1/sonar/published-pings")
    async def published_pings(after_ping_id: int = 0, limit: int = 64):
        try:
            return service.published_pings(after_ping_id, limit)
        except ValueError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc

    @app.get("/api/v1/sonar/display-selection")
    async def display_selection():
        return service.display_selection()

    @app.put("/api/v1/sonar/display-selection")
    async def set_display_selection(payload: dict):
        if set(payload) != {"source"}:
            raise HTTPException(status_code=422, detail="body must contain only source")
        try:
            return {**service.set_display_source(payload["source"]), "clear_client_history": True}
        except (TypeError, ValueError) as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc

    @app.get("/api/v1/detection/tracks")
    async def detection_tracks():
        latest = service.latest_detection()
        return {"tracks": [] if latest is None else latest["tracks"],
                "rule_classes": sorted(__import__("no5_sonard_openecho.detection",
                                                  fromlist=["RULE_CLASSES"]).RULE_CLASSES)}

    @app.get("/api/v1/labels")
    async def labels():
        return {"labels": service.labels()}

    @app.post("/api/v1/labels", status_code=201)
    async def append_label(payload: dict):
        try:
            return {"label": service.append_label(payload)}
        except (TypeError, ValueError) as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc

    @app.get("/api/v1/transmit")
    async def transmit_status():
        return service.transmit.snapshot()

    async def tx_call(operation, payload=None):
        try:
            return await operation() if payload is None else await operation(payload)
        except (TypeError, ValueError) as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        except (RuntimeError, ConnectionError, TimeoutError) as exc:
            raise HTTPException(status_code=409, detail=str(exc)) from exc

    @app.put("/api/v1/transmit")
    async def transmit_policy(payload: dict):
        return await tx_call(service.recovery.operator_update, payload)

    @app.post("/api/v1/transmit/fire")
    async def transmit_fire(payload: dict):
        return await tx_call(service.recovery.operator_fire, payload)

    @app.post("/api/v1/transmit/abort")
    async def transmit_abort():
        return await tx_call(service.recovery.operator_abort)

    @app.get("/api/v1/transmit/recovery")
    async def recovery_status():
        return service.recovery.snapshot()

    @app.put("/api/v1/transmit/recovery")
    async def recovery_policy(payload: dict):
        return await tx_call(service.recovery.set_policy, payload)

    @app.post('/api/v1/transmit/start-continuous')
    async def start_continuous(payload: dict):
        return await tx_call(service.recovery.start_continuous,payload)

    @app.get('/api/v1/transmit/live')
    async def transmit_live():
        if not hasattr(service.transmit,'live'):
            raise HTTPException(status_code=409,detail='no clip endurance session')
        return service.transmit.live()

    return app


def main(argv=None):
    args = parse_args(argv)
    host_lock = None
    if args.control_root is not None:
        import fcntl
        args.control_root.mkdir(parents=True,exist_ok=True,mode=0o700)
        host_lock=(args.control_root/'host.lock').open('a')
        fcntl.flock(host_lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
    import logging
    # trackpy otherwise logs every historical frame of every rolling link.
    logging.getLogger("trackpy").setLevel(logging.DEBUG if args.log_level == "debug" else logging.WARNING)
    profile = TransmitProfile.load(args.transmit_profile) if args.transmit_profile else None
    if args.clip_endurance_profile:
        from .clip_endurance import ClipProfile
        profile=ClipProfile.load(args.clip_endurance_profile)
    if args.hardware_tx_marker and profile and profile.kind=="pico_loopback":
        raise ValueError("GPIO loopback is not a transducer depth reference")
    journal_guard=None
    if args.capture_journal_root is not None:
        if args.archive_chunk_pings!=256:
            raise ValueError('capture journal requires 256-row rotating RAW parts')
        device=os.environ.get('NO5_DATA_VOLUME_DEVICE')
        mount=os.environ.get('NO5_DATA_VOLUME_MOUNT')
        volume_uuid=os.environ.get('NO5_DATA_VOLUME_UUID')
        if not device or not mount or not volume_uuid or os.stat('.').st_dev!=int(device):
            raise ValueError('capture journal requires the identified data-volume launcher')
        journal_guard={'mount':mount,'device':int(device),'uuid':volume_uuid}
    service = HostService(
        ServiceConfig(
        device=args.device, storage_root=args.storage_root,
        zarr_chunk_pings=args.archive_chunk_pings,
        sample_period_ns=args.sample_period_us * 1000,
        sample_count=args.sample_count, pretrigger_samples=args.pretrigger,
        trigger_threshold=args.trigger_threshold, trigger_hysteresis=args.trigger_hysteresis,
        capture_timeout_us=args.capture_timeout_us,
        origin_operator_selected=args.origin,
        tx_marker_requested=args.empirical_marker_aba or args.physical_marker or args.hardware_tx_marker or profile is not None,
        empirical_trigger_fallback=args.empirical_marker_aba and not (args.physical_marker or profile),
        tx_reference_basis=("hardware_gp16_transducer" if args.hardware_tx_marker else "owned_marker_aba_v1" if profile and profile.depth_reference_verified
                            else "empirical_marker_aba_20261012" if args.empirical_marker_aba else "unverified"),
        tx_reference_verified=args.empirical_marker_aba or args.hardware_tx_marker or bool(profile and profile.depth_reference_verified)),
        expected_identity={"device_serial": args.expected_serial, "fw_major": 0,
                           "fw_minor": 7 if args.firmware_contract in {"owned-tx-07","clip-076","clip-077","clip-078", "clip-079", "clip-0710", "clip-0711", "clip-0712", "clip-0713"} else 5,
                           "fw_patch": 13 if args.firmware_contract=='clip-0713' else 12 if args.firmware_contract=='clip-0712' else 11 if args.firmware_contract=='clip-0711' else 10 if args.firmware_contract=='clip-0710' else 9 if args.firmware_contract=='clip-079' else 8 if args.firmware_contract=='clip-078' else 7 if args.firmware_contract=='clip-077' else 6 if args.firmware_contract=='clip-076' else 0, "adc_bits": 12,
                           "max_sample_rate_hz": 500000, "max_samples": 16384,
                           "capabilities": 0xC0003FEE if args.firmware_contract in {'clip-0712','clip-0713'} else 0xC0001FEE if args.firmware_contract=='clip-0711' else 0xC0000FEE if args.firmware_contract=='clip-0710' else 0xC00007EE if args.firmware_contract=='clip-079' else 0xC00003EE if args.firmware_contract=='clip-078' else 0xC00001EE if args.firmware_contract=='clip-077' else 0xC00000EE if args.firmware_contract=='clip-076' else 0xC000006E if args.firmware_contract=="owned-tx-07" else 0xC000002E},
        detection_policy=DetectionPolicy(mode=args.detection_filter_mode), transmit_profile=profile,
        display_ping_source=args.display_ping_source, noise_reduction_mode=args.noise_reduction_mode,
        cobs_decoder=args.cobs_decoder,
        capture_journal_root=None,data_volume_guard=None,
        control_root=args.control_root, operational_receipt_path=(args.pid_file.with_suffix(".shutdown.json") if args.pid_file else None), test_archive_root=(args.storage_root / "test-archive" / __import__("uuid").uuid4().hex if args.test_archive else None))
    if args.depth_rate_trial_spacing_us is not None:
        from .depth_rate import DepthRateController
        if not isinstance(service.transmit, DepthRateController):
            raise ValueError('trial requires a depth-rate hardware profile')
        service.transmit.trial_spacing_us=args.depth_rate_trial_spacing_us
        service.transmit.diagnostic_source=args.depth_rate_trial_probe_source
        if args.depth_rate_trial_probe_source!='ECHO_AD':
            from dataclasses import replace
            service.config=replace(service.config,tx_reference_verified=False)
            service.update_acquisition_context({'mount':{'probe_source':args.depth_rate_trial_probe_source,
                'purpose':'finite_tx_recovery_diagnostic_not_depth'}})
        if args.depth_rate_trial_evidence:
            evidence=json.loads(args.depth_rate_trial_evidence.read_text())
            if (evidence.get('schema')!='no5.depth-rate-prequalification/1' or
                evidence.get('minimum_spacing_us')!=args.depth_rate_trial_spacing_us or
                evidence.get('serial')!=args.expected_serial or
                not all(evidence.get(k) is True for k in ('short_passed','thirty_seconds_passed',
                    'r17_recovery_passed','echo_aba_passed','all_physical_links','raw_samples_unchanged'))):
                raise ValueError('candidate lacks physical prequalification')
            service.transmit.trial_endurance_evidence=evidence
    if args.pid_file:
        service._ensure_session()
        args.pid_file.parent.mkdir(parents=True, exist_ok=True)
        identity = subprocess.check_output(
            ["ps", "-p", str(os.getpid()), "-o", "pid=,lstart=,comm="], text=True).strip()
        with args.pid_file.open("x") as output:
            json.dump({"pid": os.getpid(), "process_identity": identity,
                       "package": "no5-sonard-openecho", "version": __version__,
                       "final_status_path": str(service.operational_receipt_path)}, output)
            output.write("\n")
    # SIGINT/SIGTERM run FastAPI lifespan -> STOP/STATUS -> WAL close -> port close.
    uvicorn.run(create_host_app(service), host=args.host, port=args.port, log_level=args.log_level,
                timeout_graceful_shutdown=2, access_log=False)


if __name__ == "__main__":
    main()
