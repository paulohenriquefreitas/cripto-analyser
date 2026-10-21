"""Manual read-only Windows/MT5 WINZ26 clock diagnostic; no clock correction."""
from __future__ import annotations

import argparse
from dataclasses import asdict
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import statistics
import subprocess
import sys
import time

from mt5_canonical_price_stream import Cursor, Counters, query
from mt5_dom_probe import SYMBOL, json_safe
from mt5_dom_trades_probe import raw_tick


def clock_sample(now=None):
    instant = (now or (lambda: datetime.now(timezone.utc)))()
    if instant.utcoffset() is None:
        raise ValueError("Relogio exige datetime com timezone")
    utc = instant.astimezone(timezone.utc)
    local = utc.astimezone()
    return {"utc": utc.isoformat(timespec="microseconds"),
            "unix_ns_approx": int(utc.timestamp()*1_000_000_000),
            "local": local.isoformat(timespec="microseconds"),
            "local_utc_offset_seconds": local.utcoffset().total_seconds(),
            "local_timezone_name": local.tzname()}


def windows_sync(runner=subprocess.run):
    records = []
    for command in (["w32tm", "/query", "/status", "/verbose"],
                    ["w32tm", "/query", "/source"]):
        try:
            completed = runner(command, capture_output=True, text=True, errors="replace", timeout=2,
                               check=False)
            output = completed.stdout or ""
            # Preserve localized evidence; never interpret missing offset as zero.
            offset_lines = [line for line in output.splitlines()
                            if any(word in line.casefold() for word in ("offset", "deslocamento", "defasagem"))]
            records.append({"command": command, "returncode": completed.returncode,
                "stdout": output, "stderr": completed.stderr, "offset_evidence_lines": offset_lines,
                "available": completed.returncode == 0})
        except (OSError, subprocess.TimeoutExpired) as exc:
            records.append({"command": command, "available": False, "error": str(exc)})
    return {"queries": records, "offset_interpretation": "Evidence only in raw localized output; unavailable is not zero",
            "external_clock_accuracy_verified": False}


def describe_time(raw_time, time_msc):
    return {"raw_time": raw_time, "raw_time_msc": time_msc,
        "time_as_epoch_utc": datetime.fromtimestamp(raw_time, timezone.utc).isoformat(timespec="milliseconds"),
        "time_msc_as_epoch_utc": datetime.fromtimestamp(time_msc/1000, timezone.utc).isoformat(timespec="milliseconds"),
        "seconds_consistent": raw_time == time_msc//1000}


def summary_values(values):
    return {"count":len(values),"min":min(values) if values else None,
            "median":statistics.median(values) if values else None,
            "max":max(values) if values else None}


def run(mt5, seconds=5, *, clock=time.monotonic, sleep=time.sleep, now=None, runner=subprocess.run):
    if not math.isfinite(seconds) or not 0 < seconds <= 10:
        raise ValueError("seconds deve ser maior que zero e no maximo 10")
    report = {"diagnostic":"experimental_windows_mt5_clocks", "symbol":SYMBOL,
              "windows_clock":clock_sample(now), "windows_sync":windows_sync(runner),
              "batches":[], "errors":[]}
    counters = Counters()
    differences_start, differences_end, new_differences = [], [], []
    seed = None
    try:
        # Explicit timeout avoids the default long terminal connection wait.
        if not mt5.initialize(timeout=3000):
            raise RuntimeError(f"initialize: {mt5.last_error()}")
        account = mt5.account_info()
        if account is None: raise RuntimeError(f"account_info: {mt5.last_error()}")
        report['mt5'] = {"company":account.company,"server":account.server}
        if 'clear' not in (account.company+' '+account.server).lower():
            raise RuntimeError("Terminal nao identificado como Clear")
        info_start = clock_sample(now)
        info = mt5.symbol_info(SYMBOL)
        info_end = clock_sample(now)
        if info is None: raise RuntimeError(f"symbol_info: {mt5.last_error()}")
        report['symbol_info'] = {"raw":info,"query_start":info_start,"query_end":info_end,
            "time_as_epoch_utc":datetime.fromtimestamp(info.time,timezone.utc).isoformat(timespec='milliseconds')}
        initial_start = clock_sample(now)
        initial = mt5.symbol_info_tick(SYMBOL)
        initial_end = clock_sample(now)
        report['initial_tick'] = {"raw":initial,"query_start":initial_start,"query_end":initial_end}
        if initial is None or int(initial.time_msc)<=0:
            raise RuntimeError("Tick inicial sem time_msc valido; sem cursor substituto ou symbol_select")
        seed = int(initial.time_msc)
        report['initial_tick']['interpretation'] = describe_time(int(initial.time), seed)
        report['initial_tick']['query_start_minus_market_ms'] = datetime.fromisoformat(initial_start['utc']).timestamp()*1000-seed
        cursor = Cursor(seed,0)
        started = clock()
        deadline = started+seconds
        while clock()<deadline:
            begin_mono = clock()
            start = clock_sample(now)
            try:
                rows,updated,drained = query(mt5,SYMBOL,cursor,2**63-1,counters)
            except Exception as exc:
                report['batches'].append({"query_start":start,"query_end":clock_sample(now),
                    "monotonic_start":begin_mono,"monotonic_end":clock(),"error":str(exc),"cursor":asdict(cursor)})
                raise
            end_mono = clock()
            end = clock_sample(now)  # Immediately after return, before processing rows.
            ticks = []
            for row in rows:
                raw = raw_tick(row)
                ms = int(raw['time_msc'])
                before = datetime.fromisoformat(start['utc']).timestamp()*1000-ms
                after = datetime.fromisoformat(end['utc']).timestamp()*1000-ms
                differences_start.append(before); differences_end.append(after)
                if ms>seed: new_differences.append(before)
                ticks.append({"raw":raw,"interpretation":describe_time(int(raw['time']),ms),
                    "strictly_after_initial_time":ms>seed,
                    "query_start_minus_market_ms":before,"query_end_minus_market_ms":after})
            report['batches'].append({"sequence":len(report['batches'])+1,"query_start":start,"query_end":end,
                "monotonic_start":begin_mono,"monotonic_end":end_mono,
                "query_duration_ms":(end_mono-begin_mono)*1000,
                "cursor_before":asdict(cursor),"cursor_after":asdict(updated),"drained":drained,"ticks":ticks})
            cursor=updated
            sleep(max(0,min(deadline,begin_mono+.02 if drained else clock())-clock()))
        report['capture_elapsed_seconds']=clock()-started
    except KeyboardInterrupt:
        report['errors'].append('interrupted')
    except Exception as exc:
        report['errors'].append(str(exc))
    finally:
        try: mt5.shutdown()
        except Exception as exc: report['errors'].append(f'shutdown: {exc}')
    report['finished_clock']=clock_sample(now)
    report['counts']={"tick_queries":len(report['batches']),"ticks":len(differences_start),
                      "ticks_strictly_after_initial_time":len(new_differences)}
    report['tick_counters']=asdict(counters)
    report['observed_differences_ms']={"query_start_minus_market":summary_values(differences_start),
        "query_end_minus_market":summary_values(differences_end),
        "new_ticks_query_start_minus_market":summary_values(new_differences)}
    median=statistics.median(new_differences) if new_differences else None
    hour_candidate=round(median/3600000)*3600000 if median is not None else None
    report['interpretation']={
        "apparent_offset":{"nearest_whole_hour_ms":hour_candidate,"hypothesis_only":True,
            "residual_if_that_hypothesis_ms":summary_values([x-hour_candidate for x in new_differences]) if hour_candidate is not None else None,
            "cause_established":False,"timestamps_corrected":False},
        "reception_delay":{"measured_independently":False,
            "reason":"Diferenca local/mercado mistura offset, idade do tick, entrega e polling; residual nao e latencia medida."},
        "limitations":["w32tm informa estado/estimativas do Windows, nao certifica relogio do feed nem UTC externo neste teste.",
            "Fuso local e rotulagem UTC explicitados; epoch original preservado.",
            "Primeiro lote pode conter ticks de borda anteriores a janela; novos sao destacados por time_msc > inicial.",
            "Nao ha ajuste de relogio, symbol_select, configuracao MT5, ordens ou escrita historica.",
            "Chamadas sincronas podem ultrapassar deadline; 10s limita agendamento, nao interrupcao forcada de API."]}
    report['status']='incomplete' if report['errors'] else 'new_ticks_observed' if new_differences else 'no_new_ticks_observed'
    return report


def duration(value):
    seconds=float(value)
    if not math.isfinite(seconds) or not 0<seconds<=10:
        raise argparse.ArgumentTypeError('Informe segundos >0 e <=10')
    return seconds


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--seconds',type=duration,default=5)
    parser.add_argument('--output',type=Path)
    args=parser.parse_args()
    import MetaTrader5 as mt5
    # Open before connection so an existing output cannot trigger a live diagnostic.
    if args.output:
        with args.output.open('x',encoding='utf-8') as out:
            report=run(mt5,args.seconds)
            out.write(json.dumps(json_safe(report),ensure_ascii=False,allow_nan=False,indent=2)+'\n')
        print(f"status={report['status']} arquivo={args.output.resolve()}")
    else:
        report=run(mt5,args.seconds)
        print(json.dumps(json_safe(report),ensure_ascii=False,allow_nan=False,indent=2))
    return 0 if report['status']=='new_ticks_observed' else 1


if __name__=='__main__': raise SystemExit(main())
