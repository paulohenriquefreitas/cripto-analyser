import io
import json
import unittest
from unittest.mock import Mock

from mt5_capture_validation import CaptureQuery, CursorValidationError
from mt5_canonical_price_stream import Cursor, Counters
import test_mt5_dom_trades_probe as joint_probe_tests
from observe_absorption import Observation


def row(t=1500, volume=2):
    return dict(time=t//1000, time_msc=t, last=100., volume=volume,
                volume_real=float(volume), flags=40, bid=95., ask=105.)


class CaptureValidationTest(unittest.TestCase):
    def seed(self, prefix=None):
        api = Mock()
        api.COPY_TICKS_ALL = -1
        api.terminal_info.return_value.connected = True
        prefix = prefix or [row(), row()]
        api.copy_ticks_from.return_value = prefix
        reader, counters = CaptureQuery(api), Counters()
        _, cursor, _, _ = reader.read('X', Cursor(1500), 2**63-1, counters)
        return api, reader, counters, cursor, prefix

    def test_transient_empty_revalidates_same_cursor_preserves_duplicates(self):
        api, reader, counters, cursor, prefix = self.seed()
        api.copy_ticks_from.side_effect = [[], prefix + [row(), row(1501)]]
        rows, updated, _, diagnostics = reader.read('X', cursor, 2**63-1, counters)
        self.assertEqual(rows, [row(), row(1501)])
        self.assertEqual(updated, Cursor(1501, 1))
        self.assertEqual(diagnostics[0]['reason'], 'empty_response')
        self.assertEqual(api.copy_ticks_from.call_args_list[-2], api.copy_ticks_from.call_args_list[-1])
        self.assertEqual(counters.rowsReceived, 4)

    def test_absent_anchor_persistent_failure_keeps_committed_state(self):
        api, reader, counters, cursor, prefix = self.seed()
        api.copy_ticks_from.return_value = [row(1501)]
        with self.assertRaises(CursorValidationError) as raised:
            reader.read('X', cursor, 2**63-1, counters)
        self.assertEqual(len(raised.exception.diagnostics), 3)
        self.assertEqual(raised.exception.diagnostics[-1]['reason'], 'anchor_absent')
        self.assertEqual(reader.expected, cursor)
        self.assertEqual(counters.rowsReceived, 2)
        d = raised.exception.diagnostics[-1]
        self.assertEqual((d['returned_count'], d['first_time_msc'], d['last_time_msc']), (1,1501,1501))
        self.assertTrue(d['connected'])
        self.assertGreaterEqual(d['copy_ticks_api_ms'], 0)

    def test_changed_content_order_and_duplicate_multiplicity_rejected(self):
        for changed in ([row(volume=3),row()], [row(),row(volume=3)], [row()]):
            with self.subTest(changed=changed):
                api, reader, counters, cursor, prefix = self.seed()
                api.copy_ticks_from.return_value = changed
                with self.assertRaises(CursorValidationError):
                    reader.read('X', cursor, 2**63-1, counters)
                self.assertEqual(counters.rowsReceived, 2)
        api, reader, counters, cursor, prefix = self.seed([row(), row(volume=3)])
        api.copy_ticks_from.return_value = list(reversed(prefix))
        with self.assertRaises(CursorValidationError):
            reader.read('X', cursor, 2**63-1, counters)

    def test_limit_is_distinct_from_empty_and_anchor_absent(self):
        api, reader, counters, cursor, prefix = self.seed()
        api.copy_ticks_from.return_value = [row(1000),row(1100)]
        with self.assertRaises(CursorValidationError) as raised:
            reader.read('X', cursor, 2**63-1, counters, batch_size=2)
        self.assertEqual(raised.exception.diagnostics[-1]['reason'], 'record_limit_before_cursor')
        self.assertTrue(raised.exception.diagnostics[-1]['limit_reached'])

    def test_empty_persistent_is_bounded(self):
        api, reader, counters, cursor, prefix = self.seed()
        api.copy_ticks_from.return_value = []
        before = api.copy_ticks_from.call_count
        with self.assertRaises(CursorValidationError):
            reader.read('X', cursor, 2**63-1, counters)
        self.assertEqual(api.copy_ticks_from.call_count-before, 3)
        self.assertEqual(reader.expected, cursor)

    def test_api_error_after_empty_keeps_diagnostics_and_stops(self):
        api, reader, counters, cursor, prefix = self.seed()
        api.copy_ticks_from.side_effect = [[], None]
        with self.assertRaises(RuntimeError) as raised:
            reader.read('X', cursor, 2**63-1, counters)
        self.assertEqual([d['reason'] for d in raised.exception.diagnostics],
                         ['empty_response', 'query_error'])
        self.assertEqual(reader.expected, cursor)
        self.assertEqual(counters.rowsReceived, 2)

    def test_capture_emits_no_unverified_batch_and_preserves_error_details(self):
        fixture = joint_probe_tests.JointProbeTest()
        api = fixture.api()
        api.copy_ticks_from.side_effect = [fixture.rows, [row(2000)], [row(2000)], [row(2000)]]
        summary, records = fixture.capture(api)
        batches = [r for r in records if r['kind']=='tick_batch']
        self.assertEqual(len(batches), 1)
        self.assertEqual(summary['tick_cursor'], batches[0]['cursor_after'])
        error = next(r for r in records if r['kind']=='tick_query_error')
        out = io.StringIO()
        Observation(out).receive(error)
        logged = json.loads(out.getvalue().splitlines()[-1])
        self.assertEqual(len(logged['cursor_validation']), 3)
        self.assertTrue(logged['blocked'])

    def test_invalid_dom_details_survive_observation_output(self):
        for levels, reason in (([], 'empty_book'),
                               ([dict(type=3,price=0,volume=1,volume_dbl=1)], 'unsupported_level_type')):
            out = io.StringIO()
            observer = Observation(out, connected=lambda: True)
            observer.receive(dict(kind='snapshot', sequence=1, levels=levels, error=None,
                                  completed_utc='2026-10-20T20:59:57.045+00:00'))
            logged = json.loads(out.getvalue().splitlines()[-1])
            diagnostic = logged['dom_diagnostic']
            self.assertIn(reason, diagnostic['reasons'])
            self.assertEqual(diagnostic['levels'], levels)
            self.assertEqual(diagnostic['level_count'], len(levels))
            self.assertTrue(diagnostic['connected'])


if __name__ == '__main__':
    unittest.main()
