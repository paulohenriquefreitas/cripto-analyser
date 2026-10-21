"""Manual 30-second WINZ26 DOM + T&T feasibility probe, one serial MT5 session."""
from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import asdict
import json
import math
from pathlib import Path
import sys
import time

from mt5_canonical_price_stream import Cursor, Counters
from mt5_capture_validation import CaptureQuery
from mt5_dom_probe import FIELDS, SYMBOL, analyze, finite_positive, json_safe, utc_now, write_record
from mt5_ticks_diagnostic import flag_definitions, is_trade
from mt5_experimental_aggressor import aggressor_side, unknown_flag_mask


def raw_tick(row):
    if hasattr(row, "dtype") and row.dtype.names:
        return {name: row[name].item() for name in row.dtype.names}
    if hasattr(row, "_asdict"):
        return dict(row._asdict())
    return dict(row)


def run(mt5, out, seconds=30, clock=time.monotonic, sleep=time.sleep, on_record=None,
        should_stop=None):
    # Synchronous delivery precedes serialization/flush; legacy output is unchanged.
    def emit(out, record):
        if on_record is not None:
            on_record(record)
        if out is not None:
            write_record(out, record)

    if not math.isfinite(seconds) or seconds <= 0:
        raise ValueError("seconds deve ser positivo e finito")
    summary = {"kind": "summary", "symbol": SYMBOL, "started_utc": utc_now(),
               "seconds_requested": seconds, "dom_queries": 0, "dom_nonempty": 0,
               "dom_empty": 0, "dom_failures": 0, "tick_queries": 0, "ticks": 0,
               "trade_ticks": 0, "valid_trade_ticks": 0, "invalid_trade_ticks": 0,
               "quote_ticks": 0, "valid_trades_after_initial_tick": 0, "trade_sides": Counter(), "raw_flags": Counter(),
               "unknown_flag_masks": Counter(), "ticks_with_unknown_flags": 0,
               "first_tick_time_msc": None, "last_tick_time_msc": None,
               "first_trade_time_msc": None, "last_trade_time_msc": None,
               "errors": [], "release_ok": None}
    counters = Counters()
    validated_query = CaptureQuery(mt5, clock=clock)
    subscribed = False
    latencies = {"dom": [], "ticks": []}
    lateness = {"dom": [], "ticks": []}
    last_starts = {}
    intervals = {"dom": [], "ticks": []}
    cursor = None
    started = clock()
    try:
        if not mt5.initialize():
            raise RuntimeError(f"initialize: {mt5.last_error()}")
        account = mt5.account_info()
        if account is None:
            raise RuntimeError(f"account_info: {mt5.last_error()}")
        if "clear" not in (account.company + " " + account.server).lower():
            raise RuntimeError("Terminal nao identificado como Clear")
        info = mt5.symbol_info(SYMBOL)
        if info is None:
            raise RuntimeError(f"symbol_info: {mt5.last_error()}")
        if not mt5.symbol_select(SYMBOL, True):
            raise RuntimeError(f"symbol_select: {mt5.last_error()}")
        tick = mt5.symbol_info_tick(SYMBOL)
        emit(out, {"kind": "session", "symbol": SYMBOL, "company": account.company,
                           "server": account.server, "symbol_info": info, "initial_tick": tick,
                           "captured_utc": utc_now(), "dom_interval_ms": 100, "tick_interval_ms": 20})
        if tick is None or int(tick.time_msc) <= 0:
            raise RuntimeError("Tick inicial sem time_msc valido; impossivel estabelecer cursor de mercado")
        # Inclusive seed: first market millisecond is retained, not assumed already consumed.
        cursor = Cursor(int(tick.time_msc), 0)
        summary["initial_tick_time_msc"] = cursor.time_msc
        definitions = flag_definitions(mt5)
        emit(out, {"kind": "flag_definitions", "value": definitions})
        subscribed = bool(mt5.market_book_add(SYMBOL))
        if not subscribed:
            raise RuntimeError(f"market_book_add: {mt5.last_error()}")
        capture_start = clock()
        deadline = capture_start + seconds
        due = {"dom": capture_start, "ticks": capture_start}
        while clock() < deadline:
            if should_stop is not None and should_stop():
                break
            # At most one query of each kind per pass: backlog cannot starve DOM.
            for kind in ("dom", "ticks"):
                if should_stop is not None and should_stop():
                    break
                begin = clock()
                if begin >= deadline or begin < due[kind]:
                    continue
                captured = utc_now()
                lateness[kind].append(max(0, (begin - due[kind]) * 1000))
                if kind in last_starts:
                    intervals[kind].append((begin - last_starts[kind]) * 1000)
                last_starts[kind] = begin
                if kind == "dom":
                    summary["dom_queries"] += 1
                    error = None
                    try:
                        book = mt5.market_book_get(SYMBOL)
                        if book is None:
                            error = str(mt5.last_error())
                    except Exception as exc:
                        book, error = None, repr(exc)
                    end = clock()
                    levels = None if book is None else [{f: getattr(row, f) for f in FIELDS} for row in book]
                    summary["dom_failures" if levels is None else "dom_nonempty" if levels else "dom_empty"] += 1
                    if error:
                        summary["errors"].append(f"DOM: {error}")
                    emit(out, {"kind": "snapshot", "symbol": SYMBOL,
                        "sequence": summary["dom_queries"], "captured_utc": captured,
                        "completed_utc": utc_now(), "monotonic_start": begin, "monotonic_end": end,
                        "latency_ms": (end-begin)*1000, "levels": levels, "error": error,
                        **(analyze(levels, getattr(info, "trade_tick_size", 0)) if levels is not None else {})})
                    due[kind] = begin + .1
                else:
                    summary["tick_queries"] += 1
                    try:
                        rows, updated, drained, validation_attempts = validated_query.read(
                            SYMBOL, cursor, 2**63-1, counters)
                    except Exception as exc:
                        emit(out, {"kind": "tick_query_error", "symbol": SYMBOL,
                            "captured_utc": captured, "completed_utc": utc_now(),
                            "monotonic_start": begin, "monotonic_end": clock(), "error": str(exc),
                            "cursor": asdict(cursor),
                            "cursor_validation": getattr(exc, "diagnostics", [])})
                        raise  # No silent cursor reset or reconnect across unknown data.
                    end = clock()
                    emitted = []
                    for row in rows:
                        raw = raw_tick(row)
                        flags = int(raw["flags"])
                        trade = is_trade(flags, definitions)
                        side = aggressor_side(flags) if trade else None
                        unknown = unknown_flag_mask(flags)
                        valid = trade and all(math.isfinite(float(raw[field])) and float(raw[field]) > 0
                                              for field in ("last", "volume_real"))
                        summary["ticks"] += 1
                        summary["raw_flags"][flags] += 1
                        summary["unknown_flag_masks"][unknown] += 1
                        summary["ticks_with_unknown_flags"] += int(unknown != 0)
                        time_msc = int(raw["time_msc"])
                        if summary["first_tick_time_msc"] is None:
                            summary["first_tick_time_msc"] = time_msc
                        summary["last_tick_time_msc"] = time_msc
                        if valid and time_msc > summary["initial_tick_time_msc"]:
                            summary["valid_trades_after_initial_tick"] += 1
                        summary["trade_ticks" if trade else "quote_ticks"] += 1
                        if trade:
                            summary["valid_trade_ticks" if valid else "invalid_trade_ticks"] += 1
                            summary["trade_sides"][side] += 1
                            if summary["first_trade_time_msc"] is None:
                                summary["first_trade_time_msc"] = time_msc
                            summary["last_trade_time_msc"] = time_msc
                        emitted.append({"raw": raw, "classification": "trade" if trade else "quote",
                                        "side": side, "unknown_flag_mask": unknown, "valid_trade": bool(valid) if trade else None})
                    emit(out, {"kind": "tick_batch", "symbol": SYMBOL,
                        "sequence": summary["tick_queries"], "captured_utc": captured,
                        "completed_utc": utc_now(), "monotonic_start": begin, "monotonic_end": end,
                        "latency_ms": (end-begin)*1000, "cursor_before": asdict(cursor),
                        "cursor_after": asdict(updated), "drained": drained, "ticks": emitted,
                        "cursor_validation": validation_attempts})
                    cursor = updated
                    due[kind] = begin + .02 if drained else end
                latencies[kind].append((end-begin)*1000)
            sleep(max(0, min(min(due.values()), deadline) - clock()))
        summary["capture_elapsed_seconds"] = clock() - capture_start
    except KeyboardInterrupt:
        summary["errors"].append("interrupted")
    except Exception as exc:
        summary["errors"].append(str(exc))
    finally:
        try:
            if subscribed:
                try:
                    summary["release_ok"] = bool(mt5.market_book_release(SYMBOL))
                    if not summary["release_ok"]:
                        summary["errors"].append(f"market_book_release: {mt5.last_error()}")
                except Exception as exc:
                    summary["release_ok"] = False
                    summary["errors"].append(f"market_book_release: {exc}")
        finally:
            try:
                mt5.shutdown()
            except Exception as exc:
                summary["errors"].append(f"shutdown: {exc}")
        summary.update(ended_utc=utc_now(), elapsed_seconds=clock()-started,
                       tick_cursor=asdict(cursor) if cursor else None, tick_counters=asdict(counters))
        for name, metrics in (("latency_ms", latencies), ("schedule_lateness_ms", lateness), ("query_interval_ms", intervals)):
            summary[name] = {kind: {"count": len(values), "max": max(values) if values else None,
                "mean": sum(values)/len(values) if values else None} for kind, values in metrics.items()}
        summary["status"] = "both_observed" if summary["dom_nonempty"] and summary["valid_trades_after_initial_tick"] else "joint_capture_unproven"
        if summary["errors"]:
            summary["status"] = "incomplete"
        emit(out, summary)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seconds", type=finite_positive, default=30)
    parser.add_argument("--output", type=Path, help="Optional experimental NDJSON; refuses overwrite")
    args = parser.parse_args()
    import MetaTrader5 as mt5
    if args.output:
        with args.output.open("x", encoding="utf-8") as out:
            result = run(mt5, out, args.seconds)
        print(json.dumps(json_safe(result), ensure_ascii=False, allow_nan=False))
    else:
        result = run(mt5, sys.stdout, args.seconds)
    return 0 if result["status"] == "both_observed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
