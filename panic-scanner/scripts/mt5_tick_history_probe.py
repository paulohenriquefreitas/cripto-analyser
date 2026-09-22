"""Probe WINV26 tick-history depth with small, bounded MT5 requests."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta, timezone

from mt5_ticks_diagnostic import SYMBOL, analyse, flag_definitions


UTC = timezone.utc
OFFSETS = (1, 7, 30, 60, 90, 180, 365, 540, 730)


@dataclass(frozen=True)
class ProbeResult:
    requested: date
    sampled: date | None
    start: datetime | None
    end: datetime | None
    rows: object


def utc_start(day):
    return datetime.combine(day, time.min, UTC)


def day_of_tick(row):
    return datetime.fromtimestamp(int(row["time_msc"]) / 1000, UTC).date()


def first_tick_on_or_after(mt5, day, copy_flag=None):
    flag = mt5.COPY_TICKS_ALL if copy_flag is None else copy_flag
    rows = mt5.copy_ticks_from(SYMBOL, utc_start(day), 1, flag)
    if rows is None:
        raise RuntimeError(f"copy_ticks_from falhou em {day}: {mt5.last_error()}")
    return None if len(rows) == 0 else rows[0]


def sample_near(mt5, requested, minutes, search_days=4):
    """Use one-tick discovery, accepting the next nearby trading date."""
    first = first_tick_on_or_after(mt5, requested, mt5.COPY_TICKS_TRADE)
    if first is None:
        return ProbeResult(requested, None, None, None, [])
    sampled = day_of_tick(first)
    if sampled > requested + timedelta(days=search_days):
        return ProbeResult(requested, None, None, None, [])
    start = datetime.fromtimestamp(int(first["time_msc"]) / 1000, UTC)
    end = start + timedelta(minutes=minutes)
    rows = mt5.copy_ticks_range(SYMBOL, start, end, mt5.COPY_TICKS_ALL)
    if rows is None:
        raise RuntimeError(f"copy_ticks_range falhou em {sampled}: {mt5.last_error()}")
    start_msc = int(start.timestamp() * 1000)
    end_msc = int(end.timestamp() * 1000)
    bounded = [row for row in rows if start_msc <= int(row["time_msc"]) <= end_msc]
    return ProbeResult(requested, sampled, start, end, bounded)


def format_msc(value):
    return datetime.fromtimestamp(int(value) / 1000, UTC).isoformat(timespec="milliseconds")


def summarize(result, definitions):
    if result.sampled is None or len(result.rows) == 0:
        return {
            "requested": result.requested.isoformat(), "sampled": None,
            "status": "SEM DADOS (contrato não negociado ou histórico indisponível)",
        }
    stats = analyse(result.rows, definitions)
    return {
        "requested": result.requested.isoformat(),
        "sampled": result.sampled.isoformat(),
        "status": "SIM",
        "start": result.start.isoformat(timespec="milliseconds"),
        "end": result.end.isoformat(timespec="milliseconds"),
        "events": stats["total"], "trades": stats["trades"],
        "buy": stats["buy"], "sell": stats["sell"],
        "buySell": stats["buy_sell"], "noSide": stats["no_side"],
        "volumeRealZero": stats["trade_volume_real_zero"],
        "volumeRealFractional": stats["trade_volume_real_fractional"],
        "firstTimeMsc": int(result.rows[0]["time_msc"]),
        "lastTimeMsc": int(result.rows[-1]["time_msc"]),
    }


def related_symbols(mt5):
    found = {}
    for pattern in ("WIN*", "*WIN*"):
        for symbol in mt5.symbols_get(pattern) or ():
            name = symbol.name.upper()
            description = (symbol.description or "").upper()
            if name.startswith("WIN") or "MINI" in description and "IND" in description:
                found[symbol.name] = symbol.description
    return sorted(found.items())


def symbol_metadata(info):
    fields = ("name", "description", "path", "time", "start_time", "expiration_time",
              "trade_mode", "trade_calc_mode", "volume_min", "volume_max", "volume_step")
    return {field: getattr(info, field, None) for field in fields}


def print_summary(label, summary):
    print(f"{label}: solicitado={summary['requested']} amostrado={summary['sampled']} dados={summary['status']}")
    if summary.get("events") is None:
        return
    print(f"  janela={summary['start']} .. {summary['end']}")
    print(f"  eventos={summary['events']} trades={summary['trades']} BUY={summary['buy']}"
          f" SELL={summary['sell']} BUY+SELL={summary['buySell']} sem_lado={summary['noSide']}")
    print(f"  trades_volume_real_zero={summary['volumeRealZero']}"
          f" trades_volume_real_fracionário={summary['volumeRealFractional']}")
    print(f"  primeiro={format_msc(summary['firstTimeMsc'])} último={format_msc(summary['lastTimeMsc'])}")


def main():
    parser = argparse.ArgumentParser(description="Profundidade histórica de ticks do WINV26")
    parser.add_argument("--date", type=date.fromisoformat, help="consulta manual YYYY-MM-DD")
    parser.add_argument("--minutes", type=int, default=5, help="janela por amostra (1..30, padrão 5)")
    args = parser.parse_args()
    if not 1 <= args.minutes <= 30:
        parser.error("--minutes deve estar entre 1 e 30")

    import MetaTrader5 as mt5
    try:
        if not mt5.initialize():
            raise RuntimeError(f"mt5.initialize() falhou: {mt5.last_error()}")
        definitions = flag_definitions(mt5)
        info = mt5.symbol_info(SYMBOL)
        if info is None:
            raise RuntimeError(f"symbol_info({SYMBOL}) falhou: {mt5.last_error()}")
        latest = mt5.symbol_info_tick(SYMBOL)
        if latest is None:
            raise RuntimeError(f"symbol_info_tick({SYMBOL}) falhou: {mt5.last_error()}")
        latest_day = datetime.fromtimestamp(latest.time_msc / 1000, UTC).date()

        print(f"MetaTrader5 Python: {mt5.__version__}")
        print("Metadados WINV26:", symbol_metadata(info))
        print("Último tick:", format_msc(latest.time_msc))
        print("Símbolos WIN encontrados:")
        for name, description in related_symbols(mt5):
            print(f"  {name}: {description}")
        print()

        if args.date:
            print_summary("data manual", summarize(sample_near(mt5, args.date, args.minutes), definitions))
            return

        summaries = []
        for days in OFFSETS:
            requested = latest_day - timedelta(days=days)
            summary = summarize(sample_near(mt5, requested, args.minutes), definitions)
            summaries.append((days, summary))
            print_summary(f"{days:>3} dias", summary)

        # A one-row request from a distant date discovers the first retained tick
        # without transferring the intervening history.
        discovery_day = latest_day - timedelta(days=max(OFFSETS))
        earliest = first_tick_on_or_after(mt5, discovery_day)
        earliest_trade = first_tick_on_or_after(mt5, discovery_day, mt5.COPY_TICKS_TRADE)
        print()
        if earliest is None:
            print("Primeiro tick disponível: não encontrado desde", discovery_day)
        else:
            first_day = day_of_tick(earliest)
            print("Primeiro tick de qualquer tipo desde a busca:", format_msc(earliest["time_msc"]))
        if earliest_trade is None:
            print("Primeiro trade disponível: não encontrado desde", discovery_day)
        else:
            first_trade_day = day_of_tick(earliest_trade)
            print("Primeiro trade disponível desde a busca:", format_msc(earliest_trade["time_msc"]))
            print_summary("primeiro dia com trade",
                          summarize(sample_near(mt5, first_trade_day, args.minutes, search_days=0), definitions))
            previous = sample_near(mt5, first_trade_day - timedelta(days=1), args.minutes, search_days=0)
            print_summary("dia anterior", summarize(previous, definitions))
    finally:
        mt5.shutdown()


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"Erro no probe histórico MT5: {exc}")
        raise SystemExit(1)
