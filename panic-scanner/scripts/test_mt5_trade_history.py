"""Historical validation source tests without MetaTrader5."""

from types import SimpleNamespace
import unittest
from unittest.mock import Mock

from mt5_trade_history import historical_trades, stream_historical_trades, summarize_trades
from test_mt5_trade_stream import DEFINITIONS, row


class HistoricalTradesTest(unittest.TestCase):
    def test_direct_summary_matches_normalized_events(self):
        messages = [
            {"price": 100.0, "volume": 10.0, "side": "BUY"},
            {"price": 98.0, "volume": 4.0, "side": "SELL"},
            {"price": 103.0, "volume": 20.0, "side": "AMBIGUOUS"},
        ]
        result = summarize_trades(messages)
        self.assertEqual(3, result["trades"])
        self.assertEqual(34, result["volume"])
        self.assertEqual(6, result["knownDelta"])
        self.assertEqual((100, 103, 98, 103), (result["firstPrice"], result["lastPrice"],
                                               result["minPrice"], result["maxPrice"]))

    def test_filters_inclusive_millisecond_bounds_and_preserves_duplicates(self):
        mt5 = Mock()
        mt5.COPY_TICKS_ALL = 0
        for name, value in DEFINITIONS.items():
            setattr(mt5, "TICK_FLAG_" + name, value)
        duplicate = row(1_000)
        mt5.copy_ticks_range.return_value = [row(999), duplicate, duplicate, row(1_001), row(1_002)]

        result = historical_trades(mt5, "WINV26", 1_000, 1_001)

        self.assertEqual([1_000, 1_000, 1_001], [item["timeMsc"] for item in result])
        self.assertEqual(3, len(result))

    def test_query_failure_is_not_treated_as_empty_market(self):
        mt5 = Mock(COPY_TICKS_ALL=0)
        mt5.copy_ticks_range.return_value = None
        mt5.last_error.return_value = (-1, "failed")
        with self.assertRaisesRegex(RuntimeError, "copy_ticks_range"):
            historical_trades(mt5, "WINV26", 1_000, 1_001)

    def test_chunked_stream_is_inclusive_ordered_and_does_not_duplicate_boundaries(self):
        mt5 = Mock(COPY_TICKS_ALL=0)
        for name, value in DEFINITIONS.items():
            setattr(mt5, "TICK_FLAG_" + name, value)
        source = [row(1_000), row(1_999), row(2_000), row(2_000), row(2_001)]
        mt5.copy_ticks_range.return_value = source

        result = list(stream_historical_trades(mt5, "WINV26", 1_000, 2_001, 1))

        self.assertEqual([1_000, 1_999, 2_000, 2_000, 2_001],
                         [item["timeMsc"] for item in result])
        self.assertEqual(2, mt5.copy_ticks_range.call_count)


if __name__ == "__main__":
    unittest.main()
