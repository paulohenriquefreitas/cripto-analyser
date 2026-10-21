"""Offline changes in visible DOM quantities; no order/execution inference."""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import statistics

from mt5_dom_persistence import analyze_file as analyze_persistence

KINDS = ("increase", "decrease", "disappearance", "reappearance", "first_appearance")


def distribution(values):
    values = sorted(values)
    return {"count": len(values), "values": values, "sum": sum(values),
            "median": statistics.median(values) if values else None,
            "p90": values[math.ceil(.9 * len(values)) - 1] if values else None,
            "max": max(values) if values else None}


def analyze_file(path, max_gap_ms=500):
    events = []
    previous = None
    previous_time = None
    epoch = 0
    seen = set()
    absent_since = {}
    last_event = {}
    comparable_counts = Counter()
    unknown_baseline_levels = Counter()

    def observe(current, now, sequence, comparable):
        nonlocal previous, previous_time, epoch
        if not comparable:
            # First usable snapshot or a break in knowledge: establish a new baseline.
            epoch += 1
            seen.clear()
            absent_since.clear()
            last_event.clear()
            previous = current
            previous_time = now
            seen.update(current)
            for kind, price in current:
                unknown_baseline_levels["BID" if kind == 2 else "ASK"] += 1
            return
        for key in sorted(previous.keys() | current.keys()):
            side = "BID" if key[0] == 2 else "ASK"
            before, after = previous.get(key), current.get(key)
            if before is not None and after is not None:
                comparable_counts[side] += 1
                if before == after:
                    continue
                kind = "increase" if after > before else "decrease"
                magnitude = abs(after - before)
            elif before is not None:
                kind, magnitude = "disappearance", before
                absent_since[key] = now
            else:
                kind = "reappearance" if key in seen else "first_appearance"
                magnitude = after
            event_key = (key, kind)
            event = {"side": side, "price": key[1], "kind": kind, "sequence": sequence,
                     "observed_ms": now, "previous_snapshot_ms": previous_time,
                     "observation_epoch": epoch, "before_quantity": before, "after_quantity": after,
                     "magnitude": magnitude,
                     "same_price_kind_interval_ms": now - last_event[event_key] if event_key in last_event else None,
                     "observed_absence_ms": now - absent_since[key] if kind == "reappearance" else None}
            events.append(event)
            last_event[event_key] = now
            if after is not None:
                seen.add(key)
                absent_since.pop(key, None)
        previous, previous_time = current, now

    source = Path(path)
    digest_before = hashlib.sha256(source.read_bytes()).hexdigest()
    persistence = analyze_persistence(source, max_gap_ms, on_observation=observe)
    if digest_before != hashlib.sha256(source.read_bytes()).hexdigest():
        raise ValueError("Arquivo de entrada mudou durante a analise")
    seconds = persistence["quality"]["comparable_ms"] / 1000
    sides = {}
    per_level = []
    for side in ("BID", "ASK"):
        selected = [event for event in events if event["side"] == side]
        grouped = {}
        for kind in KINDS:
            group = [e for e in selected if e["kind"] == kind]
            # Cross-price intervals only inside the same uninterrupted epoch.
            intervals = [b["observed_ms"] - a["observed_ms"] for a, b in zip(group, group[1:])
                         if a["observation_epoch"] == b["observation_epoch"]]
            grouped[kind] = {"count": len(group), "events_per_second": len(group) / seconds if seconds else None,
                "magnitude": distribution([e["magnitude"] for e in group]),
                "side_interevent_ms": distribution(intervals),
                "same_price_kind_interevent_ms": distribution([e["same_price_kind_interval_ms"] for e in group
                                                               if e["same_price_kind_interval_ms"] is not None]),
                "observed_absence_ms": distribution([e["observed_absence_ms"] for e in group
                                                      if e["observed_absence_ms"] is not None])}
        changes = grouped["increase"]["count"] + grouped["decrease"]["count"]
        sides[side] = {"matched_level_comparisons": comparable_counts[side],
            "quantity_change_count": changes,
            "quantity_change_fraction": changes / comparable_counts[side] if comparable_counts[side] else None,
            "baseline_level_observations": unknown_baseline_levels[side], "event_types": grouped}
        for price in sorted({e["price"] for e in selected}):
            group = [e for e in selected if e["price"] == price]
            per_level.append({"side": side, "price": price,
                              "event_counts": {kind: sum(e["kind"] == kind for e in group) for kind in KINDS}})
    return {"analysis": "experimental_dom_quantity_changes", "report_version": 1,
        "symbol": "WINZ26", "source": str(source.resolve()), "source_sha256": digest_before,
        "max_gap_ms": max_gap_ms, "percentile_method": "nearest_rank",
        "status": persistence["status"], "quality": persistence["quality"],
        "sides": sides, "levels": per_level, "events": events,
        "limitations": ["Aumentos/reducoes sao diferencas liquidas de volume_dbl, nao fluxo bruto.",
            "Desaparecimento usa ultima quantidade visivel; reaparecimento usa nova quantidade visivel, sem assumir zero fora da janela.",
            "Primeira aparicao em cada trecho conhecido e separada de reaparecimento; baseline nao gera evento.",
            "Vazios, falhas, livros invalidos e gaps interrompem comparacoes e historico de eventos.",
            "Intervalos sao entre deteccoes locais; eventos simultaneos entre precos produzem intervalos zero.",
            "Ausencia observada mede primeira ausencia ate reaparecimento, sem extrapolar intervalos censurados.",
            "Reducao/saida nao identifica cancelamento ou execucao; reaparecimento nao identifica ordem ou instituicao."]}


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
