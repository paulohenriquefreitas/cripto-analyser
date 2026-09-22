import unittest

from mt5_quote_history_probe import compare


class QuoteHistoryProbeTest(unittest.TestCase):
    def test_polled_states_can_be_subsequence_while_history_has_more_events(self):
        polled = [{"time_msc": 1, "last": 100}, {"time_msc": 3, "last": 102}]
        historical = [
            {"time_msc": 1, "last": 100},
            {"time_msc": 2, "last": 101},
            {"time_msc": 2, "last": 101},
            {"time_msc": 3, "last": 102},
        ]
        result = compare(polled, historical)
        self.assertEqual(2, result["polledSubsequenceMatches"])
        self.assertEqual(4, result["historical"])
        self.assertEqual(1, result["historicalRepeatedTimeMsc"])


if __name__ == "__main__":
    unittest.main()
