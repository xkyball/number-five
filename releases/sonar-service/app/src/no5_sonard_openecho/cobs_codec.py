"""Process-local COBS decoder selection; framing/CRC/encoding stay unchanged."""
from importlib import import_module, metadata, machinery

VERSION = '1.2.2'
_native_decode = None
_native_error = None
_status = {'requested': 'python', 'selected': 'python', 'native_available': False,
           'native_version': None, 'reason': 'not configured'}


def _load_native():
    if metadata.version('cobs') != VERSION:
        raise ImportError('unreviewed cobs package version')
    module = import_module('cobs.cobs._cobs_ext')
    if not any(module.__file__.endswith(s) for s in machinery.EXTENSION_SUFFIXES):
        raise ImportError('cobs decoder is not a native extension')
    return module.decode, module.DecodeError


def configure(mode='auto'):
    """Configure before opening the process's single source connection."""
    global _native_decode, _native_error, _status
    if mode not in ('auto', 'python', 'native'):
        raise ValueError('cobs decoder must be auto, python or native')
    decode_native = error = None
    reason = 'explicit Python reference' if mode == 'python' else None
    if mode != 'python':
        try:
            decode_native, error = _load_native()
        except (ImportError, metadata.PackageNotFoundError):
            if mode == 'native':
                raise RuntimeError('reviewed native cobs 1.2.2 extension required') from None
            reason = 'reviewed native extension unavailable; Python reference selected'
    _native_decode, _native_error = decode_native, error
    _status = {'requested': mode, 'selected': 'native' if decode_native else 'python',
               'native_available': decode_native is not None,
               'native_version': VERSION if decode_native else None, 'reason': reason}
    return snapshot()


def snapshot():
    return dict(_status)


def decode(data, reference):
    # The reference accepts empty/zero-containing inputs differently from the
    # external decoder. Preserve its exact return/ValueError contract there.
    # Live framed inputs are already nonempty zero-free immutable bytes.
    if (_native_decode is None or not isinstance(data, (bytes, bytearray))
            or not data or 0 in data):
        return reference(data)
    try:
        return _native_decode(data)
    except _native_error:
        # For nonempty zero-free input the only COBS failure is an overrun.
        raise ValueError('COBS code overruns frame') from None
