import unittest

from mt5_continuous_probe import positional_comparison, side, trade_identity


DEFINITIONS = {"BID": 2, "ASK": 4, "LAST": 8, "VOLUME": 16, "BUY": 32, "SELL": 64}


def row(ms, price, volume, flags):
    return {"time_msc": ms, "last": price, "volume_real": volume,
            "volume": volume, "flags": flags}


class ContinuousProbeTest(unittest.TestCase):
    def test_buy_sell_is_ambiguous(self):
        self.assertEqual("BUY", side(32, DEFINITIONS))
        self.assertEqual("SELL", side(64, DEFINITIONS))
        self.assertEqual("AMBIGUOUS", side(32 | 64, DEFINITIONS))
        self.assertEqual("NONE", side(0, DEFINITIONS))

    def test_identity_contains_all_fields_but_not_a_unique_timestamp_assumption(self):
        value = row(1000, 188375, 2.0, 8 | 16 | 32)
        self.assertEqual((1000, 188375.0, 2.0, "BUY"), trade_identity(value, DEFINITIONS))

    def test_comparison_preserves_multiple_trades_at_same_millisecond_and_order(self):
        left = [row(1000, 10, 1, 8 | 16 | 32), row(1000, 11, 2, 8 | 16 | 64)]
        equal = positional_comparison(left, list(left), DEFINITIONS)
        self.assertEqual(2, equal["identicalMatches"])
        self.assertEqual(2, equal["marketDataMatches"])
        self.assertEqual(100.0, equal["identicalPercent"])

        reversed_right = list(reversed(left))
        changed = positional_comparison(left, reversed_right, DEFINITIONS)
        self.assertEqual(2, changed["timestampMatches"])
        self.assertEqual(0, changed["priceMatches"])
        self.assertEqual(0, changed["volumeMatches"])
        self.assertEqual(0, changed["sideMatches"])
        self.assertEqual(0, changed["marketDataMatches"])
        self.assertEqual(0, changed["identicalMatches"])

    def test_missing_trade_counts_against_the_larger_stream(self):
        left = [row(1000, 10, 1, 8 | 16 | 32), row(1001, 11, 2, 8 | 16 | 64)]
        result = positional_comparison(left, left[:1], DEFINITIONS)
        self.assertEqual(2, result["denominator"])
        self.assertEqual(50.0, result["identicalPercent"])


if __name__ == "__main__":
    unittest.main()
