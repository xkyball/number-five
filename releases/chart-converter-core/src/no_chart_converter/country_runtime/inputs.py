"""Explicit private data configuration. Importing this module reads no inputs.

Pins describe data, never executable Python. Relocations translate only data
paths recorded inside old manifests; contents remain checked by size and SHA256.
"""
from contextvars import ContextVar
from pathlib import Path
import hashlib
import json

CURRENT = ContextVar('country_runtime_inputs')
REQUIRED = ('composition', 'groundCatalog', 'groundGeometry', 'pointInputs',
            'coverageFixture', 'fillAvailability', 'containerInputs',
            'detailInputs', 'remainingDetailInputs', 'sourceRegistry',
            'sourcePresentationIndex', 'caOwnerProgramBindings',
            'presentationParent', 'pixelModelAuthorizations', 'pixelModelCallInputs')


def file_pin(path):
    path = Path(path)
    digest = hashlib.sha256()
    size = 0
    with path.open('rb') as handle:
        for chunk in iter(lambda: handle.read(4 * 1024 * 1024), b''):
            digest.update(chunk)
            size += len(chunk)
    return {'path': str(path), 'bytes': size, 'sha256': digest.hexdigest()}


def same_content(a, b):
    return (a['bytes'], a['sha256']) == (b['bytes'], b['sha256'])


class RuntimeInputs:
    def __init__(self, config):
        if isinstance(config, (str, Path)):
            path = Path(config).resolve(strict=True)
            self.base = path.parent
            self.config = json.loads(path.read_bytes())
            self.config_pin = file_pin(path)
        elif isinstance(config, dict):
            # Copy through JSON: configuration cannot inject executable objects.
            self.config = json.loads(json.dumps(config, allow_nan=False))
            self.base = Path(self.config.get('baseDirectory', '.')).resolve()
            encoded = json.dumps(self.config, sort_keys=True, separators=(',', ':')).encode()
            self.config_pin = {'bytes': len(encoded), 'sha256': hashlib.sha256(encoded).hexdigest()}
        else:
            raise ValueError('COUNTRY_CONFIG_PATH_OR_OBJECT_REQUIRED')
        if self.config.get('format') != 'no5-country-runtime-config/1':
            raise ValueError('COUNTRY_CONFIG_FORMAT')
        pins = self.config.get('inputs', {})
        if not isinstance(pins, dict) or not set(REQUIRED) <= pins.keys():
            raise ValueError('COUNTRY_REQUIRED_INPUTS:' + ','.join(sorted(set(REQUIRED) - pins.keys())))
        self.remaps = []
        for row in self.config.get('pathRemaps', []):
            if set(row) != {'from', 'to'} or not isinstance(row['from'], str) or not row['from']:
                raise ValueError('COUNTRY_DATA_PATH_REMAP')
            self.remaps.append((row['from'].rstrip('/'), self.path(row['to'])))
        self.remaps.sort(key=lambda item: -len(item[0]))
        self.pins = {name: self.normalize_pin(value) for name, value in pins.items()}
        self.output = self.path(self.config['outputDirectory'])
        self.read_receipts = {}
        self.verified_stats = {}

    def path(self, path):
        p = Path(path)
        return (p if p.is_absolute() else self.base / p).resolve()

    def resolve(self, path):
        text = str(path)
        for source, destination in self.remaps:
            if text == source or text.startswith(source + '/'):
                return destination / text[len(source):].lstrip('/')
        return self.path(text)

    def normalize_pin(self, pin):
        if not isinstance(pin, dict) or not {'path', 'bytes', 'sha256'} <= pin.keys():
            raise ValueError('COUNTRY_DATA_PIN_REQUIRED')
        if type(pin['bytes']) is not int or pin['bytes'] < 0 or not isinstance(pin['sha256'], str) or len(pin['sha256']) != 64:
            raise ValueError('COUNTRY_DATA_PIN_TYPES')
        try:
            bytes.fromhex(pin['sha256'])
        except ValueError as error:
            raise ValueError('COUNTRY_DATA_PIN_SHA256') from error
        return {**pin, 'path': str(self.resolve(pin['path']))}

    def read(self, pin):
        actual = self.normalize_pin(pin)
        path = Path(actual['path'])
        before = path.stat()
        raw = path.read_bytes()
        after = path.stat()
        fields = ('st_dev', 'st_ino', 'st_size', 'st_mtime_ns', 'st_ctime_ns')
        if any(getattr(before, name) != getattr(after, name) for name in fields):
            raise ValueError('COUNTRY_INPUT_CHANGED_DURING_READ')
        if (len(raw), hashlib.sha256(raw).hexdigest()) != (actual['bytes'], actual['sha256']):
            raise ValueError('COUNTRY_INPUT_PIN_MISMATCH:' + path.name)
        self.read_receipts[str(path)] = actual
        return raw

    def verify(self):
        # The presentation parent is potentially large. Verify by streaming,
        # then its selected metadata ranges are read by the composer.
        for name in REQUIRED:
            pin = self.pins[name]
            if name == 'presentationParent':
                if not same_content(file_pin(pin['path']), pin):
                    raise ValueError('COUNTRY_PRESENTATION_PARENT_PIN')
                self.read_receipts[pin['path']] = pin
            else:
                self.read(pin)
            stat = Path(pin['path']).stat()
            self.verified_stats[name] = tuple(getattr(stat, field) for field in ('st_dev','st_ino','st_size','st_mtime_ns','st_ctime_ns'))

    def assert_unchanged(self, name):
        stat = Path(self.pins[name]['path']).stat()
        actual = tuple(getattr(stat, field) for field in ('st_dev','st_ino','st_size','st_mtime_ns','st_ctime_ns'))
        if actual != self.verified_stats[name]:
            raise ValueError('COUNTRY_INPUT_CHANGED_AFTER_VERIFICATION:' + name)


def read(pin):
    return CURRENT.get().read(pin)


def input_pin(name):
    return CURRENT.get().pins[name]
