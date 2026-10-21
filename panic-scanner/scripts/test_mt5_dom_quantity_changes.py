import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from mt5_dom_quantity_changes import analyze_file, distribution


def book(bid=10, ask=20, bid_price=100, ask_price=105):
    return [{"type": 1, "price": ask_price, "volume": ask, "volume_dbl": float(ask)},
            {"type": 2, "price": bid_price, "volume": bid, "volume_dbl": float(bid)}]


class QuantityChangesTest(unittest.TestCase):
    def run_rows(self, samples, times=None, sequences=None, symbol="WINZ26"):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "capture.ndjson"
            records = [{"kind": "symbol_info", "value": {"name": symbol}}]
            for i, levels in enumerate(samples):
                millis = times[i] if times else i * 100
                records.append({"kind": "snapshot", "sequence": sequences[i] if sequences else i + 1,
                    "captured_utc": f"2026-10-15T19:00:{millis // 1000:02d}.{millis % 1000:03d}+00:00",
                    "levels": levels, "error": None})
            path.write_text("\n".join(json.dumps(r) for r in records), encoding="utf-8")
            before = path.read_bytes()
            result = analyze_file(path)
            self.assertEqual(before, path.read_bytes())
            self.assertEqual(result["source_sha256"], hashlib.sha256(before).hexdigest())
            return result

    def test_magnitudes_frequency_and_side_intervals(self):
        result = self.run_rows([book(), book(15, 17), book(12, 25), book(12,25), book(20,25)])
        bid = result["sides"]["BID"]
        self.assertEqual(bid["matched_level_comparisons"], 4)
        self.assertEqual(bid["quantity_change_fraction"], .75)
        inc = bid["event_types"]["increase"]
        self.assertEqual(inc["magnitude"]["values"], [5,8])
        self.assertEqual(inc["events_per_second"], 5)
        self.assertEqual(inc["side_interevent_ms"]["values"], [300])
        self.assertEqual(inc["same_price_kind_interevent_ms"]["values"], [300])
        self.assertEqual(bid["event_types"]["decrease"]["magnitude"]["sum"], 3)
        ask = result["sides"]["ASK"]["event_types"]
        self.assertEqual(ask["decrease"]["magnitude"]["sum"], 3)
        self.assertEqual(ask["increase"]["magnitude"]["sum"], 8)

    def test_disappearance_first_appearance_and_reappearance(self):
        result = self.run_rows([book(), book(bid_price=95), book(bid_price=95), book(bid=7)])
        bid = result["sides"]["BID"]["event_types"]
        self.assertEqual(bid["disappearance"]["count"], 2)
        self.assertEqual(bid["first_appearance"]["count"], 1)
        self.assertEqual(bid["reappearance"]["count"], 1)
        re = next(e for e in result["events"] if e["kind"] == "reappearance")
        self.assertIsNone(re["before_quantity"])
        self.assertEqual(re["after_quantity"], 7)
        self.assertEqual(re["magnitude"], 7)
        self.assertEqual(re["observed_absence_ms"], 200)
        self.assertEqual(re["price"], 100)
        self.assertEqual(bid["increase"]["count"], 0)

    def test_unknown_periods_reset_reappearance_and_interevent_history(self):
        invalid = book()
        invalid[0]["volume_dbl"] = -1
        for separator in ([], None, invalid):
            with self.subTest(separator=separator):
                result = self.run_rows([book(), book(12), separator, book(50), book(55)])
                inc = result["sides"]["BID"]["event_types"]["increase"]
                self.assertEqual(inc["count"], 2)
                self.assertEqual(inc["magnitude"]["sum"], 7)
                self.assertEqual(inc["side_interevent_ms"]["count"], 0)
                self.assertEqual(inc["same_price_kind_interevent_ms"]["count"], 0)
                self.assertEqual(result["sides"]["BID"]["event_types"]["reappearance"]["count"], 0)
        result = self.run_rows([book(), book(bid_price=95), [], book()])
        self.assertEqual(result["sides"]["BID"]["event_types"]["reappearance"]["count"], 0)

    def test_gap_and_repetition(self):
        result = self.run_rows([book(), book(), book(50), book(52)], [0,100,1000,1100])
        self.assertEqual(result["quality"]["large_gaps"], 1)
        self.assertEqual(result["sides"]["BID"]["quantity_change_count"], 1)
        self.assertEqual(result["sides"]["BID"]["event_types"]["increase"]["magnitude"]["sum"], 2)
        self.assertEqual(result["sides"]["BID"]["baseline_level_observations"], 2)

    def test_empty_and_single_snapshot_have_no_events(self):
        for samples in ([[],None], [book()]):
            result = self.run_rows(samples)
            self.assertEqual(result["events"], [])
            self.assertIsNone(result["sides"]["BID"]["event_types"]["increase"]["events_per_second"])

    def test_percentiles(self):
        self.assertEqual(distribution([1,2,3,4])["median"], 2.5)
        self.assertEqual(distribution([1,2,3,4])["p90"], 4)
        self.assertIsNone(distribution([])["max"])

    def test_reject_wrong_symbol_order_and_missing_sequence(self):
        with self.assertRaises(ValueError):
            self.run_rows([book()], symbol="WINV26")
        with self.assertRaises(ValueError):
            self.run_rows([book(),book()], times=[100,0])
        with self.assertRaises(ValueError):
            self.run_rows([book(),book()], sequences=[1,3])


if __name__ == "__main__":
    unittest.main()
