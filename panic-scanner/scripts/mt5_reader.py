"""Read MT5 candles (default WINV26 M5); stdout is exclusively JSON."""

import json
import sys
import argparse
from datetime import date, datetime, time, timedelta, timezone


def read_candles(tick_only=False, symbol="WINV26", timeframe="M5", from_date=None, to_date=None):
    if (from_date is None) != (to_date is None):
        raise ValueError("--from e --to devem ser informados juntos")
    if from_date is not None and from_date > to_date:
        raise ValueError("--from deve ser anterior ou igual a --to")
    if tick_only and (timeframe != "M5" or from_date is not None):
        raise ValueError("--tick nao aceita timeframe ou intervalo historico")
    import MetaTrader5 as mt5

    try:
        if not mt5.initialize():
            raise RuntimeError(f"mt5.initialize() falhou: {mt5.last_error()}")
        if tick_only:
            tick = mt5.symbol_info_tick(symbol)
            if tick is None:
                raise RuntimeError(f"Sem tick para WINV26: {mt5.last_error()}")
            return {
                "time": int(tick.time),
                "time_msc": int(tick.time_msc),
                "bid": float(tick.bid),
                "ask": float(tick.ask),
                "last": float(tick.last),
                "volume": float(tick.volume),
            }
        if timeframe not in ("M5", "D1"):
            raise ValueError("Timeframe suportado: M5 ou D1")
        period = getattr(mt5, "TIMEFRAME_" + timeframe)
        # Keep the legacy no-argument query exactly as before.
        if symbol != "WINV26" or timeframe != "M5" or from_date is not None:
            if not mt5.symbol_select(symbol, True):
                raise RuntimeError(f"symbol_select({symbol}) falhou: {mt5.last_error()}")
        if from_date is None:
            rates = mt5.copy_rates_from_pos(symbol, period, 0, 1000)
        else:
            start = datetime.combine(from_date, time.min, timezone.utc)
            end = datetime.combine(to_date + timedelta(days=1), time.min, timezone.utc)
            rates = mt5.copy_rates_range(symbol, period, start, end - timedelta(seconds=1))
            if rates is not None:
                rates = [r for r in rates if start.timestamp() <= int(r["time"]) < end.timestamp()]
        if rates is None or len(rates) == 0:
            raise RuntimeError(f"Sem candles para {symbol} {timeframe}: {mt5.last_error()}")
        return [
            {
                "time": int(rate["time"]),
                "open": float(rate["open"]),
                "high": float(rate["high"]),
                "low": float(rate["low"]),
                "close": float(rate["close"]),
                "tickVolume": int(rate["tick_volume"]),
                "realVolume": int(rate["real_volume"]),
            }
            for rate in rates
        ]
    finally:
        mt5.shutdown()


if __name__ == "__main__":
    try:
        parser = argparse.ArgumentParser()
        parser.add_argument("--tick", action="store_true")
        parser.add_argument("--symbol", default="WINV26")
        parser.add_argument("--timeframe", choices=("M5", "D1"), default="M5")
        parser.add_argument("--from", dest="from_date", type=date.fromisoformat)
        parser.add_argument("--to", dest="to_date", type=date.fromisoformat)
        args = parser.parse_args()
        payload = json.dumps(read_candles(args.tick, args.symbol, args.timeframe,
                                         args.from_date, args.to_date), allow_nan=False)
    except Exception as exc:
        print(f"Erro ao consultar MT5: {exc}", file=sys.stderr)
        sys.exit(1)
    print(payload)
