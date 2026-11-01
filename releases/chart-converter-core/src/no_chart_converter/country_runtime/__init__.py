"""Portable V15 country authoring from explicitly pinned private preparations.

This does not load a proprietary reader or execute configuration-supplied code.
Cache decoding and the prepared-manifest adapter are separate interfaces.
"""
from pathlib import Path
from threading import RLock
import hashlib
import json

from .inputs import CURRENT, RuntimeInputs, file_pin

_BUILD_LOCK = RLock()


def build_country(config: dict | Path, *, prepare_only: bool = False) -> dict:
    """Prepare or build and independently read back a country graph.

    ``config`` follows ``no5-country-runtime-config/1``. Every private input is
    an explicit {path, bytes, sha256} pin. Relative paths resolve beside the
    configuration; optional pathRemaps relocate embedded data paths only.
    Output is a fresh directory, with raw/ and country/ generations on a build.
    A prepare call emits source/ownership/scale planning and no chart.
    """
    if type(prepare_only) is not bool:
        raise ValueError('COUNTRY_PREPARE_BOOL')
    runtime = RuntimeInputs(config)
    with _BUILD_LOCK:
        runtime.verify()
        if runtime.output.exists():
            raise ValueError('FRESH_COUNTRY_OUTPUT_DIRECTORY_REQUIRED')
        runtime.output.mkdir(parents=True, mode=0o700)
        token = CURRENT.set(runtime)
        from . import composer, robust_v2, native_hooks, producer_runtime, finalizer
        try:
            robust_v2.configure_authorizations(
                json.loads(runtime.read(runtime.pins['pixelModelAuthorizations'])),
                json.loads(runtime.read(runtime.pins['pixelModelCallInputs'])))
            hook_config = {name: runtime.pins[name] for name in (
                'sourcePresentationIndex', 'caOwnerProgramBindings')}
            hook_config['diagnosticDirectory'] = str(runtime.output)
            native_hooks.configure(hook_config)
            producer_runtime.configure_source_registry(runtime.pins['sourceRegistry'])
            execution = {
                'format': 'no5-portable-country-execution/1',
                'transformedExecutableSHA256': file_pin(Path(composer.__file__))['sha256'],
                'runner': file_pin(Path(__file__)),
                'buildInputs': runtime.config_pin,
                'policy': composer.ZOOM.POLICY,
                'arbitraryCodeConfigurationAllowed': False,
                'cacheToPreparedManifestAdapterIncluded': False,
                'nativeCalls': 0,
            }
            result = composer.compose(runtime.config, prepare_only, execution)
            if not prepare_only:
                finalizer.finalize(runtime.output / 'raw', runtime.output / 'country')
                result = finalizer.readback(runtime.output / 'country')
            receipt = runtime.output / 'RUNTIME_INPUT_RECEIPT.json'
            payload = {'format': 'no5-country-runtime-input-receipt/1',
                       'configuration': runtime.config_pin,
                       'inputsActuallyRead': list(runtime.read_receipts.values()),
                       'execution': execution, 'prepareOnly': prepare_only,
                       'physicalPlotterAcceptanceClaim': False}
            receipt.write_text(json.dumps(payload, indent=2, sort_keys=True, allow_nan=False) + '\n')
            receipt.chmod(0o600)
            return {**result, 'runtimeInputReceipt': file_pin(receipt)}
        finally:
            native_hooks.configure(None)
            producer_runtime.SOURCE_REGISTRY = None
            producer_runtime.SOURCE_REGISTRY_PIN = None
            robust_v2.configure_authorizations({'rows': []}, {'calls': []})
            CURRENT.reset(token)


__all__ = ['build_country']
