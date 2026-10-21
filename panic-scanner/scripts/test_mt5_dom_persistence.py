import json
from pathlib import Path
import tempfile
import unittest

from mt5_dom_persistence import analyze_file


def book(volume=10):
    return [{"type": 1, "price": 105, "volume": volume, "volume_dbl": float(volume)},
            {"type": 2, "price": 100, "volume": 10, "volume_dbl": 10.0}]


class PersistenceTest(unittest.TestCase):
    def run_rows(self, samples, times=None, symbol="WINZ26"):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "capture.ndjson"
            records = [{"kind": "symbol_info", "value": {"name": symbol}}]
            for i, levels in enumerate(samples):
                millis = times[i] if times else i * 100
                records.append({"kind": "snapshot", "sequence": i + 1,
                    "captured_utc": f"2026-10-15T19:00:{millis // 1000:02d}.{millis % 1000:03d}+00:00",
                    "levels": levels, "error": None})
            path.write_text("\n".join(json.dumps(r) for r in records), encoding="utf-8")
            before = path.read_bytes()
            result = analyze_file(path)
            self.assertEqual(before, path.read_bytes())
            return result

    def test_repeated_snapshots_and_quantity_changes(self):
        result = self.run_rows([book(), book(), book(20), book(5)])
        ask = result["levels"][0]
        self.assertEqual(ask["observations"], 4)
        self.assertEqual(ask["runs"], 1)
        self.assertEqual(ask["longest_run_ms"], 300)
        self.assertEqual(ask["matched_presence_ratio"], 1)
        self.assertEqual(ask["quantity_changes"], 2)
        self.assertEqual(ask["quantity_increases"], 1)
        self.assertEqual(ask["quantity_decreases"], 1)
        self.assertEqual(ask["quantity_mean_sampled"], 11.25)

    def test_unknown_periods_break_runs(self):
        result = self.run_rows([book(), [], book(), None, book(), book()], [0,100,200,300,400,1200])
        self.assertEqual(result["quality"]["empty"], 1)
        self.assertEqual(result["quality"]["failed"], 1)
        self.assertEqual(result["quality"]["large_gaps"], 1)
        self.assertEqual(result["levels"][0]["runs"], 4)
        self.assertEqual(result["levels"][0]["observed_exits"], 0)
        self.assertIsNone(result["levels"][0]["matched_presence_ratio"])

    def test_visible_exit_and_reappearance(self):
        moved = book()
        moved[0]["price"] = 110
        result = self.run_rows([book(), book(), moved, book()])
        ask = next(x for x in result["levels"] if x["price"] == 105)
        self.assertEqual(ask["runs"], 2)
        self.assertEqual(ask["observed_exits"], 1)
        self.assertEqual(ask["longest_run_ms"], 100)
        self.assertAlmostEqual(ask["matched_presence_ratio"], 1/3)

    def test_invalid_book_is_reported_and_breaks_run(self):
        invalid = book()
        invalid[0]["volume_dbl"] = -1
        result = self.run_rows([book(), invalid, book()])
        self.assertEqual(result["quality"]["invalid"], 1)
        self.assertTrue(result["quality"]["anomalies"])
        self.assertEqual(result["levels"][0]["runs"], 2)

    def test_empty_capture_has_no_fabricated_levels(self):
        result = self.run_rows([[], None])
        self.assertEqual(result["status"], "no_usable_dom")
        self.assertEqual(result["levels"], [])

    def test_complete_and_boundary_censored_distributions(self):
        moved = book()
        moved[0]["price"] = 110
        result = self.run_rows([book(), moved, moved, book()])
        runs = [r for r in result["runs"] if r["side"] == "ASK"]
        complete = next(r for r in runs if r["price"] == 110)
        self.assertFalse(complete["censored"])
        self.assertEqual(complete["end_reason"], "observed_exit")
        self.assertEqual(complete["duration_ms"], 100)
        self.assertEqual(complete["end_detected_ms"] - complete["last_seen_ms"], 100)
        first = next(r for r in runs if r["start_sequence"] == 1)
        self.assertTrue(first["left_censored"])
        self.assertFalse(first["right_censored"])
        final = next(r for r in runs if r["end_reason"] == "capture_end")
        self.assertTrue(final["right_censored"])
        self.assertFalse(final["left_censored"])
        dist = result["duration_distributions"]["ASK"]
        self.assertEqual(dist["all_observed"]["durations_ms"], [0, 0, 100])
        self.assertEqual(dist["all_observed"]["median_ms"], 0)
        self.assertEqual(dist["all_observed"]["p90_ms"], 100)
        self.assertEqual(dist["uncensored"]["median_ms"], 100)
        self.assertEqual(dist["uncensored"]["count"], 1)
        self.assertEqual(dist["left_censored_count"], 1)
        self.assertEqual(dist["right_censored_count"], 1)

    def test_interruption_reasons_are_censored(self):
        invalid = book()
        invalid[0]["volume_dbl"] = -1
        result = self.run_rows([book(), [], book(), None, book(), invalid, book(), book()],
                               [0,100,200,300,400,500,600,1200])
        ask_runs = [r for r in result["runs"] if r["side"] == "ASK"]
        self.assertEqual([r["end_reason"] for r in ask_runs],
                         ["empty_book", "query_failure", "invalid_book", "large_gap", "capture_end"])
        self.assertTrue(all(r["left_censored"] and r["right_censored"] for r in ask_runs))
        dist = result["duration_distributions"]["ASK"]
        self.assertEqual(dist["uncensored"]["count"], 0)
        self.assertIsNone(dist["uncensored"]["median_ms"])
        self.assertEqual(dist["all_observed"]["count"], 5)

    def test_reject_wrong_contract_and_time_reversal(self):
        with self.assertRaisesRegex(ValueError, "Contrato inesperado"):
            self.run_rows([book()], symbol="WINV26")
        with self.assertRaisesRegex(ValueError, "ordem temporal"):
            self.run_rows([book(), book()], [200, 100])


if __name__ == "__main__":
    unittest.main()
