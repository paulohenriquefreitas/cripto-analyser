"""Offline temporal audit of experimental joint WINZ26 capture; no MT5 access."""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import statistics

from mt5_dom_persistence import timestamp
from mt5_dom_probe import analyze as validate_book
from mt5_experimental_aggressor import aggressor_side, unknown_flag_mask


def dist(values):
    values = sorted(values)
    return {"count": len(values), "min": min(values) if values else None,
        "median": statistics.median(values) if values else None,
        "p90": values[math.ceil(.9*len(values))-1] if values else None,
        "max": max(values) if values else None,
        "mean": statistics.mean(values) if values else None}


def utc(ms):
    return datetime.fromtimestamp(ms/1000, timezone.utc).isoformat(timespec="milliseconds")


def association(trade_ms, intervals, offset_ms, uncertainty_ms, precision_ms=1):
    """Conditional association only; source timestamps are never overwritten."""
    low = trade_ms + offset_ms - uncertainty_ms
    high = trade_ms + offset_ms + uncertainty_ms
    # Candidate interval contains some possible trade/local observation ordering.
    candidates = [i for i, interval in enumerate(intervals)
                  if interval["possible_start_ms"]-precision_ms <= high
                  and interval["possible_end_ms"]+precision_ms >= low]
    robust = [i for i in candidates if intervals[i]["usable"]
              and low > intervals[i]["safe_start_ms"]+precision_ms
              and high < intervals[i]["safe_end_ms"]-precision_ms]
    if len(robust) == 1 and len(candidates) == 1:
        status = "unique_conditional"
    elif not candidates:
        status = "outside_dom_interval_coverage"
    elif not any(intervals[i]["usable"] for i in candidates):
        status = "unknown_dom_period"
    else:
        status = "indeterminate_boundary_or_uncertainty"
    return {"status": status, "candidate_intervals": candidates,
            "unique_interval": robust[0] if status == "unique_conditional" else None}


def analyze_file(path, max_gap_ms=500):
    if not math.isfinite(max_gap_ms) or max_gap_ms <= 0:
        raise ValueError("max_gap_ms deve ser positivo e finito")
    path = Path(path)
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    dom, batches, trades = [], [], []
    failures = []
    symbol_seen = False
    previous_mono = None
    previous_tick = None
    wall_anchors = []
    sides, unknown = Counter(), Counter()
    quote_count = 0
    with path.open(encoding="utf-8-sig") as stream:
        for line_number, line in enumerate(stream, 1):
            try:
                record = json.loads(line)
                kind = record["kind"]
                if kind == "session":
                    if record["symbol"] != "WINZ26": raise ValueError("Contrato inesperado")
                    symbol_seen = True
                if kind == "tick_query_error": failures.append(record)
                if kind not in ("snapshot", "tick_batch"): continue
                if record["symbol"] != "WINZ26": raise ValueError("Contrato inesperado")
                start, end = timestamp(record["captured_utc"]), timestamp(record["completed_utc"])
                mono_start, mono_end = record["monotonic_start"]*1000, record["monotonic_end"]*1000
                if not all(math.isfinite(x) for x in (start,end,mono_start,mono_end)):
                    raise ValueError("Timestamp nao finito")
                if end < start or mono_end < mono_start: raise ValueError("Janela invertida")
                if previous_mono is not None and mono_start < previous_mono:
                    raise ValueError("Chamadas sobrepostas ou fora de ordem")
                previous_mono = mono_end
                wall_anchors.append(start-mono_start)
                window = {"sequence": record["sequence"], "start_ms": start, "end_ms": end,
                          "monotonic_start_ms": mono_start, "monotonic_end_ms": mono_end}
                target = dom if kind == "snapshot" else batches
                if window["sequence"] != len(target)+1: raise ValueError("Sequencia nao contigua")
                if kind == "snapshot":
                    raw = record["levels"]
                    window["status"] = "query_failure" if raw is None or record.get("error") else "empty" if not raw else "invalid" if validate_book(raw)["anomalies"] else "valid"
                else:
                    for index, item in enumerate(record["ticks"]):
                        raw = item["raw"]
                        flags, market_ms = int(raw["flags"]), int(raw["time_msc"])
                        if previous_tick is not None and market_ms < previous_tick:
                            raise ValueError("Ticks fora de ordem")
                        if int(raw["time"]) != market_ms//1000: raise ValueError("time/time_msc inconsistentes")
                        previous_tick = market_ms
                        unknown[unknown_flag_mask(flags)] += 1
                        if not flags & (8|16):
                            quote_count += 1
                            continue
                        side = aggressor_side(flags)
                        sides[side] += 1
                        trades.append({"trade_index": len(trades)+1, "batch_sequence": record["sequence"],
                            "index_in_batch": index, "market_time_msc": market_ms, "market_utc": utc(market_ms),
                            "flags": flags, "side": side, "unknown_flag_mask": unknown_flag_mask(flags),
                            "query_start_minus_market_ms": start-market_ms,
                            "query_end_minus_market_ms": end-market_ms})
                target.append(window)
            except (KeyError, ValueError, TypeError, OverflowError) as exc:
                raise ValueError(f"Linha {line_number}: {exc}") from exc
    if not symbol_seen or not dom: raise ValueError("Captura WINZ26/DOM ausente")
    if hashlib.sha256(path.read_bytes()).hexdigest() != digest: raise ValueError("Fonte alterada durante leitura")
    intervals = []
    for a,b in zip(dom,dom[1:]):
        elapsed = b["monotonic_start_ms"]-a["monotonic_start_ms"]
        intervals.append({"from_sequence": a["sequence"], "to_sequence": b["sequence"],
            "elapsed_ms": elapsed, "possible_start_ms": a["start_ms"], "possible_end_ms": b["end_ms"],
            "safe_start_ms": a["end_ms"], "safe_end_ms": b["start_ms"],
            "usable": a["status"]==b["status"]=="valid" and elapsed <= max_gap_ms
                      and b["start_ms"] > a["end_ms"]})
    scenarios = []
    # Explicit sensitivity hypotheses, never inferred calibration of the source clock.
    for offset in (0,10800000):
        for uncertainty in (0,25,50,100,1000):
            counts = Counter()
            for trade in trades:
                counts[association(trade["market_time_msc"],intervals,offset,uncertainty)["status"]] += 1
            scenarios.append({"hypothesis_only": True, "market_to_local_offset_ms": offset,
                              "clock_uncertainty_ms": uncertainty, "counts": dict(counts)})
    def window_metrics(windows, target):
        gaps = [b["monotonic_start_ms"]-a["monotonic_start_ms"] for a,b in zip(windows,windows[1:])]
        return {"query_count":len(windows), "start_intervals_ms":dist(gaps),
            "query_duration_ms":dist([w["monotonic_end_ms"]-w["monotonic_start_ms"] for w in windows]),
            "excess_over_target_ms":dist([max(0,x-target) for x in gaps]),
            "gaps_over_max_gap":sum(x>max_gap_ms for x in gaps)}
    starts=[t["query_start_minus_market_ms"] for t in trades]
    ends=[t["query_end_minus_market_ms"] for t in trades]
    return {"analysis":"experimental_joint_temporal_audit", "report_version":1,
        "source":str(path.resolve()), "source_sha256":digest, "source_unchanged":True,
        "symbol":"WINZ26", "max_gap_ms":max_gap_ms,
        "dom":{**window_metrics(dom,100), "statuses":dict(Counter(w['status'] for w in dom)),
               "first_utc":utc(dom[0]['start_ms']),"last_utc":utc(dom[-1]['start_ms'])},
        "ticks":{**window_metrics(batches,20), "quote_ticks":quote_count, "trade_ticks":len(trades),
                 "trade_sides":dict(sides),"unknown_flag_masks":dict(unknown),"query_errors":failures},
        "trade_market_intervals_ms":dist([b['market_time_msc']-a['market_time_msc'] for a,b in zip(trades,trades[1:])]),
        "trade_market_first_utc":trades[0]['market_utc'] if trades else None,
        "trade_market_last_utc":trades[-1]['market_utc'] if trades else None,
        "query_start_minus_market_ms":dist(starts), "query_end_minus_market_ms":dist(ends),
        "local_wall_minus_monotonic_spread_ms":max(wall_anchors)-min(wall_anchors) if wall_anchors else None,
        "strict_association":{"clock_offset_known":False,"clock_uncertainty_bound_ms":None,
            "uniquely_established":0,"indeterminate":len(trades)},
        "conditional_association_scenarios":scenarios, "dom_intervals":intervals, "trades":trades,
        "assessment":"clock_alignment_unverified_exploration_only_under_explicit_hypotheses",
        "limitations":["Offset e atraso do feed nao sao separaveis por timestamps de recepcao.",
            "Cenarios +3h sao hipoteses explicitas, nao correcao de timezone comprovada.",
            "Precisao textual UTC de 1ms nao equivale a exatidao; cenarios expandem bordas locais em 1ms.",
            "completed_utc de tick_batch inclui processamento local; monotonic_end marca retorno da consulta.",
            "DOM nao tem timestamp de origem; intervalos sao observacoes locais, nao eventos de bolsa.",
            "Duplicatas legitimas e intervalos zero entre negocios sao preservados.",
            "Sem dados externos nao se certifica ausencia de perdas nem causalidade negocio/livro."]}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input',type=Path)
    parser.add_argument('--output',type=Path)
    parser.add_argument('--max-gap-ms',type=float,default=500)
    args=parser.parse_args()
    try:
        payload=json.dumps(analyze_file(args.input,args.max_gap_ms),ensure_ascii=False,allow_nan=False,indent=2)
        if args.output:
            with args.output.open('x',encoding='utf-8') as out: out.write(payload+'\n')
        else: print(payload)
    except (OSError,ValueError) as exc: parser.exit(1,f'Erro: {exc}\n')


if __name__=='__main__': main()
