"""Experimental timing labels, not an acoustic source authentication claim.

Seven-cycle binary pattern; every requested interval is at least the qualified
floor. Actual device-window revisions and physical markers remain authoritative.
"""
PATTERN = (1, 1, 1, 0, 0, 1, 0)
PATTERN_NAME = "native_cycle_code_v1"


def interval_symbol(native_stable, floor_us):
    index = int(native_stable) % len(PATTERN)
    bit = PATTERN[index]
    return {"pattern": PATTERN_NAME, "index": index, "symbol": bit,
            "requested_spacing_us": max(floor_us, 27000) if bit else floor_us}
