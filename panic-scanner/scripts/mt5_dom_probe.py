"""Manual, read-only WINZ26 DOM probe. No trading or stream integration."""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import time

SYMBOL = "WINZ26"
FIELDS = ("type", "price", "volume", "volume_dbl")


def utc_now():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def json_safe(value):
    # JSON has no NaN/Infinity: preserve their identity explicitly, never as null/zero.
    if isinstance(value, float) and not math.isfinite(value):
        return {"non_finite": repr(value)}
    if hasattr(value, "_asdict"):
        value = value._asdict()
    if isinstance(value, dict):
        return {k: json_safe(v) for k, v in value.items()}
    if isinstance(value, (tuple, list)):
        return [json_safe(v) for v in value]
    return value


def write_record(out, record):
    out.write(json.dumps(json_safe(record), allow_nan=False, ensure_ascii=False) + "\n")
    out.flush()


def positive(value):
    return isinstance(value, (int, float)) and math.isfinite(value) and value > 0


def analyze(levels, tick_size=0):
    anomalies = []
    prices = {1: [], 2: []}  # BOOK_TYPE_SELL / BOOK_TYPE_BUY, never execution aggressors.
    seen = set()
    for i, row in enumerate(levels):
        kind = row["type"]
        if kind not in (1, 2, 3, 4):
            anomalies.append(f"unknown_type:{i}")
        for field in ("volume", "volume_dbl"):
            if not positive(row[field]):
                anomalies.append(f"invalid_{field}:{i}")
        if positive(row["volume"]) and row["volume"] != int(row["volume"]):
            anomalies.append(f"non_integer_volume:{i}")
        if positive(row["volume"]) and positive(row["volume_dbl"]):
            if int(row["volume_dbl"]) != row["volume"]:
                anomalies.append(f"quantity_mismatch:{i}")
        # Market buy/sell (3/4) have no limit price; exclude from BBO.
        if kind in (1, 2):
            price = row["price"]
            if not positive(price):
                anomalies.append(f"invalid_price:{i}")
                continue
            prices[kind].append(price)
            if (kind, price) in seen:
                anomalies.append(f"duplicate_level:{i}")
            seen.add((kind, price))
            if positive(tick_size) and not math.isclose(price / tick_size, round(price / tick_size), abs_tol=1e-6, rel_tol=0):
                anomalies.append(f"off_tick_price:{i}")
    # MT5 book order is descending price on each side (asks worst to best).
    for kind, side in ((1, "ask"), (2, "bid")):
        if any(a < b for a, b in zip(prices[kind], prices[kind][1:])):
            anomalies.append(f"unordered_{side}")
    bid = max(prices[2], default=None)
    ask = min(prices[1], default=None)
    spread = ask - bid if bid is not None and ask is not None else None
    if bid is None or ask is None:
        anomalies.append("missing_limit_side")
    if spread is not None and spread <= 0:
        anomalies.append("crossed_spread" if spread < 0 else "locked_spread")
    return {"level_count": len(levels), "best_bid": bid, "best_ask": ask,
            "spread": spread, "anomalies": anomalies}


def capture(mt5, out, seconds=60, interval_ms=100, clock=time.monotonic, sleep=time.sleep):
    summary = {"kind": "summary", "symbol": SYMBOL, "started_utc": utc_now(),
               "requested_seconds": seconds, "interval_ms": interval_ms,
               "queries": 0, "nonempty": 0, "empty": 0, "failures": 0,
               "changes": 0, "repeated": 0, "anomaly_counts": Counter(),
               "release_ok": None, "errors": []}
    subscribed = False
    previous = None
    latencies, counts, spreads = [], [], []
    started = clock()
    try:
        if not mt5.initialize():
            raise RuntimeError(f"initialize: {mt5.last_error()}")
        account = mt5.account_info()
        # Record only broker identity, never account number or credentials.
        if account is None:
            raise RuntimeError(f"account_info: {mt5.last_error()}")
        broker = {"company": account.company, "server": account.server}
        write_record(out, {"kind": "connection", "captured_utc": utc_now(), **broker})
        if "clear" not in (account.company + " " + account.server).lower():
            raise RuntimeError("Terminal conectado nao identificado como Clear; captura cancelada")
        info = mt5.symbol_info(SYMBOL)
        write_record(out, {"kind": "symbol_info", "value": info})
        if info is None:
            raise RuntimeError(f"symbol_info: {mt5.last_error()}")
        selected = mt5.symbol_select(SYMBOL, True)
        write_record(out, {"kind": "symbol_select", "ok": selected})
        if not selected:
            raise RuntimeError(f"symbol_select: {mt5.last_error()}")
        tick = mt5.symbol_info_tick(SYMBOL)
        write_record(out, {"kind": "symbol_info_tick", "value": tick})
        if tick is None:
            raise RuntimeError(f"symbol_info_tick: {mt5.last_error()}")
        subscribed = bool(mt5.market_book_add(SYMBOL))
        write_record(out, {"kind": "subscription", "ok": subscribed})
        if not subscribed:
            raise RuntimeError(f"market_book_add: {mt5.last_error()}")
        deadline = clock() + seconds
        while clock() < deadline:
            captured = utc_now()
            begin = clock()
            error = None
            try:
                book = mt5.market_book_get(SYMBOL)
                if book is None:
                    error = str(mt5.last_error())
            except Exception as exc:
                book, error = None, repr(exc)
            latency = (clock() - begin) * 1000
            latencies.append(latency)
            summary["queries"] += 1
            levels = None if book is None else [{f: getattr(row, f) for f in FIELDS} for row in book]
            metrics = analyze(levels, getattr(info, "trade_tick_size", 0)) if levels is not None else {}
            changed = None
            if levels is None:
                summary["failures"] += 1
            else:
                summary["nonempty" if levels else "empty"] += 1
                counts.append(len(levels))
                summary["anomaly_counts"].update(metrics["anomalies"])
                if metrics["spread"] is not None:
                    spreads.append(metrics["spread"])
                if previous is not None:
                    changed = levels != previous
                    summary["changes" if changed else "repeated"] += 1
                previous = levels
            write_record(out, {"kind": "snapshot", "sequence": summary["queries"],
                               "captured_utc": captured, "completed_utc": utc_now(),
                               "latency_ms": latency, "levels": levels, "error": error,
                               "changed_since_previous_success": changed, **metrics})
            sleep(max(0, min(interval_ms / 1000 - (clock() - begin), deadline - clock())))
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
        summary.update(ended_utc=utc_now(), elapsed_seconds=clock() - started,
                       dom_status="observed" if summary["nonempty"] else "unavailable_in_capture")
        for name, values in (("latency_ms", latencies), ("level_count", counts), ("spread", spreads)):
            summary[name] = {"min": min(values), "max": max(values), "mean": sum(values) / len(values)} if values else None
        write_record(out, summary)
    return summary


def finite_positive(value):
    number = float(value)
    if not positive(number):
        raise argparse.ArgumentTypeError("Informe um numero finito maior que zero")
    return number


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seconds", type=finite_positive, default=60)
    parser.add_argument("--interval-ms", type=finite_positive, default=100)
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[1] / "dom-captures" / ("WINZ26-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ") + ".ndjson"))
    args = parser.parse_args()
    import MetaTrader5 as mt5
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as out:
        summary = capture(mt5, out, args.seconds, args.interval_ms)
    print(json.dumps(json_safe(summary), ensure_ascii=False, allow_nan=False))
    print(f"Arquivo: {args.output.resolve()}")
    return 0 if summary["nonempty"] and not summary["errors"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
