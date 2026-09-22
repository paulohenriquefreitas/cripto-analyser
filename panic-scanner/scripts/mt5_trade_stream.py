"""Persistent WINV26 Times & Trades stream; NDJSON on stdout, logs on stderr."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import json
import math
import sys
import threading

from mt5_ticks_diagnostic import flag_definitions, is_trade


SYMBOL = "WINV26"
POLL_SECONDS = 0.02
RETRY_SECONDS = 1.0
MAX_CONSECUTIVE_ERRORS = 3
BATCH_SIZE = 100_000
WARMUP_SECONDS = 10
UTC = timezone.utc


@dataclass(frozen=True)
class TradeCursor:
    time_msc: int
    emitted_at_time: int


@dataclass
class StreamCounters:
    ticks_read: int = 0
    trades_emitted: int = 0
    buy_trades: int = 0
    sell_trades: int = 0
    ambiguous_trades: int = 0
    no_side_trades: int = 0
    query_errors: int = 0
    last_trade_time_msc: int | None = None


def aggressor_side(flags, definitions):
    buy = bool(flags & definitions["BUY"])
    sell = bool(flags & definitions["SELL"])
    return "AMBIGUOUS" if buy == sell else "BUY" if buy else "SELL"


def trade_rows(rows, definitions):
    return [row for row in rows if is_trade(int(row["flags"]), definitions)]


def unseen_at_cursor(rows, cursor):
    """Return new ordered rows and a cursor; identity is timestamp plus ordinal."""
    unseen = []
    ordinal = 0
    group_time = None
    last_time = cursor.time_msc
    last_ordinal = cursor.emitted_at_time
    for row in rows:
        time_msc = int(row["time_msc"])
        if time_msc < cursor.time_msc:
            # copy_ticks_from can round its datetime boundary to the second.
            # Older rows are replayed by the API, not new source events.
            continue
        if time_msc != group_time:
            group_time = time_msc
            ordinal = 0
        if time_msc != cursor.time_msc or ordinal >= cursor.emitted_at_time:
            unseen.append(row)
            last_time = time_msc
            last_ordinal = ordinal + 1
        ordinal += 1
    return unseen, TradeCursor(last_time, last_ordinal)


def normalize_trade(row, definitions):
    price = float(row["last"])
    volume_real = float(row["volume_real"])
    if not math.isfinite(price) or price <= 0:
        raise RuntimeError(f"Trade com last inválido: {price!r}")
    if not math.isfinite(volume_real) or volume_real <= 0:
        raise RuntimeError(
            f"Trade com volume_real inválido: volume_real={volume_real!r} volume={float(row['volume'])!r}")
    return {
        "type": "trade",
        "symbol": SYMBOL,
        "timeMsc": int(row["time_msc"]),
        "price": price,
        "volume": volume_real,
        "side": aggressor_side(int(row["flags"]), definitions),
    }


def query_from_cursor(mt5, cursor, definitions):
    start = datetime.fromtimestamp(cursor.time_msc / 1000, UTC)
    copied = mt5.copy_ticks_from(SYMBOL, start, BATCH_SIZE, mt5.COPY_TICKS_ALL)
    if copied is None:
        raise RuntimeError(f"copy_ticks_from({SYMBOL}) falhou: {mt5.last_error()}")
    trades = trade_rows(copied, definitions)
    unseen, updated = unseen_at_cursor(trades, cursor)
    if len(copied):
        last_tick_time = int(copied[-1]["time_msc"])
        if last_tick_time > updated.time_msc:
            # Quotes also consume COPY_TICKS_ALL capacity. Advancing across a
            # quote-only tail prevents a full batch from being fetched forever;
            # ordinal remains zero so a later trade at this millisecond is kept.
            updated = TradeCursor(last_tick_time, 0)
    if len(copied) == BATCH_SIZE and updated == cursor:
        raise RuntimeError(
            "lote MT5 cheio sem progresso do cursor; nao e seguro assumir backlog drenado")
    return unseen, updated, len(copied)


def warmup(mt5, latest_time_msc, definitions):
    start_msc = max(0, latest_time_msc - WARMUP_SECONDS * 1000)
    start = datetime.fromtimestamp(start_msc / 1000, UTC)
    end = datetime.fromtimestamp((latest_time_msc + 1) / 1000, UTC)
    copied = mt5.copy_ticks_range(SYMBOL, start, end, mt5.COPY_TICKS_ALL)
    if copied is None:
        raise RuntimeError(f"copy_ticks_range warm-up ({SYMBOL}) falhou: {mt5.last_error()}")
    trades = trade_rows(copied, definitions)
    unseen, cursor = unseen_at_cursor(trades, TradeCursor(start_msc, 0))
    if not unseen:
        cursor = TradeCursor(latest_time_msc, 0)
    return unseen, cursor, len(copied)


def emit(rows, definitions, counters, output, errors):
    for row in rows:
        message = normalize_trade(row, definitions)
        flags = int(row["flags"])
        if message["side"] == "AMBIGUOUS" and not (
                flags & (definitions["BUY"] | definitions["SELL"])):
            counters.no_side_trades += 1
            print(f"Trade sem BUY/SELL mapeado para AMBIGUOUS; total={counters.no_side_trades}",
                  file=errors, flush=True)
        counters.trades_emitted += 1
        counters.last_trade_time_msc = message["timeMsc"]
        if message["side"] == "BUY":
            counters.buy_trades += 1
        elif message["side"] == "SELL":
            counters.sell_trades += 1
        else:
            counters.ambiguous_trades += 1
        print(json.dumps(message, allow_nan=False, separators=(",", ":")),
              file=output, flush=True)


def stream_trades(mt5, stop_event, output=sys.stdout, errors=sys.stderr):
    counters = StreamCounters()
    try:
        if not mt5.initialize():
            raise RuntimeError(f"mt5.initialize() falhou: {mt5.last_error()}")
        if not mt5.symbol_select(SYMBOL, True):
            raise RuntimeError(f"symbol_select({SYMBOL}) falhou: {mt5.last_error()}")
        latest = mt5.symbol_info_tick(SYMBOL)
        if latest is None:
            raise RuntimeError(f"symbol_info_tick({SYMBOL}) falhou: {mt5.last_error()}")
        definitions = flag_definitions(mt5)
        initial, cursor, ticks_read = warmup(mt5, int(latest.time_msc), definitions)
        counters.ticks_read += ticks_read
        emit(initial, definitions, counters, output, errors)
        consecutive_errors = 0
        while not stop_event.is_set():
            try:
                unseen, updated_cursor, ticks_read = query_from_cursor(mt5, cursor, definitions)
                counters.ticks_read += ticks_read
                emit(unseen, definitions, counters, output, errors)
                cursor = updated_cursor
                consecutive_errors = 0
            except RuntimeError as exc:
                counters.query_errors += 1
                consecutive_errors += 1
                print(f"Falha temporária na consulta MT5 ({consecutive_errors}/"
                      f"{MAX_CONSECUTIVE_ERRORS}): {exc}", file=errors, flush=True)
                if consecutive_errors >= MAX_CONSECUTIVE_ERRORS:
                    raise RuntimeError("limite de falhas consecutivas do MT5 atingido") from exc
                stop_event.wait(RETRY_SECONDS)
                continue
            # A full result does not prove that the stream reached the present.
            # Drain again immediately from the updated ordinal cursor.
            if ticks_read < BATCH_SIZE:
                stop_event.wait(POLL_SECONDS)
    finally:
        print("Trade stream encerrado: "
              f"ticksRead={counters.ticks_read} tradesEmitted={counters.trades_emitted} "
              f"BUY={counters.buy_trades} SELL={counters.sell_trades} "
              f"AMBIGUOUS={counters.ambiguous_trades} semLado={counters.no_side_trades} "
              f"queryErrors={counters.query_errors} lastTradeTimeMsc={counters.last_trade_time_msc}",
              file=errors, flush=True)
        mt5.shutdown()


def main():
    import MetaTrader5 as mt5

    stop_event = threading.Event()

    def wait_for_stop():
        sys.stdin.readline()
        stop_event.set()

    threading.Thread(target=wait_for_stop, daemon=True).start()
    stream_trades(mt5, stop_event)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
    except Exception as exc:
        print(f"Erro no trade stream MT5: {exc}", file=sys.stderr, flush=True)
        raise SystemExit(1)
