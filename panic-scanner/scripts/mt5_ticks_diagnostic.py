"""Diagnose WINV26 tick/trade data exposed by the installed MetaTrader5 package."""

from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timedelta, timezone
import time


SYMBOL = "WINV26"
POLL_SECONDS = 0.02


def flag_definitions(mt5):
    return {
        "BID": mt5.TICK_FLAG_BID,
        "ASK": mt5.TICK_FLAG_ASK,
        "LAST": mt5.TICK_FLAG_LAST,
        "VOLUME": mt5.TICK_FLAG_VOLUME,
        "BUY": mt5.TICK_FLAG_BUY,
        "SELL": mt5.TICK_FLAG_SELL,
    }


def flag_names(flags, definitions):
    return [name for name, bit in definitions.items() if flags & bit]


def is_trade(flags, definitions):
    return bool(flags & (definitions["LAST"] | definitions["VOLUME"]))


def analyse(rows, definitions):
    stats = Counter(total=len(rows))
    timestamps = Counter()
    volume_diff = 0
    volume_fractional = 0
    volume_zero = 0
    volume_real_zero = 0
    known_mask = sum(definitions.values())
    for row in rows:
        flags = int(row["flags"])
        if flags & ~known_mask:
            stats["unknown_flag_events"] += 1
            stats["unknown_flag_mask"] |= flags & ~known_mask
        timestamps[int(row["time_msc"])] += 1
        for name, bit in definitions.items():
            if flags & bit:
                stats["flag_" + name.lower()] += 1
        volume = float(row["volume"])
        volume_real = float(row["volume_real"])
        if is_trade(flags, definitions):
            stats["trades"] += 1
            stats["trade_volume_real_zero"] += volume_real == 0
            stats["trade_volume_real_fractional"] += not volume_real.is_integer()
            buy = bool(flags & definitions["BUY"])
            sell = bool(flags & definitions["SELL"])
            stats["buy_sell" if buy and sell else "buy" if buy else "sell" if sell else "no_side"] += 1
        volume_diff += volume != volume_real
        volume_fractional += not volume_real.is_integer()
        volume_zero += volume == 0
        volume_real_zero += volume_real == 0
    stats.update(
        duplicate_time_msc=sum(count - 1 for count in timestamps.values() if count > 1),
        time_msc_with_multiple_events=sum(count > 1 for count in timestamps.values()),
        volume_diff=volume_diff,
        volume_fractional=volume_fractional,
        volume_zero=volume_zero,
        volume_real_zero=volume_real_zero,
    )
    return stats


def format_tick(row, definitions):
    instant = datetime.fromtimestamp(int(row["time_msc"]) / 1000, timezone.utc)
    names = "|".join(flag_names(int(row["flags"]), definitions)) or "NONE"
    return (
        f"{instant:%H:%M:%S.%f}"[:-3]
        + f" UTC  last={float(row['last']):.1f} bid={float(row['bid']):.1f}"
        + f" ask={float(row['ask']):.1f} volume={float(row['volume']):g}"
        + f" volume_real={float(row['volume_real']):g} flags={int(row['flags'])} [{names}]"
    )


def collect(mt5, seconds):
    definitions = flag_definitions(mt5)
    start_utc = datetime.now(timezone.utc)
    start_msc = int(start_utc.timestamp() * 1000)
    deadline = time.monotonic() + seconds
    polled = []
    previous = None
    while time.monotonic() < deadline:
        tick = mt5.symbol_info_tick(SYMBOL)
        if tick is None:
            raise RuntimeError(f"symbol_info_tick falhou: {mt5.last_error()}")
        # A snapshot can change without changing time_msc; retain the full state.
        identity = tuple(tick)
        if identity != previous:
            polled.append(tick._asdict())
            previous = identity
        time.sleep(POLL_SECONDS)
    end_utc = datetime.now(timezone.utc)
    end_msc = int(end_utc.timestamp() * 1000)
    # Inclusive API boundary plus exact millisecond filtering avoids unrelated rows.
    copied = mt5.copy_ticks_range(SYMBOL, start_utc - timedelta(milliseconds=1),
                                  end_utc + timedelta(milliseconds=1), mt5.COPY_TICKS_ALL)
    if copied is None:
        raise RuntimeError(f"copy_ticks_range falhou: {mt5.last_error()}")
    rows = [row for row in copied if start_msc <= int(row["time_msc"]) <= end_msc]
    return start_utc, end_utc, polled, rows, definitions


def print_report(start, end, polled, rows, definitions, sample_size):
    stats = analyse(rows, definitions)
    trade_rows = [row for row in rows if is_trade(int(row["flags"]), definitions)]
    print(f"MetaTrader5 tick diagnostic — {SYMBOL}")
    print(f"Período UTC: {start.isoformat()} a {end.isoformat()}")
    print(f"Polling symbol_info_tick ({POLL_SECONDS * 1000:.0f} ms): {len(polled)} estados capturados")
    print(f"copy_ticks_range COPY_TICKS_ALL: {stats['total']} eventos")
    print(f"Trades (flag LAST ou VOLUME): {stats['trades']}")
    print()
    print("Flags na amostra:")
    for name in ("BID", "ASK", "LAST", "VOLUME"):
        print(f"  {name}: {stats['flag_' + name.lower()]}")
    print(f"  bits fora das constantes documentadas: eventos={stats['unknown_flag_events']}"
          f" máscara={stats['unknown_flag_mask']}")
    print("Lado agressor nos eventos classificados como trade:")
    print(f"  BUY: {stats['buy']}")
    print(f"  SELL: {stats['sell']}")
    print(f"  BUY+SELL: {stats['buy_sell']}")
    print(f"  nenhum: {stats['no_side']}")
    print()
    print("Volume em todos os eventos:")
    print(f"  volume != volume_real: {stats['volume_diff']}")
    print(f"  volume_real fracionário: {stats['volume_fractional']}")
    print(f"  volume zero: {stats['volume_zero']}")
    print(f"  volume_real zero: {stats['volume_real_zero']}")
    print("Precisão/ordem:")
    print(f"  timestamps time_msc compartilhados: {stats['time_msc_with_multiple_events']}")
    print(f"  eventos adicionais no mesmo time_msc: {stats['duplicate_time_msc']}")
    ordered = all(int(rows[i]["time_msc"]) <= int(rows[i + 1]["time_msc"]) for i in range(len(rows) - 1))
    print(f"  ordem não decrescente retornada pelo MT5: {ordered}")
    print()
    print("Amostra de negócios (ordem original do MT5):")
    for row in trade_rows[:sample_size]:
        print("  " + format_tick(row, definitions))


def recent_history(mt5, lookback_seconds):
    latest = mt5.symbol_info_tick(SYMBOL)
    if latest is None:
        raise RuntimeError(f"Sem último tick para {SYMBOL}: {mt5.last_error()}")
    end = datetime.fromtimestamp(latest.time_msc / 1000, timezone.utc)
    start = end - timedelta(seconds=lookback_seconds)
    copied = mt5.copy_ticks_range(SYMBOL, start, end + timedelta(milliseconds=1), mt5.COPY_TICKS_ALL)
    if copied is None:
        raise RuntimeError(f"copy_ticks_range histórico falhou: {mt5.last_error()}")
    rows = [row for row in copied if int(row["time_msc"]) <= int(latest.time_msc)]
    return start, end, rows


def main():
    parser = argparse.ArgumentParser(description="Diagnóstico Times & Trades do WINV26")
    parser.add_argument("--seconds", type=float, default=10, help="duração da amostra (padrão: 10)")
    parser.add_argument("--sample", type=int, default=12, help="negócios impressos (padrão: 12)")
    parser.add_argument("--history-seconds", type=int, default=60,
                        help="janela anterior ao último tick, usada se a captura ficar vazia")
    args = parser.parse_args()
    if args.seconds <= 0 or args.sample < 0:
        parser.error("--seconds deve ser positivo e --sample não negativo")

    import MetaTrader5 as mt5
    print(f"MetaTrader5 Python: {mt5.__version__}")
    print("Constantes:", {name: getattr(mt5, name) for name in (
        "COPY_TICKS_ALL", "COPY_TICKS_INFO", "COPY_TICKS_TRADE",
        "TICK_FLAG_BID", "TICK_FLAG_ASK", "TICK_FLAG_LAST", "TICK_FLAG_VOLUME",
        "TICK_FLAG_BUY", "TICK_FLAG_SELL")})
    try:
        if not mt5.initialize():
            raise RuntimeError(f"mt5.initialize() falhou: {mt5.last_error()}")
        start, end, polled, rows, definitions = collect(mt5, args.seconds)
        print_report(start, end, polled, rows, definitions, args.sample)
        if not rows and args.history_seconds > 0:
            historical_start, historical_end, historical = recent_history(mt5, args.history_seconds)
            print()
            print("Sem ticks no intervalo ao vivo; amostra histórica junto ao último tick disponível:")
            print_report(historical_start, historical_end, [], historical, definitions, args.sample)
    finally:
        mt5.shutdown()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("Diagnóstico interrompido.")
    except Exception as exc:
        print(f"Erro no diagnóstico MT5: {exc}")
        raise SystemExit(1)
