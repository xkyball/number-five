"""Owned TX control: one existing USB owner, finite device lease, physical evidence.

No profile is qualified by a simulation or API request. A hardware profile must
reference immutable measurement receipts; its absence leaves TX disabled.
"""
from __future__ import annotations
import asyncio
from dataclasses import asdict, dataclass
import hashlib
import json
import os
from pathlib import Path
import struct
import time
import uuid
from no5_sonard.pico_protocol import MessageType

CONFIG = struct.Struct("<IIIIII")
ARM = struct.Struct("<III")
FIRE = struct.Struct("<IQ")
ACK = struct.Struct("<IIIIQ")
EVENT = struct.Struct("<IIQQQQ")
TX_CAPABILITY = 1 << 6
REASONS = {0:"ok",1:"invalid",2:"unarmed",3:"busy",4:"duplicate",5:"missing_marker",
           6:"lease_expired",7:"stopped",8:"disconnected",9:"acquisition_fault",10:"missing_ping",
           11:"native_reference_lost",12:"native_collision",13:"late_output"}
EVENTS = {1:"output_started",2:"physical_marker",3:"output_finished",4:"fault",5:"aborted",6:"ping_link"}


class TxCommandTimeout(TimeoutError):
    """An unconfirmed USB control reply, distinct from archive or detector failures."""
    def __init__(self, command, request, timeout_s):
        self.command, self.request, self.timeout_s = command, request, timeout_s
        super().__init__(f'TX command 0x{command:02x} request {request} unconfirmed after {timeout_s:g}s')


@dataclass(frozen=True)
class TransmitProfile:
    serial: str
    kind: str
    frequency_hz: int
    max_high_ns: int
    max_cycles: int
    min_period_us: int
    max_high_ns_per_second: int
    sha256: str
    evidence: tuple
    series_ohms: int = 1000
    depth_reference_verified: bool = False

    @classmethod
    def load(cls, path):
        path = Path(path).resolve()
        data = path.read_bytes()
        row = json.loads(data)
        if row.get("schema") != "no5.transmit-profile/1" or row.get("qualified") is not True:
            raise ValueError("hardware profile is not qualified")
        kind = row.get("kind")
        required = {"pico_loopback": {"setup_isolated", "output_idle"},
                    "venterior_q13": {"q13_role", "original_isolation", "idle_no_tx", "drive_load", "native_reference"}}
        if kind not in required:
            raise ValueError("unsupported hardware profile kind")
        receipts = row.get("evidence", [])
        if not required[kind] <= {r.get("check") for r in receipts}:
            raise ValueError("missing qualification receipts")
        physical_checks = {}
        for r in receipts:
            evidence = (path.parent / r["path"]).resolve()
            if hashlib.sha256(evidence.read_bytes()).hexdigest() != r["sha256"]:
                raise ValueError("qualification receipt hash mismatch")
            receipt = json.loads(evidence.read_bytes())
            check = receipt.get("checks", {}).get(r["check"], {})
            if (receipt.get("schema") != "no5.hardware-check/1"
                    or receipt.get("pico_serial") != row.get("serial")
                    or check.get("passed") is not True
                    or check.get("basis") not in {"operator_measurement", "physical_capture"}):
                raise ValueError("qualification requires a passed physical measurement, not a simulation")
            physical_checks[r["check"]] = check
        values = {key: row[key] for key in ("frequency_hz", "max_high_ns", "max_cycles", "min_period_us", "max_high_ns_per_second")}
        if any(type(v) is not int or v <= 0 for v in values.values()):
            raise ValueError("profile limits must be positive integers")
        if not 180000 <= values["frequency_hz"] <= 220000 or not 500 <= values["max_high_ns"] <= 2500 or not 1 <= values["max_cycles"] <= 128 or values["min_period_us"] < 200000:
            raise ValueError("profile exceeds firmware bounds")
        if not isinstance(row.get("serial"), str) or len(row["serial"]) != 16:
            raise ValueError("profile requires the Pico serial")
        series = row.get("series_ohms", 1000)
        if type(series) is not int or series < 330:
            raise ValueError("series resistor must be at least 330 ohms")
        if kind == "venterior_q13":
            native = physical_checks["native_reference"].get("limits", {})
            keys = {"frequency_min_hz", "frequency_max_hz", "high_ns_lower_bound", "cycles_lower_bound", "high_ns_per_second_lower_bound"}
            if not keys <= set(native) or any(type(native[k]) is not int or native[k] <= 0 for k in keys):
                raise ValueError("native reference requires measured conservative limits")
            if (not native["frequency_min_hz"] <= values["frequency_hz"] <= native["frequency_max_hz"]
                    or values["max_high_ns"] > native["high_ns_lower_bound"]
                    or values["max_cycles"] > native["cycles_lower_bound"]
                    or values["max_high_ns_per_second"] > native["high_ns_per_second_lower_bound"]):
                raise ValueError("profile exceeds measured native limits")
            drive = physical_checks["drive_load"].get("measurements", {})
            current = drive.get("gpio_peak_source_ma")
            if (drive.get("series_ohms") != series or type(current) not in {int,float}
                    or not 0 < current <= 10):
                raise ValueError("drive load must match the resistor and a measured GPIO load <=10mA")
        owned_echo = kind == "venterior_q13" and "echo_aba" in physical_checks
        return cls(row["serial"], kind, sha256=hashlib.sha256(data).hexdigest(), evidence=tuple(receipts),
                   series_ohms=series, depth_reference_verified=owned_echo, **values)


class TransmitController:
    def __init__(self, service, profile=None):
        self.service, self.profile = service, profile
        self.client = None
        self.connection_id = None
        self.mode = "off"
        self.reason = "hardware qualification pending" if profile is None else "operator disabled"
        self.policy = {"mode":"off", "frequency_hz":197000, "high_ns":1000, "cycles":1, "period_us":1000000, "marker_timeout_us":5000}
        self._lock = asyncio.Lock()
        self._waiters = {}
        self._request = 0
        self._renewal = None
        self._requests = {}
        self._request_path = Path(getattr(service, "control_root", service.config.storage_root)) / "transmit-requests.jsonl"
        if self._request_path.exists():
            for line in self._request_path.read_text().splitlines():
                if line:
                    row = json.loads(line)
                    self._requests[row["request_id"]] = row
        self._ledger = {}
        self._pings = {}
        self.last_event = None
        self._evidence_journal = None
        self._archive_fault = None
        self.physical_markers = self.linked_pings = self.output_starts = 0
        self._fault_generation = 0

    def _persist_request(self, result):
        self._request_path.parent.mkdir(parents=True, exist_ok=True)
        with self._request_path.open("a") as output:
            output.write(json.dumps(result, sort_keys=True) + "\n")
            output.flush(); os.fsync(output.fileno())

    def _archive(self, event, **fields):
        if self._archive_fault:
            raise OSError(self._archive_fault)
        row = {"schema":"no5.transmit-evidence/1", "event":event, "host_utc_ns":time.time_ns(),
               "host_monotonic_ns":time.monotonic_ns(), "connection_id":self.connection_id,
               "segment_id":self.service.status.segment_id,
               "profile_sha256":None if self.profile is None else self.profile.sha256, **fields}
        recorder = getattr(self.service, 'test_recorder', None)
        if recorder is not None: recorder.evidence('transmit', row)
        if self.service._session_path is not None:
            path = self.service._session_path / "derived" / "transmit.jsonl"
            if getattr(self.service, '_async_evidence', False):
                from .evidence_journal import EvidenceJournal
                if self._evidence_journal is None: self._evidence_journal = EvidenceJournal(
                    root=getattr(self.service,'_capture_journal_root',None),
                    volume_guard=getattr(self.service,'_data_volume_guard',None))
                try:
                    self._evidence_journal.append(path, (json.dumps(row, sort_keys=True)+'\n').encode())
                except OSError as exc:
                    self._archive_fault = str(exc)
                    raise
                return row
            path.parent.mkdir(parents=True, exist_ok=True)
            with path.open("a") as output:
                output.write(json.dumps(row, sort_keys=True) + "\n"); output.flush(); os.fsync(output.fileno())
        return row

    def bind(self, client):
        self.client = client
        self.connection_id = str(uuid.uuid4())
        self.mode = self.policy["mode"] = "off"
        self._ledger.clear(); self._pings.clear()
        client.transport.transmit_handler = self.on_packet
        self.reason = "hardware qualification pending" if self.profile is None else "operator disabled"

    async def unbind(self):
        await self._cancel_renewal()
        if self.client:
            self.client.transport.transmit_handler = None
        self.client = None; self.mode = self.policy["mode"] = "off"
        for future in self._waiters.values():
            if not future.done(): future.set_exception(ConnectionError("TX transport disconnected"))
        self._waiters.clear()
        fault=getattr(self.service,'_storage_fault',None)
        self.reason = 'archive HOLD: '+fault if fault else "disconnected"
        # Evidence spans reconnects and final recovery/HOLD events in this
        # session. Closing it here allowed a second offset-zero writer to
        # collide with the same transmit.jsonl during later teardown.

    async def close_archive(self):
        journal=self._evidence_journal
        if journal is not None:
            try: await asyncio.to_thread(journal.close)
            except OSError as exc:
                self._archive_fault = str(exc)
                raise

    def snapshot(self):
        capable = bool(self.client and self.client.hello and self.client.hello.capabilities & TX_CAPABILITY)
        return {"schema":"no5.transmit-status/1", "mode":self.mode, "policy":dict(self.policy),
                "qualified":self.profile is not None, "firmware_capable":capable, "reason":self.reason,
                "profile":None if self.profile is None else asdict(self.profile), "last_event":self.last_event,
                "output_starts":self.output_starts, "physical_markers":self.physical_markers, "linked_pings":self.linked_pings,
                "last_transmissions":list(self._ledger.values())[-10:],
                "recovery": (None if getattr(self.service, 'recovery', None) is None
                             else self.service.recovery.snapshot())}

    def _ready(self):
        if self.profile is None: raise RuntimeError("hardware qualification pending")
        if not self.client or not self.client.hello or not (self.client.hello.capabilities & TX_CAPABILITY):
            raise RuntimeError("connected firmware has no owned TX capability")
        if self.client.hello.device_serial != self.profile.serial: raise RuntimeError("TX profile belongs to another Pico")
        cfg = self.client.accepted_config
        if not cfg or not cfg["requested"].get("reserved", 0) & 1:
            raise RuntimeError("TX requires physical GP16 marker capture")
        if self.service.status.state != "RUNNING": raise RuntimeError("acquisition is not running")

    def _validate(self, payload):
        if not isinstance(payload, dict) or set(payload)-set(self.policy): raise ValueError("unknown TX policy fields")
        result = {**self.policy, **payload}
        if result["mode"] not in {"off", "manual", "periodic"}: raise ValueError("mode must be off, manual or periodic")
        for key in set(result)-{"mode"}:
            if type(result[key]) is not int: raise ValueError(f"{key} must be an integer")
        if not (180000 <= result["frequency_hz"] <= 220000 and 500 <= result["high_ns"] <= 2500
                and 1 <= result["cycles"] <= 128 and 200000 <= result["period_us"] <= 10000000
                and 100 <= result["marker_timeout_us"] <= 10000
                and 1000000000//result["frequency_hz"]-result["high_ns"] >= 2000):
            raise ValueError("policy exceeds finite firmware bounds")
        if result["mode"] != "off":
            self._ready(); p = self.profile
            if (result["frequency_hz"] != p.frequency_hz or result["high_ns"] > p.max_high_ns
                    or result["cycles"] > p.max_cycles or result["period_us"] < p.min_period_us
                    or result["cycles"]*result["high_ns"]*1000000 > p.max_high_ns_per_second*result["period_us"]):
                raise ValueError("policy exceeds qualified hardware limits or average on-time budget")
        return result

    async def _command(self, kind, body=b""):
        if not self.client: raise RuntimeError("Pico is disconnected")
        if kind in (0x41, 0x42):
            check = getattr(self.service, "check_archive", None)
            if check: check()
        self._request = self._request % 0xFFFFFFFF + 1
        request = self._request
        future = asyncio.get_running_loop().create_future(); self._waiters[request] = future
        try:
            await self.client.send(MessageType(kind), struct.pack("<I", request) + body)
            # A successful ARM ACK was measured at 1.764s, with the device
            # still armed; 1.5s falsely aborted it. Keep 100ms below the unchanged
            # 2s hardware lease. No FIRE retry or extension of device authority.
            timeout = 1.9 if kind in (0x41, 0x43) else 1.0
            try:
                ack = await asyncio.wait_for(future, timeout)
            except asyncio.TimeoutError as exc:
                raise TxCommandTimeout(kind, request, timeout) from exc
        finally:
            self._waiters.pop(request, None)
        if ack["result"]: raise RuntimeError("Pico TX: " + REASONS.get(ack["result"], str(ack["result"])))
        return ack

    async def _cancel_renewal(self):
        task, self._renewal = self._renewal, None
        if task:
            task.cancel()
            try: await task
            except asyncio.CancelledError: pass

    async def update(self, payload):
        async with self._lock:
            policy = self._validate(payload)
            await self._cancel_renewal()
            self.mode = self.policy["mode"] = "off"
            self.reason = "control transition; TX remains disabled until confirmed"
            if self.client and self.client.hello and self.client.hello.capabilities & TX_CAPABILITY:
                await self._command(0x43)
            if policy["mode"] != "off":
                fault_generation = self._fault_generation
                try:
                    await self._command(0x40, struct.pack("<IIIII", *(policy[k] for k in ("frequency_hz","high_ns","cycles","period_us","marker_timeout_us"))))
                    await self._command(0x41, struct.pack("<II", 1 if policy["mode"]=="manual" else 2, 2000000))
                    if self._fault_generation != fault_generation:
                        raise RuntimeError("device fault while arming: " + self.reason)
                except Exception as exc:
                    self.reason = f"control failed; device lease bounds uncertain output: {exc}"
                    try: await self._command(0x43)
                    except Exception: pass
                    raise
            self.policy = policy; self.mode = policy["mode"]
            self.reason = "operator disabled" if self.mode == "off" else "armed; waiting for physical TX evidence"
            self._archive("policy", policy=policy)
            if self.mode != "off": self._renewal = asyncio.create_task(self._renew(), name="tx-lease")
            return self.snapshot()

    async def _renew(self):
        try:
            while self.mode != "off":
                await asyncio.sleep(.5)
                if self.mode == "off": break
                await self._command(0x41, struct.pack("<II", 1 if self.mode=="manual" else 2, 2000000))
        except (Exception, asyncio.CancelledError) as exc:
            if isinstance(exc, asyncio.CancelledError): raise
            self.mode = self.policy["mode"] = "off"; self.reason = f"lease renewal failed: {exc}"
            self._archive("host_fault", reason=self.reason)
            try: await self._command(0x43)
            except Exception: pass  # Device lease is bounded even if USB has failed.

    async def fire(self, payload):
        if not isinstance(payload, dict) or set(payload) != {"request_id"}: raise ValueError("body must contain only request_id (UUID)")
        try: key = str(uuid.UUID(payload["request_id"]))
        except (ValueError, TypeError, AttributeError) as exc: raise ValueError("request_id must be a UUID") from exc
        async with self._lock:
            if key in self._requests: return dict(self._requests[key])
            self._ready()
            if self.mode != "manual": raise RuntimeError("single FIRE requires manual mode")
            if len(self._requests) >= 10000: raise RuntimeError("request ledger full; no IDs are evicted")
            tx_id = time.monotonic_ns()
            result = {"request_id":key, "tx_id":tx_id, "command_accepted":False, "physical_tx_confirmed":False, "state":"attempted"}
            self._requests[key] = result
            self._persist_request(result)  # Durable before any bytes can cause a burst.
            self._ledger[tx_id] = {**result, "ping_id":None}
            self._archive("fire_attempt", **result, policy=dict(self.policy))
            try:
                await self._command(0x42, struct.pack("<Q", tx_id))
                result["command_accepted"] = True; result["state"] = "accepted"
                self._ledger[tx_id]["command_accepted"] = True
            except Exception as exc:
                result["state"] = "uncertain_or_rejected"; result["error"] = str(exc)
                self._persist_request(result)
                self._archive("fire_result", **result)
                raise
            self._archive("fire_result", **result)
            self._persist_request(result)
            return dict(result)

    async def abort(self):
        async with self._lock:
            await self._cancel_renewal()
            self.mode = self.policy["mode"] = "off"
            fault=getattr(self.service,'_storage_fault',None)
            self.reason = 'archive HOLD: '+fault if fault else "operator disabled"
            if self.client and self.client.hello and self.client.hello.capabilities & TX_CAPABILITY:
                await self._command(0x43)
            self._archive("abort_requested")
            return self.snapshot()

    def on_packet(self, packet):
        if int(packet.type) == 0x44:
            if len(packet.payload) != ACK.size: raise ValueError("TX_ACK payload length")
            keys = ("request", "result", "state", "mode", "lease_until_us")
            ack = dict(zip(keys, ACK.unpack(packet.payload)))
            future = self._waiters.get(ack["request"])
            if future and not future.done(): future.set_result(ack)
            self._archive("ack", **ack)
            return
        if len(packet.payload) != EVENT.size: raise ValueError("TX_EVENT payload length")
        event, reason, tx_id, start, marker, ping_id = EVENT.unpack(packet.payload)
        row = {"firmware_event":EVENTS.get(event, str(event)), "reason":REASONS.get(reason,str(reason)),
               "tx_id":tx_id, "output_start_device_us":start, "marker_device_us":marker, "ping_id":ping_id or None}
        self.last_event = row
        item = self._ledger.setdefault(tx_id, {"tx_id":tx_id, "physical_tx_confirmed":False, "ping_id":None})
        # Control/end events carry ping_id=0. They can arrive after a link
        # event but before its queued raw PING is processed by the archive.
        # Keep the observed link so the late raw PING can still reconcile it.
        item.update({key: value for key, value in row.items()
                     if key != "ping_id" or value is not None})
        if event == 1: self.output_starts += 1
        if event == 2:
            self.physical_markers += 1; item["physical_tx_confirmed"] = True
        if event in (4, 5):
            self._fault_generation += 1
            self.mode = self.policy["mode"] = "off"; self.reason = row["reason"]
        if event == 6: self._reconcile(item)
        self._archive("device_event", **row)
        # Keep a bounded recent view; durable JSONL retains every event.
        if len(self._ledger) > 512: self._ledger.pop(next(iter(self._ledger)))

    def on_ping(self, ping):
        self._pings[ping.ping_id] = {"time_us":ping.trigger_device_time_us, "period_ns":ping.sample_period_ns,
                                    "marker_valid":bool(ping.ping_flags & 1) and ping.tx_zero_sample_index == 0
                                    and not bool(ping.ping_flags & ((1 << 2) | (1 << 4) | (1 << 5) | (1 << 6)))}
        for item in self._ledger.values():
            if item.get("ping_id") == ping.ping_id: self._reconcile(item)
        if len(self._pings) > 512: self._pings.pop(next(iter(self._pings)))

    def _reconcile(self, item):
        ping = self._pings.get(item.get("ping_id"))
        if not ping or item.get("raw_ping_linked"): return
        valid = (item.get("physical_tx_confirmed", False) and ping["marker_valid"] and
                 abs(ping["time_us"]-item["marker_device_us"]) <= ping["period_ns"]//1000+1)
        item["raw_ping_linked"] = valid
        if valid:
            self.linked_pings += 1
            self._archive("verified_raw_ping_link", **item)
        else:
            self.mode = self.policy["mode"] = "off"; self.reason = "physical marker/PING mismatch"
            self._fault_generation += 1
            self._archive("association_rejected", **item)
            if self.client:
                asyncio.get_running_loop().create_task(self._association_abort(), name="tx-association-abort")

    async def _association_abort(self):
        await self._cancel_renewal()
        try:
            await self._command(0x43)
        except Exception as exc:
            self._archive("association_abort_unconfirmed", error=str(exc))
