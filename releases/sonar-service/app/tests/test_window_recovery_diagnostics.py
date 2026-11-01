import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from window_recovery_diagnostics import parse_window_recovery_diagnostics as parse

LINE = ('No5WindowRecovery: v=1 instance=1234:987654321 api=29 baseline=true '
        'requested=0 failures=0 probeErrors=0 closed=false outcome=WINDOW_BASELINE_COMMITTED')


class WindowRecoveryDiagnosticsTest(unittest.TestCase):
    def test_valid_with_activity_header_and_indent(self):
        value = parse('ACTIVITY com.no5.helm/.MainActivity\n    ' + LINE + '\n', expected_instance='1234:987654321')
        self.assertEqual(value['requested'], 0)

    def test_one_successful_recovery_kept(self):
        text = LINE.replace('requested=0', 'requested=1').replace('WINDOW_BASELINE_COMMITTED', 'RECOVERY_FRAME_COMMITTED_AND_SURFACE_VALID')
        self.assertEqual(parse(text)['requested'], 1)

    def test_pending_allowed_only_for_active_poll(self):
        text = LINE.replace('requested=0', 'requested=1').replace('WINDOW_BASELINE_COMMITTED', 'RECOVERY_REQUESTED')
        with self.assertRaises(RuntimeError): parse(text)
        self.assertEqual(parse(text, require_settled=False)['outcome'], 'RECOVERY_REQUESTED')

    def test_error_counters_never_filtered(self):
        for token in ['failures=1', 'probeErrors=1']:
            text = LINE.replace(token.split('=')[0] + '=0', token)
            with self.subTest(token=token), self.assertRaises(RuntimeError): parse(text)

    def test_restart_relayout_or_process_change_denied(self):
        with self.assertRaises(RuntimeError): parse(LINE, expected_instance='1234:987654322')

    def test_missing_duplicate_unknown_and_malformed_fields(self):
        for text in ['', 'No5WindowRecovery: NO_ACTIVE_BINDING', LINE + '\n' + LINE,
                     LINE + ' requested=0', LINE + ' unknown=0', LINE.replace('v=1 ', ''),
                     LINE.replace('requested=0', 'requested=-1'), LINE.replace('api=29', 'api=30'),
                     LINE.replace('v=1', 'v=2'), LINE.replace('instance=1234:987654321', 'instance=oops')]:
            with self.subTest(text=text), self.assertRaises(ValueError): parse(text)

    def test_no_baseline_closed_exhausted_and_unknown_outcome_denied(self):
        for text in [LINE.replace('baseline=true', 'baseline=false'), LINE.replace('closed=false', 'closed=true'),
                     LINE.replace('requested=0', 'requested=2'), LINE.replace('WINDOW_BASELINE_COMMITTED', 'UNKNOWN')]:
            with self.subTest(text=text), self.assertRaises(RuntimeError): parse(text)

    def test_counter_consistency(self):
        with self.assertRaises(ValueError):
            parse(LINE.replace('WINDOW_BASELINE_COMMITTED', 'RECOVERY_FRAME_COMMITTED_AND_SURFACE_VALID'))

    def test_size_bound_is_a_failure_not_truncation(self):
        with self.assertRaises(ValueError): parse('x' * 65536 + LINE)


if __name__ == '__main__':
    unittest.main()
