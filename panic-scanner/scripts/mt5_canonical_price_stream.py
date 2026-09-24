"""Isolated LAST event acquisition. Does not import or change the Trade Stream.

Cursor counts ALL raw rows at a millisecond, including quotes. A one-feed-second
settlement delay closes timestamp groups before Java performs as-of comparison.
"""
import argparse
from dataclasses import dataclass, asdict
import json
import math
import sys
import threading
import time
from mt5_price_history import candle, utc, M5_MSC

LAST = 8
BATCH_SIZE = 100_000


@dataclass(frozen=True)
class Cursor:
    time_msc: int
    ordinal: int = 0


@dataclass
class Counters:
    rowsReceived: int = 0  # unique cursor rows, not repeatedly fetched overlap
    rowsFetched: int = 0
    lastEventsReceived: int = 0
    sameTimeMscGroups: int = 0
    sameTimeMscEvents: int = 0  # additional events after the first in each group
    duplicateEvents: int = 0  # consecutive canonical (time, price) equals; preserved
    outOfOrderErrors: int = 0
    sourceRestarts: int = 0
    queryErrors: int = 0
    fullBatches: int = 0
    noProgressErrors: int = 0


def query(mt5, symbol, cursor, through, counters, batch_size=BATCH_SIZE):
    rows = mt5.copy_ticks_from(symbol, utc(cursor.time_msc), batch_size, mt5.COPY_TICKS_ALL)
    if rows is None:
        counters.queryErrors += 1
        raise RuntimeError(f"SOURCE_GAP copy_ticks_from: {mt5.last_error()}")
    counters.rowsFetched += len(rows)
    if len(rows) == batch_size: counters.fullBatches += 1
    unseen = []
    previous = -1
    ordinal = 0
    updated = cursor
    seen_cursor = 0
    for row in rows:
        timestamp = int(row['time_msc'])
        if timestamp < previous:
            counters.outOfOrderErrors += 1
            raise RuntimeError('ORDER_ERROR decreasing raw time_msc')
        if int(row['time']) != timestamp // 1000:
            raise RuntimeError('SOURCE_GAP inconsistent time/time_msc')
        ordinal = ordinal + 1 if timestamp == previous else 1
        previous = timestamp
        if timestamp == cursor.time_msc: seen_cursor = ordinal
        if timestamp < cursor.time_msc or timestamp > through:
            continue
        if timestamp == cursor.time_msc and ordinal <= cursor.ordinal:
            continue
        unseen.append(row)
        updated = Cursor(timestamp, ordinal)
    # An overlapping prefix may grow, but cannot disappear without invalidating
    # the positional cursor. Never silently restart or deduplicate by content.
    if cursor.ordinal and seen_cursor < cursor.ordinal:
        raise RuntimeError('SOURCE_GAP cursor prefix disappeared or query truncated before cursor')
    drained = len(rows) < batch_size or (len(rows) > 0 and int(rows[-1]['time_msc']) > through)
    if not drained and updated == cursor:
        counters.noProgressErrors += 1
        raise RuntimeError('SOURCE_GAP full batch without cursor progress')
    counters.rowsReceived += len(unseen)
    return unseen, updated, drained


class LastEmitter:
    def __init__(self, symbol, counters, emit):
        self.symbol, self.counters, self.emit = symbol, counters, emit
        self.previous = None
        self.group_count = 0

    def accept(self, rows):
        for row in rows:
            if not int(row['flags']) & LAST: continue
            timestamp, price = int(row['time_msc']), float(row['last'])
            if not math.isfinite(price) or price <= 0:
                raise RuntimeError('SOURCE_GAP invalid LAST')
            if self.previous and timestamp == self.previous[0]:
                self.counters.sameTimeMscEvents += 1
                if self.group_count == 1: self.counters.sameTimeMscGroups += 1
                self.group_count += 1
            else:
                self.group_count = 1
            if self.previous == (timestamp, price): self.counters.duplicateEvents += 1
            self.previous = (timestamp, price)
            self.counters.lastEventsReceived += 1
            self.emit(dict(type='price', symbol=self.symbol, timeMsc=timestamp, price=price))


def settled_through(latest_time_msc):
    # Keep a COMPLETE feed second, not just the fractional current second.
    # At xx:xx:01.000, xx:xx:00.999 has aged only 1ms and is not settled.
    return latest_time_msc // 1000 * 1000 - 1001


def active_market(mt5, symbol, stop, seconds, monotonic=time.monotonic):
    initial = mt5.symbol_info_tick(symbol)
    if initial is None: raise RuntimeError(f'No initial tick: {mt5.last_error()}')
    anchor = int(initial.time_msc)
    cursor = Cursor(anchor)
    deadline = monotonic() + seconds
    counters = Counters()
    first_new = None
    while not stop.is_set() and monotonic() < deadline:
        latest = mt5.symbol_info_tick(symbol)
        if latest is None: raise RuntimeError(f'No tick: {mt5.last_error()}')
        through = settled_through(int(latest.time_msc))
        rows, cursor, drained = query(mt5, symbol, cursor, through, counters)
        for row in rows:
            t = int(row['time_msc'])
            if t > anchor and int(row['flags']) & LAST:
                if first_new is None: first_new = t
                elif t > first_new: return latest
        if drained: stop.wait(0.1)
    return None


def stream(mt5, symbol, stop, emit, probe_seconds=30):
    counters = Counters()
    try:
        if not mt5.initialize(): raise RuntimeError(f'initialize failed: {mt5.last_error()}')
        if not mt5.symbol_select(symbol, True): raise RuntimeError(f'symbol_select failed: {mt5.last_error()}')
        latest = active_market(mt5, symbol, stop, probe_seconds)
        if latest is None:
            emit(dict(type='inactive', reason='NO_ACTIVE_MARKET', stats=asdict(counters)))
            return
        anchor = int(latest.time_msc)
        start = anchor // M5_MSC * M5_MSC
        rates = mt5.copy_rates_from(symbol, mt5.TIMEFRAME_M5, utc(start - 1000), 1000)
        if rates is None: raise RuntimeError(f'warm-up failed: {mt5.last_error()}')
        closed = [candle(r) for r in rates if int(r['time']) * 1000 < start]
        if len(closed) < 20: raise RuntimeError('SHADOW_NOT_READY insufficient closed warm-up')
        emit(dict(type='header', symbol=symbol, startMsc=start, anchorMsc=anchor, warmup=closed))
        cursor = Cursor(start)
        emitter = LastEmitter(symbol, counters, emit)
        last_watermark = -1
        last_official_bucket = -1
        last_official_query = 0.0
        while not stop.is_set():
            latest = mt5.symbol_info_tick(symbol)
            if latest is None: raise RuntimeError(f'symbol_info_tick failed: {mt5.last_error()}')
            through = settled_through(int(latest.time_msc))
            if through < last_watermark:
                counters.outOfOrderErrors += 1
                raise RuntimeError('ORDER_ERROR feed watermark decreased')
            rows, cursor, drained = query(mt5, symbol, cursor, through, counters)
            emitter.accept(rows)
            if not drained: continue  # full batch: no wait, no premature watermark
            emit(dict(type='watermark', timeMsc=through, stats=asdict(counters)))
            last_watermark = through
            bucket = int(latest.time_msc) // M5_MSC * M5_MSC
            # Once per newly closed bucket, with retries every 5s for late official
            # availability. At most 1000 bars per query; never a call per event.
            now = time.monotonic()
            if bucket > start and through >= bucket + 2000 and (
                    bucket != last_official_bucket or now - last_official_query >= 5):
                official = mt5.copy_rates_from(symbol, mt5.TIMEFRAME_M5, utc(bucket - 1000), 1000)
                if official is None: raise RuntimeError(f'official rates failed: {mt5.last_error()}')
                emit(dict(type='official', closedThroughMsc=bucket,
                          candles=[candle(r) for r in official if int(r['time']) * 1000 < bucket]))
                last_official_query = now
                # Once a full closed bar is available, no periodic query until the
                # next bucket. Quote-only missing bars are harmless (bounded retry).
                if len(official) and int(official[-1]['time']) * 1000 == bucket - M5_MSC:
                    last_official_query = float('inf')
                last_official_bucket = bucket
            stop.wait(0.1)
    finally:
        emit(dict(type='stopped', stats=asdict(counters)))
        mt5.shutdown()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--symbol', default='WINV26')
    parser.add_argument('--probe-seconds', type=int, default=30)
    args = parser.parse_args()
    if not 1 <= args.probe_seconds <= 60: raise ValueError('probe-seconds must be 1..60')
    # Load the native MT5/NumPy modules before a thread blocks on stdin. On
    # Windows/Python 3.13 the reversed order stalled NumPy import until stdin EOF.
    import MetaTrader5 as mt5
    stop = threading.Event()
    threading.Thread(target=lambda: (sys.stdin.readline(), stop.set()), daemon=True).start()
    stream(mt5, args.symbol, stop, lambda row: print(json.dumps(row, allow_nan=False), flush=True), args.probe_seconds)


if __name__ == '__main__':
    try: main()
    except Exception as exc:
        print(f'Canonical source failed: {exc}', file=sys.stderr, flush=True)
        raise SystemExit(1)
