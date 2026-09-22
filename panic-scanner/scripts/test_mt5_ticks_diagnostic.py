import unittest

from mt5_ticks_diagnostic import analyse, flag_names, is_trade


DEFINITIONS = {"BID": 2, "ASK": 4, "LAST": 8, "VOLUME": 16, "BUY": 32, "SELL": 64}


class TickDiagnosticTest(unittest.TestCase):
    def test_flags_and_trade_classification(self):
        self.assertEqual(["LAST", "VOLUME", "BUY"], flag_names(8 | 16 | 32, DEFINITIONS))
        self.assertTrue(is_trade(8, DEFINITIONS))
        self.assertTrue(is_trade(16, DEFINITIONS))
        self.assertFalse(is_trade(2 | 4, DEFINITIONS))

    def test_analysis_preserves_same_timestamp_as_distinct_events(self):
        rows = [
            {"time_msc": 1000, "flags": 8 | 16 | 32, "volume": 2, "volume_real": 2.0},
            {"time_msc": 1000, "flags": 8 | 16 | 64, "volume": 3, "volume_real": 3.5},
            {"time_msc": 1001, "flags": 2 | 4, "volume": 0, "volume_real": 0.0},
        ]
        stats = analyse(rows, DEFINITIONS)
        self.assertEqual(3, stats["total"])
        self.assertEqual(2, stats["trades"])
        self.assertEqual(1, stats["buy"])
        self.assertEqual(1, stats["sell"])
        self.assertEqual(1, stats["flag_buy"])
        self.assertEqual(1, stats["flag_sell"])
        self.assertEqual(1, stats["time_msc_with_multiple_events"])
        self.assertEqual(1, stats["duplicate_time_msc"])
        self.assertEqual(1, stats["volume_diff"])
        self.assertEqual(1, stats["volume_fractional"])
        self.assertEqual(0, stats["trade_volume_real_zero"])
        self.assertEqual(1, stats["trade_volume_real_fractional"])


if __name__ == "__main__":
    unittest.main()
