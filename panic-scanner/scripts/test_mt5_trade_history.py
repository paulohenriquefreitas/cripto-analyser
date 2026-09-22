"""Historical validation source tests without MetaTrader5."""

from types import SimpleNamespace
import unittest
from unittest.mock import Mock

from mt5_trade_history import historical_trades
from test_mt5_trade_stream import DEFINITIONS, row


class HistoricalTradesTest(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
