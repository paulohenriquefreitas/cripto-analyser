"""One MT5 session; NDJSON on stdout, stop/EOF on stdin."""

import json
import sys
import threading

POLL_SECONDS = 0.02


def stream_ticks(mt5, stop_event):
    try:
        if not mt5.initialize():
            raise RuntimeError(f"mt5.initialize() falhou: {mt5.last_error()}")
        previous_time_msc = None
        while not stop_event.is_set():
            tick = mt5.symbol_info_tick("WINV26")
            if tick is None:
                raise RuntimeError(f"Sem tick para WINV26: {mt5.last_error()}")
            if tick.time_msc != previous_time_msc:
                data = {
                    "time": int(tick.time),
                    "timeMsc": int(tick.time_msc),
                    "bid": float(tick.bid),
                    "ask": float(tick.ask),
                    "last": float(tick.last),
                    "volume": float(tick.volume),
                }
                print(json.dumps(data, allow_nan=False), flush=True)
                previous_time_msc = tick.time_msc
            # Interruptible pause: no busy-loop, even when the market is idle.
            stop_event.wait(POLL_SECONDS)
    finally:
        mt5.shutdown()


def main():
    import MetaTrader5 as mt5

    stop_event = threading.Event()

    def wait_for_stop():
        # Java closes stdin for graceful stop. EOF also detects a lost parent.
        sys.stdin.readline()
        stop_event.set()

    threading.Thread(target=wait_for_stop, daemon=True).start()
    stream_ticks(mt5, stop_event)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass  # stream_ticks finally still shuts MT5 down.
    except Exception as exc:
        print(f"Erro no stream MT5: {exc}", file=sys.stderr, flush=True)
        sys.exit(1)
