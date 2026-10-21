"""Offline experimental DOM liquidity persistence; never connects to MT5."""
from __future__ import annotations

import argparse
from datetime import datetime
import json
import math
import statistics
from pathlib import Path

from mt5_dom_probe import analyze as validate_book


def timestamp(value):
    result = datetime.fromisoformat(value)
    if result.utcoffset() is None:
        raise ValueError("captured_utc deve conter fuso horario")
    return result.timestamp() * 1000


def analyze_file(path, max_gap_ms=500, *, on_observation=None):
    if not math.isfinite(max_gap_ms) or max_gap_ms <= 0:
        raise ValueError("max_gap_ms deve ser positivo e finito")
    stats = {"snapshots": 0, "valid_nonempty": 0, "empty": 0, "failed": 0,
             "invalid": 0, "large_gaps": 0, "comparable_intervals": 0,
             "comparable_ms": 0, "anomalies": []}
    levels = {}
    active = {}
    runs = []
    previous = None
    previous_time = None
    last_time = None
    expected_sequence = 1
    symbol_seen = False

    def finish_run(key, reason, detected_time=None):
        run = active.pop(key)
        duration = previous_time - run["start_ms"]
        levels[key]["longest_run_ms"] = max(levels[key]["longest_run_ms"], duration)
        right_censored = reason != "observed_exit"
        runs.append({"side": "ASK" if key[0] == 1 else "BID", "price": key[1],
                     **run, "last_seen_ms": previous_time, "duration_ms": duration,
                     "end_reason": reason, "end_detected_ms": detected_time,
                     "right_censored": right_censored,
                     "censored": run["left_censored"] or right_censored})

    def close_runs(reason, detected_time=None):
        for key in list(active):
            finish_run(key, reason, detected_time)

    with Path(path).open(encoding="utf-8-sig") as source:
        for line_number, line in enumerate(source, 1):
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
                stats["snapshots"] += 1
                now = timestamp(record["captured_utc"])
                if last_time is not None and now < last_time:
                    raise ValueError("Snapshots fora de ordem temporal")
                last_time = now
                sequence = record["sequence"]
                if not isinstance(sequence, int) or sequence != expected_sequence:
                    raise ValueError(f"Sequencia esperada {expected_sequence}, recebida {sequence}")
                expected_sequence += 1
                raw = record["levels"]
                if raw is None or record.get("error") is not None:
                    stats["failed"] += 1
                    close_runs("query_failure", now)
                    previous = None
                    continue
                if not isinstance(raw, list):
                    raise ValueError("levels deve ser lista ou null")
                if not raw:
                    stats["empty"] += 1
                    close_runs("empty_book", now)
                    previous = None
                    continue
                anomalies = validate_book(raw)["anomalies"]
                if any(row["type"] not in (1, 2) for row in raw):
                    anomalies.append("unsupported_non_limit_type")
                if anomalies:
                    stats["invalid"] += 1
                    stats["anomalies"].append({"sequence": sequence, "issues": anomalies})
                    close_runs("invalid_book", now)
                    previous = None
                    continue
                stats["valid_nonempty"] += 1
                current = {(row["type"], row["price"]): row["volume_dbl"] for row in raw}
                gap = now - previous_time if previous is not None else None
                if gap is not None and gap > max_gap_ms:
                    stats["large_gaps"] += 1
                    close_runs("large_gap", now)
                    previous = None
                comparable = previous is not None
                if on_observation is not None:
                    on_observation(current, now, sequence, comparable)
                if comparable:
                    stats["comparable_intervals"] += 1
                    stats["comparable_ms"] += gap
                for key, quantity in current.items():
                    item = levels.setdefault(key, {"side": "ASK" if key[0] == 1 else "BID", "type": key[0],
                        "price": key[1], "observations": 0, "runs": 0, "longest_run_ms": 0,
                        "matched_presence_ms": 0, "quantity_min": quantity, "quantity_max": quantity,
                        "quantity_sum": 0, "quantity_changes": 0, "quantity_increases": 0,
                        "quantity_decreases": 0, "observed_exits": 0})
                    item["observations"] += 1
                    item["quantity_sum"] += quantity
                    item["quantity_min"] = min(item["quantity_min"], quantity)
                    item["quantity_max"] = max(item["quantity_max"], quantity)
                    if comparable and key in previous:
                        item["matched_presence_ms"] += gap
                        delta = quantity - previous[key]
                        if delta:
                            item["quantity_changes"] += 1
                            item["quantity_increases" if delta > 0 else "quantity_decreases"] += 1
                    else:
                        active[key] = {"start_ms": now, "start_sequence": sequence,
                                       "left_censored": not comparable}
                        item["runs"] += 1
                if comparable:
                    for key in previous.keys() - current.keys():
                        finish_run(key, "observed_exit", now)
                        levels[key]["observed_exits"] += 1
                previous, previous_time = current, now
            except (KeyError, TypeError, ValueError, OverflowError) as exc:
                raise ValueError(f"Linha {line_number}: {exc}") from exc
    close_runs("capture_end")
    if not symbol_seen:
        raise ValueError("Arquivo sem identificacao WINZ26 em symbol_info ou summary")
    if not stats["snapshots"]:
        raise ValueError("Arquivo sem snapshots")
    output = []
    for item in levels.values():
        item["quantity_mean_sampled"] = item.pop("quantity_sum") / item["observations"]
        item["sample_presence_ratio"] = item["observations"] / stats["valid_nonempty"]
        item["matched_presence_ratio"] = item["matched_presence_ms"] / stats["comparable_ms"] if stats["comparable_ms"] else None
        output.append(item)
    output.sort(key=lambda item: (item["type"], -item["price"]))
    distributions = {}
    for side in ("BID", "ASK"):
        side_runs = [run for run in runs if run["side"] == side]
        def distribution(selected):
            durations = sorted(run["duration_ms"] for run in selected)
            return {"count": len(durations), "durations_ms": durations,
                    "median_ms": statistics.median(durations) if durations else None,
                    "p90_ms": durations[math.ceil(.9 * len(durations)) - 1] if durations else None,
                    "max_ms": max(durations) if durations else None}
        distributions[side] = {
            "all_observed": distribution(side_runs),
            "uncensored": distribution([run for run in side_runs if not run["censored"]]),
            "censored": distribution([run for run in side_runs if run["censored"]]),
            "by_end_reason": {reason: distribution([run for run in side_runs if run["end_reason"] == reason])
                for reason in ("observed_exit", "query_failure", "empty_book", "invalid_book", "large_gap", "capture_end")},
            "left_censored_count": sum(run["left_censored"] for run in side_runs),
            "right_censored_count": sum(run["right_censored"] for run in side_runs)}
    return {"analysis": "experimental_dom_liquidity_persistence", "symbol": "WINZ26",
            "source": str(Path(path).resolve()), "max_gap_ms": max_gap_ms,
            "report_version": 2, "runs": runs, "duration_distributions": distributions,
            "percentile_method": "nearest_rank", "duration_unit": "ms",
            "status": "analyzed" if output else "no_usable_dom", "quality": stats, "levels": output,
            "limitations": ["Persistencia amostrada por lado/preco; nao identifica ordens individuais.",
                "Intervalos com presenca nos dois extremos nao comprovam permanencia entre consultas.",
                "Vazios, falhas, anomalias e gaps grandes interrompem runs; nao sao ausencia confirmada.",
                "Saida da profundidade visivel nao comprova cancelamento ou execucao.",
                "Variacao de quantidade nao indica agressao, spoofing ou sinal de trading."]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("--max-gap-ms", type=float, default=500)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        result = analyze_file(args.input, args.max_gap_ms)
        payload = json.dumps(result, ensure_ascii=False, allow_nan=False, indent=2)
        if args.output:
            with args.output.open("x", encoding="utf-8") as out:
                out.write(payload + "\n")
        else:
            print(payload)
    except (OSError, ValueError) as exc:
        parser.exit(1, f"Erro: {exc}\n")


if __name__ == "__main__":
    main()
