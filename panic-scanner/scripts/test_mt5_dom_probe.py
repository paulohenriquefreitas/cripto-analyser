import io
import json
import unittest
from collections import namedtuple
from types import SimpleNamespace
from unittest.mock import Mock

from mt5_dom_probe import analyze, capture, write_record

Book = namedtuple("Book", "type price volume volume_dbl")


def row(kind, price, volume=10, volume_dbl=10.0):
    return dict(zip(Book._fields, (kind, price, volume, volume_dbl)))


class Clock:
    def __init__(self):
        self.now = 0

    def __call__(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class DomProbeTest(unittest.TestCase):
    def api(self):
        api = Mock()
        api.account_info.return_value = SimpleNamespace(company="Clear", server="Clear-Demo")
        api.symbol_info.return_value = namedtuple("SymbolInfo", "trade_tick_size")(5)
        api.symbol_info_tick.return_value = Book(2, 100, 10, 10.0)
        api.initialize.return_value = True
        api.symbol_select.return_value = True
        api.market_book_add.return_value = True
        api.market_book_release.return_value = True
        api.last_error.return_value = (-1, "synthetic error")
        return api

    def run_capture(self, api, seconds=.4, out=None):
        timer = Clock()
        out = out or io.StringIO()
        result = capture(api, out, seconds, 100, timer, timer.sleep)
        return result, [json.loads(line) for line in out.getvalue().splitlines()]

    def test_serialization_preserves_fields_and_nonfinite(self):
        out = io.StringIO()
        write_record(out, {"levels": [Book(1, float("nan"), 7, 7.5)]})
        data = json.loads(out.getvalue())["levels"][0]
        self.assertEqual(set(data), set(Book._fields))
        self.assertEqual(data["price"], {"non_finite": "nan"})
        self.assertEqual(data["volume_dbl"], 7.5)

    def test_sides_bbo_and_mt5_descending_order(self):
        result = analyze([row(1, 110), row(1, 105), row(2, 100), row(2, 95), row(3, 0), row(4, 0)], 5)
        self.assertEqual((result["best_bid"], result["best_ask"], result["spread"]), (100, 105, 5))
        self.assertEqual(result["anomalies"], [])

    def test_anomalies_do_not_repair_original(self):
        levels = [row(1, 90), row(1, 105), row(2, 100), row(2, 100), row(8, 0), row(2, float("inf"), -1, float("nan")), row(1, 106, 7, 8)]
        result = analyze(levels, 5)
        for anomaly in ("unordered_ask", "crossed_spread", "duplicate_level:3", "unknown_type:4", "invalid_price:5", "invalid_volume:5", "invalid_volume_dbl:5", "quantity_mismatch:6", "off_tick_price:6"):
            self.assertIn(anomaly, result["anomalies"])
        self.assertEqual(levels[0]["price"], 90)
        self.assertIn("locked_spread", analyze([row(1, 100), row(2, 100)])["anomalies"])
        self.assertIn("missing_limit_side", analyze([])["anomalies"])

    def test_metrics_repetitions_empty_failure_and_cleanup(self):
        api = self.api()
        book = (Book(1, 105, 10, 10.), Book(2, 100, 10, 10.))
        api.market_book_get.side_effect = [book, book, (), None]
        result, records = self.run_capture(api)
        self.assertEqual([result[k] for k in ("queries", "nonempty", "empty", "failures", "changes", "repeated")], [4, 2, 1, 1, 1, 1])
        snapshots = [r for r in records if r["kind"] == "snapshot"]
        self.assertEqual(len(snapshots), 4)
        self.assertEqual(snapshots[0]["levels"], snapshots[1]["levels"])
        self.assertEqual(result["spread"]["mean"], 5)
        self.assertRegex(snapshots[0]["captured_utc"], r"\.\d{3}\+00:00$")
        self.assertEqual(snapshots[3]["levels"], None)
        api.market_book_release.assert_called_once_with("WINZ26")
        api.shutdown.assert_called_once()

    def test_add_failure_no_release_or_fabricated_data(self):
        api = self.api()
        api.market_book_add.return_value = False
        result, records = self.run_capture(api)
        self.assertEqual(result["dom_status"], "unavailable_in_capture")
        api.market_book_get.assert_not_called()
        api.market_book_release.assert_not_called()
        api.shutdown.assert_called_once()

    def test_interrupt_releases(self):
        api = self.api()
        api.market_book_get.side_effect = KeyboardInterrupt()
        result, _ = self.run_capture(api)
        self.assertIn("interrupted", result["errors"])
        api.market_book_release.assert_called_once()
        api.shutdown.assert_called_once()

    def test_query_exception_and_release_failure(self):
        api = self.api()
        api.market_book_get.side_effect = RuntimeError("query failed")
        api.market_book_release.side_effect = RuntimeError("release failed")
        result, _ = self.run_capture(api, .1)
        self.assertEqual(result["failures"], 1)
        self.assertFalse(result["release_ok"])
        api.shutdown.assert_called_once()

    def test_wrong_broker_stops_before_subscription(self):
        api = self.api()
        api.account_info.return_value = SimpleNamespace(company="Other", server="Other")
        result, _ = self.run_capture(api)
        self.assertTrue(result["errors"])
        api.market_book_add.assert_not_called()


if __name__ == "__main__":
    unittest.main()

