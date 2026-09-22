"""Compare the polled symbol_info_tick input with COPY_TICKS_ALL history."""

from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import time

from mt5_tick_stream import POLL_SECONDS


def compare(polled, historical):
    historical_pairs = [(int(row["time_msc"]), float(row["last"])) for row in historical]
    polled_pairs = [(int(row["time_msc"]), float(row["last"])) for row in polled]
    index = 0
    matched = 0
    for value in polled_pairs:
        while index < len(historical_pairs) and historical_pairs[index] != value:
            index += 1
        if index < len(historical_pairs):
            matched += 1
            index += 1
    historical_times = {value[0] for value in historical_pairs}
    return {
        "polled": len(polled_pairs),
        "historical": len(historical_pairs),
        "polledSubsequenceMatches": matched,
        "historicalDistinctTimeMsc": len(historical_times),
        "historicalRepeatedTimeMsc": len(historical_pairs) - len(historical_times),
    }


def collect(mt5, symbol, seconds):
    deadline = time.monotonic() + seconds
    polled = []
    previous_time_msc = None
    while time.monotonic() < deadline:
        tick = mt5.symbol_info_tick(symbol)
        if tick is None:
            raise RuntimeError(f"symbol_info_tick({symbol}) falhou: {mt5.last_error()}")
        if int(tick.time_msc) != previous_time_msc:
            polled.append(tick._asdict())
            previous_time_msc = int(tick.time_msc)
        time.sleep(POLL_SECONDS)
    if not polled:
        raise RuntimeError("symbol_info_tick did not return an initial state")
    start_msc = int(polled[0]["time_msc"])
    end_msc = int(polled[-1]["time_msc"])
    start = datetime.fromtimestamp(start_msc / 1000, timezone.utc)
    end = datetime.fromtimestamp(end_msc / 1000, timezone.utc)
    copied = mt5.copy_ticks_range(symbol, start - timedelta(seconds=1),
                                  end + timedelta(seconds=1), mt5.COPY_TICKS_ALL)
    if copied is None:
        raise RuntimeError(f"copy_ticks_range({symbol}) falhou: {mt5.last_error()}")
    historical = [row for row in copied if start_msc <= int(row["time_msc"]) <= end_msc]
    return start_msc, end_msc, polled, historical


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--symbol", default="WINV26")
    parser.add_argument("--seconds", type=int, default=30)
    args = parser.parse_args()
    import MetaTrader5 as mt5
    try:
        if not mt5.initialize():
            raise RuntimeError(f"mt5.initialize() falhou: {mt5.last_error()}")
        start, end, polled, historical = collect(mt5, args.symbol, args.seconds)
        result = compare(polled, historical)
        print(f"symbol={args.symbol} startMsc={start} endMsc={end}")
        for key, value in result.items():
            print(f"{key}={value}")
    finally:
        mt5.shutdown()


if __name__ == "__main__":
    main()
