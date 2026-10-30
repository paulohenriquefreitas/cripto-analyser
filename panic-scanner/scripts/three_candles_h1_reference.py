"""Read-only H1/M5 validation fixture for strategy.threecandles; never opens a tick stream."""
import argparse
import json
from datetime import datetime, timezone, timedelta
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--symbol', default='WIN$N')
    parser.add_argument('--from', dest='start', required=True)
    parser.add_argument('--to', dest='end', required=True, help='Inclusive session date')
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    start = datetime.fromisoformat(args.start).replace(tzinfo=timezone.utc)
    end = datetime.fromisoformat(args.end).replace(tzinfo=timezone.utc) + timedelta(days=1)
    if end <= start:
        raise ValueError('Invalid date range')
    import MetaTrader5 as mt5
    if not mt5.initialize():
        raise RuntimeError(mt5.last_error())
    try:
        data = {'symbol': args.symbol, 'source': 'MT5 copy_rates_range read-only', 'series': {}}
        for name, period in [('M5', mt5.TIMEFRAME_M5), ('H1', mt5.TIMEFRAME_H1)]:
            rows = mt5.copy_rates_range(args.symbol, period, start, end - timedelta(seconds=1))
            if rows is None or not len(rows):
                raise RuntimeError(f'Missing {name}: {mt5.last_error()}')
            data['series'][name] = [{key: int(row[key]) if key == 'time' else float(row[key])
                                    for key in ['time', 'open', 'high', 'low', 'close']} for row in rows]
        path = Path(args.output)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data, allow_nan=False), encoding='utf-8')
        print(json.dumps({'file': str(path), 'counts': {k: len(v) for k, v in data['series'].items()}}))
    finally:
        mt5.shutdown()


if __name__ == '__main__':
    main()
