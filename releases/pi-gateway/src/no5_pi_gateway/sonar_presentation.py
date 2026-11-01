"""Same-ping projection of Sonard 0.3.5 analysis onto its actual display frame.

This is interpretation only. Neither samples nor the independent legacy
detector are changed, and no acquisition command is synthesized.
"""
from __future__ import annotations

import math

from .protocol import APP_BINS, EchogramFrame, ProtocolError

EMPTY_BOTTOM = {"bin": None, "confidence": None, "strength": None}
SOURCES = {"m1_raw", "cfar_track_rules_v1", "direct_envelope_track_rules_v1"}
QUALITIES = {"WAITING", "SEARCHING", "LOCKED", "UNSTABLE", "UNVERIFIED_TX"}
RULE_CLASSES = {"bottom_like", "fish_like", "weed_like", "bubble_like", "multipath_like", "unknown"}
MAX_DISPLAY_TRACKS = 8  # Keeps the existing 4096-byte Android metadata bound.


def geometry(frame: EchogramFrame) -> dict[str, object]:
    valid_tx = bool(frame.flags & 1) and not bool(frame.flags & (1 << 5))
    if frame.presentation is not None and frame.presentation["quality"] == "UNVERIFIED_TX":
        valid_tx = False
    maximum = frame.displayed_max_depth_m
    valid = valid_tx and math.isfinite(maximum) and maximum > 0
    return {
        "displayedMaxDepthM": float(maximum) if valid else None,
        "txReference": ("EMPIRICAL_FALLBACK" if frame.flags & (1 << 7) else "HOST_VERIFIED")
        if valid else "UNVERIFIED",
        "flags": frame.flags,
        "adcSamplePeriodUs": (frame.publication.sample_period_ns / 1000 if frame.publication is not None else
                              None if frame.presentation is None else frame.presentation["samplePeriodNs"] / 1000),
    }


def _number(value: object, low: float, high: float) -> float:
    if isinstance(value, bool) or not isinstance(value, (float, int)):
        raise ProtocolError("invalid Sonard analysis number")
    result = float(value)
    if not math.isfinite(result) or not low <= result <= high:
        raise ProtocolError("Sonard analysis number out of range")
    return result


def _text(value: object) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str) or any(ord(c) < 32 for c in value):
        raise ProtocolError("invalid Sonard analysis text")
    return value[:200]


def project(frame: EchogramFrame, payload: object) -> dict[str, object] | None:
    """Return a bounded display projection only for this exact ping and UTC time."""
    if not isinstance(payload, dict):
        raise ProtocolError("Sonard latest detection must be an object")
    detection, presentation = payload.get("detection"), payload.get("presentation")
    if detection is None:
        return None
    if not isinstance(detection, dict) or not isinstance(presentation, dict):
        raise ProtocolError("Sonard detection/presentation must be objects")
    if detection.get("schema") != "no5.sonard-detection/2" or presentation.get("schema") != "no5.sonard-presentation/1":
        raise ProtocolError("unsupported Sonard analysis schema")
    if (type(detection.get("ping_id")) is not int or
            type(presentation.get("ping_id")) is not int or
            type(detection.get("time_utc_ns")) is not int):
        raise ProtocolError("Sonard analysis requires integer ping/time identities")
    if (detection["ping_id"] != frame.ping_id or presentation["ping_id"] != frame.ping_id or
            detection["time_utc_ns"] != frame.time_utc_ns):
        return None
    quality = presentation.get("quality")
    if not isinstance(quality, str) or quality not in QUALITIES or type(detection.get("valid_tx")) is not bool:
        raise ProtocolError("invalid Sonard analysis quality")
    sample_period = _number(detection.get("sample_period_ns"), 1, 1_000_000)
    selection = presentation.get("filter")
    recommendation = presentation.get("recommendations")
    if not isinstance(selection, dict) or not isinstance(recommendation, dict):
        raise ProtocolError("Sonard filter/recommendations must be objects")
    if (not isinstance(selection.get("requested_mode"), str) or selection["requested_mode"] not in {"off", "auto", "on"} or
            not isinstance(selection.get("effective_mode"), str) or selection["effective_mode"] not in {"off", "on", "auto_raw", "auto_filter"} or type(selection.get("active")) is not bool):
        raise ProtocolError("invalid Sonard filter selection")
    if not isinstance(recommendation.get("range"), str) or recommendation["range"] not in {"FOLLOW_BOTTOM", "HOLD"} or type(recommendation.get("freeze")) is not bool:
        raise ProtocolError("invalid Sonard recommendations")
    bottom: dict[str, object] = dict(EMPTY_BOTTOM)
    end_bin = None
    candidate = presentation.get("bottom")
    if quality == "LOCKED":
        if not detection["valid_tx"] or not isinstance(candidate, dict):
            raise ProtocolError("LOCKED requires a time-referenced bottom")
        source = candidate.get("source")
        if not isinstance(source, str) or source not in SOURCES:
            raise ProtocolError("unsupported Sonard bottom source")
        confidence = _number(candidate.get("confidence"), 0, 1)
        if source == "m1_raw" and confidence < .75:
            raise ProtocolError("raw bottom below Sonard auto threshold")
        depth = _number(candidate.get("depth_m"), 0, 1_000_000)
        strength = _number(candidate.get("strength"), 0, 1)
        track = candidate.get("track_id")
        classification = candidate.get("classification")
        if not isinstance(classification, str) or classification not in {"bottom_like", "fish_like", "weed_like", "bubble_like", "multipath_like", "unknown"}:
            raise ProtocolError("unknown Sonard rule class")
        if source == "m1_raw":
            if track is not None or candidate.get("classification_source") is not None:
                raise ProtocolError("raw bottom cannot identify a classified track")
        elif type(track) is not int or track <= 0 or candidate.get("classification_source") != "rules_v1":
            raise ProtocolError("track bottom requires a rules_v1 identity")
        if source != "m1_raw":
            tracks = presentation.get("tracks")
            if not isinstance(tracks, list):
                raise ProtocolError("Sonard confirmed tracks must be a list")
            selected = [item for item in tracks if isinstance(item, dict) and type(item.get("track_id")) is int and item["track_id"] == track]
            if len(selected) != 1 or selected[0].get("state") != "confirmed":
                raise ProtocolError("selected Sonard track must be confirmed")
            if (_number(selected[0].get("depth_m"), 0, 1_000_000) != depth or
                    _number(selected[0].get("confidence"), 0, 1) != confidence):
                raise ProtocolError("selected Sonard track disagrees with its presentation")
        scale = geometry(frame)["displayedMaxDepthM"]
        if scale is not None and depth <= scale:
            bottom = {"bin": depth * (APP_BINS - 1) / scale, "confidence": confidence,
                      "strength": strength, "source": source, "trackId": track,
                      "classification": classification}
            # Sonard's display_bin/range_end_bin use the full ADC window.
            # Their physical-depth ratio converts the recommendation to the
            # actual post-TX stream without assuming zero pretrigger or an ADC
            # period equal to display-bin spacing.
            full_bin = _number(candidate.get("display_bin"), 0, APP_BINS - 1)
            requested_end = recommendation.get("range_end_bin")
            if full_bin > 0 and requested_end is not None:
                end_bin = min(APP_BINS - 1, max(32.0,
                    _number(requested_end, 32, APP_BINS - 1) * depth / full_bin / scale * (APP_BINS - 1)))
    elif candidate is not None:
        raise ProtocolError("non-LOCKED analysis cannot select a bottom")
    projected_tracks = []
    confirmed = presentation.get("tracks", [])
    if not isinstance(confirmed, list): raise ProtocolError("Sonard tracks must be a list")
    scale = geometry(frame)["displayedMaxDepthM"] if quality != "UNVERIFIED_TX" else None
    track_total = 0
    seen = set()
    for item in confirmed:
        if not isinstance(item, dict) or item.get("state") != "confirmed": continue
        track_total += 1
        # Older contract fixtures omit classification metadata. Such entries cannot
        # acquire a made-up rule class or provenance for the new overlay.
        if item.get("classification_source") != "rules_v1": continue
        identifier = item.get("track_id")
        if type(identifier) is not int or identifier <= 0 or identifier in seen:
            raise ProtocolError("invalid or duplicate confirmed track identity")
        seen.add(identifier)
        classification = item.get("classification")
        detector = item.get("detector")
        if classification not in RULE_CLASSES or detector not in {"cfar", "direct_envelope"}:
            raise ProtocolError("invalid track classification provenance")
        depth = _number(item.get("depth_m"), 0, 1_000_000)
        confidence = _number(item.get("confidence"), 0, 1)
        if scale is not None and depth <= scale:
            projected_tracks.append({"trackId": identifier, "bin": depth / scale * (APP_BINS - 1),
                                     "confidence": confidence, "classification": classification,
                                     "classificationSource": "rules_v1", "detector": detector})
    projected_tracks.sort(key=lambda item: (item["bin"], item["trackId"]))
    projected_tracks = projected_tracks[:MAX_DISPLAY_TRACKS]
    return {"pingId": frame.ping_id, "quality": quality,
            "reason": _text(presentation.get("reason")), "bottom": bottom,
            "samplePeriodNs": sample_period,
            "filter": {"requestedMode": selection["requested_mode"],
                       "effectiveMode": selection["effective_mode"], "active": selection["active"]},
            "recommendations": {"range": recommendation["range"],
                                "rangeEndBin": end_bin, "freeze": recommendation["freeze"]},
            "tracks": projected_tracks, "trackTotal": track_total,
            "trackOmitted": track_total - len(projected_tracks)}
