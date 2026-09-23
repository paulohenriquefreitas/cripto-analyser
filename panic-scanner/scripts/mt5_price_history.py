"""Bounded COPY_TICKS_ALL export for Java canonical price diagnostics; [start, end)."""
import argparse
from datetime import datetime, timezone
import json
import sys

M5_MSC = 300_000


def utc(time_msc):
    return datetime.fromtimestamp(time_msc / 1000, timezone.utc)


def candle(row):
    return {"time": int(row["time"]), "open": float(row["open"]),
            "high": float(row["high"]), "low": float(row["low"]),
            "close": float(row["close"]), "tickVolume": int(row["tick_volume"]),
            "realVolume": int(row["real_volume"])}


def export(mt5, symbol, start, end, emit):
    if start < 0 or start % M5_MSC or end <= start or end % M5_MSC:
        raise ValueError("Diagnostic interval must contain complete M5 buckets: [start, end)")
    if not mt5.symbol_select(symbol, True):
        raise RuntimeError(f"symbol_select failed: {mt5.last_error()}")
    # Request bars strictly before the first analysis bucket, not the current bar.
    warmup = mt5.copy_rates_from(symbol, mt5.TIMEFRAME_M5, utc(start - 1), 1000)
    official = mt5.copy_rates_range(symbol, mt5.TIMEFRAME_M5, utc(start), utc(end - 1))
    if warmup is None or len(warmup) < 20 or official is None or len(official) == 0:
        raise RuntimeError(f"Missing closed warm-up or official rates: {mt5.last_error()}")
    emit({"type": "header", "symbol": symbol, "startMsc": start, "endMsc": end,
          "warmup": [candle(r) for r in warmup if int(r["time"]) * 1000 < start],
          "official": [candle(r) for r in official if start <= int(r["time"]) * 1000 < end]})
    count = 0
    previous = -1
    for chunk_start in range(start, end, M5_MSC):
        chunk_end = min(chunk_start + M5_MSC, end)
        # The Python bridge truncates datetime bounds to whole seconds. Query the
        # full upper boundary, then exclude it by time_msc below; end-1ms loses
        # the entire last second on the observed terminal.
        rows = mt5.copy_ticks_range(symbol, utc(chunk_start), utc(chunk_end), mt5.COPY_TICKS_ALL)
        if rows is None:
            raise RuntimeError(f"COPY_TICKS_ALL failed: {mt5.last_error()}")
        for row in rows:
            timestamp = int(row["time_msc"])
            if not chunk_start <= timestamp < chunk_end:
                continue
            if timestamp < previous:
                raise ValueError("MT5 returned decreasing time_msc")
            if int(row["time"]) != timestamp // 1000:
                raise ValueError("MT5 returned inconsistent time/time_msc")
            previous = timestamp
            emit({"type": "tick", "timeMsc": timestamp,
                  "last": float(row["last"]), "flags": int(row["flags"])})
            count += 1
    emit({"type": "end", "rows": count})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--symbol", default="WINV26")
    parser.add_argument("--start-msc", type=int, required=True)
    parser.add_argument("--end-msc", type=int, required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    import MetaTrader5 as mt5
    try:
        if not mt5.initialize():
            raise RuntimeError(f"initialize failed: {mt5.last_error()}")
        with open(args.output, "w", encoding="utf-8") as out:
            export(mt5, args.symbol, args.start_msc, args.end_msc,
                   lambda row: out.write(json.dumps(row, allow_nan=False) + "\n"))
    finally:
        mt5.shutdown()


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"Canonical history export failed: {exc}", file=sys.stderr)
        sys.exit(1)
