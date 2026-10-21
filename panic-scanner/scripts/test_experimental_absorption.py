import json
from copy import deepcopy
from dataclasses import replace
from datetime import datetime, timedelta, timezone
import unittest
from unittest.mock import patch

from experimental_absorption import (Config, Detector, POSSIBLE, CONFIRMED,
                                     INVALID, INSUFFICIENT, timestamp)


BASE = 1700000000000


def utc(ms):
    return datetime.fromtimestamp((BASE + ms) / 1000, timezone.utc).isoformat(timespec="milliseconds")


def book(seq, ms, quantity=20, empty=False):
    return {"kind": "snapshot", "sequence": seq, "symbol": "WINZ26",
        "captured_utc": utc(ms), "completed_utc": utc(ms + 1),
        "levels": [] if empty else [
            {"type": 1, "price": 105, "volume": quantity, "volume_dbl": quantity},
            {"type": 2, "price": 100, "volume": quantity, "volume_dbl": quantity}]}


def batch(seq, ms, trades, previous=None):
    # Each tuple is (market offset, price, flags, quantity).
    cursor = {"time_msc": BASE + ms, "ordinal": len(trades)}
    return {"kind": "tick_batch", "sequence": seq, "symbol": "WINZ26",
        "captured_utc": utc(ms), "completed_utc": utc(ms + 1), "drained": True,
        "cursor_before": deepcopy(previous) if previous else {"time_msc": BASE, "ordinal": 0},
        "cursor_after": cursor,
        "ticks": [{"raw": {"time_msc": BASE+t, "last": p, "flags": f,
                            "volume_real": v}, "side": "WRONG"} for t, p, f, v in trades]}


class AbsorptionTest(unittest.TestCase):
    def config(self, **changes):
        return replace(Config(window_ms=100, min_aggressor_volume=10,
            min_dom_span_ms=20, max_dom_age_ms=500, max_poll_gap_ms=500,
            market_to_local_offset_ms=0, clock_uncertainty_ms=0), **changes)

    def records(self, sell=True):
        price, flags = (100, 88) if sell else (105, 56)
        first = batch(1, 30, [(30, price, flags, 5)])
        second = batch(2, 130, [(130, price, flags, 10)], first["cursor_after"])
        return [{"kind": "session", "symbol": "WINZ26"}, book(1, 0), first,
                book(2, 70, 25), second]

    def run_records(self, records, config=None):
        d = Detector(config or self.config())
        for record in records:
            d.feed(record)
        return d

    def test_utc_and_brasilia_offsets_represent_identical_instants(self):
        # Explicit offsets avoid OS timezone/tzdata dependencies. Sao Paulo is
        # UTC-03 on these dates; source epoch milliseconds have no display zone.
        pairs = [
            ("2026-10-15T20:47:15.280+00:00", "2026-10-15T17:47:15.280-03:00"),
            ("2026-10-16T00:00:00.001Z", "2026-10-15T21:00:00.001-03:00"),
            ("2026-10-16T02:59:59.999+00:00", "2026-10-15T23:59:59.999-03:00"),
            ("2026-10-16T03:00:00.001+00:00", "2026-10-16T00:00:00.001-03:00"),
        ]
        for utc_text, brasilia_text in pairs:
            with self.subTest(utc=utc_text):
                self.assertEqual(timestamp(utc_text), timestamp(brasilia_text))
        with self.assertRaises(ValueError):
            timestamp("2026-10-15T17:47:15.280")

    def assert_timezone_equivalent_replay(self, records):
        original = deepcopy(records)
        expected = self.run_records(records)
        brasilia = timezone(timedelta(hours=-3))
        converted = deepcopy(records)
        for i, record in enumerate(converted):
            for key in ("captured_utc", "completed_utc"):
                if key in record and (key == "completed_utc" or i % 2):
                    record[key] = datetime.fromisoformat(record[key]).astimezone(brasilia).isoformat(timespec="milliseconds")
        actual = self.run_records(converted)
        self.assertFalse(actual.faults)
        self.assertEqual(actual.events, expected.events)
        self.assertEqual(actual.events[-1]["state"], POSSIBLE)
        self.assertEqual(actual.events[-1]["reference_market_time_msc"],
                         records[-1]["ticks"][-1]["raw"]["time_msc"])
        self.assertEqual(records, original)

    def test_detector_utc_brasilia_representations_do_not_shift_market_epoch(self):
        self.assert_timezone_equivalent_replay(self.records())

    def test_timezone_normalization_across_utc_midnight(self):
        base = int(datetime(2026, 10, 15, 23, 59, 59, 950000, tzinfo=timezone.utc).timestamp()*1000)
        with patch(__name__ + ".BASE", base):
            records = self.records()
        self.assertNotEqual(records[1]["captured_utc"][:10], records[-1]["captured_utc"][:10])
        self.assert_timezone_equivalent_replay(records)

    def test_timezone_normalization_across_brasilia_midnight(self):
        base = int(datetime(2026, 10, 16, 2, 59, 59, 950000, tzinfo=timezone.utc).timestamp()*1000)
        with patch(__name__ + ".BASE", base):
            records = self.records()
        brasilia = timezone(timedelta(hours=-3))
        dates = [datetime.fromisoformat(r["captured_utc"]).astimezone(brasilia).date()
                 for r in records if "captured_utc" in r]
        self.assertNotEqual(dates[0], dates[-1])
        self.assert_timezone_equivalent_replay(records)

    def test_both_sides_and_separate_reaction(self):
        for sell in (True, False):
            records = self.records(sell)
            d = self.run_records(records)
            self.assertEqual(d.events[-1]["state"], POSSIBLE)
            detection = json.dumps(d.events[-1], sort_keys=True)
            self.assertEqual(d.events[-1]["dom_evidence"][0]["observed_quantity_increases"], 1)
            reaction = batch(3, 150, [(150, 110 if sell else 95, 56, 1)], records[-1]["cursor_after"])
            d.feed(reaction)
            self.assertEqual(d.events[-1]["state"], CONFIRMED)
            self.assertEqual(json.dumps(d.events[-2], sort_keys=True), detection)
            self.assertGreater(d.events[-1]["reaction"]["known_at_local_ms"],
                               d.events[-1]["suspected_at_local_ms"])

    def test_false_positive_displacement(self):
        records = self.records()
        records[2]["ticks"][0]["raw"]["last"] = 115
        self.assertEqual(self.run_records(records).events[-1]["state"], INVALID)

    def test_absent_empty_and_stale_dom(self):
        for mode in ("absent", "empty", "stale"):
            records = self.records()
            config = self.config()
            if mode == "absent":
                records = [r for r in records if r["kind"] != "snapshot"]
            elif mode == "empty":
                records[3] = book(2, 70, empty=True)
            else:
                config = self.config(max_dom_age_ms=10)
            d = self.run_records(records, config)
            self.assertEqual(d.events[-1]["state"], INSUFFICIENT)
            self.assertFalse(any(e["state"] == POSSIBLE for e in d.events))

    def test_capture_reference_needs_no_market_clock_offset_and_rejects_ambiguous_aggressor(self):
        d = self.run_records(self.records(), self.config(
            market_to_local_offset_ms=None, clock_uncertainty_ms=None))
        self.assertEqual(d.events[-1]["state"], POSSIBLE)
        self.assertEqual(d.events[-1]["quality"]["clock_alignment"], "common_capture_clock")
        records = self.records()
        records[2]["ticks"][0]["raw"]["flags"] = 120
        records[2]["ticks"][0]["raw"]["volume_real"] = 1
        self.assertEqual(self.run_records(records).events[-1]["reason"], "ambiguous_aggressor_in_window")

    def test_duplicates_preserved_and_legacy_side_ignored(self):
        records = self.records()
        raw = records[-1]["ticks"][0]
        records[-1]["ticks"] = [raw, raw.copy()]
        d = self.run_records(records)
        self.assertEqual(d.events[-1]["volume_sell"], 25)
        self.assertEqual(d.counts["trades"], 3)

    def test_disconnect_and_cursor_fault_latch(self):
        for kind in ("disconnect", "cursor_error", "tick_query_error", "source_gap"):
            records = self.records()
            records.insert(3, {"kind": kind, "captured_utc": utc(50)})
            d = self.run_records(records)
            self.assertIn(kind, d.faults)
            self.assertFalse(any(e["state"] == POSSIBLE for e in d.events))
        records = self.records()
        records[-1]["cursor_before"]["ordinal"] = 999
        self.assertIn("cursor_discontinuity", self.run_records(records).faults)

    def test_incomplete_batch_sequence_gap_invalid_trade_and_poll_gap(self):
        for change, expected in (("drained", "batch_completeness_unknown"),
                                 ("sequence", "sequence_gap"),
                                 ("volume", "invalid_trade"), ("gap", "poll_gap")):
            records = self.records()
            if change == "drained": records[-1]["drained"] = False
            if change == "sequence": records[-1]["sequence"] = 3
            if change == "volume": records[-1]["ticks"][0]["raw"]["volume_real"] = 0
            if change == "gap":
                records[-1]["captured_utc"] = utc(1000)
                records[-1]["completed_utc"] = utc(1001)
            d = self.run_records(records)
            self.assertIn(expected, d.faults)
            self.assertFalse(any(e["state"] == POSSIBLE for e in d.events))

    def test_every_prefix_has_identical_emissions_and_no_future_evidence(self):
        records = self.records()
        records += [batch(3, 150, [(150, 110, 56, 1)], records[-1]["cursor_after"]), book(3, 180)]
        full = self.run_records(records)
        for n in range(1, len(records)+1):
            prefix = self.run_records(records[:n])
            self.assertEqual(prefix.events, full.events[:len(prefix.events)])
        for event in full.events:
            self.assertLessEqual(event["window_end_market_msc"], event["reference_market_time_msc"])
            for level in event["dom_evidence"]:
                for observation in level["observations"]:
                    self.assertLessEqual(observation["known_at_local_ms"], event["known_at_local_ms"])

    def test_batch_cannot_confirm_its_own_detection(self):
        records = self.records()
        records[-1]["ticks"].append({"raw": {"time_msc": BASE+140,
            "last": 110, "flags": 56, "volume_real": 1}})
        d = self.run_records(records)
        self.assertFalse(any(e["state"] == CONFIRMED for e in d.events))

    def test_intrabatch_break_cannot_be_hidden_by_last_trade(self):
        records = self.records()
        d = self.run_records(records)
        d.feed(batch(3, 160, [(150, 90, 88, 1), (160, 110, 56, 1)], records[-1]["cursor_after"]))
        self.assertEqual(d.events[-1]["state"], INVALID)

    def test_capture_end_and_timeout_are_inconclusive(self):
        d = self.run_records(self.records())
        self.assertEqual(d.finish()["summary"]["inconclusive_reasons"], {"capture_end_before_reaction": 1})
        records = self.records()
        d = self.run_records(records, self.config(reaction_timeout_ms=10))
        d.feed(batch(3, 150, [(150, 110, 56, 1)], records[-1]["cursor_after"]))
        self.assertEqual(d.events[-1]["reason"], "reaction_horizon_expired")

    def test_no_new_dom_is_not_persistence_and_legacy_offset_has_no_effect(self):
        records = self.records()
        records.pop(3)
        self.assertEqual(self.run_records(records).events[-1]["state"], INSUFFICIENT)
        d = self.run_records(self.records(), self.config(clock_uncertainty_ms=100))
        self.assertEqual(d.events, self.run_records(self.records()).events)

    def ea_records(self, sources=None):
        result = [{"kind": "session", "symbol": "WINZ26", "start_gmt_s": BASE//1000,
                   "cursor_seed_ms": BASE, "copy_ticks_batch_limit": 2048}]
        for sequence, source in enumerate((sources or self.records())[1:], 1):
            r = deepcopy(source)
            start = timestamp(r.pop("captured_utc"))
            end = timestamp(r.pop("completed_utc"))
            r.update(sequence=sequence, time_gmt_before=int(start//1000), time_gmt_after=int(end//1000),
                time_local_before=int(start//1000)-10800, time_local_after=int(end//1000)-10800,
                query_start_us=int((start-BASE+1000)*1000), query_end_us=int((end-BASE+1000)*1000),
                handler_start_us=int((start-BASE+1000)*1000), handler_end_us=int((end-BASE+1000)*1000),
                ok=True, error=0)
            if r["kind"] == "snapshot":
                r["kind"] = "book_snapshot"
                r["raw_levels"] = r.pop("levels")
                r["dom_market_time_msc"] = None
            else:
                r.pop("drained")
                r["ticks"] = [t["raw"] for t in r["ticks"]]
                previous = r["cursor_before"]
                for raw in r["ticks"]:
                    raw["cursor_ordinal"] = previous["ordinal"]+1 if raw["time_msc"] == previous["time_msc"] else 1
                    previous = {"time_msc": raw["time_msc"], "ordinal": raw["cursor_ordinal"]}
                r.update(rows_are_new=True, returned_rows=len(r["ticks"]))
            result.append(r)
        return result

    def test_real_ea_schema_uses_batch_capture_and_preserves_second_resolution(self):
        records = self.ea_records()
        d = self.run_records(records)
        self.assertFalse(d.faults)
        self.assertEqual(d.events[-1]["state"], POSSIBLE)
        e = d.events[-1]
        self.assertEqual(e["quality"]["temporal_reference"], "ea_capture")
        self.assertFalse(e["quality"]["individual_tick_receipt_available"])
        self.assertEqual(e["capture_known_utc_interval_ms"], [BASE, BASE+1000])
        self.assertEqual(e["reference_market_time_msc"], records[-1]["ticks"][-1]["time_msc"])
        self.assertEqual(e["reference_capture_utc_ms"], BASE)
        # Separate records inside the SAME UTC second are ordered by EA sequence
        # and measured with actual monotonic fields, not invented UTC fractions.
        for n in range(1, len(records)+1):
            prefix = self.run_records(records[:n])
            self.assertEqual(prefix.events, d.events[:len(prefix.events)])

    def test_market_epoch_three_hours_apart_does_not_affect_capture_classification(self):
        records = self.ea_records()
        original = self.run_records(records)
        shifted = deepcopy(records)
        shifted[0]["cursor_seed_ms"] -= 10800000
        for r in shifted[1:]:
            if r["kind"] == "tick_batch":
                for key in ("cursor_before", "cursor_after"):
                    r[key]["time_msc"] -= 10800000
                for raw in r["ticks"]:
                    raw["time_msc"] -= 10800000
        actual = self.run_records(shifted)
        self.assertFalse(actual.faults)
        self.assertEqual(actual.events[-1]["state"], original.events[-1]["state"])
        self.assertEqual(actual.events[-1]["dom_evidence"], original.events[-1]["dom_evidence"])
        self.assertEqual(actual.events[-1]["reference_market_time_msc"], BASE+130-10800000)
        self.assertEqual(actual.events[-1]["reference_capture_utc_ms"], BASE)

    def test_ea_missing_capture_global_sequence_and_bad_ordinal_block_signals(self):
        for problem in ("missing_time", "sequence", "ordinal", "incomplete", "clock_jump"):
            records = self.ea_records()
            if problem == "missing_time": records[-1].pop("time_gmt_after")
            if problem == "sequence": records[-1]["sequence"] += 1
            if problem == "ordinal": records[-1]["ticks"][0]["cursor_ordinal"] = 42
            if problem == "incomplete": records[-1]["rows_are_new"] = False
            if problem == "clock_jump": records[-1]["time_gmt_before"] += 10; records[-1]["time_gmt_after"] += 10
            d = self.run_records(records)
            self.assertTrue(d.faults, problem)
            self.assertFalse(any(e["state"] == POSSIBLE for e in d.events), problem)

    def test_python_batch_capture_changes_no_raw_market_timestamps(self):
        records = self.records()
        for r in records:
            if r["kind"] == "tick_batch":
                for key in ("cursor_before", "cursor_after"):
                    r[key]["time_msc"] -= 10800000
                for t in r["ticks"]:
                    t["raw"]["time_msc"] -= 10800000
        d = self.run_records(records, Config(window_ms=100, min_aggressor_volume=10, min_dom_span_ms=20))
        self.assertEqual(d.events[-1]["state"], POSSIBLE)
        self.assertEqual(d.events[-1]["reference_market_time_msc"], BASE+130-10800000)

    def test_atomic_batch_invalid_tail_cannot_emit_reaction(self):
        records = self.records()
        d = self.run_records(records)
        d.feed(batch(3, 160, [(150, 110, 56, 1), (160, 110, 56, 0)], records[-1]["cursor_after"]))
        self.assertIn("invalid_trade", d.faults)
        self.assertFalse(any(e["state"] == CONFIRMED for e in d.events))
        self.assertEqual(d.events[-1]["state"], INSUFFICIENT)

    def test_ea_subsequent_batch_can_confirm_inside_same_utc_second(self):
        sources = self.records()
        sources.append(batch(3, 150, [(150, 110, 56, 1)], sources[-1]["cursor_after"]))
        records = self.ea_records(sources)
        d = self.run_records(records)
        self.assertFalse(d.faults)
        self.assertEqual(d.events[-1]["state"], CONFIRMED)
        self.assertEqual(d.events[-1]["known_at_local_ms"], d.events[-2]["known_at_local_ms"])
        self.assertGreater(d.events[-1]["reaction"]["capture_axis_ms"],
                           d.events[-2]["suspected_at_capture_axis_ms"])
        for n in range(1, len(records)+1):
            prefix = self.run_records(records[:n])
            self.assertEqual(prefix.events, d.events[:len(prefix.events)])

    def test_ea_capture_reference_across_utc_date_boundary(self):
        base = int(datetime(2026, 10, 15, 23, 59, 59, 950000, tzinfo=timezone.utc).timestamp()*1000)
        with patch(__name__ + ".BASE", base):
            records = self.ea_records()
        d = self.run_records(records)
        self.assertFalse(d.faults)
        self.assertEqual(d.events[-1]["state"], POSSIBLE)
        self.assertEqual(d.events[-1]["reference_market_time_msc"], base+130)

    def test_configuration_validation(self):
        for args in ({"window_ms": 0}, {"min_dominance": .5},
                     {"min_dom_samples": 1}, {"reaction_points": float("nan")},
                     {"market_to_local_offset_ms": 0}):
            with self.assertRaises(ValueError): Config(**args)


if __name__ == "__main__":
    unittest.main()
