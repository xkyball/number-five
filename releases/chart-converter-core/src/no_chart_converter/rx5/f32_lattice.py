"""Pure integer API for exact binary32 constant-addition trajectories.

There is no I/O, host floating-point dependency or automatic integration.
Callers explicitly bind a fixed rounding mode and underflow premise. The
returned arithmetic bounds do not qualify a renderer or authorize data edits.
"""
from dataclasses import dataclass
from bisect import bisect_left

MODES = ('rne', 'rtp', 'rtn', 'rtz')
MAX_FINITE_UNITS = ((1 << 24)-1) << 253
UNIT_EXPONENT = -149
MAX_STEPS = (1 << 31)-1
MAX_PHASES = 2048


def need(value, reason):
    if not value:
        raise ValueError(reason)


def units(bits):
    need(type(bits) is int and 0 <= bits <= 0xffffffff, 'RAW_U32_BITS')
    sign, exponent, fraction = bits >> 31, (bits >> 23) & 255, bits & 0x7fffff
    need(exponent != 255, 'NONFINITE_INPUT_KEEP')
    value = fraction if exponent == 0 else ((1 << 23) | fraction) << (exponent-1)
    return -value if sign else value


def exact_bits(value, *, negative_zero=False):
    need(type(value) is int and abs(value) <= MAX_FINITE_UNITS, 'FINITE_EXACT_UNITS')
    need(type(negative_zero) is bool, 'EXPLICIT_ZERO_SIGN_FLAG')
    sign = 0x80000000 if value < 0 or (value == 0 and negative_zero) else 0
    magnitude = abs(value)
    if magnitude < 1 << 23:
        return sign | magnitude
    shift = max(0, magnitude.bit_length()-24)
    need(magnitude % (1 << shift) == 0, 'EXACT_BINARY32_LATTICE_POINT')
    mantissa = magnitude >> shift
    return sign | ((shift+1) << 23) | (mantissa-(1 << 23))


def round_units(value, mode):
    need(type(value) is int and type(mode) is str and mode in MODES, 'EXPLICIT_INTEGER_AND_ROUNDING_MODE')
    sign = value < 0
    magnitude = abs(value)
    shift = max(0, magnitude.bit_length()-24)
    mantissa, remainder = divmod(magnitude, 1 << shift)
    increment = False
    if remainder:
        if mode == 'rne':
            halfway = 1 << (shift-1)
            increment = remainder > halfway or (remainder == halfway and mantissa & 1)
        elif mode == 'rtp':
            increment = not sign
        elif mode == 'rtn':
            increment = sign
    rounded = (mantissa+increment) << shift
    if rounded > MAX_FINITE_UNITS:
        toward_finite = mode == 'rtz' or (mode == 'rtp' and sign) or (mode == 'rtn' and not sign)
        need(toward_finite, 'NONFINITE_RESULT_KEEP')
        rounded = MAX_FINITE_UNITS
    return -rounded if sign else rounded


def add_bits(a, b, mode):
    """IEEE finite F32 addition with fixed mode and gradual underflow.

    Exact zero signs are retained. Nonfinite inputs/results fail closed.
    FZ/DN or an unbound FPSCR profile must be discharged by the caller.
    """
    need(type(mode) is str and mode in MODES, 'EXPLICIT_FIXED_ROUNDING_MODE')
    x, y = units(a), units(b)
    result = round_units(x+y, mode)
    negative_zero = result == 0 and ((a == b == 0x80000000) or (x+y == 0 and mode == 'rtn'))
    return exact_bits(result, negative_zero=negative_zero)


def lattice_band(value):
    """A closed interval whose exact pre-round sums use one fixed F32 lattice."""
    magnitude = abs(value)
    need(magnitude <= MAX_FINITE_UNITS, 'FINITE_BAND')
    if magnitude < 1 << 24:
        return 1, -((1 << 24)-1), (1 << 24)-1
    spacing = 1 << (magnitude.bit_length()-24)
    lower, upper = (1 << 23)*spacing, ((1 << 24)-1)*spacing
    return (spacing, lower, upper) if value > 0 else (spacing, -upper, -lower)


@dataclass(frozen=True)
class Phase:
    first: int
    steps: int
    start_units: int
    increment_units: int
    first_bits: int
    last_bits: int
    spacing_units: int
    method: str

    @property
    def end(self):
        return self.first+self.steps

    def at(self, step):
        need(self.first <= step <= self.end, 'PHASE_CLOSED_STEP')
        return self.start_units+(step-self.first)*self.increment_units


def trajectory(start_bits, step_bits, count, *, mode, gradual_underflow):
    """Exact piecewise-affine values for ALL integer k=0..count, no k sampling.

    A constant-step F32 recurrence is monotone. Each uniform-lattice phase has
    a constant integer increment. RNE half-ties starting on an odd lattice
    index take one explicit step; the rounded index is then even. We jump
    only while every exact pre-round sum remains inside the current band.
    Boundary crossings use one exact IEEE addition. Stagnation jumps to EOF.
    """
    need(type(mode) is str and mode in MODES and gradual_underflow is True, 'FIXED_FPSCR_PREMISES_REQUIRED')
    need(type(count) is int and 0 <= count <= MAX_STEPS, 'FINITE_NONNEGATIVE_S32_STEP_COUNT')
    current, delta = units(start_bits), units(step_bits)
    first_bits, index, phases = start_bits, 0, []
    while index < count:
        need(len(phases) < MAX_PHASES, 'FINITE_PHASE_BOUND_KEEP')
        spacing, lower, upper = lattice_band(current)
        next_bits = add_bits(first_bits, step_bits, mode)
        next_value = units(next_bits)
        increment = next_value-current
        remaining = count-index
        if increment == 0:
            # Values are identical for the whole suffix. Preserve a possible
            # first signed-zero transition in the endpoint bit pattern.
            final_bits = add_bits(next_bits, step_bits, mode) if remaining > 1 else next_bits
            phases.append(Phase(index, remaining, current, 0, first_bits, final_bits,
                                spacing, 'EXACT_STAGNATION'))
            index = count
            first_bits = final_bits
            continue
        need((increment > 0) == (delta > 0), 'MONOTONE_CONSTANT_ADD')
        exact_sum = current+delta
        _, remainder = divmod(delta, spacing)
        odd_half_tie = mode == 'rne' and 2*remainder == spacing and (current//spacing) & 1
        safe = lower <= exact_sum <= upper and not odd_half_tie
        if safe:
            # At all these iterations, the exact pre-round sum and the result
            # use the same uniform grid; no asymmetric binade seam is skipped.
            limit = ((upper-exact_sum)//increment+1 if increment > 0 else
                     (exact_sum-lower)//(-increment)+1)
            steps = min(remaining, limit)
            need(steps >= 1, 'NONEMPTY_LATTICE_JUMP')
            last_previous = current+(steps-1)*increment
            final_bits = add_bits(exact_bits(last_previous), step_bits, mode)
            need(units(final_bits) == current+steps*increment, 'EXACT_JUMP_ENDPOINT_INVERSE')
            method = 'EXACT_UNIFORM_LATTICE_JUMP'
        else:
            steps, final_bits = 1, next_bits
            method = 'EXACT_TIE_OR_BINADAL_BOUNDARY_STEP'
        phases.append(Phase(index, steps, current, increment, first_bits, final_bits, spacing, method))
        current = units(final_bits)
        first_bits = final_bits
        index += steps
    need(index == count and (not phases or phases[-1].end == count), 'EXACT_TRAJECTORY_EOF')
    return dict(start_bits=start_bits, step_bits=step_bits, count=count, mode=mode,
                final_bits=first_bits, phases=phases,
                phase_ends=tuple(phase.end for phase in phases), gradual_underflow=True)


def at(result, step):
    need(type(step) is int and 0 <= step <= result['count'], 'TRAJECTORY_STEP_RANGE')
    if step == 0:
        return units(result['start_bits'])
    index = bisect_left(result['phase_ends'], step)
    need(index < len(result['phases']), 'TRAJECTORY_COVERAGE_GAP')
    phase = result['phases'][index]
    need(phase.first < step <= phase.end, 'TRAJECTORY_COVERAGE_GAP')
    return phase.at(step)


def joint_chunks(results):
    """Merge phase boundaries by the SAME k; every interior vector is affine."""
    need(type(results) is list and 1 <= len(results) <= 8 and
         len({r['count'] for r in results}) == 1, 'BOUNDED_SAME_ORDINAL_TRAJECTORIES')
    count = results[0]['count']
    if count == 0:
        values = [units(result['start_bits']) for result in results]
        return [dict(first=0, last=0, lower=values, upper=values)]
    cuts = sorted({0, count} | {p.end for result in results for p in result['phases']})
    return [dict(first=a, last=b, lower=[at(r, a) for r in results],
                 upper=[at(r, b) for r in results]) for a, b in zip(cuts, cuts[1:])]


def family_bounds(start_min, start_max, step_min, step_max, count, *, mode, gradual_underflow):
    """All starts/constant steps in closed intervals, ALL k, by monotonicity.

    Endpoint trajectories use the same fixed mode and count. Parameter
    correlations may tighten this family but cannot invalidate enclosure.
    No native angle/start/step interval is manufactured here.
    """
    need(units(start_min) <= units(start_max) and units(step_min) <= units(step_max),
         'ORDERED_FINITE_PARAMETER_INTERVALS')
    low = trajectory(start_min, step_min, count, mode=mode, gradual_underflow=gradual_underflow)
    high = trajectory(start_max, step_max, count, mode=mode, gradual_underflow=gradual_underflow)
    chunks = joint_chunks([low, high])
    need(all(row['lower'][0] <= row['lower'][1] and row['upper'][0] <= row['upper'][1]
             for row in chunks), 'MONOTONE_FAMILY_ENDPOINT_INVERSE')
    return dict(low=low, high=high, chunks=chunks,
                premise='Every iteration uses one fixed finite constant step and the bound fixed rounding mode.')


def linear_support(chunks, weights):
    """Exact extrema of a linear form over synchronized affine trajectories."""
    need(chunks and len(weights) == len(chunks[0]['lower']) and
         all(type(w) is int for w in weights), 'EXPLICIT_INTEGER_NORMAL')
    values = [sum(w*v for w, v in zip(weights, row[side]))
              for row in chunks for side in ('lower', 'upper')]
    return min(values), max(values)


def normal_or_zero_witness(result):
    """Prove that FZ cannot affect this finite constant-add trajectory.

    A normal/zero step and normal/zero state at EVERY iteration imply the
    same results with input/output subnormal flushing. The nearest
    arithmetic-lattice states to zero are found exactly, not sampled.
    A failed witness leaves an FZ-enabled profile unqualified.
    """
    threshold = 1 << 23
    step = abs(units(result['step_bits']))
    if 0 < step < threshold:
        return dict(qualified=False, reason='SUBNORMAL_CONSTANT_STEP')
    minimum = None
    phases = result['phases']
    if not phases:
        candidates = [units(result['start_bits'])]
    else:
        candidates = []
        for phase in phases:
            offsets = {0, phase.steps}
            if phase.increment_units:
                nearest = (-phase.start_units)//phase.increment_units
                offsets.update(max(0, min(phase.steps, k)) for k in (nearest-1, nearest, nearest+1))
            candidates.extend(phase.start_units+k*phase.increment_units for k in offsets)
    for value in candidates:
        if value:
            minimum = abs(value) if minimum is None else min(minimum, abs(value))
    return dict(qualified=minimum is None or minimum >= threshold,
                reason='ALL_STATES_AND_CONSTANT_NORMAL_OR_ZERO' if minimum is None or minimum >= threshold
                       else 'SUBNORMAL_STATE_REACHABLE',
                minimumNonzeroStateUnits=minimum, minimumNormalUnits=threshold,
                allIntegerStatesCovered=True)


def family_support_2d(x_family, y_family, normal):
    """All parameter values and shared k for two axis-translation families.

    The four endpoint trajectories are synchronized before choosing a signed
    normal's lower/upper member. Thus this does not replace a slanted edge by
    the unsynchronized whole-trajectory bounding box.
    """
    need(type(normal) in (list, tuple) and len(normal) == 2 and
         all(type(v) is int for v in normal) and any(normal), 'NONZERO_INTEGER_NORMAL')
    trajectories = [x_family['low'], x_family['high'], y_family['low'], y_family['high']]
    need(len({r['mode'] for r in trajectories}) == 1, 'SAME_FIXED_ROUNDING_MODE')
    chunks = joint_chunks(trajectories)
    lower, upper = [], []
    nx, ny = normal
    for row in chunks:
        for endpoint in ('lower', 'upper'):
            xl, xh, yl, yh = row[endpoint]
            lower.append(nx*(xl if nx >= 0 else xh)+ny*(yl if ny >= 0 else yh))
            upper.append(nx*(xh if nx >= 0 else xl)+ny*(yh if ny >= 0 else yl))
    return dict(minimum_units=min(lower), maximum_units=max(upper),
                synchronizedChunkCount=len(chunks), allIntegerStatesAndClosedParametersCovered=True)
