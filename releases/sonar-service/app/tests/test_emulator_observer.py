import importlib.util
import json
from pathlib import Path

import pytest

spec = importlib.util.spec_from_file_location('emulator_observer',
    Path(__file__).resolve().parents[1]/'tools/emulator_observer.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class Emulator:
    def __init__(self):
        self.maps = {2222: 22}
        self.port = None
        self.address = '127.0.0.1'
        self.fd = '42'
        self.started = 'Mon Oct 19 10:00:00 2026'
        self.avd = 'No5_Lumina_API29'
        self.refuse = False
        self.deleted = []

    def command(self, args):
        if args[0] == 'lsof':
            return f'p12345\ncqemu-system-aarch64\nf{self.fd}\nn{self.address}:{self.port}\n'.encode()
        if args[0] == 'ps':
            return (self.started if args[-1] == 'lstart=' else 'qemu-system-aarch64 -avd '+self.avd).encode()
        assert args[:3] == ['adb', '-s', 'emulator-5554']
        command = args[3:]
        if command == ['emu', 'redir', 'list']:
            return ''.join(f'ipv4 tcp:{h} => {g}\n' for h, g in self.maps.items()).encode()+b'OK\n'
        if command[:3] == ['emu', 'redir', 'add']:
            _, host, guest = command[3].split(':'); self.port = int(host)
            if self.refuse:
                self.maps[self.port] = 9000  # A competing allocation is never ours.
                return b'KO: port already in use\n'
            self.maps[self.port] = int(guest)
            return b'OK\n'
        if command[:3] == ['emu', 'redir', 'del']:
            port = int(command[3].split(':')[1]); self.deleted.append(port); del self.maps[port]
            return b'OK\n'
        raise AssertionError(args)


def forward(emulator, path):
    return module.ConsoleRelayForward(['adb', '-s', 'emulator-5554'], emulator.command, path)


def test_owned_loopback_lifetime_preserves_existing_redirections(tmp_path):
    emulator = Emulator(); receipt = tmp_path/'receipt.json'
    with forward(emulator, receipt) as port:
        assert emulator.maps[port] == 9443
        assert json.loads(receipt.read_text())['loopback_only_verified']
    assert emulator.maps == {2222: 22}
    assert emulator.deleted == [port]
    assert json.loads(receipt.read_text())['closed']


@pytest.mark.parametrize('address', ['*', '0.0.0.0', '[::1]'])
def test_non_loopback_or_ambiguous_binding_is_rejected_and_own_tuple_rolled_back(tmp_path, address):
    emulator = Emulator(); emulator.address = address
    with pytest.raises(RuntimeError, match='loopback'):
        with forward(emulator, tmp_path/'receipt.json'): pass
    assert emulator.maps == {2222: 22}


@pytest.mark.parametrize('change', ['fd', 'mapping', 'process'])
def test_changed_owner_or_mapping_is_preserved_on_cleanup(tmp_path, change):
    emulator = Emulator(); receipt = tmp_path/'receipt.json'
    with forward(emulator, receipt) as port:
        if change == 'fd': emulator.fd = '43'
        if change == 'mapping': emulator.maps[port] = 9000
        if change == 'process': emulator.started = 'different process start'
    assert port in emulator.maps
    assert not emulator.deleted
    assert 'cleanup_skipped' in json.loads(receipt.read_text())


def test_refused_allocation_never_deletes_competing_mapping(tmp_path):
    emulator = Emulator(); emulator.refuse = True
    with pytest.raises(RuntimeError, match='refused'):
        with forward(emulator, tmp_path/'receipt.json'): pass
    assert emulator.maps[emulator.port] == 9000
    assert not emulator.deleted


def test_wrong_avd_process_is_rejected_without_touching_existing_mapping(tmp_path):
    emulator = Emulator(); emulator.avd = 'another AVD'
    with pytest.raises(RuntimeError, match='another process'):
        with forward(emulator, tmp_path/'receipt.json'): pass
    assert emulator.maps == {2222: 22}
