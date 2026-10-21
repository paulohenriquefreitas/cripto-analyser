"""Experimental offline DOM imbalance, descriptive only; no MT5 connection."""
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

DEPTHS = (1, 3, 5, 10)


def distribution(values):
    ordered = sorted(values)
    return {"count": len(ordered), "min": min(ordered) if ordered else None,
            "max": max(ordered) if ordered else None,
            "mean": statistics.mean(ordered) if ordered else None,
            "median": statistics.median(ordered) if ordered else None,
            "p10": ordered[math.ceil(.1 * len(ordered)) - 1] if ordered else None,
            "p90": ordered[math.ceil(.9 * len(ordered)) - 1] if ordered else None,
            "stddev_population": statistics.pstdev(ordered) if ordered else None}


def depth_values(bids, asks, depth):
    if len(bids) < depth or len(asks) < depth:
        return {"status": "insufficient_levels", "bid_volume": None, "ask_volume": None, "imbalance": None}
    bid = math.fsum(row["volume_dbl"] for row in bids[:depth])
    ask = math.fsum(row["volume_dbl"] for row in asks[:depth])
    denominator = bid + ask
    if not math.isfinite(denominator):
        raise ValueError("Soma de volumes nao finita")
    return {"status": "zero_denominator" if denominator == 0 else "valid",
            "bid_volume": bid, "ask_volume": ask,
            "imbalance": (bid - ask) / denominator if denominator else None}


def analyze_file(path, max_gap_ms=500):
    if not math.isfinite(max_gap_ms) or max_gap_ms <= 0:
        raise ValueError("max_gap_ms deve ser positivo e finito")
    source = Path(path)
    digest = hashlib.sha256(source.read_bytes()).hexdigest()
    series = []
    previous_time = None
    symbol_seen = False
    quality = Counter({name: 0 for name in ("large_gaps", "query_failure", "empty_book", "invalid_book", "valid_book")})
    with source.open(encoding="utf-8-sig") as stream:
        for line_number, line in enumerate(stream, 1):
            try:
                record = json.loads(line, parse_constant=lambda x: (_ for _ in ()).throw(ValueError(f"JSON nao finito: {x}")))
                kind = record["kind"]
                if kind in ("symbol_info", "summary"):
                    symbol = record["value"]["name"] if kind == "symbol_info" else record["symbol"]
                    if symbol != "WINZ26":
                        raise ValueError(f"Contrato inesperado: {symbol}")
                    symbol_seen = True
                if kind != "snapshot":
                    continue
                sequence = record["sequence"]
                if type(sequence) is not int or sequence != len(series) + 1:
                    raise ValueError("Sequencia nao contigua")
                now = timestamp(record["captured_utc"])
                if previous_time is not None and now < previous_time:
                    raise ValueError("Snapshots fora de ordem temporal")
                gap = previous_time is not None and now - previous_time > max_gap_ms
                quality["large_gaps"] += int(gap)
                previous_time = now
                raw = record["levels"]
                issues = []
                if raw is None or record.get("error") is not None:
                    status = "query_failure"
                elif not isinstance(raw, list):
                    raise ValueError("levels deve ser lista ou null")
                elif not raw:
                    status = "empty_book"
                else:
                    issues = validate_book(raw)["anomalies"]
                    # Zero quantities are explicitly retained for denominator checks.
                    # Missing sides are handled independently as insufficient depth.
                    allowed = {"missing_limit_side"}
                    for i, row in enumerate(raw):
                        for field in ("volume", "volume_dbl"):
                            if type(row[field]) in (int, float) and row[field] == 0:
                                allowed.add(f"invalid_{field}:{i}")
                        if row["type"] not in (1, 2):
                            issues.append(f"unsupported_non_limit_type:{i}")
                        if all(type(row[f]) in (int, float) and math.isfinite(row[f]) and row[f] >= 0
                               for f in ("volume", "volume_dbl")) and int(row["volume_dbl"]) != row["volume"]:
                            issues.append(f"quantity_mismatch:{i}")
                    issues = sorted(set(issues) - allowed)
                    status = "invalid_book" if issues else "valid_book"
                quality[status] += 1
                row = {"sequence": sequence, "captured_utc": record["captured_utc"],
                       "time_ms": now, "gap_before": gap, "status": status, "issues": issues, "depths": {}}
                if status == "valid_book":
                    # Rank from best price. Probe's original ASK order is worst-to-best.
                    bids = sorted((r for r in raw if r["type"] == 2), key=lambda r: r["price"], reverse=True)
                    asks = sorted((r for r in raw if r["type"] == 1), key=lambda r: r["price"])
                    row.update(bid_levels=len(bids), ask_levels=len(asks))
                    for depth in DEPTHS:
                        row["depths"][str(depth)] = depth_values(bids, asks, depth)
                else:
                    row["depths"] = {str(depth): {"status": status, "bid_volume": None,
                                      "ask_volume": None, "imbalance": None} for depth in DEPTHS}
                series.append(row)
            except (KeyError, TypeError, ValueError, OverflowError) as exc:
                raise ValueError(f"Linha {line_number}: {exc}") from exc
    if not symbol_seen or not series:
        raise ValueError("Arquivo exige identificacao WINZ26 e snapshots")
    if digest != hashlib.sha256(source.read_bytes()).hexdigest():
        raise ValueError("Fonte mudou durante a analise")
    summaries = {}
    for depth in DEPTHS:
        key = str(depth)
        valid = []
        statuses = Counter({name: 0 for name in ("valid", "insufficient_levels", "zero_denominator", "query_failure", "empty_book", "invalid_book")})
        signs = Counter({name: 0 for name in ("positive", "negative", "zero")})
        previous = None
        transitions = Counter()
        comparable_ms = 0
        comparisons = 0
        timeline_bins = {}
        for row in series:
            value = row["depths"][key]
            statuses[value["status"]] += 1
            if value["status"] != "valid":
                previous = None
                continue
            valid.append(value)
            sign = "positive" if value["imbalance"] > 0 else "negative" if value["imbalance"] < 0 else "zero"
            signs[sign] += 1
            if previous is not None and not row["gap_before"]:
                prev_sign, prev_time = previous
                comparisons += 1
                comparable_ms += row["time_ms"] - prev_time
                transitions[f"{prev_sign}_to_{sign}"] += 1
            previous = (sign, row["time_ms"])
            bin_start = int(row["time_ms"] // 5000) * 5000
            timeline_bins.setdefault(bin_start, []).append(value["imbalance"])
        flips = transitions["positive_to_negative"] + transitions["negative_to_positive"]
        zero_transitions = sum(count for name, count in transitions.items() if name in
                               ("positive_to_zero", "negative_to_zero", "zero_to_positive", "zero_to_negative"))
        summaries[key] = {"statuses": dict(statuses), "sign_counts": dict(signs),
            "bid_volume": distribution([v["bid_volume"] for v in valid]),
            "ask_volume": distribution([v["ask_volume"] for v in valid]),
            "imbalance": distribution([v["imbalance"] for v in valid]),
            "comparable_intervals": comparisons, "comparable_ms": comparable_ms,
            "direct_sign_flips": flips, "direct_sign_flip_fraction": flips / comparisons if comparisons else None,
            "direct_sign_flips_per_second": flips / (comparable_ms / 1000) if comparable_ms else None,
            "zero_transitions": zero_transitions, "transitions": dict(transitions),
            "first_imbalance": valid[0]["imbalance"] if valid else None,
            "last_imbalance": valid[-1]["imbalance"] if valid else None,
            "evolution_5s": [{"start_utc": datetime.fromtimestamp(t / 1000, timezone.utc).isoformat(timespec="milliseconds"),
                              "imbalance": distribution(values)} for t, values in sorted(timeline_bins.items())]}
    return {"analysis": "experimental_dom_imbalance", "report_version": 1, "symbol": "WINZ26",
            "source": str(source.resolve()), "source_sha256": digest, "volume_field": "volume_dbl",
            "volume_unit": "original_feed_unit", "max_gap_ms": max_gap_ms, "percentile_method": "nearest_rank",
            "quality": dict(quality), "depths": summaries, "snapshots": series,
            "limitations": ["Descritivo: sinal do desequilibrio nao e sinal de trading.",
                "Volumes agregados visiveis, sem identificar ordens, intencao ou execucao.",
                "Distribuicoes e bins de 5s ponderados por snapshot, incluindo repetidos; nao por tempo.",
                "Mudancas diretas de sinal exigem snapshots consecutivos validos e sem gap.",
                "Transicoes via zero sao separadas; nao se liga positivo a negativo atraves de zero ou desconhecido.",
                "Profundidades insuficientes nao usam somas parciais; denominador zero produz null."]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("--max-gap-ms", type=float, default=500)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        payload = json.dumps(analyze_file(args.input, args.max_gap_ms), ensure_ascii=False, allow_nan=False, indent=2)
        if args.output:
            with args.output.open("x", encoding="utf-8") as out:
                out.write(payload + "\n")
        else:
            print(payload)
    except (OSError, ValueError) as exc:
        parser.exit(1, f"Erro: {exc}\n")


if __name__ == "__main__":
    main()
