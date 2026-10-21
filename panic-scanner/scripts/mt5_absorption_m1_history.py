"""Read-only WINZ26 M1 investigation/export for the existing absorption replay.

No trade API, EA changes, detector import or rewriting of original time_msc.
Clock conversion is allowed only from native same-session clock evidence.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path

from mt5_price_history import candle, utc


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def iso(seconds):
    return datetime.fromtimestamp(seconds, timezone.utc).isoformat(timespec="milliseconds")


def records_from_file(path):
    with path.open(encoding="utf-8-sig") as stream:
        for line in stream:
            yield json.loads(line)


def scan_capture(capture):
    path = Path(capture["source"])
    if sha(path) != capture["source_sha256"]:
        raise ValueError("Capture hash differs from replay: " + str(path))
    start = end = first_raw = last_raw = None
    session = None
    offsets = Counter()
    anchors = []
    minute_trades = defaultdict(list)
    previous_order = None
    raw_order_errors = 0
    for r in records_from_file(path):
        kind = r.get("kind")
        if kind == "session":
            session = r
        if kind not in ("book_snapshot", "snapshot", "tick_batch"):
            continue
        if "time_gmt_before" in r:
            a, b = r["time_gmt_before"], r["time_gmt_after"] + 1
            for suffix in ("before", "after"):
                offsets[r["time_gmt_" + suffix] - r["time_local_" + suffix]] += 1
        else:
            a = datetime.fromisoformat(r["captured_utc"]).timestamp()
            b = datetime.fromisoformat(r["completed_utc"]).timestamp() + .001
        start = a if start is None else min(start, a)
        end = b if end is None else max(end, b)
        if kind != "tick_batch":
            continue
        rows = [t.get("raw", t) for t in r.get("ticks", [])]
        if rows and "time_gmt_before" in r and r.get("ok") is True and r.get("rows_are_new") is True:
            if (r["time_gmt_before"] == r["time_gmt_after"] and
                    r["time_local_before"] == r["time_local_after"] == r["time_current"] == rows[-1]["time_msc"]//1000):
                anchors.append({"sequence": r["sequence"], "gmt_s": r["time_gmt_after"],
                    "local_s": r["time_local_after"], "server_s": r["time_current"],
                    "last_tick_time_msc": rows[-1]["time_msc"]})
        # Failed/provisional EA rows are not accepted as executions.
        if "ok" in r and (r["ok"] is not True or r.get("rows_are_new") is not True):
            continue
        for t in rows:
            raw = t["time_msc"]/1000
            first_raw = raw if first_raw is None else min(first_raw, raw)
            last_raw = raw if last_raw is None else max(last_raw, raw)
            if previous_order is not None and raw < previous_order:
                raw_order_errors += 1
            previous_order = raw
            if t["flags"] & 8 and math.isfinite(t["last"]) and t["last"] > 0:
                minute_trades[int(raw//60)*60].append((t["last"], t["volume_real"]))
    if start is None:
        raise ValueError("No capture observation timestamps")
    mapping = None
    # This establishes a documented clock CONVENTION, not a latency estimate.
    # Do not use median reception minus exchange time as an offset estimator.
    if session and "start_gmt_s" in session and len(offsets) == 1 and anchors:
        offset = next(iter(offsets))
        mapping = {"method": "same_session_native_server_wall_clock_equals_windows_local",
            "source_wall_to_utc_seconds": offset,
            "observed_gmt_minus_local_seconds": {str(k): v for k, v in offsets.items()},
            "native_equality_anchors": len(anchors), "first_anchor": anchors[0], "last_anchor": anchors[-1],
            "source_clock_resolution_seconds": 1,
            "latency_calibration_established": False}
    result = {"source": str(path), "sha256": capture["source_sha256"],
        "capture_start_utc": iso(start), "capture_end_utc_exclusive": iso(end),
        "capture_start_epoch_s": start, "capture_end_epoch_s": end,
        "raw_market_first_s": first_raw, "raw_market_last_s": last_raw,
        "raw_order_errors": raw_order_errors, "clock_mapping": mapping,
        "offset_counts": dict(offsets), "native_session": session is not None and "start_gmt_s" in session,
        "terminal_data_path": (session or {}).get("terminal_data_path"),
        "server": (session or {}).get("server"), "symbol": (session or {}).get("symbol", "WINZ26")}
    if sha(path) != capture["source_sha256"]:
        raise ValueError("Capture changed during inspection")
    return result, minute_trades


def query_rates(mt5, start, end):
    rows = mt5.copy_rates_range("WINZ26", mt5.TIMEFRAME_M1, utc(int(start)*1000), utc(int(end)*1000))
    return {"query_start_as_epoch_utc": iso(start), "query_end_as_epoch_utc": iso(end),
        "error": list(mt5.last_error()), "rows": None if rows is None else [candle(r) for r in rows]}


def compare_minutes(rows, trades, first_raw, last_raw):
    comparisons = []
    for row in rows or []:
        minute = row["time"]
        if first_raw is None or last_raw is None or not first_raw < minute or not minute + 60 <= last_raw:
            continue  # Do not claim full OHLC from partial boundary minutes.
        samples = trades.get(minute, [])
        if not samples:
            comparisons.append({"raw_minute": minute, "match": False, "reason": "no_captured_LAST"})
            continue
        prices = [p for p, _ in samples]
        observed = {"open": prices[0], "high": max(prices), "low": min(prices), "close": prices[-1]}
        comparisons.append({"raw_minute": minute, "captured_LAST_count": len(prices),
            "captured_ohlc": observed, "history_ohlc": {k: row[k] for k in observed},
            "match": all(row[k] == value for k, value in observed.items())})
    return comparisons


def normalize_rows(rows, offset):
    result = []
    previous = None
    for r in rows:
        if r["time"] % 60 or previous is not None and r["time"] <= previous:
            raise ValueError("M1 timestamps off boundary/out of order")
        if not all(math.isfinite(r[k]) for k in ("open", "high", "low", "close")) or not (
                0 < r["low"] <= min(r["open"], r["close"]) <= max(r["open"], r["close"]) <= r["high"]):
            raise ValueError("Invalid historical OHLC")
        previous = r["time"]
        result.append({**r, "rawTime": r["time"], "time": r["time"] + offset})
    return result


def investigate(mt5, captures):
    info = mt5.account_info()
    terminal = mt5.terminal_info()
    if info is None or terminal is None or not terminal.connected or "clear" not in (info.company + " " + info.server).lower():
        raise RuntimeError("Connected Clear terminal not verified")
    symbol = mt5.symbol_info("WINZ26")
    if symbol is None or symbol.name != "WINZ26":
        raise RuntimeError("Exact WINZ26 contract unavailable")
    if not symbol.visible and not mt5.symbol_select("WINZ26", True):
        raise RuntimeError("WINZ26 selection failed")
    results, sessions = [], []
    for capture in captures:
        r, trades = scan_capture(capture)
        start, end = r["capture_start_epoch_s"], r["capture_end_epoch_s"]
        lower, upper = int(start//60)*60, int(math.ceil(end/60))*60
        r["direct_capture_utc_query"] = query_rates(mt5, lower, upper-1)
        if r["raw_market_first_s"] is not None:
            r["raw_market_query"] = query_rates(mt5, int(r["raw_market_first_s"]//60)*60,
                int(math.ceil(r["raw_market_last_s"]/60))*60+59)
        r["exported"] = False
        mapping = r["clock_mapping"]
        if not mapping:
            r["reason"] = "No same-session native clock convention evidence; do not borrow offsets from another capture"
            results.append(r)
            continue
        if r["server"] != info.server or str(r["terminal_data_path"]).replace('\\','/').lower() != terminal.data_path.replace('\\','/').lower():
            r["reason"] = "Current terminal/server differs from capture"
            results.append(r)
            continue
        offset = mapping["source_wall_to_utc_seconds"]
        r["source_clock_query"] = query_rates(mt5, lower-offset, upper-offset-1)
        source_rows = r["source_clock_query"]["rows"]
        if not source_rows:
            r["reason"] = "M1 history unavailable in source clock interval"
            results.append(r)
            continue
        r["minute_comparisons"] = compare_minutes(source_rows, trades, r["raw_market_first_s"], r["raw_market_last_s"])
        comparisons = r["minute_comparisons"]
        if comparisons and not any(c["match"] for c in comparisons):
            r["reason"] = "No complete captured minute agrees with historical OHLC"
            results.append(r)
            continue
        normalized = normalize_rows(source_rows, offset)
        expected = set(range(lower, upper, 60))
        available = {c["time"] for c in normalized}
        r["missing_m1_utc"] = [iso(v) for v in sorted(expected-available)]
        r["matched_complete_minutes"] = sum(c["match"] for c in comparisons)
        r["mismatched_complete_minutes"] = sum(not c["match"] for c in comparisons)
        r["m1_count"] = len(normalized)
        r["exported"] = True
        r["reason"] = "Source clock convention established by native same-session records; original raw timestamps retained"
        sessions.append({"capture_sha256": capture["source_sha256"], "symbol": "WINZ26", "time_reference": "capture_utc",
            "timeframe_seconds": 60, "source": "Clear MT5 WINZ26 M1; native same-session clock convention; rawTime retained",
            "clock_mapping": mapping,
            "data_quality": {k: r[k] for k in ("missing_m1_utc", "matched_complete_minutes", "mismatched_complete_minutes")},
            "candles": normalized})
        results.append(r)
    return {"symbol": "WINZ26", "timeframe": "M1", "broker": info.company, "server": info.server,
        "terminal_data_path": terminal.data_path, "queried_at_utc": datetime.now(timezone.utc).isoformat(),
        "captures": results, "limitations": ["TimeGMT/TimeLocal have second resolution; native equality establishes timestamp convention, not source/feed latency.",
            "M1 OHLC is retrospective context, never used to change detector states or marker knowledge times.",
            "No assumption that all captures are complete; preserve detector integrity faults and missing minutes."]}, {"schema_version": 1, "sessions": sessions,
                "limitation": "Only sessions with native same-session timestamp convention evidence are exported; source feed delay remains unbounded."}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--report", type=Path, required=True)
    p.add_argument("--candles", type=Path, required=True)
    p.add_argument("--replay", type=Path, default=Path("panic-scanner/dom-captures/experimental-absorption-capture-clock.json"))
    args = p.parse_args()
    if args.report.exists() or args.candles.exists():
        raise ValueError("Refusing to overwrite output")
    import MetaTrader5 as mt5
    try:
        if not mt5.initialize(timeout=3000):
            raise RuntimeError(str(mt5.last_error()))
        report, bundle = investigate(mt5, json.loads(args.replay.read_text(encoding="utf-8"))["captures"])
    finally:
        mt5.shutdown()
    for path, value in ((args.report, report), (args.candles, bundle)):
        with path.open("x", encoding="utf-8") as out:
            json.dump(value, out, ensure_ascii=False, allow_nan=False, indent=2)
            out.write("\n")
    print(json.dumps({"exported_sessions": len(bundle["sessions"]), "candles": sum(len(s["candles"]) for s in bundle["sessions"]) }))


if __name__ == "__main__":
    main()
