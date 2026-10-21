import json
from pathlib import Path
import tempfile
import unittest

from mt5_dom_gap_audit import audit, log_events, network_evidence, union_us, wall, window


def row(kind, seq, start_us, **changes):
    value = dict(kind=kind, sequence=seq, handler_start_us=start_us,
                 handler_end_us=start_us+1, handler_duration_us=1,
                 query_start_us=start_us, query_end_us=start_us+1,
                 query_duration_us=1, queue_delay_us=0, write_start_us=start_us+1,
                 time_local_before=0, time_gmt_before=10800, time_current=0,
                 ok=True, error=0, trigger='OnBookEvent', raw_levels=[], dom_market_time_msc=None)
    value.update(changes)
    return value


def batch(seq, us, before, ticks):
    return row('tick_batch', seq, us, cursor_before=before,
               cursor_after=dict(time_msc=ticks[-1]['time_msc'], ordinal=ticks[-1]['cursor_ordinal']) if ticks else before,
               ticks=ticks, returned_rows=len(ticks)+1)


class DomGapAuditTest(unittest.TestCase):
    def test_half_open_market_and_open_observation_windows_keep_clocks_separate(self):
        # The next book starts its handler 1 us before its query boundary: exclude it.
        records = [row('book_snapshot', 1, 10), row('tick_trigger', 2, 11),
                   row('timer_handler', 3, 15),
                   row('book_snapshot', 4, 19, query_start_us=20)]
        result = window(records, 10, 20)
        self.assertEqual(result['record_counts'], dict(tick_trigger=1, timer_handler=1))
        self.assertEqual(result['on_tick_execution_us'], [11])

    def test_parent_timer_and_child_query_are_not_double_counted(self):
        self.assertEqual(union_us([(0, 100), (20, 90), (80, 120)], 10, 110), 100)

    def test_cadence_includes_edges_when_there_are_no_events(self):
        result = window([], 0, 135000000)
        self.assertEqual(result['on_timer']['max_interval_including_edges_ms'], 135000)
        self.assertIsNone(result['copy_duration_us']['max'])

    def test_connection_loss_later_than_onset_is_not_full_explanation(self):
        before, after = dict(time_local_before=100), dict(time_local_before=240)
        events = [dict(category='connection_lost', local_encoded_ms=132029),
                  dict(category='authorized', local_encoded_ms=239000)]
        result = network_evidence(events, before, after)
        self.assertEqual(result['classification'], 'INDETERMINADA')
        self.assertEqual(result['first_loss_after_start_range_s'], [31.029, 32.029])

    def test_loss_at_boundary_and_reauthorization_explain_to_clock_precision(self):
        result = network_evidence([dict(category='connection_lost', local_encoded_ms=100657),
                                   dict(category='authorized', local_encoded_ms=111499)],
                                  dict(time_local_before=100), dict(time_local_before=112))
        self.assertEqual(result['classification'], 'EXPLICADA')
        self.assertEqual(result['first_loss_after_start_range_s'], [-.343, .657])

    def test_absence_of_network_log_does_not_explain_gap(self):
        self.assertEqual(network_evidence([], dict(time_local_before=100),
                                         dict(time_local_before=200))['classification'], 'INDETERMINADA')

    def test_clock_labels_do_not_turn_local_or_server_into_utc(self):
        self.assertEqual(wall(0), '1970-01-01T00:00:00')
        self.assertEqual(wall(10800, True), '1970-01-01T03:00:00Z')

    def test_terminal_log_utf16_line_numbers_milliseconds_and_redaction(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'20261019.log'
            path.write_text("ignored line\nAA\t1\t16:19:34.029\tNetwork\t'123456': connection to ClearInvestimentos-CLEAR lost\n", encoding='utf-16')
            events = log_events(path)
        self.assertEqual(events[0]['line'], 2)
        self.assertEqual(events[0]['local_wall'], '2026-10-19T16:19:34.029')
        self.assertEqual(events[0]['local_encoded_ms'], 1792426774029)
        self.assertNotIn('123456', events[0]['message'])

    def test_late_history_counted_by_market_time_without_deduplicating(self):
        cursor = dict(time_msc=1000, ordinal=0)
        ticks = [dict(time=1, time_msc=1500, cursor_ordinal=n, flags=24, last=10.) for n in (1, 2)]
        records = [dict(kind='session', requested_seconds=40, cursor_seed_ms=1000,
                        copy_ticks_batch_limit=2048, queue_capacity=1024),
                   row('book_snapshot', 1, 1_000_000, time_current=1, time_local_before=1,
                       raw_levels=[dict(type=2, price=10., volume_dbl=2.),
                                   dict(type=1, price=10., volume_dbl=3.)]),
                   row('timer_handler', 2, 2_000_000),
                   row('book_snapshot', 3, 20_000_000, time_current=20, time_local_before=20),
                   batch(4, 21_000_000, cursor, ticks),
                   dict(kind='summary', start_us=0, raw_ticks=2, trade_ticks=2,
                        peak_queue=4, dropped_observations=0)]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'capture.ndjson'
            path.write_text(''.join(json.dumps(r)+'\n' for r in records))
            original = path.read_bytes()
            result = audit(path)
            self.assertEqual(path.read_bytes(), original)
        gap = result['gaps'][0]
        self.assertEqual(gap['metrics']['recorded_ticks'], 0)
        self.assertEqual(gap['market_bracket']['ticks'], 2)
        self.assertEqual(gap['market_bracket']['ticks_acquired_after_gap'], 2)
        self.assertEqual(result['integrity']['cursor_problems'], {})
        self.assertEqual(result['integrity']['raw_ticks'], 2)
        self.assertFalse(result['integrity']['recovery_summary_fields_present'])
        self.assertIsNone(result['integrity']['no_recovery_reported'])
        self.assertEqual(result['dom']['spread_anomaly_counts'], dict(locked_spread=1))


if __name__=='__main__':
    unittest.main()
