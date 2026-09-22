"""Exports an inclusive MT5 Times & Trades interval as protocol NDJSON."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import sys
from datetime import timedelta

from mt5_ticks_diagnostic import flag_definitions
from mt5_trade_stream import SYMBOL, normalize_trade, trade_rows


CHUNK_SECONDS = 300


def historical_trades(mt5, symbol: str, start_msc: int, end_msc: int):
    """Read and normalize exactly [start_msc, end_msc], preserving source order."""
    start = datetime.fromtimestamp(start_msc / 1000, timezone.utc)
    # MT5 datetime arguments have second precision in practice. Widen the API
    # query and apply millisecond bounds to the returned source timestamps.
    end = datetime.fromtimestamp((end_msc + 1000) / 1000, timezone.utc)
    copied = mt5.copy_ticks_range(symbol, start, end, mt5.COPY_TICKS_ALL)
    if copied is None:
        raise RuntimeError(f"copy_ticks_range({symbol}) falhou: {mt5.last_error()}")
    definitions = flag_definitions(mt5)
    return [normalize_trade(row, definitions) for row in trade_rows(copied, definitions)
            if start_msc <= int(row["time_msc"]) <= end_msc]


def stream_historical_trades(mt5, symbol: str, start_msc: int, end_msc: int,
                             chunk_seconds: int = CHUNK_SECONDS):
    """Yield ordered trades in bounded time chunks without cross-chunk duplicates."""
    if chunk_seconds <= 0:
        raise ValueError("chunk_seconds must be positive")
    cursor = start_msc
    step = chunk_seconds * 1000
    while cursor <= end_msc:
        chunk_end = min(end_msc, cursor + step - 1)
        for message in historical_trades(mt5, symbol, cursor, chunk_end):
            yield message
        cursor = chunk_end + 1


def summarize_trades(messages):
    summary = {"trades": 0, "BUY": 0, "SELL": 0, "AMBIGUOUS": 0,
               "volume": 0.0, "buyVolume": 0.0, "sellVolume": 0.0,
               "ambiguousVolume": 0.0, "firstPrice": None, "lastPrice": None,
               "minPrice": None, "maxPrice": None}
    for message in messages:
        price, volume, side = message["price"], message["volume"], message["side"]
        summary["trades"] += 1
        summary[side] += 1
        summary["volume"] += volume
        summary[side.lower() + "Volume"] += volume
        if summary["firstPrice"] is None:
            summary["firstPrice"] = price
            summary["minPrice"] = price
            summary["maxPrice"] = price
        summary["lastPrice"] = price
        summary["minPrice"] = min(summary["minPrice"], price)
        summary["maxPrice"] = max(summary["maxPrice"], price)
    summary["knownDelta"] = summary["buyVolume"] - summary["sellVolume"]
    return summary


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--symbol", default=SYMBOL)
    parser.add_argument("--start-msc", type=int, required=True)
    parser.add_argument("--end-msc", type=int, required=True)
    parser.add_argument("--chunk-seconds", type=int, default=CHUNK_SECONDS)
    parser.add_argument("--summary-only", action="store_true")
    args = parser.parse_args()
    if args.start_msc > args.end_msc:
        parser.error("--start-msc must be <= --end-msc")

    import MetaTrader5 as mt5
    try:
        if not mt5.initialize():
            raise RuntimeError(f"mt5.initialize() falhou: {mt5.last_error()}")
        if not mt5.symbol_select(args.symbol, True):
            raise RuntimeError(f"symbol_select({args.symbol}) falhou: {mt5.last_error()}")
        messages = stream_historical_trades(
                mt5, args.symbol, args.start_msc, args.end_msc, args.chunk_seconds)
        if args.summary_only:
            print(json.dumps(summarize_trades(messages), allow_nan=False, separators=(",", ":")), flush=True)
            return
        for message in messages:
            # normalize_trade currently uses the validated WINV26 symbol. This
            # executable only accepts that source until symbol is parameterized.
            if args.symbol != SYMBOL:
                message["symbol"] = args.symbol
            print(json.dumps(message, allow_nan=False, separators=(",", ":")), flush=True)
    finally:
        mt5.shutdown()


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"Erro na consulta historica MT5: {exc}", file=sys.stderr, flush=True)
        raise SystemExit(1)
