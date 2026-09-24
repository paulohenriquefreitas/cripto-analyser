import unittest
from types import SimpleNamespace
from unittest.mock import patch
from mt5_canonical_price_stream import Cursor, Counters, LastEmitter, query, active_market, stream


def row(t, price=100, flags=8):
    return dict(time=t // 1000, time_msc=t, last=price, flags=flags)


class Stop:
    def __init__(self, waits=1): self.waits, self.time = waits, 0
    def is_set(self): return self.waits <= 0
    def wait(self, duration): self.waits -= 1; self.time += duration


class MT5:
    COPY_TICKS_ALL = -1
    TIMEFRAME_M5 = 5
    def __init__(self, rows=()):
        self.rows, self.shutdowns, self.calls = list(rows), 0, []
        self.latest = 6_312_345
    def initialize(self): return True
    def symbol_select(self, *args): return True
    def shutdown(self): self.shutdowns += 1
    def last_error(self): return 'fixture-error'
    def symbol_info_tick(self, symbol): return SimpleNamespace(time_msc=self.latest)
    def copy_ticks_from(self, symbol, start, count, flags):
        self.calls.append((start, count, flags))
        return [r for r in self.rows if r['time_msc'] >= int(start.timestamp()) * 1000][:count]
    def copy_rates_from(self, *args):
        return [dict(time=i * 300, open=100, high=100, low=100, close=100,
                     tick_volume=1, real_volume=1) for i in range(21)]


class CanonicalPriceStreamTest(unittest.TestCase):
    def test_first_batch_and_cursor(self):
        mt5 = MT5([row(1000), row(1001)])
        rows, cursor, drained = query(mt5, 'WINV26', Cursor(1000), 1999, Counters())
        self.assertEqual(2, len(rows)); self.assertEqual(Cursor(1001, 1), cursor); self.assertTrue(drained)

    def test_overlapping_second_and_raw_ordinal(self):
        mt5 = MT5([row(1000), row(1234, flags=2), row(1234), row(1234), row(1235)])
        rows, cursor, _ = query(mt5, 'WINV26', Cursor(1234, 2), 1999, Counters())
        self.assertEqual([1234, 1235], [r['time_msc'] for r in rows]); self.assertEqual(Cursor(1235, 1), cursor)

    def test_same_millisecond_appends_preserve_identical_rows(self):
        mt5 = MT5([row(1000), row(1000)])
        stats = Counters()
        first, cursor, _ = query(mt5, 'WINV26', Cursor(1000), 1999, stats)
        mt5.rows.append(row(1000))
        second, cursor, _ = query(mt5, 'WINV26', cursor, 1999, stats)
        messages = []
        emitter = LastEmitter('WINV26', stats, messages.append)
        emitter.accept(first); emitter.accept(second)
        self.assertEqual(3, len(messages)); self.assertEqual(2, stats.sameTimeMscEvents)
        self.assertEqual(1, stats.sameTimeMscGroups); self.assertEqual(2, stats.duplicateEvents)
        self.assertEqual(Cursor(1000, 3), cursor)

    def test_batch_without_last_advances_raw_cursor(self):
        mt5 = MT5([row(1000, flags=2), row(1001, flags=4)])
        rows, cursor, _ = query(mt5, 'WINV26', Cursor(1000), 1999, Counters())
        messages = []; LastEmitter('WINV26', Counters(), messages.append).accept(rows)
        self.assertEqual([], messages); self.assertEqual(Cursor(1001, 1), cursor)

    def test_mixed_flags_only_last_is_emitted(self):
        messages = []
        LastEmitter('WINV26', Counters(), messages.append).accept([row(i, flags=f) for i, f in enumerate([2, 4, 16, 8, 24, 14, 0])])
        self.assertEqual([3, 4, 5], [m['timeMsc'] for m in messages])
        self.assertEqual({'type', 'symbol', 'timeMsc', 'price'}, set(messages[0]))

    def test_out_of_order_rejected(self):
        stats = Counters()
        with self.assertRaisesRegex(RuntimeError, 'ORDER_ERROR'):
            query(MT5([row(1001), row(1000)]), 'WINV26', Cursor(1000), 1999, stats)
        self.assertEqual(1, stats.outOfOrderErrors)

    def test_full_batches_drain_and_do_not_drop_tail(self):
        mt5 = MT5([row(i * 1000) for i in range(1, 8)])
        cursor, received, stats = Cursor(1000), [], Counters()
        for _ in range(10):
            rows, cursor, drained = query(mt5, 'WINV26', cursor, 9999, stats, 3)
            received.extend(rows)
            if drained: break
        self.assertTrue(drained); self.assertEqual(7, len(received))
        self.assertEqual(list(range(1000, 8000, 1000)), [r['time_msc'] for r in received])
        self.assertGreater(stats.fullBatches, 0)

    def test_full_batch_without_progress_fails_instead_of_looping(self):
        stats = Counters()
        with self.assertRaisesRegex(RuntimeError, 'without cursor progress'):
            query(MT5([row(1000), row(1000)]), 'WINV26', Cursor(1000, 2), 1999, stats, 2)
        self.assertEqual(1, stats.noProgressErrors)

    def test_partial_latest_second_does_not_advance_cursor_or_watermark_prematurely(self):
        rows, cursor, drained = query(MT5([row(1000), row(2000), row(2000)]), 'WINV26', Cursor(1000), 1999, Counters(), 3)
        self.assertEqual([row(1000)], rows); self.assertTrue(drained); self.assertEqual(Cursor(1000, 1), cursor)

    def test_disappearing_cursor_prefix_fails(self):
        with self.assertRaisesRegex(RuntimeError, 'prefix disappeared'):
            query(MT5([row(1000), row(2000)]), 'WINV26', Cursor(1000, 2), 2999, Counters())

    def test_mt5_error_is_explicit(self):
        mt5 = MT5(); mt5.copy_ticks_from = lambda *args: None
        stats = Counters()
        with self.assertRaisesRegex(RuntimeError, 'fixture-error'):
            query(mt5, 'WINV26', Cursor(0), 1000, stats)
        self.assertEqual(1, stats.queryErrors)

    def test_invalid_last_is_error_without_fallback_to_bid(self):
        for value in (0, -1, float('nan'), float('inf')):
            with self.assertRaisesRegex(RuntimeError, 'invalid LAST'):
                LastEmitter('WINV26', Counters(), lambda m: None).accept([row(1000, value)])

    def test_static_tick_and_quotes_do_not_prove_active_market(self):
        stop = Stop(3); mt5 = MT5([row(6_312_345)])
        self.assertIsNone(active_market(mt5, 'WINV26', stop, 1, lambda: stop.time))

    def test_new_last_at_multiple_timestamps_is_required_for_activity(self):
        mt5 = MT5([row(1000), row(2000), row(2001)])
        times = iter([1000, 4000])
        mt5.symbol_info_tick = lambda symbol: SimpleNamespace(time_msc=next(times))
        self.assertEqual(4000, active_market(mt5, 'WINV26', Stop(3), 1, lambda: 0).time_msc)

    def test_inactive_lifecycle_emits_no_historical_validation_and_shuts_down(self):
        mt5, messages = MT5(), []
        with patch('mt5_canonical_price_stream.active_market', return_value=None):
            stream(mt5, 'WINV26', Stop(), messages.append)
        self.assertEqual(['inactive', 'stopped'], [m['type'] for m in messages]); self.assertEqual(1, mt5.shutdowns)

    def test_bootstrap_reconstructs_current_bucket_and_clean_stop(self):
        mt5 = MT5([row(6_300_000), row(6_301_000), row(6_312_345)])
        messages = []
        with patch('mt5_canonical_price_stream.active_market', return_value=SimpleNamespace(time_msc=mt5.latest)):
            stream(mt5, 'WINV26', Stop(), messages.append)
        self.assertEqual('header', messages[0]['type']); self.assertEqual(6_300_000, messages[0]['startMsc'])
        self.assertEqual([6_300_000, 6_301_000], [m['timeMsc'] for m in messages if m['type'] == 'price'])
        self.assertEqual('stopped', messages[-1]['type']); self.assertEqual(1, mt5.shutdowns)

    def test_initialize_error_still_shuts_down(self):
        mt5 = MT5(); mt5.initialize = lambda: False
        with self.assertRaisesRegex(RuntimeError, 'initialize failed'): stream(mt5, 'WINV26', Stop(), lambda m: None)
        self.assertEqual(1, mt5.shutdowns)

    def test_new_session_has_fresh_cursor_and_counters(self):
        for _ in range(2):
            stats = Counters()
            rows, cursor, _ = query(MT5([row(1000)]), 'WINV26', Cursor(1000), 1999, stats)
            self.assertEqual(1, stats.rowsReceived); self.assertEqual(0, stats.sourceRestarts)
            self.assertEqual(Cursor(1000, 1), cursor)




class CanonicalAdditionalSafetyTest(unittest.TestCase):
    def test_inconsistent_seconds_are_rejected(self):
        bad = row(1001); bad['time'] = 5
        with self.assertRaisesRegex(RuntimeError, 'inconsistent'):
            query(MT5([bad]), 'WINV26', Cursor(1000), 1999, Counters())

    def test_full_batch_in_same_second_fails_explicitly_if_api_cannot_advance(self):
        mt5 = MT5([row(1001), row(1002), row(1003), row(1004)])
        rows, cursor, drained = query(mt5, 'WINV26', Cursor(1000), 1999, Counters(), 3)
        self.assertEqual(3, len(rows)); self.assertFalse(drained)
        with self.assertRaisesRegex(RuntimeError, 'without cursor progress'):
            query(mt5, 'WINV26', cursor, 1999, Counters(), 3)

    def test_quotes_only_advancing_feed_is_not_active_last_market(self):
        mt5 = MT5([row(1000, flags=2), row(2000, flags=4), row(3000, flags=2)])
        mt5.latest = 1000
        def tick(symbol):
            value = mt5.latest; mt5.latest += 1000
            return SimpleNamespace(time_msc=value)
        mt5.symbol_info_tick = tick
        stop = Stop(4)
        self.assertIsNone(active_market(mt5, 'WINV26', stop, 1, lambda: stop.time))

    def test_incomplete_warmup_stops_and_shuts_down(self):
        mt5 = MT5(); mt5.copy_rates_from = lambda *args: []
        with patch('mt5_canonical_price_stream.active_market', return_value=SimpleNamespace(time_msc=mt5.latest)):
            with self.assertRaisesRegex(RuntimeError, 'SHADOW_NOT_READY'):
                stream(mt5, 'WINV26', Stop(), lambda m: None)
        self.assertEqual(1, mt5.shutdowns)

    def test_official_query_excludes_open_candle_and_is_not_per_tick(self):
        mt5 = MT5([row(6_300_000), row(6_301_000), row(6_600_001)])
        mt5.latest = 6_605_000
        calls = []
        original = mt5.copy_rates_from
        def rates(*args):
            calls.append(args)
            return original() + [dict(time=6300,open=100,high=100,low=100,close=100,tick_volume=1,real_volume=1),
                                 dict(time=6600,open=100,high=100,low=100,close=100,tick_volume=1,real_volume=1)]
        mt5.copy_rates_from = rates
        messages = []
        with patch('mt5_canonical_price_stream.active_market', return_value=SimpleNamespace(time_msc=6_312_345)):
            stream(mt5, 'WINV26', Stop(5), messages.append)
        official = [m for m in messages if m['type'] == 'official']
        self.assertEqual(1, len(official)); self.assertEqual(2, len(calls))
        self.assertTrue(all(c['time'] < 6600 for c in official[0]['candles']))
        self.assertEqual(6_600_000, official[0]['closedThroughMsc'])

class CanonicalStartupOrderTest(unittest.TestCase):
    def test_native_import_precedes_blocking_stdin_thread(self):
        import builtins
        import mt5_canonical_price_stream as source
        imported = []
        actual_import = builtins.__import__
        def intercept(name, *args, **kwargs):
            if name == 'MetaTrader5':
                imported.append(name)
                return MT5()
            return actual_import(name, *args, **kwargs)
        def start_thread():
            self.assertEqual(['MetaTrader5'], imported)
        with patch('builtins.__import__', side_effect=intercept), \
                patch.object(source.threading.Thread, 'start', side_effect=start_thread), \
                patch.object(source, 'stream') as run, patch('sys.argv', ['source']):
            source.main()
            run.assert_called_once()

class CanonicalSettlementTest(unittest.TestCase):
    def test_watermark_keeps_at_least_one_full_second_even_at_rollover(self):
        mt5 = MT5([row(6_300_000)])
        mt5.latest = 6_313_000
        messages = []
        with patch('mt5_canonical_price_stream.active_market', return_value=SimpleNamespace(time_msc=6_312_345)):
            stream(mt5, 'WINV26', Stop(), messages.append)
        watermark = next(m['timeMsc'] for m in messages if m['type'] == 'watermark')
        self.assertGreaterEqual(mt5.latest - watermark, 1000)

    def test_late_row_in_retained_second_is_emitted_before_its_watermark(self):
        from mt5_canonical_price_stream import settled_through
        mt5 = MT5([row(1000), row(2000)])
        first, cursor, _ = query(mt5, 'WINV26', Cursor(1000), settled_through(3000), Counters())
        self.assertEqual([1000], [r['time_msc'] for r in first])
        mt5.rows.append(row(2999))
        second, cursor, _ = query(mt5, 'WINV26', cursor, settled_through(4000), Counters())
        self.assertEqual([2000, 2999], [r['time_msc'] for r in second])
        self.assertTrue(all(r['time_msc'] > settled_through(3000) for r in second))

class CanonicalWatermarkGapRegressionTest(unittest.TestCase):
    def test_delayed_history_after_rollover_reproduces_old_gap_and_fixed_order(self):
        import mt5_canonical_price_stream as source
        corrected = source.settled_through
        for cutoff, expected_late in ((lambda t: t // 1000 * 1000 - 1, 2),
                                      (corrected, 0)):
            with self.subTest(expected_late=expected_late):
                mt5 = MT5([row(6_300_000), row(6_312_000)])
                times = iter([6_313_000, 6_313_100, 6_314_000])
                def latest(symbol):
                    value = next(times)
                    if value == 6_313_100:
                        # History becomes visible after symbol_info_tick crossed
                        # the second boundary. Identical LAST rows must survive.
                        mt5.rows.extend([row(6_312_999), row(6_312_999)])
                    return SimpleNamespace(time_msc=value)
                mt5.symbol_info_tick = latest
                messages = []
                with patch.object(source, 'settled_through', side_effect=cutoff), \
                        patch.object(source, 'active_market', return_value=SimpleNamespace(time_msc=6_312_345)):
                    source.stream(mt5, 'WINV26', Stop(3), messages.append)
                watermark = -1
                late, prices = [], []
                for message in messages:
                    if message['type'] == 'watermark':
                        watermark = message['timeMsc']
                    elif message['type'] == 'price':
                        timestamp = message['timeMsc']
                        prices.append(timestamp)
                        if timestamp <= watermark:
                            late.append((timestamp, watermark))
                self.assertEqual([6_300_000, 6_312_000, 6_312_999, 6_312_999], prices)
                self.assertEqual([(6_312_999, 6_312_999)] * expected_late, late)
                self.assertEqual(1, mt5.shutdowns)

if __name__ == '__main__': unittest.main()
