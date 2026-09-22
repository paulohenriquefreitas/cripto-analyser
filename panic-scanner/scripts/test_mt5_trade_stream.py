"""Trade stream and cursor tests without a terminal or MetaTrader5 package."""

import contextlib
import io
import json
from types import SimpleNamespace
import unittest
from unittest.mock import Mock

from mt5_trade_stream import (BATCH_SIZE, POLL_SECONDS, RETRY_SECONDS, TradeCursor, aggressor_side,
                              normalize_trade, query_from_cursor, stream_trades, unseen_at_cursor)


DEFINITIONS = {"BID": 2, "ASK": 4, "LAST": 8, "VOLUME": 16, "BUY": 32, "SELL": 64}


def row(ms, price=188_375, volume=1, flags=8 | 16 | 32):
    return {"time_msc": ms, "last": price, "volume": volume,
            "volume_real": float(volume), "flags": flags}


class TradeCursorTest(unittest.TestCase):
    def test_full_batch_advances_over_quote_only_tail(self):
        mt5 = Mock(COPY_TICKS_ALL=0)
        values = [row(1_000)]
        values.extend({"time_msc": 1_001, "flags": DEFINITIONS["BID"]}
                      for _ in range(BATCH_SIZE - 1))
        mt5.copy_ticks_from.return_value = values
        unseen, cursor, count = query_from_cursor(
            mt5, TradeCursor(1_000, 1), DEFINITIONS)
        self.assertEqual([], unseen)
        self.assertEqual(TradeCursor(1_001, 0), cursor)
        self.assertEqual(BATCH_SIZE, count)

    def test_full_batch_without_cursor_progress_fails_explicitly(self):
        mt5 = Mock(COPY_TICKS_ALL=0)
        mt5.copy_ticks_from.return_value = [row(1_000)] * BATCH_SIZE
        with self.assertRaisesRegex(RuntimeError, "sem progresso"):
            query_from_cursor(mt5, TradeCursor(1_000, BATCH_SIZE), DEFINITIONS)

    def test_side_mapping_including_missing_side(self):
        self.assertEqual("BUY", aggressor_side(32, DEFINITIONS))
        self.assertEqual("SELL", aggressor_side(64, DEFINITIONS))
        self.assertEqual("AMBIGUOUS", aggressor_side(32 | 64, DEFINITIONS))
        self.assertEqual("AMBIGUOUS", aggressor_side(0, DEFINITIONS))

    def test_requery_skips_only_emitted_ordinal_and_emits_new_identical_trade(self):
        identical = row(1_000)
        first, cursor = unseen_at_cursor([identical, identical, identical], TradeCursor(1_000, 0))
        self.assertEqual(3, len(first))
        self.assertEqual(TradeCursor(1_000, 3), cursor)

        second, cursor = unseen_at_cursor(
            [identical, identical, identical, identical], cursor)
        self.assertEqual([identical], second)
        self.assertEqual(TradeCursor(1_000, 4), cursor)

    def test_cursor_advances_across_timestamps_and_preserves_order(self):
        rows = [row(1_000, 10), row(1_000, 11), row(1_001, 12), row(1_001, 13)]
        unseen, cursor = unseen_at_cursor(rows, TradeCursor(1_000, 1))
        self.assertEqual([11, 12, 13], [item["last"] for item in unseen])
        self.assertEqual(TradeCursor(1_001, 2), cursor)

    def test_ignores_older_rows_returned_by_rounded_api_boundary(self):
        unseen, cursor = unseen_at_cursor(
            [row(999), row(1_000), row(1_001)], TradeCursor(1_000, 1))
        self.assertEqual([1_001], [item["time_msc"] for item in unseen])
        self.assertEqual(TradeCursor(1_001, 1), cursor)

    def test_normalizes_volume_real_and_never_falls_back(self):
        value = row(1_000, volume=2)
        value["volume"] = 99
        message = normalize_trade(value, DEFINITIONS)
        self.assertEqual(2, message["volume"])
        self.assertEqual("trade", message["type"])
        self.assertEqual("WINV26", message["symbol"])

        value["volume_real"] = 0
        with self.assertRaisesRegex(RuntimeError, "volume_real inválido"):
            normalize_trade(value, DEFINITIONS)


class PersistentTradeStreamTest(unittest.TestCase):
    def test_full_batch_drains_again_without_polling_delay(self):
        mt5 = self.configured_mt5()
        mt5.copy_ticks_range.return_value = []
        full_batch = [{"time_msc": 1_001, "flags": DEFINITIONS["BID"]}] * BATCH_SIZE
        mt5.copy_ticks_from.side_effect = [full_batch, []]
        stop = Mock()
        stop.is_set.side_effect = [False, False, True]

        stream_trades(mt5, stop, io.StringIO(), io.StringIO())

        self.assertEqual(2, mt5.copy_ticks_from.call_count)
        stop.wait.assert_called_once_with(POLL_SECONDS)

    def test_one_session_emits_incremental_trades_and_logs_no_side_to_stderr(self):
        mt5 = Mock()
        mt5.initialize.return_value = True
        mt5.symbol_select.return_value = True
        mt5.symbol_info_tick.return_value = SimpleNamespace(time_msc=1_000)
        for name, value in DEFINITIONS.items():
            setattr(mt5, "TICK_FLAG_" + name, value)
        mt5.COPY_TICKS_ALL = 0
        initial = [row(1_000)]
        no_side = row(1_000, price=188_370, flags=8 | 16)
        mt5.copy_ticks_range.return_value = initial
        mt5.copy_ticks_from.return_value = initial + [no_side]
        stop = Mock()
        stop.is_set.side_effect = [False, True]
        output = io.StringIO()
        errors = io.StringIO()

        stream_trades(mt5, stop, output, errors)

        messages = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual(2, len(messages))
        self.assertEqual(["BUY", "AMBIGUOUS"], [item["side"] for item in messages])
        self.assertIn("Trade sem BUY/SELL", errors.getvalue())
        self.assertEqual(1, mt5.copy_ticks_from.call_count)
        self.assertEqual(1, stop.wait.call_count)
        stop.wait.assert_called_with(POLL_SECONDS)
        mt5.initialize.assert_called_once()
        mt5.shutdown.assert_called_once()

    def test_initialization_failure_has_no_protocol_output_and_always_shuts_down(self):
        mt5 = Mock()
        mt5.initialize.return_value = False
        output = io.StringIO()
        with self.assertRaisesRegex(RuntimeError, "initialize"):
            stream_trades(mt5, Mock(), output, io.StringIO())
        self.assertEqual("", output.getvalue())
        mt5.shutdown.assert_called_once()

    def test_market_without_new_trades_waits_without_fake_output(self):
        mt5 = self.configured_mt5()
        mt5.copy_ticks_range.return_value = []
        mt5.copy_ticks_from.return_value = []
        stop = Mock()
        stop.is_set.side_effect = [False, True]
        output = io.StringIO()
        stream_trades(mt5, stop, output, io.StringIO())
        self.assertEqual("", output.getvalue())
        stop.wait.assert_called_once_with(POLL_SECONDS)

    def test_transient_query_error_is_logged_and_retried_without_moving_cursor(self):
        mt5 = self.configured_mt5()
        mt5.copy_ticks_range.return_value = []
        mt5.copy_ticks_from.side_effect = [None, [row(1_001)]]
        mt5.last_error.return_value = (-1, "temporary")
        stop = Mock()
        stop.is_set.side_effect = [False, False, True]
        output, errors = io.StringIO(), io.StringIO()
        stream_trades(mt5, stop, output, errors)
        self.assertEqual(1, len(output.getvalue().splitlines()))
        self.assertIn("Falha temporária", errors.getvalue())
        self.assertEqual([RETRY_SECONDS, POLL_SECONDS],
                         [call.args[0] for call in stop.wait.call_args_list])

    @staticmethod
    def configured_mt5():
        mt5 = Mock()
        mt5.initialize.return_value = True
        mt5.symbol_select.return_value = True
        mt5.symbol_info_tick.return_value = SimpleNamespace(time_msc=1_000)
        for name, value in DEFINITIONS.items():
            setattr(mt5, "TICK_FLAG_" + name, value)
        mt5.COPY_TICKS_ALL = 0
        return mt5


if __name__ == "__main__":
    unittest.main()
