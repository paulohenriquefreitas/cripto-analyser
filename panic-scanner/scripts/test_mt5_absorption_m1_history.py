from copy import deepcopy
import hashlib
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from mt5_absorption_m1_history import scan_capture, normalize_rows, compare_minutes


class M1HistoryTest(unittest.TestCase):
    def row(self, time=1792171440):
        return {"time": time, "open": 100, "high": 110, "low": 90, "close": 105,
                "tickVolume": 4, "realVolume": 10}

    def scan(self, records):
        with TemporaryDirectory() as directory:
            path = Path(directory)/"capture.ndjson"
            text = ''.join(json.dumps(r)+'\n' for r in records)
            path.write_text(text, encoding="utf-8")
            return scan_capture({"source": str(path), "source_sha256": hashlib.sha256(path.read_bytes()).hexdigest()})

    def native(self):
        t = 1792171440
        return [{"kind": "session", "symbol": "WINZ26", "start_gmt_s": t+10800},
            {"kind": "tick_batch", "sequence": 1, "time_local_before": t, "time_local_after": t,
             "time_gmt_before": t+10800, "time_gmt_after": t+10800,
             "time_current": t, "ok": True, "rows_are_new": True,
             "ticks": [{"time_msc": t*1000+100, "flags": 56, "last": 100, "volume_real": 2}]}]

    def test_same_session_native_clock_equality_is_required_not_reception_median(self):
        records = self.native()
        result, _ = self.scan(records)
        self.assertEqual(result["clock_mapping"]["source_wall_to_utc_seconds"], 10800)
        self.assertFalse(result["clock_mapping"]["latency_calibration_established"])
        records[-1]["time_current"] -= 1
        result, _ = self.scan(records)
        self.assertIsNone(result["clock_mapping"])

    def test_inconsistent_offsets_and_failed_rows_cannot_establish_mapping(self):
        records = self.native()
        other = deepcopy(records[-1]); other["time_local_after"] -= 3600
        records.append(other)
        self.assertIsNone(self.scan(records)[0]["clock_mapping"])
        records = self.native(); records[-1]["ok"] = False
        result, trades = self.scan(records)
        self.assertIsNone(result["clock_mapping"])
        self.assertFalse(trades)

    def test_normalization_preserves_raw_time_and_prices_including_date_boundary(self):
        from datetime import datetime, timezone
        raw = int(datetime(2026,10,16,23,59,tzinfo=timezone.utc).timestamp())
        row = self.row(raw)
        normalized = normalize_rows([row], 10800)[0]
        self.assertEqual(normalized["rawTime"], raw)
        self.assertEqual(normalized["time"], raw+10800)
        self.assertEqual(datetime.fromtimestamp(normalized["time"],timezone.utc).day, 17)
        for key in ("open","high","low","close","realVolume"):
            self.assertEqual(normalized[key], row[key])
        self.assertNotIn("rawTime", row)

    def test_partial_boundary_minutes_are_not_claimed_as_complete_and_mismatches_are_preserved(self):
        row = self.row()
        trades = {row["time"]: [(100,1),(110,1),(90,1),(105,1)]}
        self.assertTrue(compare_minutes([row],trades,row["time"]-1,row["time"]+60)[0]["match"])
        self.assertEqual(compare_minutes([row],trades,row["time"]+.1,row["time"]+60), [])
        row["open"] = 101
        comparison = compare_minutes([row],trades,row["time"]-1,row["time"]+60)[0]
        self.assertFalse(comparison["match"])
        self.assertEqual(comparison["history_ohlc"]["open"],101)

    def test_invalid_history_and_capture_hash_fail_closed(self):
        for rows in ([self.row(),self.row()], [self.row(1792171441)], [{**self.row(),"low":111}]):
            with self.assertRaises(ValueError): normalize_rows(rows,10800)
        with TemporaryDirectory() as directory:
            path=Path(directory)/'capture.ndjson';path.write_text('{}\n',encoding='utf-8')
            with self.assertRaises(ValueError):scan_capture({"source":str(path),"source_sha256":"wrong"})


if __name__ == '__main__':
    unittest.main()
