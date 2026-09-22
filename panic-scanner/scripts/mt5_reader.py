"""Read WINV26 M5 candles; stdout is exclusively JSON."""

import json
import sys


def read_candles(tick_only=False):
    import MetaTrader5 as mt5

    try:
        if not mt5.initialize():
            raise RuntimeError(f"mt5.initialize() falhou: {mt5.last_error()}")
        if tick_only:
            tick = mt5.symbol_info_tick("WINV26")
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
        rates = mt5.copy_rates_from_pos("WINV26", mt5.TIMEFRAME_M5, 0, 1000)
        if rates is None or len(rates) == 0:
            raise RuntimeError(f"Sem candles para WINV26 M5: {mt5.last_error()}")
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
        if sys.argv[1:] not in ([], ["--tick"]):
            raise ValueError("Uso: python mt5_reader.py [--tick]")
        payload = json.dumps(read_candles(tick_only=bool(sys.argv[1:])), allow_nan=False)
    except Exception as exc:
        print(f"Erro ao consultar MT5: {exc}", file=sys.stderr)
        sys.exit(1)
    print(payload)
