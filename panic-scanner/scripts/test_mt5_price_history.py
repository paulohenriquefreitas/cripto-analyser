import unittest
from mt5_price_history import export, utc, M5_MSC


def rate(time):
    return dict(time=time, open=100, high=110, low=90, close=100, tick_volume=10, real_volume=100)


def tick(time, last=100, flags=8):
    return dict(time=time // 1000, time_msc=time, last=last, flags=flags)


class FakeMt5:
    TIMEFRAME_M5 = 5
    COPY_TICKS_ALL = -1

    def __init__(self):
        self.calls = []
        self.warmup = [rate(i * 300) for i in range(20)]
        self.official = [rate(6000), rate(6300)]
        self.rows = [tick(6_000_000, 100), tick(6_000_000, 105), tick(6_000_000, 105),
                     tick(6_299_999, 106, 2), tick(6_300_000, 110), tick(6_600_000, 115)]

    def symbol_select(self, symbol, selected): return True
    def last_error(self): return "test-error"
    def copy_rates_from(self, *args): return self.warmup
    def copy_rates_range(self, *args): return self.official

    def copy_ticks_range(self, symbol, start, end, flags):
        self.calls.append((symbol, start, end, flags))
        # Simulate API overfetch: exporter owns precise inclusive/exclusive filtering.
        return self.rows


class PriceHistoryTest(unittest.TestCase):
    def test_chunk_boundaries_duplicates_flags_and_trailer(self):
        mt5 = FakeMt5()
        result = []
        export(mt5, "WINV26", 6_000_000, 6_600_000, result.append)
        self.assertEqual("header", result[0]["type"])
        rows = result[1:-1]
        self.assertEqual([6_000_000, 6_000_000, 6_000_000, 6_299_999, 6_300_000], [r["timeMsc"] for r in rows])
        self.assertEqual(2, rows[3]["flags"])  # selection belongs to the Java adapter
        self.assertEqual({"type": "end", "rows": 5}, result[-1])
        self.assertEqual(2, len(mt5.calls))
        self.assertEqual(utc(6_300_000), mt5.calls[0][2])
        self.assertEqual(utc(6_300_000), mt5.calls[1][1])
        self.assertEqual(0, utc(6_000_000).utcoffset().total_seconds())

    def test_rejects_non_aligned_or_empty_intervals(self):
        for start, end in [(1, M5_MSC), (0, 1), (M5_MSC, 0), (0, 0), (-M5_MSC, 0)]:
            with self.assertRaises(ValueError): export(FakeMt5(), "WINV26", start, end, lambda r: None)

    def test_bridge_truncation_does_not_drop_last_second_or_duplicate_next_boundary(self):
        mt5 = FakeMt5()
        rows = [tick(6_299_001), tick(6_299_999), tick(6_300_000), tick(6_599_999), tick(6_600_000)]
        mt5.copy_ticks_range = lambda symbol, start, end, flags: [
            r for r in rows if int(start.timestamp()) * 1000 <= r["time_msc"] <= int(end.timestamp()) * 1000]
        result = []
        export(mt5, "WINV26", 6_000_000, 6_600_000, result.append)
        self.assertEqual([6_299_001, 6_299_999, 6_300_000, 6_599_999],
                         [r["timeMsc"] for r in result if r["type"] == "tick"])

    def test_missing_rates_fails_before_header(self):
        for attr in ("warmup", "official"):
            mt5 = FakeMt5()
            setattr(mt5, attr, None)
            result = []
            with self.assertRaises(RuntimeError): export(mt5, "WINV26", 6_000_000, 6_600_000, result.append)
            self.assertEqual([], result)

    def test_decreasing_raw_timestamp_fails_without_trailer(self):
        mt5 = FakeMt5()
        mt5.rows = [tick(6_000_001), tick(6_000_000)]
        result = []
        with self.assertRaises(ValueError): export(mt5, "WINV26", 6_000_000, 6_600_000, result.append)
        self.assertNotEqual("end", result[-1]["type"])

    def test_inconsistent_seconds_are_rejected(self):
        mt5 = FakeMt5()
        mt5.rows[0]["time"] = 1
        with self.assertRaises(ValueError): export(mt5, "WINV26", 6_000_000, 6_600_000, lambda r: None)

    def test_empty_chunk_is_valid_but_api_error_is_not(self):
        mt5 = FakeMt5()
        mt5.rows = []
        result = []
        export(mt5, "WINV26", 6_000_000, 6_600_000, result.append)
        self.assertEqual({"type": "end", "rows": 0}, result[-1])
        mt5.copy_ticks_range = lambda *args: None
        with self.assertRaises(RuntimeError): export(mt5, "WINV26", 6_000_000, 6_600_000, lambda r: None)


if __name__ == "__main__": unittest.main()
