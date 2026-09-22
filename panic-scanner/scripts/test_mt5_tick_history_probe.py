import unittest
from datetime import date, datetime, timezone

from mt5_tick_history_probe import ProbeResult, day_of_tick, summarize, utc_start


DEFINITIONS = {"BID": 2, "ASK": 4, "LAST": 8, "VOLUME": 16, "BUY": 32, "SELL": 64}


class HistoryProbeTest(unittest.TestCase):
    def test_utc_date_logic_does_not_depend_on_local_timezone(self):
        self.assertEqual(datetime(2026, 8, 20, tzinfo=timezone.utc), utc_start(date(2026, 8, 20)))
        self.assertEqual(date(2026, 8, 20), day_of_tick({"time_msc": 1787184000000}))

    def test_summary_preserves_counts_sides_and_bounds(self):
        rows = [
            {"time_msc": 1000, "flags": 8 | 16 | 32, "volume": 2, "volume_real": 2.0},
            {"time_msc": 1001, "flags": 8 | 16 | 64, "volume": 3, "volume_real": 3.0},
        ]
        result = ProbeResult(date(1970, 1, 1), date(1970, 1, 1),
                             datetime.fromtimestamp(1, timezone.utc),
                             datetime.fromtimestamp(301, timezone.utc), rows)
        value = summarize(result, DEFINITIONS)
        self.assertEqual(2, value["events"])
        self.assertEqual(2, value["trades"])
        self.assertEqual(1, value["buy"])
        self.assertEqual(1, value["sell"])
        self.assertEqual(1000, value["firstTimeMsc"])
        self.assertEqual(1001, value["lastTimeMsc"])

    def test_empty_window_is_explicitly_ambiguous(self):
        value = summarize(ProbeResult(date(2025, 1, 1), None, None, None, []), DEFINITIONS)
        self.assertIn("contrato não negociado ou histórico indisponível", value["status"])


if __name__ == "__main__":
    unittest.main()
