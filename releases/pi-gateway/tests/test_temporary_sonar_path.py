"""Synthetic local files and real SimulationController CAS; no target/runtime writes."""
from __future__ import annotations

import copy
from dataclasses import replace
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import tomllib
import unittest
from unittest.mock import patch

from no5_pi_gateway.configuration import GatewayConfig, SimulationConfig
from no5_pi_gateway.simulation import SimulationController, default_settings

SPEC = importlib.util.spec_from_file_location('temporary_sonar_path', Path(__file__).resolve().parents[1] / 'tools/temporary_sonar_path.py')
helper = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = helper
SPEC.loader.exec_module(helper)

OLD = 'http://127.0.0.1:18001'
TEST = 'http://127.0.0.1:18002'
CONFIG = ('# synthetic, never a device configuration\n'
          '[server]\nport = 18003\n\n[source]\nproducer = "m1"\n'
          f'upstream_base_url = "{OLD}" # preserve comment\n'
          'stale_after_s = 5.0\n\n[audio]\nname = "fixture"\n').encode()


class LocalControl:
    def __init__(self, directory):
        self.controller = SimulationController(GatewayConfig(simulation=SimulationConfig(
            enabled=True, state_file=str(directory / 'simulation.json'),
            sonar_m0_upstream_base_url=OLD, sonar_m1_upstream_base_url=TEST)), 'synthetic-session')
        self.requests = []
        self.before_apply = None
        self.after_apply = None
        self.ack_transform = None
        self.foreign('M0')

    def read(self):
        return self.controller.control_state()

    def foreign(self, mode=None, overlays=None):
        current = self.read()
        settings = copy.deepcopy(current['settings'])
        if mode is not None: settings['sonar']['mode'] = mode
        if overlays is not None: settings['helm']['overlayFields'] = overlays
        return self.controller.apply({'version': 1, 'serverSessionId': current['serverSessionId'],
            'expectedRevision': current['revision'], 'requestId': f'foreign-{current["revision"]:08}',
            'action': 'apply', 'settings': settings})

    def apply(self, command):
        self.requests.append(copy.deepcopy(command))
        if self.before_apply: self.before_apply()
        result = self.controller.apply(command)
        if self.after_apply: self.after_apply()
        return self.ack_transform(result) if self.ack_transform else result


class TemporarySonarPathTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        # macOS /var is a symlink; production deliberately disallows traversal.
        self.directory = Path(self.temporary.name).resolve()
        self.config = self.directory / 'config.toml'
        self.config.write_bytes(CONFIG); self.config.chmod(0o640)
        self.private = self.directory / 'operation.private.jsonl'
        self.parameters = helper.Parameters(str(self.config), '/explicit/runtime/python', 'explicit-local-target',
            OLD, TEST, str(self.private), 'M0', 'INHERIT')
        self.control = LocalControl(self.directory)
        # The production guard is independently tested, never relaxed in code.
        self.guard = patch.object(helper, 'local_owner', return_value=os.geteuid())
        self.guard.start()

    def tearDown(self):
        self.guard.stop(); self.temporary.cleanup()

    def begin(self): helper.begin(self.parameters, self.control)
    def url(self): return tomllib.loads(self.config.read_text())['source']['upstream_base_url']
    def mode(self): return self.control.read()['settings']['sonar']['mode']
    def state(self): return json.loads(self.private.read_bytes().splitlines()[-1])

    def applied(self):
        self.begin(); helper.change_url(self.parameters); helper.change_mode(self.parameters, self.control)

    def test_round_trip_uses_current_revision_and_changes_only_the_two_owned_values(self):
        original = tomllib.loads(CONFIG.decode())
        self.applied()
        self.assertEqual(self.url(), TEST); self.assertEqual(self.mode(), 'INHERIT')
        expected = copy.deepcopy(original); expected['source']['upstream_base_url'] = TEST
        self.assertEqual(tomllib.loads(self.config.read_text()), expected)
        self.assertIn('# preserve comment', self.config.read_text())
        self.assertEqual(stat.S_IMODE(self.config.stat().st_mode), 0o640)
        helper.change_mode(self.parameters, self.control, restore=True)
        helper.change_url(self.parameters, restore=True)
        self.assertEqual(self.config.read_bytes(), CONFIG); self.assertEqual(self.mode(), 'M0')
        self.assertEqual(len(self.control.requests), 2)
        self.assertEqual([x['expectedRevision'] for x in self.control.requests], [1, 2])
        self.assertNotEqual(self.control.requests[0]['requestId'], self.control.requests[1]['requestId'])
        self.assertEqual((self.state()['url'], self.state()['mode']), ('RESTORED', 'RESTORED'))

    def test_initial_foreign_url_or_mode_is_not_overwritten(self):
        self.config.write_bytes(CONFIG.replace(OLD.encode(), b'http://127.0.0.1:18009'))
        with self.assertRaisesRegex(helper.Conflict, 'SOURCE_URL_OWNERSHIP_CONFLICT'): self.begin()
        self.assertFalse(self.private.exists())
        self.config.write_bytes(CONFIG); self.control.foreign('INHERIT')
        with self.assertRaisesRegex(helper.Conflict, 'MODE_OWNERSHIP_CONFLICT'): self.begin()
        self.assertFalse(self.private.exists())

    def test_config_change_after_begin_fails_before_any_url_write(self):
        self.begin(); changed = CONFIG + b'\n# concurrent change\n'; self.config.write_bytes(changed)
        with self.assertRaisesRegex(helper.Conflict, 'CONFIG_CAS_CONFLICT'): helper.change_url(self.parameters)
        self.assertEqual(self.config.read_bytes(), changed)
        self.assertEqual(self.state()['url'], 'PLANNED')

    def test_fresh_mode_cas_preserves_non_sonar_changes_before_apply_and_restore(self):
        self.begin(); helper.change_url(self.parameters)
        self.control.foreign(overlays=['speed'])
        helper.change_mode(self.parameters, self.control)
        self.assertEqual(self.control.requests[0]['expectedRevision'], 2)
        self.assertEqual(self.control.requests[0]['settings']['helm']['overlayFields'], ['speed'])
        self.control.foreign(overlays=['depth'])
        helper.change_mode(self.parameters, self.control, restore=True)
        self.assertEqual(self.control.requests[1]['expectedRevision'], 4)
        self.assertEqual(self.control.read()['settings']['helm']['overlayFields'], ['depth'])

    def test_concurrent_non_sonar_cas_conflict_is_not_retried(self):
        self.begin(); helper.change_url(self.parameters)
        self.control.before_apply = lambda: self.control.foreign(overlays=['speed'])
        with self.assertRaisesRegex(helper.Conflict, 'MODE_COMPLETION_UNKNOWN_NO_REPLAY'):
            helper.change_mode(self.parameters, self.control)
        self.assertEqual(len(self.control.requests), 1); self.assertEqual(self.mode(), 'M0')
        self.assertEqual(self.control.read()['settings']['helm']['overlayFields'], ['speed'])
        self.assertEqual(self.state()['mode'], 'COMPLETION_UNKNOWN')
        with self.assertRaisesRegex(helper.Conflict, 'MODE_OPERATION_NOT_OWNED'):
            helper.change_mode(self.parameters, self.control)
        self.assertEqual(len(self.control.requests), 1)

    def test_lost_ack_after_effect_is_unknown_and_never_replayed_or_blindly_restored(self):
        self.begin(); helper.change_url(self.parameters)
        def lost(): raise TimeoutError('synthetic response lost')
        self.control.after_apply = lost
        with self.assertRaisesRegex(helper.Conflict, 'MODE_COMPLETION_UNKNOWN_NO_REPLAY'):
            helper.change_mode(self.parameters, self.control)
        self.assertEqual(self.mode(), 'INHERIT')
        with self.assertRaisesRegex(helper.Conflict, 'MODE_OPERATION_NOT_OWNED'):
            helper.change_mode(self.parameters, self.control, restore=True)
        with self.assertRaisesRegex(helper.Conflict, 'URL_OPERATION_NOT_OWNED'):
            helper.change_url(self.parameters, restore=True)
        self.assertEqual(len(self.control.requests), 1)

    def test_mismatched_ack_cannot_confirm_even_if_target_was_applied(self):
        self.begin(); helper.change_url(self.parameters)
        self.control.ack_transform = lambda value: dict(value, requestId='another-request')
        with self.assertRaisesRegex(helper.Conflict, 'MODE_COMPLETION_UNKNOWN_NO_REPLAY'):
            helper.change_mode(self.parameters, self.control)
        self.assertEqual(self.state()['mode'], 'COMPLETION_UNKNOWN')

    def test_foreign_change_after_ack_is_observed_and_preserved(self):
        self.begin(); helper.change_url(self.parameters)
        self.control.after_apply = lambda: self.control.foreign(overlays=['speed'])
        helper.change_mode(self.parameters, self.control)
        self.assertTrue(self.state()['nonSonarPreserved'])
        self.assertTrue(self.state()['newerNonSonarChangeObserved'])
        self.assertEqual(self.control.read()['settings']['helm']['overlayFields'], ['speed'])

    def test_foreign_mode_change_during_readback_is_unknown(self):
        self.begin(); helper.change_url(self.parameters)
        self.control.after_apply = lambda: self.control.foreign('M0')
        with self.assertRaisesRegex(helper.Conflict, 'MODE_COMPLETION_UNKNOWN_NO_REPLAY'):
            helper.change_mode(self.parameters, self.control)
        self.assertEqual(self.mode(), 'M0'); self.assertEqual(self.state()['mode'], 'COMPLETION_UNKNOWN')

    def test_restore_requires_still_owned_mode_and_url(self):
        self.applied(); self.control.foreign('M0')
        with self.assertRaisesRegex(helper.Conflict, 'MODE_OWNERSHIP_CONFLICT'):
            helper.change_mode(self.parameters, self.control, restore=True)
        self.assertEqual(self.mode(), 'M0'); self.assertEqual(len(self.control.requests), 1)
        self.control.foreign('INHERIT')
        self.config.write_bytes(self.config.read_bytes().replace(TEST.encode(), b'http://127.0.0.1:18009'))
        with self.assertRaisesRegex(helper.Conflict, 'SOURCE_URL_OWNERSHIP_CONFLICT'):
            helper.change_mode(self.parameters, self.control, restore=True)
        self.assertEqual(len(self.control.requests), 1)

    def test_url_restore_preserves_foreign_non_sonar_config_and_settings(self):
        self.applied(); helper.change_mode(self.parameters, self.control, restore=True)
        changed = self.config.read_bytes().replace(b'name = "fixture"', b'name = "newer-owner"')
        self.config.write_bytes(changed)
        self.control.foreign(overlays=['speed'])
        helper.change_url(self.parameters, restore=True)
        self.assertEqual(self.config.read_bytes(), changed.replace(TEST.encode(), OLD.encode()))
        self.assertEqual(self.control.read()['settings']['helm']['overlayFields'], ['speed'])

    def test_url_restore_refuses_foreign_url_and_leaves_it_untouched(self):
        self.applied(); helper.change_mode(self.parameters, self.control, restore=True)
        changed = self.config.read_bytes().replace(TEST.encode(), b'http://127.0.0.1:18009')
        self.config.write_bytes(changed)
        with self.assertRaisesRegex(helper.Conflict, 'SOURCE_URL_OWNERSHIP_CONFLICT'):
            helper.change_url(self.parameters, restore=True)
        self.assertEqual(self.config.read_bytes(), changed)

    def test_journal_is_private_append_only_and_never_contains_full_state(self):
        self.begin(); inode = self.private.stat().st_ino
        with self.assertRaises(FileExistsError): self.begin()
        helper.change_url(self.parameters); helper.change_mode(self.parameters, self.control)
        self.assertEqual(stat.S_IMODE(self.private.stat().st_mode), 0o600)
        self.assertEqual(self.private.stat().st_ino, inode)
        records = [json.loads(line) for line in self.private.read_bytes().splitlines()]
        self.assertEqual(len(records), 5)
        self.assertFalse(any('settings' in row or 'authorization' in row or 'token' in row for row in records))

    def test_incomplete_or_damaged_journal_never_resumes(self):
        self.begin(); good = self.private.read_bytes()
        for bad in (good.rstrip(b'\n'), good + b'{', b'{}\n', b'[]\n'):
            with self.subTest(bad=bad[:16]):
                self.private.write_bytes(bad)
                with self.assertRaisesRegex(helper.Conflict, 'JOURNAL_INCOMPLETE'): helper.change_url(self.parameters)
                self.assertEqual(self.config.read_bytes(), CONFIG)

    def test_process_loss_at_url_intent_cannot_replay_or_claim_restoration(self):
        self.begin()
        with helper.journal(self.parameters, os.geteuid()) as (state, save):
            state['url'] = 'APPLY_INTENT'; save()
        for restore in (False, True):
            with self.assertRaisesRegex(helper.Conflict, 'URL_OPERATION_NOT_OWNED'):
                helper.change_url(self.parameters, restore=restore)
        self.assertEqual(self.config.read_bytes(), CONFIG)

    def test_process_loss_at_mode_intent_cannot_replay_or_restore_other_values(self):
        self.begin(); helper.change_url(self.parameters)
        with helper.journal(self.parameters, os.geteuid()) as (state, save):
            state['mode'] = 'APPLY_INTENT'; save()
        for restore in (False, True):
            with self.assertRaisesRegex(helper.Conflict, 'MODE_OPERATION_NOT_OWNED'):
                helper.change_mode(self.parameters, self.control, restore=restore)
        with self.assertRaisesRegex(helper.Conflict, 'URL_OPERATION_NOT_OWNED'):
            helper.change_url(self.parameters, restore=True)
        self.assertEqual(self.control.requests, []); self.assertEqual(self.url(), TEST)

    def test_journal_cannot_be_reused_for_another_target_or_mode_pair(self):
        self.begin()
        for parameters in (replace(self.parameters, target='another-target'), replace(self.parameters, old_mode='INHERIT', test_mode='M0')):
            with self.assertRaisesRegex(helper.Conflict, 'JOURNAL_TARGET_MISMATCH'): helper.change_url(parameters)

    def test_config_journal_and_parent_symlinks_are_rejected_without_cleanup(self):
        real = self.directory / 'real'; real.write_bytes(CONFIG); real.chmod(0o640)
        self.config.unlink(); self.config.symlink_to(real)
        with self.assertRaises(OSError): self.begin()
        self.assertTrue(self.config.is_symlink()); self.assertEqual(real.read_bytes(), CONFIG)
        self.config.unlink(); self.config.write_bytes(CONFIG); self.config.chmod(0o640)
        self.private.symlink_to(real)
        with self.assertRaises(FileExistsError): self.begin()
        self.assertTrue(self.private.is_symlink()); self.private.unlink()
        link = self.directory / 'link'; link.symlink_to(self.directory, target_is_directory=True)
        with self.assertRaises(OSError):
            helper.begin(replace(self.parameters, config=str(link / self.config.name)), self.control)
        self.assertTrue(link.is_symlink())

    def test_group_writable_config_or_nonprivate_journal_is_rejected(self):
        self.config.chmod(0o660)
        with self.assertRaisesRegex(helper.Conflict, 'UNSAFE_FILE_IDENTITY'): self.begin()
        self.config.chmod(0o640); self.begin(); self.private.chmod(0o640)
        with self.assertRaisesRegex(helper.Conflict, 'UNSAFE_FILE_IDENTITY'): helper.change_url(self.parameters)
        self.assertEqual(self.url(), OLD)

    def test_hard_link_and_wrong_owner_are_rejected(self):
        alias = self.directory / 'alias'; os.link(self.config, alias)
        with self.assertRaisesRegex(helper.Conflict, 'UNSAFE_FILE_IDENTITY'): self.begin()
        alias.unlink()
        with self.config.open('rb') as handle:
            with self.assertRaisesRegex(helper.Conflict, 'UNSAFE_FILE_IDENTITY'):
                helper.metadata(handle.fileno(), os.geteuid() + 1)

    def test_competing_journal_owner_cannot_mutate(self):
        self.begin()
        with helper.journal(self.parameters, os.geteuid()):
            with self.assertRaises(BlockingIOError): helper.change_url(self.parameters)
        self.assertEqual(self.url(), OLD)

    def test_journal_replacement_during_mode_call_is_not_deleted_or_overwritten(self):
        self.begin(); helper.change_url(self.parameters)
        replacement = b'foreign private file\n'
        def replace_journal():
            self.private.unlink(); self.private.write_bytes(replacement); self.private.chmod(0o600)
        self.control.after_apply = replace_journal
        with self.assertRaisesRegex(helper.Conflict, 'JOURNAL_IDENTITY_CHANGED'):
            helper.change_mode(self.parameters, self.control)
        self.assertEqual(self.private.read_bytes(), replacement)

    def test_compare_and_replace_refuses_changed_inode_even_with_identical_bytes(self):
        original = helper.read_config
        def replace_before_final_read(path, owner):
            other = self.directory / 'other'; other.write_bytes(CONFIG); other.chmod(0o640)
            os.replace(other, path)
            return original(path, owner)
        with patch.object(helper, 'read_config', side_effect=replace_before_final_read):
            with self.assertRaisesRegex(helper.Conflict, 'CONFIG_CAS_CONFLICT'):
                helper.cas_url(self.config, os.geteuid(), helper.digest(CONFIG), OLD, TEST)
        self.assertEqual(self.config.read_bytes(), CONFIG)
        self.assertEqual(list(self.directory.glob('*.sonar-test-*')), [])

    def test_foreign_staging_replacement_is_never_removed(self):
        foreign = self.directory / 'foreign'; foreign.write_bytes(b'foreign')
        original = helper.read_config
        def replace_stage(path, owner):
            stage = next(self.directory.glob('.*.sonar-test-*'))
            stage.unlink(); stage.symlink_to(foreign)
            return original(path, owner)
        with patch.object(helper, 'read_config', side_effect=replace_stage):
            with self.assertRaisesRegex(helper.Conflict, 'TEMP_IDENTITY_CHANGED'):
                helper.cas_url(self.config, os.geteuid(), helper.digest(CONFIG), OLD, TEST)
        residues = list(self.directory.glob('.*.sonar-test-*'))
        self.assertEqual(len(residues), 1); self.assertTrue(residues[0].is_symlink())
        self.assertEqual(foreign.read_bytes(), b'foreign'); self.assertEqual(self.config.read_bytes(), CONFIG)

    def test_original_producer_and_single_url_field_are_required(self):
        with self.assertRaisesRegex(helper.Conflict, 'SOURCE_URL_OWNERSHIP_CONFLICT'):
            helper.replace_url_bytes(CONFIG.replace(b'"m1"', b'"m0"'), OLD, TEST)
        inline = b'source = {producer="m1",upstream_base_url="' + OLD.encode() + b'"}\n'
        with self.assertRaisesRegex(helper.Conflict, 'UNIQUE_SOURCE_SECTION_REQUIRED'):
            helper.replace_url_bytes(inline, OLD, TEST)


class LocalTransportGuardTest(unittest.TestCase):
    def parameters(self):
        return helper.Parameters('/explicit/config', '/explicit/runtime', 'explicit-target', OLD, TEST,
            '/explicit/private-journal', 'M0', 'INHERIT')

    def test_local_root_target_runtime_are_all_required(self):
        parameters = self.parameters()
        with patch.object(helper.sys, 'platform', 'linux'), patch.object(helper.os, 'geteuid', return_value=0), \
                patch.object(helper.socket, 'gethostname', return_value=parameters.target), patch.object(helper.sys, 'executable', parameters.runtime):
            self.assertEqual(helper.local_owner(parameters), 0)
            for changed in (replace(parameters, target='foreign-target'), replace(parameters, runtime='/another/runtime')):
                with self.assertRaisesRegex(helper.Conflict, 'WRONG_LOCAL_TARGET_OR_RUNTIME'): helper.local_owner(changed)
            with patch.object(helper.os, 'geteuid', return_value=1000):
                with self.assertRaisesRegex(helper.Conflict, 'WRONG_LOCAL_TARGET_OR_RUNTIME'): helper.local_owner(parameters)
            with patch.object(helper.sys, 'platform', 'darwin'):
                with self.assertRaisesRegex(helper.Conflict, 'WRONG_LOCAL_TARGET_OR_RUNTIME'): helper.local_owner(parameters)

    def test_parameters_reject_implicit_paths_remote_urls_and_other_modes(self):
        parameters = self.parameters()
        for changes in ({'config': 'relative'}, {'runtime': '/a/../runtime'}, {'private_journal': parameters.config},
                {'test_url': OLD}, {'test_url': 'http://example.invalid:8787'}, {'test_url': 'http://localhost:8787'},
                {'test_url': 'http://127.0.0.1:8787/path'}, {'test_url': 'http://user:secret@127.0.0.1:8787'},
                {'test_mode': 'M1'}, {'target': ''}):
            with self.subTest(changes=changes), self.assertRaises(helper.Conflict): replace(parameters, **changes)

    def test_bad_control_schema_never_becomes_a_mode_write(self):
        for state in (None, {}, {'version': 2, 'kind': 'simulation.control', 'serverSessionId': 'session', 'revision': True, 'settings': {'sonar': {'mode': 'M0'}}},
                {'version': 2, 'kind': 'simulation.control', 'serverSessionId': 'session', 'revision': 0, 'settings': {'sonar': []}},
                {'version': 2, 'kind': 'simulation.control', 'serverSessionId': 'session', 'revision': 0, 'settings': {'sonar': {'mode': 'M0', 'extra': True}}}):
            with self.assertRaisesRegex(helper.Conflict, 'INVALID_CONTROL_STATE'): helper.control_state(state)

    def test_loopback_transport_disables_proxy_and_redirect_and_uses_secret_only_in_ram(self):
        observed = []
        class Opener:
            def open(self, request, timeout):
                observed.append((request, timeout))
                return io.BytesIO(b'{"synthetic":true}')
        with patch.object(helper.urllib.request, 'build_opener', return_value=Opener()) as build:
            control = helper.LoopbackControl('http://127.0.0.1:18003', lambda: 'synthetic-private-bearer')
            self.assertEqual(control.read(), {'synthetic': True})
        self.assertEqual(observed[0][0].full_url, 'http://127.0.0.1:18003/admin/v1/simulation')
        self.assertEqual(observed[0][0].get_header('Authorization'), 'Bearer synthetic-private-bearer')
        self.assertEqual(observed[0][1], 5)
        self.assertEqual(build.call_args.args[0].proxies, {})
        self.assertIs(build.call_args.args[1], helper._NoRedirect)
        self.assertIsNone(helper._NoRedirect().redirect_request(None, None, None, None, None, None))

    def test_failed_transport_does_not_echo_bearer_or_exception_detail(self):
        with patch.object(helper.urllib.request, 'build_opener', side_effect=RuntimeError('synthetic-private-bearer')):
            with self.assertRaisesRegex(helper.Conflict, '^CONTROL_REQUEST_FAILED$'):
                helper.LoopbackControl('http://127.0.0.1:18003', lambda: 'synthetic-private-bearer').read()
        with self.assertRaisesRegex(helper.Conflict, '^LOOPBACK_CONTROL_REQUIRED$'):
            helper.LoopbackControl('http://example.invalid:18003', lambda: 'unused')


if __name__ == '__main__':
    unittest.main()
