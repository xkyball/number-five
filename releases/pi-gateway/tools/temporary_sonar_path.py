"""Local-only primitives for an explicitly owned, temporary Sonar path test.

This is a test helper, not a Gateway feature or installer. It never opens SSH,
restarts a service, starts Sonard/TX, or manages a tunnel. See the public test
contract for the deliberately separate operator steps and recovery limits.
"""
from __future__ import annotations

from contextlib import contextmanager
import copy
from dataclasses import asdict, dataclass
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import socket
import stat
import sys
import tomllib
import urllib.request

MAX_FILE = 262144


class Conflict(RuntimeError):
    """Stable, non-secret failure code; no input document or credential echo."""


def digest(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def canonical(value) -> str:
    return digest(json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode())


def loopback_url(value: str) -> bool:
    match = re.fullmatch(r'http://127\.0\.0\.1:([0-9]{1,5})', value)
    return match is not None and 1 <= int(match[1]) <= 65535


@dataclass(frozen=True)
class Parameters:
    config: str
    runtime: str
    target: str
    old_url: str
    test_url: str
    private_journal: str
    old_mode: str
    test_mode: str

    def __post_init__(self):
        for value in (self.config, self.runtime, self.private_journal):
            if not Path(value).is_absolute() or os.path.normpath(value) != value:
                raise Conflict('ABSOLUTE_NORMAL_PATH_REQUIRED')
        if not self.target or not loopback_url(self.old_url) or not loopback_url(self.test_url) or self.old_url == self.test_url:
            raise Conflict('EXPLICIT_DISTINCT_LOOPBACK_PATHS_REQUIRED')
        if {self.old_mode, self.test_mode} != {'M0', 'INHERIT'}:
            raise Conflict('ONLY_M0_INHERIT_ALLOWED')
        if self.config == self.private_journal:
            raise Conflict('JOURNAL_MUST_BE_SEPARATE')


def local_owner(parameters: Parameters) -> int:
    # Exact interpreter spelling distinguishes an intentionally selected venv
    # from its shared /usr/bin/python executable target.
    if (sys.platform != 'linux' or os.geteuid() != 0 or socket.gethostname() != parameters.target or
            os.path.abspath(sys.executable) != parameters.runtime):
        raise Conflict('WRONG_LOCAL_TARGET_OR_RUNTIME')
    return 0


@contextmanager
def parent_fd(path: Path):
    """No symlink traversal, including parent components."""
    fd = os.open('/', os.O_RDONLY | os.O_DIRECTORY)
    try:
        for part in path.parts[1:-1]:
            nxt = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
            os.close(fd); fd = nxt
        yield fd
    finally:
        os.close(fd)


def metadata(fd: int, owner: int, *, journal=False):
    value = os.fstat(fd)
    if (not stat.S_ISREG(value.st_mode) or value.st_uid != owner or value.st_nlink != 1 or
            stat.S_IMODE(value.st_mode) & 0o022 or journal and stat.S_IMODE(value.st_mode) != 0o600):
        raise Conflict('UNSAFE_FILE_IDENTITY')
    return value


def read_fd(fd: int) -> bytes:
    os.lseek(fd, 0, os.SEEK_SET)
    value = os.read(fd, MAX_FILE + 1)
    if len(value) > MAX_FILE:
        raise Conflict('FILE_TOO_LARGE')
    return value


def write_all(fd: int, value: bytes):
    while value:
        written = os.write(fd, value)
        if written <= 0:
            raise Conflict('WRITE_FAILED')
        value = value[written:]


def read_config(path: Path, owner: int):
    with parent_fd(path) as directory:
        fd = os.open(path.name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=directory)
        try:
            info = metadata(fd, owner); raw = read_fd(fd)
            return raw, tomllib.loads(raw.decode()), info
        finally:
            os.close(fd)


def replace_url_bytes(raw: bytes, previous: str, target: str) -> bytes:
    document = tomllib.loads(raw.decode())
    if document.get('source', {}).get('producer') != 'm1' or document['source'].get('upstream_base_url') != previous:
        raise Conflict('SOURCE_URL_OWNERSHIP_CONFLICT')
    text = raw.decode()
    headers = list(re.finditer(r'(?m)^[ \t]*\[source\][ \t]*(?:#.*)?$', text))
    if len(headers) != 1:
        raise Conflict('UNIQUE_SOURCE_SECTION_REQUIRED')
    start = headers[0].end(); following = re.search(r'(?m)^[ \t]*\[', text[start:])
    end = start + following.start() if following else len(text)
    pattern = r'(?m)^([ \t]*upstream_base_url[ \t]*=[ \t]*)(["\x27])' + re.escape(previous) + r'\2([ \t]*(?:#.*)?)$'
    changed, count = re.subn(pattern, lambda m: m[1] + m[2] + target + m[2] + m[3], text[start:end])
    if count != 1:
        raise Conflict('UNIQUE_SOURCE_URL_REQUIRED')
    result = (text[:start] + changed + text[end:]).encode()
    expected = copy.deepcopy(document); expected['source']['upstream_base_url'] = target
    if tomllib.loads(result.decode()) != expected:
        raise Conflict('UNRELATED_CONFIG_CHANGE')
    return result


def cas_url(path: Path, owner: int, expected_hash: str, previous: str, target: str) -> str:
    with parent_fd(path) as directory:
        fd = os.open(path.name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=directory)
        temporary = '.' + path.name + '.sonar-test-' + secrets.token_hex(12)
        temporary_info = None
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            original = metadata(fd, owner); raw = read_fd(fd)
            if digest(raw) != expected_hash:
                raise Conflict('CONFIG_CAS_CONFLICT')
            updated = replace_url_bytes(raw, previous, target)
            out = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=directory)
            try:
                temporary_info = os.fstat(out)
                if (temporary_info.st_uid, temporary_info.st_gid) != (original.st_uid, original.st_gid):
                    os.fchown(out, original.st_uid, original.st_gid)
                os.fchmod(out, stat.S_IMODE(original.st_mode)); write_all(out, updated); os.fsync(out)
            finally:
                os.close(out)
            fresh, _, info = read_config(path, owner)
            if (digest(fresh) != expected_hash or
                    (info.st_dev, info.st_ino, info.st_uid, info.st_gid, info.st_mode) !=
                    (original.st_dev, original.st_ino, original.st_uid, original.st_gid, original.st_mode)):
                raise Conflict('CONFIG_CAS_CONFLICT')
            current_temp = os.stat(temporary, dir_fd=directory, follow_symlinks=False)
            if (current_temp.st_dev, current_temp.st_ino) != (temporary_info.st_dev, temporary_info.st_ino):
                raise Conflict('TEMP_IDENTITY_CHANGED')
            os.replace(temporary, path.name, src_dir_fd=directory, dst_dir_fd=directory); os.fsync(directory)
            return digest(updated)
        finally:
            os.close(fd)
            # Only our still-identical staging inode is eligible for removal.
            # A symlink, replacement, or unproven crash residue is never cleaned.
            if temporary_info is not None:
                try:
                    item = os.stat(temporary, dir_fd=directory, follow_symlinks=False)
                    if (item.st_dev, item.st_ino) == (temporary_info.st_dev, temporary_info.st_ino):
                        os.unlink(temporary, dir_fd=directory)
                except FileNotFoundError:
                    pass


class LoopbackControl:
    """Credential callback returns a bearer in RAM; it is never persisted/logged."""
    def __init__(self, origin: str, read_bearer):
        if not loopback_url(origin):
            raise Conflict('LOOPBACK_CONTROL_REQUIRED')
        self._origin, self._read_bearer = origin, read_bearer

    def _request(self, body=None):
        try:
            token = self._read_bearer()
            if not isinstance(token, str) or not token or len(token) > 4096 or any(ord(c) < 32 for c in token):
                raise Conflict('CONTROL_CREDENTIAL_UNAVAILABLE')
            encoded = None if body is None else json.dumps(body, separators=(',', ':'), allow_nan=False).encode()
            if encoded is not None and len(encoded) > 4096:
                raise Conflict('CONTROL_REQUEST_TOO_LARGE')
            request = urllib.request.Request(self._origin + '/admin/v1/simulation', data=encoded,
                headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
            # Never follow a redirect carrying this local credential elsewhere.
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirect)
            with opener.open(request, timeout=5) as response:
                raw = response.read(131073)
                if len(raw) > 131072:
                    raise Conflict('CONTROL_RESPONSE_TOO_LARGE')
                return json.loads(raw)
        except Exception:
            raise Conflict('CONTROL_REQUEST_FAILED') from None

    def read(self): return self._request()
    def apply(self, body): return self._request(body)


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs): return None


def control_state(value):
    if (not isinstance(value, dict) or not isinstance(value.get('serverSessionId'), str) or
            value.get('version') != 2 or value.get('kind') != 'simulation.control' or
            not 1 <= len(value['serverSessionId']) <= 200 or type(value.get('revision')) is not int or value['revision'] < 0 or
            not isinstance(value.get('settings'), dict) or not isinstance(value['settings'].get('sonar'), dict) or
            set(value['settings']['sonar']) != {'mode'} or value['settings']['sonar']['mode'] not in {'M0', 'INHERIT'}):
        raise Conflict('INVALID_CONTROL_STATE')
    return value


def non_sonar(settings): return {k: v for k, v in settings.items() if k != 'sonar'}


def validate_journal(state, parameters):
    if not isinstance(state, dict) or state.get('schema') != 'no5.temporary-sonar-path/1':
        raise Conflict('JOURNAL_INCOMPLETE')
    if state.get('parameters') != asdict(parameters):
        raise Conflict('JOURNAL_TARGET_MISMATCH')
    if (not isinstance(state.get('url'), str) or not isinstance(state.get('mode'), str) or
            state['url'] not in {'PLANNED', 'APPLY_INTENT', 'APPLIED', 'RESTORE_INTENT', 'RESTORED'} or
            state['mode'] not in {'PLANNED', 'APPLY_INTENT', 'APPLIED', 'RESTORE_INTENT', 'RESTORED', 'COMPLETION_UNKNOWN'} or
            any(not isinstance(state.get(key), str) or re.fullmatch('[0-9a-f]{64}', state[key]) is None
                for key in ('originalConfigSha256', 'originalNonSonarSettingsSha256'))):
        raise Conflict('JOURNAL_INCOMPLETE')


@contextmanager
def journal(parameters: Parameters, owner: int, initial=None):
    path = Path(parameters.private_journal)
    with parent_fd(path) as directory:
        flags = os.O_RDWR | os.O_NOFOLLOW | (os.O_CREAT | os.O_EXCL if initial is not None else 0)
        fd = os.open(path.name, flags, 0o600, dir_fd=directory)
        try:
            metadata(fd, owner, journal=True); fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            raw = read_fd(fd)
            if initial is not None:
                state = initial
            else:
                if not raw.endswith(b'\n'): raise Conflict('JOURNAL_INCOMPLETE')
                try: state = json.loads(raw.splitlines()[-1])
                except Exception: raise Conflict('JOURNAL_INCOMPLETE') from None
            validate_journal(state, parameters)
            def save():
                validate_journal(state, parameters)
                named = os.stat(path.name, dir_fd=directory, follow_symlinks=False); opened = os.fstat(fd)
                if (named.st_dev, named.st_ino) != (opened.st_dev, opened.st_ino): raise Conflict('JOURNAL_IDENTITY_CHANGED')
                metadata(fd, owner, journal=True)
                value = json.dumps(state, separators=(',', ':'), allow_nan=False).encode() + b'\n'
                if os.fstat(fd).st_size + len(value) > MAX_FILE: raise Conflict('JOURNAL_FULL')
                os.lseek(fd, 0, os.SEEK_END); write_all(fd, value); os.fsync(fd); os.fsync(directory)
            if initial is not None: save()
            yield state, save
        finally:
            os.close(fd)


def begin(parameters: Parameters, control: LoopbackControl):
    owner = local_owner(parameters)
    raw, config, _ = read_config(Path(parameters.config), owner)
    replace_url_bytes(raw, parameters.old_url, parameters.test_url)
    before = control_state(control.read())
    if before['settings']['sonar']['mode'] != parameters.old_mode: raise Conflict('MODE_OWNERSHIP_CONFLICT')
    state = {'schema': 'no5.temporary-sonar-path/1', 'parameters': asdict(parameters),
             'originalConfigSha256': digest(raw), 'originalNonSonarSettingsSha256': canonical(non_sonar(before['settings'])),
             'url': 'PLANNED', 'mode': 'PLANNED'}
    with journal(parameters, owner, initial=state): pass


def change_url(parameters: Parameters, *, restore=False):
    owner = local_owner(parameters)
    with journal(parameters, owner) as (state, save):
        expected = 'APPLIED' if restore else 'PLANNED'
        if state['url'] != expected or restore and state['mode'] not in {'PLANNED', 'RESTORED'}:
            raise Conflict('URL_OPERATION_NOT_OWNED')
        previous, target = (parameters.test_url, parameters.old_url) if restore else (parameters.old_url, parameters.test_url)
        raw, _, _ = read_config(Path(parameters.config), owner)
        if not restore and digest(raw) != state['originalConfigSha256']: raise Conflict('CONFIG_CAS_CONFLICT')
        state['url'] = 'RESTORE_INTENT' if restore else 'APPLY_INTENT'; save()
        state['latestConfigSha256'] = cas_url(Path(parameters.config), owner, digest(raw), previous, target)
        state['url'] = 'RESTORED' if restore else 'APPLIED'; save()


def change_mode(parameters: Parameters, control: LoopbackControl, *, restore=False):
    owner = local_owner(parameters)
    with journal(parameters, owner) as (state, save):
        if state['url'] != 'APPLIED' or state['mode'] != ('APPLIED' if restore else 'PLANNED'):
            raise Conflict('MODE_OPERATION_NOT_OWNED')
        _, config, _ = read_config(Path(parameters.config), owner)
        if (config.get('source', {}).get('producer') != 'm1' or
                config.get('source', {}).get('upstream_base_url') != parameters.test_url):
            raise Conflict('SOURCE_URL_OWNERSHIP_CONFLICT')
        before = control_state(control.read())
        previous, target = (parameters.test_mode, parameters.old_mode) if restore else (parameters.old_mode, parameters.test_mode)
        if before['settings']['sonar']['mode'] != previous: raise Conflict('MODE_OWNERSHIP_CONFLICT')
        if before.get('sonar', {}).get('availableModes', {}).get(target) is not True: raise Conflict('MODE_UNAVAILABLE')
        settings = copy.deepcopy(before['settings']); settings['sonar']['mode'] = target
        command = {'version': 1, 'serverSessionId': before['serverSessionId'], 'expectedRevision': before['revision'],
                   'requestId': 'sonar-path-' + secrets.token_hex(12), 'action': 'apply', 'settings': settings}
        state['mode'] = 'RESTORE_INTENT' if restore else 'APPLY_INTENT'
        state['request'] = {k: command[k] for k in ('serverSessionId', 'expectedRevision', 'requestId')}; save()
        try:
            ack = control.apply(command)
            echoed = control_state(ack.get('state'))
            if (ack.get('version') != 1 or ack.get('kind') != 'simulation.ack' or ack.get('outcome') != 'APPLIED' or
                    ack.get('replayed') is not False or ack.get('revision') != echoed['revision'] or ack.get('requestId') != command['requestId'] or
                    ack.get('serverSessionId') != before['serverSessionId'] or echoed['serverSessionId'] != before['serverSessionId'] or
                    echoed['revision'] != before['revision'] + 1 or echoed['settings'] != settings):
                raise Conflict('MODE_ACK_UNCONFIRMED')
            after = control_state(control.read())
            if (after['serverSessionId'] != before['serverSessionId'] or after['revision'] < echoed['revision'] or
                    after['settings']['sonar']['mode'] != target): raise Conflict('MODE_READBACK_CONFLICT')
        except Exception:
            state['mode'] = 'COMPLETION_UNKNOWN'; save()
            raise Conflict('MODE_COMPLETION_UNKNOWN_NO_REPLAY') from None
        state['mode'] = 'RESTORED' if restore else 'APPLIED'
        state['confirmedRevision'] = echoed['revision']
        state['nonSonarPreserved'] = non_sonar(echoed['settings']) == non_sonar(before['settings'])
        state['newerNonSonarChangeObserved'] = non_sonar(after['settings']) != non_sonar(echoed['settings'])
        save()
