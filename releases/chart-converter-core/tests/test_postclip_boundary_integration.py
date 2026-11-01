"""Two invented tests of installed postclip wiring, never a country build.

Run: python -B -m unittest discover -s tests -p test_postclip_boundary_integration.py -v
"""
import ast
import copy
import importlib.util
import inspect
from pathlib import Path
import struct
import types
import unittest

from no_chart_converter.country_runtime import composer, native_hooks
from no_chart_converter.country_runtime import postclip_alias as alias
from no_chart_converter.country_runtime import writer_extension as writer


def kernel_fixtures():
    """Load adjacent invented constructors, without running their test class."""
    path = Path(__file__).with_name('test_postclip_boundary_alias.py')
    spec = importlib.util.spec_from_file_location('_postclip_adjacent_invented_fixtures', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.fixture, module.element, module.LIMITS


def invented_clip():
    fixture, element, _ = kernel_fixtures()
    owner, original, ro, binding = fixture()
    co = b''.join(e[1] for e in original)
    ix = struct.pack('<III', 2, 16 << 24, (16 << 24) | len(original[0][1]))
    owner.update(coordinates=co, indices=ix, bbox=(0, 0, 20, 20),
                 paintPriority=4, originalSequence=(0, 2))
    # The fake quantizer's final data deliberately differs from input data.
    final = [element([(x + 30, y + 40) for x, y in e[3]]) for e in original]
    return owner, final, ro, b''.join(e[1] for e in final), ix


def compile_invented(enabled):
    owner, final_elements, ro, co, ix = invented_clip()
    trace = []
    wrapped = types.SimpleNamespace(events=[dict(event='INVENTED_FINAL_VALIDATION')])

    def visible(*args):
        trace.append('visibility')
        return dict(eligible=True, policy='EXPLICIT_INVENTED_ONLY')

    def for_owner(*args, **kwargs):
        trace.append('coverage')
        return object(), dict(invented=True)

    def wrap(*args, **kwargs):
        trace.append('wrap')
        return wrapped

    def clip(*args, **kwargs):
        trace.append('clip')
        return co, ix, owner['record'], dict(invented=True, geometryRepair=False)

    def validate(*args, **kwargs):
        trace.append('validate')
        return dict(passed=True, invented=True)

    def callback(bound_owner, elements, actual_ro, source_binding, **kwargs):
        if trace[-1] != 'validate' or elements != final_elements:
            raise AssertionError('Alias must consume clipped and finally validated data')
        if actual_ro != ro or bound_owner['record'] != owner['record']:
            raise AssertionError('Final FI and actual RO frame must be passed through')
        trace.append('alias')
        return alias.separate_boundary_runs(bound_owner, elements, actual_ro, source_binding, **kwargs)

    kwargs = dict(requested_level=5, authoring_last_level=7,
        coverage_service=types.SimpleNamespace(for_owner=for_owner),
        quantizer=types.SimpleNamespace(wrap_clipper=wrap, clip_owner=clip,
                                        validate_final_owner=validate), visibility=visible)
    if enabled:
        _, _, limits = kernel_fixtures()
        kwargs.update(postclip_alias=callback, postclip_policy=alias.POLICY,
            postclip_limits=limits, render_stream=b'\0' * ro['offset'] + ro['raw'],
            render_frame_ends={ro['offset']: ro['offset'] + len(ro['raw'])})
    scene, receipts = writer.compile_overlay_profile([owner], **kwargs)
    return scene, receipts, trace, owner, final_elements


def composer_ast():
    # Source of the actual imported installed function; no private freeze or
    # source transformation. The full compose body is never executed.
    return ast.parse(inspect.getsource(composer.compose))


def compact_with_installed_stanza(receipts):
    matches = []
    for node in ast.walk(composer_ast()):
        if not isinstance(node, ast.For):
            continue
        for i, statement in enumerate(node.body):
            if (isinstance(statement, ast.Assign) and len(statement.targets) == 1 and
                isinstance(statement.targets[0], ast.Name) and statement.targets[0].id == 'compact'):
                matches.append(node.body[i:i + 3])
    if len(matches) != 1 or len(matches[0]) != 3:
        raise AssertionError('Unique installed compact/retain-ledger/extend stanza required')
    scope = dict(vars(composer), receipts=receipts, detail_clip_receipts=[])
    exec(compile(ast.Module(body=copy.deepcopy(matches[0]), type_ignores=[]),
                 '<installed-composer-compact-stanza>', 'exec'), scope)
    return scope['detail_clip_receipts']


def emit_three(scene, enabled):
    nodes = [n for n in ast.walk(composer_ast()) if isinstance(n, ast.FunctionDef) and n.name == 'emit']
    if len(nodes) != 1:
        raise AssertionError('Unique installed composer.emit required')
    record = scene[0]['record']
    records = [record]
    for uid in (0x80000012, 0x80000013):
        fields = list(composer.F.unpack(record)); fields[0] = uid
        records.append(composer.F.pack(*fields))
    prefix = struct.pack('<II', 1, 16 << 24)
    arena = dict(name='invented-existing-arena', data=bytearray(), dedup={})
    scope = dict(vars(composer), service=types.SimpleNamespace(mode_for_tile=lambda *args: 'HARD'),
        tile=(5, 1, 1), arena=arena, index_pool=bytearray(prefix), features=bytearray(), owner_rows=[],
        ground_by_uid={}, detail_by_uid={composer.F.unpack(r)[0]:
            dict(paintPriority=4, originalSequence=(0, i)) for i, r in enumerate(records)})
    exec(compile(ast.Module(body=[copy.deepcopy(nodes[0])], type_ignores=[]),
                 '<installed-composer-emit>', 'exec'), scope)
    visible = [e for e in scene[0]['elements'] if e[0] == 16]
    first = scene[0]['elements'] if enabled else visible
    for i, (rec, elements) in enumerate(zip(records, [first, visible[:1], visible])):
        scope['emit'](rec, 'OWN-INVENTED', i, [(u, raw) for u, raw, _, _ in elements],
            'SOURCE_WINNER_DETAIL', scene[0]['postclipAliasLedger'] if enabled and i == 0 else None)
    return scope, prefix


class PostclipBoundaryIntegration(unittest.TestCase):
    def test_installed_compile_order_default_and_receipt_retention(self):
        scene, receipts, trace, owner, final = compile_invented(False)
        expected_owner = dict(owner, elements=final, bbox=(30, 40, 50, 60), triangle=False)
        expected_receipt = dict(uid=owner['uid'], cellId=owner['cellId'],
            sourceOwnerOrdinal=owner['sourceOwnerOrdinal'], level=5, scope=dict(invented=True),
            visibility=dict(eligible=True, policy='EXPLICIT_INVENTED_ONLY'), disposition='RETAINED',
            geometry=dict(invented=True, geometryRepair=False),
            integerContinuationEvents=[dict(event='INVENTED_FINAL_VALIDATION')],
            finalOwnerMaskValidation=dict(passed=True, invented=True))
        self.assertEqual(scene, [expected_owner])
        self.assertEqual(receipts, [expected_receipt])
        self.assertEqual(trace, ['visibility', 'coverage', 'wrap', 'clip', 'validate'])
        self.assertNotIn('postclipAliasLedger', scene[0])
        scene, receipts, trace, _, _ = compile_invented(True)
        self.assertEqual(trace, ['visibility', 'coverage', 'wrap', 'clip', 'validate', 'alias'])
        self.assertEqual([e[0] for e in scene[0]['elements']], [16, 8, 16])
        self.assertEqual(scene[0]['postclipAliasLedger']['status'], 'APPLIED')
        ordinary = native_hooks.compact_receipts(receipts)
        retained = compact_with_installed_stanza(receipts)
        self.assertNotIn('postclipAliasLedger', ordinary[0])
        self.assertEqual(retained[0]['postclipAliasLedger'], receipts[0]['postclipAliasLedger'])
        self.assertEqual(retained[0]['completeReceiptSHA256'], ordinary[0]['completeReceiptSHA256'])
        self.assertEqual(retained[0]['finalOwnerMaskValidation'], dict(passed=True, invented=True))

    def test_installed_emit_deduplicates_alias_and_rebases_every_FI(self):
        scene, _, _, _, _ = compile_invented(True)
        new, prefix = emit_three(scene, True)
        old, _ = emit_three(scene, False)
        self.assertEqual(new['index_pool'][:len(prefix)], prefix)
        self.assertEqual(new['arena']['data'], old['arena']['data'])
        self.assertEqual(len(new['arena']['dedup']), 2)
        self.assertEqual(len(new['arena']['data']), 88)
        old_records = [bytes(old['features'][i:i + 32]) for i in range(0, 96, 32)]
        new_records = [bytes(new['features'][i:i + 32]) for i in range(0, 96, 32)]
        self.assertEqual([composer.F.unpack(r)[1] for r in old_records], [8, 20, 28])
        self.assertEqual([composer.F.unpack(r)[1] for r in new_records], [8, 24, 32])
        for old_record, new_record in zip(old_records, new_records):
            self.assertEqual(old_record[:8] + old_record[12:], new_record[:8] + new_record[12:])
        words = struct.unpack_from('<III', new['index_pool'], 12)
        self.assertEqual([x >> 24 for x in words], [16, 8, 16])
        self.assertEqual([x & 0xffffff for x in words], [0, 44, 44])
        emitted = new['owner_rows'][0]['postclipAliasEmittedBinding']
        self.assertEqual(emitted['aliasOffsets'][0]['actualCOOffset'], 44)
        self.assertTrue(emitted['FIExceptIndexExact'])
        self.assertEqual(emitted['rawParts'], 3)
        self.assertEqual(emitted['visibleParts'], 2)
        self.assertNotIn('postclipAliasEmittedBinding', new['owner_rows'][1])
        self.assertEqual(len(new['index_pool']) - len(old['index_pool']), 4)


if __name__ == '__main__':
    unittest.main()
