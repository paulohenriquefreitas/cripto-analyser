"""Bounded Times & Trades validation for the unadjusted WIN$N continuous symbol."""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta, timezone
from pathlib import Path

from mt5_ticks_diagnostic import analyse, flag_definitions, is_trade


UTC = timezone.utc
CONTINUOUS = "WIN$N"
CONTRACT = "WINV26"
OFFSETS = (0, 30, 60, 90, 180, 270, 365)
COMPARISON_DATES = (date(2026, 7, 15), date(2026, 8, 20), date(2026, 9, 10))
ROLLOVER_DATES = (date(2026, 8, 10), date(2026, 8, 11), date(2026, 8, 12), date(2026, 8, 13))


@dataclass(frozen=True)
class Window:
    requested: datetime
    start: datetime | None
    end: datetime | None
    rows: tuple


def utc_millis(value: datetime) -> int:
    return int(value.timestamp() * 1000)


def side(flags: int, definitions: dict[str, int]) -> str:
    buy = bool(flags & definitions["BUY"])
    sell = bool(flags & definitions["SELL"])
    return "AMBIGUOUS" if buy and sell else "BUY" if buy else "SELL" if sell else "NONE"


def trade_identity(row, definitions: dict[str, int]) -> tuple:
    """Full trade identity; position in the returned array disambiguates duplicates."""
    return (int(row["time_msc"]), float(row["last"]), float(row["volume_real"]),
            side(int(row["flags"]), definitions))


def trade_rows(rows, definitions):
    return [row for row in rows if is_trade(int(row["flags"]), definitions)]


def positional_comparison(left_rows, right_rows, definitions):
    """Compare ordered trades by ordinal, without treating time_msc as a key."""
    left = trade_rows(left_rows, definitions)
    right = trade_rows(right_rows, definitions)
    paired = min(len(left), len(right))
    denominator = max(len(left), len(right))
    fields = {name: 0 for name in ("timestamp", "price", "volume", "marketData", "side", "identical")}
    for index in range(paired):
        a = trade_identity(left[index], definitions)
        b = trade_identity(right[index], definitions)
        equal = (a[0] == b[0], a[1] == b[1], a[2] == b[2], a[3] == b[3])
        fields["timestamp"] += equal[0]
        fields["price"] += equal[1]
        fields["volume"] += equal[2]
        fields["marketData"] += all(equal[:3])
        fields["side"] += equal[3]
        fields["identical"] += all(equal)

    def percentage(count):
        return None if denominator == 0 else round(100 * count / denominator, 6)

    return {
        "tradesContinuous": len(left), "tradesContract": len(right),
        "pairedByOrdinal": paired, "denominator": denominator,
        **{name + "Matches": count for name, count in fields.items()},
        **{name + "Percent": percentage(count) for name, count in fields.items()},
    }


def bounded_rows(mt5, symbol, start, end, copy_flag):
    copied = mt5.copy_ticks_range(symbol, start, end, copy_flag)
    if copied is None:
        raise RuntimeError(f"copy_ticks_range({symbol}) falhou: {mt5.last_error()}")
    lower, upper = utc_millis(start), utc_millis(end)
    return tuple(row for row in copied if lower <= int(row["time_msc"]) < upper)


def sample_on_or_after(mt5, symbol, requested, minutes, search_days=4):
    """Try short fixed windows on nearby dates; never scan the intervening history."""
    for days in range(search_days + 1):
        start = requested + timedelta(days=days)
        end = start + timedelta(minutes=minutes)
        rows = bounded_rows(mt5, symbol, start, end, mt5.COPY_TICKS_ALL)
        if trade_rows(rows, flag_definitions(mt5)):
            return Window(requested, start, end, rows)
    return Window(requested, None, None, ())


def summary(window, definitions):
    if not window.rows:
        return {"requested": window.requested.isoformat(), "available": False}
    stats = analyse(window.rows, definitions)
    trades = trade_rows(window.rows, definitions)
    return {
        "requested": window.requested.isoformat(), "available": True,
        "start": window.start.isoformat(timespec="milliseconds"),
        "end": window.end.isoformat(timespec="milliseconds"),
        "events": stats["total"], "trades": stats["trades"],
        "last": stats["flag_last"], "volumeFlag": stats["flag_volume"],
        "buy": stats["buy"], "sell": stats["sell"],
        "buySell": stats["buy_sell"], "noSide": stats["no_side"],
        "volumeDiff": stats["volume_diff"],
        "tradeVolumeRealZero": stats["trade_volume_real_zero"],
        "tradeVolumeRealFractional": stats["trade_volume_real_fractional"],
        "sharedTimeMsc": stats["time_msc_with_multiple_events"],
        "ordered": all(int(window.rows[i]["time_msc"]) <= int(window.rows[i + 1]["time_msc"])
                       for i in range(len(window.rows) - 1)),
        "firstTradeMsc": int(trades[0]["time_msc"]) if trades else None,
        "lastTradeMsc": int(trades[-1]["time_msc"]) if trades else None,
    }


def comparison_window(mt5, start, minutes):
    end = start + timedelta(minutes=minutes)
    return (bounded_rows(mt5, CONTINUOUS, start, end, mt5.COPY_TICKS_ALL),
            bounded_rows(mt5, CONTRACT, start, end, mt5.COPY_TICKS_ALL))


def run(mt5, minutes):
    definitions = flag_definitions(mt5)
    latest = mt5.symbol_info_tick(CONTINUOUS)
    info = mt5.symbol_info(CONTINUOUS)
    if latest is None or info is None:
        raise RuntimeError(f"{CONTINUOUS} indisponível: {mt5.last_error()}")
    latest_at = datetime.fromtimestamp(latest.time_msc / 1000, UTC)
    report = {
        "generatedAt": datetime.now(UTC).isoformat(timespec="seconds"),
        "metaTraderVersion": mt5.__version__, "symbol": CONTINUOUS,
        "description": info.description, "latestTick": latest_at.isoformat(timespec="milliseconds"),
        "minutesPerWindow": minutes, "depth": [], "comparisons": [], "rollover": [],
    }
    print(f"Último tick {CONTINUOUS}: {report['latestTick']}", flush=True)
    anchor = datetime.combine(latest_at.date(), time(15, 0), UTC)
    for offset in OFFSETS:
        requested = latest_at - timedelta(minutes=minutes) if offset == 0 else anchor - timedelta(days=offset)
        try:
            item = summary(sample_on_or_after(mt5, CONTINUOUS, requested, minutes), definitions)
        except RuntimeError as exc:
            item = {"requested": requested.isoformat(), "available": False, "error": str(exc)}
        item["offsetDays"] = offset
        report["depth"].append(item)
        print(f"Profundidade {offset:>3}d: {item.get('start', 'SEM DADOS')} "
              f"trades={item.get('trades', 0)}", flush=True)
    for day in COMPARISON_DATES:
        start = datetime.combine(day, time(15, 0), UTC)
        continuous, contract = comparison_window(mt5, start, minutes)
        item = {"start": start.isoformat(),
                "continuous": summary(Window(start, start, start + timedelta(minutes=minutes), continuous), definitions),
                "contract": summary(Window(start, start, start + timedelta(minutes=minutes), contract), definitions),
                "tradeComparison": positional_comparison(continuous, contract, definitions)}
        report["comparisons"].append(item)
        print(f"Comparação {day}: {item['tradeComparison']}", flush=True)
    for day in ROLLOVER_DATES:
        start = datetime.combine(day, time(15, 0), UTC)
        continuous, contract = comparison_window(mt5, start, minutes)
        item = {"start": start.isoformat(),
                "continuous": summary(Window(start, start, start + timedelta(minutes=minutes), continuous), definitions),
                "contract": summary(Window(start, start, start + timedelta(minutes=minutes), contract), definitions),
                "tradeComparison": positional_comparison(continuous, contract, definitions)}
        report["rollover"].append(item)
        print(f"Rollover {day}: {item['tradeComparison']}", flush=True)
    return report


def main():
    parser = argparse.ArgumentParser(description="Valida Times & Trades do WIN$N")
    parser.add_argument("--minutes", type=int, default=1, choices=range(1, 6))
    parser.add_argument("--output", type=Path, default=Path("win_continuous_probe.json"))
    args = parser.parse_args()
    import MetaTrader5 as mt5
    try:
        if not mt5.initialize():
            raise RuntimeError(f"mt5.initialize() falhou: {mt5.last_error()}")
        report = run(mt5, args.minutes)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"Relatório bruto: {args.output.resolve()}", flush=True)
    finally:
        mt5.shutdown()


if __name__ == "__main__":
    main()
