"""Causal, offline absorption research. No MT5 dependency or operational integration."""
from __future__ import annotations

import argparse
from collections import Counter, deque
from copy import deepcopy
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path

from mt5_dom_persistence import timestamp
from mt5_dom_probe import analyze as validate_book
from mt5_experimental_aggressor import aggressor_side, unknown_flag_mask

OBSERVING = "OBSERVANDO"
POSSIBLE = "POSSÍVEL_ABSORÇÃO"
CONFIRMED = "REAÇÃO_CONFIRMADA"
INVALID = "INVALIDADA"
INSUFFICIENT = "DADOS_INSUFICIENTES"


@dataclass(frozen=True)
class Config:
    # Illustrative research parameters, never a universal contract threshold.
    window_ms: float = 1000
    min_aggressor_volume: float = 100
    min_dominance: float = .75
    max_adverse_points: float = 5
    dom_distance_points: float = 5
    min_dom_samples: int = 2
    min_dom_span_ms: float = 100
    max_dom_age_ms: float = 500
    max_poll_gap_ms: float = 500
    reaction_points: float = 10
    invalidation_points: float = 10
    reaction_timeout_ms: float = 2000
    # Legacy config compatibility only; neither parameter participates in capture correlation.
    market_to_local_offset_ms: float | None = None
    clock_uncertainty_ms: float | None = None

    def __post_init__(self):
        for key, value in asdict(self).items():
            if key in ("market_to_local_offset_ms", "clock_uncertainty_ms"):
                if value is not None and (not math.isfinite(value) or
                        (key == "clock_uncertainty_ms" and value < 0)):
                    raise ValueError(key)
            elif not math.isfinite(value) or value <= 0:
                raise ValueError(key)
        if not .5 < self.min_dominance <= 1:
            raise ValueError("min_dominance must be > .5 and <= 1")
        if type(self.min_dom_samples) is not int or self.min_dom_samples < 2:
            raise ValueError("min_dom_samples must be an integer >= 2")
        if (self.market_to_local_offset_ms is None) != (self.clock_uncertainty_ms is None):
            raise ValueError("offset and independently bounded uncertainty must be supplied together")


class Detector:
    """Feed records in FILE/availability order, never sort by exchange timestamp.

    Every returned transition is an immutable JSON-compatible snapshot. A batch is
    atomic: detection happens only after its entire content was made available.
    Integrity faults latch until a new Detector/session; no automatic cursor reset.
    """
    def __init__(self, config=Config()):
        self.c = config
        self.trades = deque()
        self.books = deque()
        self.events = []
        self.active = None
        self.faults = set()
        self.counts = Counter()
        self.sequence = Counter()
        self.last_poll = {}
        self.last_known = None
        self.last_market = None
        self.last_mono = None
        self.cursor = None
        self.symbol = None
        self.summary_seen = False
        self.capture_end_known = None
        self.next_window = None
        self.ea = False
        self.global_sequence = 0
        self.batch_limit = None
        self.axis_known = None
        self.capture_bounds = None
        self.observed_order = 0
        self.ea_anchor = None

    def normalize_capture(self, record):
        """Adapt actual EA fields, never reinterpret time_msc or synthesize UTC us.

        UTC fields have one-second resolution. Monotonic us measure relative
        durations within this EA only; no interpolation into a precise UTC epoch.
        Parent timer spans are not separate market observations.
        """
        kind = record.get("kind")
        if kind == "session":
            self.ea = "start_gmt_s" in record
            self.batch_limit = record.get("copy_ticks_batch_limit")
            if self.ea and type(record.get("cursor_seed_ms")) is int:
                self.cursor = {"time_msc": record["cursor_seed_ms"], "ordinal": 0}
        if not self.ea:
            return record
        if "sequence" in record and not record.get("diagnostic_only"):
            seq = record["sequence"]
            if type(seq) is not int or seq != self.global_sequence + 1:
                self.fault("sequence_gap", self.last_known)
            self.global_sequence = seq
        if kind == "summary":
            if record.get("fault") or record.get("dropped_observations", 0) or record.get("query_errors", 0) or record.get("status") == "incomplete":
                self.fault("capture_summary_errors", self.last_known)
            return record
        if kind not in ("book_snapshot", "tick_batch"):
            if record.get("ok") is False or record.get("error", 0):
                self.fault("ea_record_error", self.last_known)
            return record
        r = dict(record)
        for key in ("time_gmt_before", "time_gmt_after", "query_start_us", "query_end_us", "handler_end_us"):
            if type(r.get(key)) is not int or r[key] < 0:
                raise ValueError("missing/invalid EA capture field: " + key)
        if r["query_end_us"] < r["query_start_us"] or r["handler_end_us"] < r["query_end_us"]:
            raise ValueError("invalid EA capture order")
        if self.ea_anchor is not None:
            gmt, mono = self.ea_anchor
            if abs((r["time_gmt_before"] - gmt)*1000 - (r["query_start_us"] - mono)/1000) > 1000:
                self.fault("capture_utc_monotonic_discontinuity", self.last_known)
        self.ea_anchor = (r["time_gmt_before"], r["query_start_us"])
        r["captured_utc"] = datetime.fromtimestamp(r["time_gmt_before"], timezone.utc).isoformat()
        r["completed_utc"] = datetime.fromtimestamp(r["time_gmt_after"], timezone.utc).isoformat()
        r["monotonic_start"] = r["query_start_us"] / 1e6
        r["monotonic_end"] = r["handler_end_us"] / 1e6
        r["_axis_start_ms"] = r["query_start_us"] / 1000
        r["_axis_end_ms"] = r["handler_end_us"] / 1000
        r["_utc_resolution_ms"] = 1000
        r["_processing_ms"] = (r["handler_end_us"] - r["query_end_us"]) / 1000
        if r.get("ok") is not True or r.get("error", 0):
            self.fault("ea_query_error", self.last_known)
        if kind == "book_snapshot":
            r["kind"] = "snapshot"
            r["levels"] = r.get("raw_levels")
            if r.get("dom_market_time_msc") is not None:
                self.fault("invented_dom_market_timestamp", self.last_known)
        else:
            rows = r.get("ticks")
            if not isinstance(rows, list) or type(r.get("returned_rows")) is not int or r["returned_rows"] < len(rows):
                raise ValueError("invalid EA batch rows")
            if not isinstance(self.batch_limit, int) or self.batch_limit <= 0:
                raise ValueError("EA batch limit unavailable")
            r["drained"] = r.get("rows_are_new") is True and r.get("ok") is True and r["returned_rows"] < self.batch_limit
            expected = dict(r["cursor_before"])
            for raw in rows:
                ordinal = expected["ordinal"] + 1 if raw["time_msc"] == expected["time_msc"] else 1
                if raw.get("cursor_ordinal") != ordinal or raw["time_msc"] < expected["time_msc"]:
                    self.fault("cursor_ordinal_mismatch", self.last_known)
                expected = {"time_msc": raw["time_msc"], "ordinal": ordinal}
            if expected != r["cursor_after"]:
                self.fault("cursor_after_rows_mismatch", self.last_known)
            r["ticks"] = [{"raw": raw} for raw in rows]
        r["_source_sequence"] = r["sequence"]
        r["sequence"] = self.sequence[r["kind"]] + 1
        return r

    def transition(self, state, reason, known, market=None):
        event = self.active
        event["state"] = state
        event["reason"] = reason
        event["known_at_local_ms"] = known
        event["capture_known_utc_interval_ms"] = deepcopy(self.capture_bounds) if known is not None else None
        event["observed_order"] = self.observed_order
        event["state_history"].append({"state": state, "reason": reason,
            "known_at_local_ms": known, "market_time_msc": market,
            "capture_axis_ms": self.axis_known, "observed_order": self.observed_order,
            "capture_known_utc_interval_ms": deepcopy(self.capture_bounds) if known is not None else None})
        if state == POSSIBLE:
            event["suspected_at_market_msc"] = market
            event["suspected_at_local_ms"] = known
            event["suspected_at_capture_axis_ms"] = self.axis_known
        event["quality"]["integrity_faults"] = sorted(self.faults)
        self.events.append(deepcopy(event))
        if state in (CONFIRMED, INVALID, INSUFFICIENT):
            self.active = None

    def fault(self, reason, known):
        self.faults.add(reason)
        self.counts[reason] += 1
        self.trades.clear()
        self.books.clear()
        if self.active:
            self.transition(INSUFFICIENT, reason, known)

    def dom_evidence(self, market, price, side, known):
        c = self.c
        # Association is now local CAPTURE order, not exchange execution order.
        # All ticks share the batch availability. Never include a later book.
        books = [b for b in self.books if b["end"] <= self.axis_known and
                 b["order"] < self.observed_order and
                 self.axis_known - b["start"] <= c.max_dom_age_ms]
        evidence = []
        book_type = 2 if side == "COMPRADORA" else 1
        keys = set.intersection(*(set(b["levels"]) for b in books)) if books else set()
        for key in sorted(keys):
            kind, level_price = key
            if kind != book_type or abs(level_price - price) > c.dom_distance_points:
                continue
            quantities = [b["levels"][key] for b in books]
            span = books[-1]["start"] - books[0]["end"]
            if len(books) >= c.min_dom_samples and span >= c.min_dom_span_ms:
                evidence.append({"side": "BID" if kind == 2 else "ASK", "price": level_price,
                    "observations": [{"sequence": b["sequence"], "observed_order": b["order"],
                        "observed_start_local_ms": b["utc_start"], "known_at_local_ms": b["utc_end"],
                        "capture_known_utc_interval_ms": b["utc_bounds"],
                        "capture_start_axis_ms": b["start"], "capture_end_axis_ms": b["end"],
                        "quantity": q} for b, q in zip(books, quantities)],
                    "sampled_span_ms": span,
                    "observed_quantity_increases": sum(b > a for a, b in zip(quantities, quantities[1:])),
                    "interpretation": "sampled_presence_and_quantity_only; execution_and_replenishment_cause_unknown"})
        return evidence, None if evidence else "dom_evidence_missing_stale_or_insufficient"

    def evaluate(self, known):
        if not self.trades or self.faults:
            return
        c = self.c
        market, price, _, _, axis, capture_utc = self.trades[-1]
        while self.trades and axis - self.trades[0][4] > c.window_ms:
            self.trades.popleft()
        if self.active:
            event = self.active
            # A strictly later market event AND later availability are mandatory.
            if market <= event["suspected_at_market_msc"] or axis <= event["suspected_at_capture_axis_ms"]:
                return
            elapsed = axis - event["suspected_at_capture_axis_ms"]
            direction = 1 if event["suspected_type"] == "COMPRADORA" else -1
            move = direction * (price - event["reference_price"])
            event["reaction"] = {"market_time_msc": market, "known_at_local_ms": known,
                                 "capture_axis_ms": axis, "observed_order": self.observed_order,
                                 "capture_known_utc_interval_ms": deepcopy(self.capture_bounds),
                                 "price": price, "signed_points": move}
            if elapsed > c.reaction_timeout_ms:
                self.transition(INSUFFICIENT, "reaction_horizon_expired", known, market)
            elif move <= -c.invalidation_points:
                self.transition(INVALID, "adverse_price_break", known, market)
            elif move >= c.reaction_points:
                self.transition(CONFIRMED, "subsequent_price_reaction", known, market)
            return
        # Non-overlapping evaluation cadence avoids repeated bubbles for one burst.
        if self.next_window is None:
            self.next_window = axis + c.window_ms
        if axis < self.next_window:
            return
        self.next_window = axis + c.window_ms
        volumes = Counter()
        for _, _, volume, side, _, _ in self.trades:
            volumes[side] += volume
        total = sum(volumes.values())
        side = "SELL" if volumes["SELL"] > volumes["BUY"] else "BUY"
        if volumes[side] < c.min_aggressor_volume or volumes[side] / total < c.min_dominance:
            return
        suspected = "COMPRADORA" if side == "SELL" else "VENDEDORA"
        prices = [t[1] for t in self.trades]
        adverse = prices[0] - min(prices) if side == "SELL" else max(prices) - prices[0]
        evidence, missing = self.dom_evidence(market, price, suspected, known)
        self.active = {"event_id": 1 + len({e["event_id"] for e in self.events}),
            "symbol": self.symbol, "reference_market_time_msc": market,
            "reference_capture_utc_ms": capture_utc,
            "window_start_capture_axis_ms": self.trades[0][4], "window_end_capture_axis_ms": axis,
            "reference_price": price, "suspected_type": suspected,
            "window_start_market_msc": self.trades[0][0], "window_end_market_msc": market,
            "volume_buy": volumes["BUY"], "volume_sell": volumes["SELL"],
            "volume_ambiguous": volumes["AMBIGUOUS"], "delta": volumes["BUY"]-volumes["SELL"],
            "adverse_points": adverse, "dom_evidence": evidence, "state_history": [],
            "quality": {"clock_alignment": "common_capture_clock",
                "temporal_reference": "ea_capture" if self.ea else "python_capture",
                "utc_resolution_ms": 1000 if self.ea else 1,
                "individual_tick_receipt_available": False,
                "exchange_execution_dom_alignment_proven": False,
                "dom_continuity_between_samples_proven": False, "feed_completeness_proven": False,
                "duplicate_trades_preserved": True, "integrity_faults": [],
                "ambiguous_volume_present": bool(volumes["AMBIGUOUS"]),
                "unknown_flag_ticks_so_far": self.counts["unknown_flag_ticks"]}}
        self.transition(OBSERVING, "aggressor_threshold_reached", known, market)
        if volumes["AMBIGUOUS"]:
            self.transition(INSUFFICIENT, "ambiguous_aggressor_in_window", known, market)
        elif adverse > c.max_adverse_points:
            self.transition(INVALID, "aggression_with_price_displacement", known, market)
        elif missing:
            self.transition(INSUFFICIENT, missing, known, market)
        else:
            self.transition(POSSIBLE, "aggression_stalled_with_sampled_dom_support", known, market)

    def feed(self, record):
        before = len(self.events)
        self.observed_order += 1
        try:
            record = self.normalize_capture(record)
        except (KeyError, TypeError, ValueError, OverflowError) as exc:
            self.fault("invalid_capture_reference", self.last_known)
            return deepcopy(self.events[before:])
        kind = record.get("kind")
        if kind in ("session", "symbol_info"):
            symbol = record.get("symbol") if kind == "session" else (record.get("value") or {}).get("name")
            if self.symbol is not None:
                self.fault("session_restart_or_symbol_change", self.last_known)
            self.symbol = symbol
            if not symbol:
                self.fault("missing_symbol", self.last_known)
            return []
        if kind == "summary":
            self.summary_seen = True
            if record.get("ended_utc"):
                self.capture_end_known = timestamp(record["ended_utc"])
            if record.get("errors"):
                self.fault("capture_summary_errors", self.capture_end_known or self.last_known)
            return deepcopy(self.events[before:])
        if kind not in ("snapshot", "tick_batch"):
            if kind in ("tick_query_error", "disconnect", "cursor_error", "source_gap"):
                known = timestamp(record["completed_utc"] if "completed_utc" in record else record["captured_utc"])
                self.fault(kind, known)
                self.last_known = known
            return deepcopy(self.events[before:])
        try:
            start, known = timestamp(record["captured_utc"]), timestamp(record["completed_utc"])
            axis_start = record.get("_axis_start_ms", start)
            axis_end = record.get("_axis_end_ms", known)
            self.axis_known = axis_end
            resolution = record.get("_utc_resolution_ms", 1)
            self.capture_bounds = [known, known + resolution + record.get("_processing_ms", 0)]
            if not all(math.isfinite(v) for v in (start, known)) or known < start:
                raise ValueError("invalid time interval")
            if self.last_known is not None and (known < self.last_known if self.ea else start < self.last_known):
                self.fault("availability_order_error", self.last_known)
                return deepcopy(self.events[before:])
            self.last_known = known
            if not self.symbol or record.get("symbol", self.symbol) != self.symbol:
                self.fault("symbol_mismatch", known)
            if "monotonic_start" in record:
                begin, end = record["monotonic_start"], record["monotonic_end"]
                if not all(math.isfinite(v) for v in (begin, end)) or end < begin or (
                        self.last_mono is not None and begin < self.last_mono):
                    self.fault("monotonic_order_error", known)
                self.last_mono = end
            self.sequence[kind] += 1
            if record["sequence"] != self.sequence[kind]:
                self.fault("sequence_gap", known)
            if kind in self.last_poll and axis_start - self.last_poll[kind] > self.c.max_poll_gap_ms:
                self.fault("poll_gap", known)
            if axis_end - axis_start > self.c.max_poll_gap_ms:
                self.fault("query_latency_gap", known)
            self.last_poll[kind] = axis_start
            self.counts[kind] += 1
            if kind == "snapshot":
                levels = record.get("levels")
                valid = bool(levels) and not record.get("error") and not validate_book(levels)["anomalies"]
                if not valid or any(l["type"] not in (1, 2) for l in levels):
                    self.books.clear()
                    self.counts["unusable_dom"] += 1
                    if self.active:
                        self.transition(INSUFFICIENT, "dom_observation_interrupted", known)
                else:
                    self.books.append({"start": axis_start, "end": axis_end,
                        "utc_start": start, "utc_end": known, "utc_bounds": deepcopy(self.capture_bounds),
                        "order": self.observed_order, "sequence": record.get("_source_sequence", record["sequence"]),
                        "levels": {(l["type"], l["price"]): l["volume_dbl"] for l in levels}})
                    while self.books and axis_end - self.books[0]["start"] > self.c.max_dom_age_ms + self.c.window_ms:
                        self.books.popleft()
            else:
                # The batch is atomic: a later malformed/out-of-order trade must
                # block all decisions from this batch, including a reaction near
                # its beginning. Do not emit before validating the whole payload.
                previous_market = self.last_market
                for item in record["ticks"]:
                    raw = item["raw"]
                    aggressor_side(raw["flags"])
                    market = raw["time_msc"]
                    if type(market) is not int or market <= 0:
                        raise ValueError("invalid market time")
                    if previous_market is not None and market < previous_market:
                        self.fault("market_order_error", known)
                    previous_market = market
                    if raw["flags"] & 24 and not all(isinstance(v, (int, float)) and math.isfinite(v) and v > 0
                            for v in (raw["last"], raw["volume_real"])):
                        self.fault("invalid_trade", known)
                if self.cursor is not None and record["cursor_before"] != self.cursor:
                    self.fault("cursor_discontinuity", known)
                cursor_before, cursor_after = record["cursor_before"], record["cursor_after"]
                for cursor in (cursor_before, cursor_after):
                    if type(cursor["time_msc"]) is not int or cursor["time_msc"] <= 0 or type(cursor["ordinal"]) is not int or cursor["ordinal"] < 0:
                        raise ValueError("invalid cursor")
                if (cursor_after["time_msc"], cursor_after["ordinal"]) < (cursor_before["time_msc"], cursor_before["ordinal"]):
                    self.fault("cursor_regression", known)
                self.cursor = cursor_after
                if record.get("drained") is not True or record.get("error"):
                    self.fault("batch_completeness_unknown", known)
                for item in record["ticks"]:
                    raw = item["raw"]
                    flags = raw["flags"]
                    side = aggressor_side(flags)  # Never trust a legacy stored side.
                    if unknown_flag_mask(flags):
                        self.counts["unknown_flag_ticks"] += 1
                    market = raw["time_msc"]
                    if type(market) is not int or market <= 0:
                        raise ValueError("invalid market time")
                    if self.last_market is not None and market < self.last_market:
                        self.fault("market_order_error", known)
                    self.last_market = market
                    if not flags & 24:
                        self.counts["quotes"] += 1
                        continue
                    price, volume = raw["last"], raw["volume_real"]
                    if not all(isinstance(v, (int, float)) and math.isfinite(v) and v > 0 for v in (price, volume)):
                        self.fault("invalid_trade", known)
                        continue
                    self.counts["trades"] += 1
                    self.counts[side] += 1
                    if not self.faults:
                        self.trades.append((market, price, volume, side, axis_end, known))
                    # Check every subsequent trade, including intrabatch breaks;
                    # never allow the last trade to hide an earlier invalidation.
                    if self.active:
                        self.evaluate(known)
                self.evaluate(known)
            if self.active and axis_end - self.active["suspected_at_capture_axis_ms"] > self.c.reaction_timeout_ms:
                self.transition(INSUFFICIENT, "reaction_availability_timeout", known)
        except (KeyError, TypeError, ValueError, OverflowError) as exc:
            self.fault("malformed_record", self.last_known)
            self.counts["malformed_details:" + str(exc)] += 1
        return deepcopy(self.events[before:])

    def finish(self):
        if self.active:
            self.active["capture_boundary"] = {"last_observation_local_ms": self.last_known,
                "timestamp_basis": "summary_ended_utc" if self.capture_end_known is not None else
                    "offline_eof_without_capture_timestamp"}
            self.transition(INSUFFICIENT, "capture_end_before_reaction", self.capture_end_known)
        latest = {e["event_id"]: e for e in self.events}
        return {"config": asdict(self.c), "transitions": deepcopy(self.events),
            "temporal_reference": {"basis": "ea_capture" if self.ea else "python_capture",
                "utc_resolution_ms": 1000 if self.ea else 1,
                "duration_clock": "ea_monotonic_us" if self.ea else "capture_utc_ms",
                "tick_receipt_resolution": "batch_only_no_individual_receipt_timestamp",
                "legacy_market_offset_parameters_used": False,
                "market_time_msc_preserved": True,
                "limitation": "capture_order_is_not_exchange_execution_order; delivery_delay_unbounded"},
            "summary": {"candidates": len(latest),
                "possible_absorption_events": len({e["event_id"] for e in self.events if e["state"] == POSSIBLE}),
                "final_states": dict(Counter(e["state"] for e in latest.values())),
                "inconclusive_reasons": dict(Counter(e["reason"] for e in latest.values() if e["state"] == INSUFFICIENT)),
                "integrity_faults": sorted(self.faults), "counts": dict(self.counts),
                "capture_summary_present": self.summary_seen,
                "missing_inputs": [name for name in ("tick_batch", "snapshot") if not self.counts[name]],
                "capture_end_completeness": "summary_present_not_feed_certification" if self.summary_seen else "unverified_truncated_or_no_summary"}}


def replay(path, config=Config()):
    path = Path(path)
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    detector = Detector(config)
    with path.open(encoding="utf-8-sig") as stream:
        for line in stream:
            try:
                detector.feed(json.loads(line))
            except (ValueError, TypeError, AttributeError):
                detector.fault("malformed_json_or_record", detector.last_known)
    result = detector.finish()
    result.update(source=str(path), source_sha256=digest)
    if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
        raise RuntimeError("source changed during replay")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("captures", nargs="+", type=Path)
    parser.add_argument("--config", type=Path, help="JSON Config; capture correlation preserves raw market timestamps")
    parser.add_argument("--output", type=Path, help="New JSON file; refuses overwrite")
    args = parser.parse_args()
    config = Config(**json.loads(args.config.read_text(encoding="utf-8-sig"))) if args.config else Config()
    result = {"schema_version": 2, "purpose": "experimental_only_no_order_recommendations",
              "captures": [replay(path, config) for path in args.captures]}
    encoded = json.dumps(result, ensure_ascii=False, allow_nan=False, indent=2)
    if args.output:
        with args.output.open("x", encoding="utf-8") as out:
            out.write(encoded + "\n")
    else:
        print(encoded)


if __name__ == "__main__":
    main()
