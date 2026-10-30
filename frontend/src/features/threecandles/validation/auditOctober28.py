"""Offline audit only. Never feeds candidates or sensitivity hypotheses into the engines."""
import json, csv, hashlib
from pathlib import Path
from datetime import datetime, timezone
from collections import Counter

ROOT = Path(__file__).resolve().parent / "oct28"
view = json.loads((ROOT / "session.json").read_text(encoding="utf-8"))
source = json.loads((ROOT / "source.json").read_text(encoding="utf-8"))
micro = view["microDow"]
bars = [v["candle"] for v in view["candles"]]
by_id = {p["extreme"]["id"]: p for p in micro["pivots"]}
selected = set(micro["selectedPivotIds"])
fmt = lambda t: datetime.fromtimestamp(t / 1000, timezone.utc).strftime("%H:%M")
point = lambda p: f'{fmt(p["extreme"]["extremeTimeMsc"])} {p["extreme"]["type"]} {p["extreme"]["price"]:g}'
raw_at = {}
for pivot in micro["pivots"]:
    raw_at.setdefault(pivot["extreme"]["extremeTimeMsc"], []).append(pivot)

candle_audit = []
for i, bar in enumerate(bars):
    evaluations = []
    for kind, key in [("TOP", "high"), ("BOTTOM", "low")]:
        left = bars[i-1] if i else None
        right = bars[i+1] if i+1 < len(bars) else None
        known = next((p for p in raw_at.get(bar["bucketStartTimeMsc"], []) if p["extreme"]["type"] == kind), None)
        if left and right:
            expected = bar[key] > max(left[key], right[key]) if kind == "TOP" else bar[key] < min(left[key], right[key])
            assert bool(known) == expected, (fmt(bar["bucketStartTimeMsc"]), kind, "strict detector mismatch")
        if known:
            assert known["extreme"]["confirmedAtMsc"] == right["bucketStartTimeMsc"] + 300000
            assert known["extreme"]["confirmedAtMsc"] <= view["availableAtMsc"]
        equal = bool(left and right and (bar[key] == left[key] or bar[key] == right[key]))
        decision = "CONFIRMED" if known else "OPENING_BOUNDARY" if not left else "RIGHT_BAR_NOT_CLOSED" if not right else "EQUAL_NEIGHBOR" if equal else "NO_STRICT_FRACTAL"
        evaluations.append(dict(type=kind, price=bar[key], decision=decision,
            confirmedAtMsc=known["extreme"]["confirmedAtMsc"] if known else None,
            selected=bool(known and known["extreme"]["id"] in selected),
            pivot=known,
            decisions=[d for d in micro["decisions"] if known and d["pivotId"] == known["extreme"]["id"]]))
    candle_audit.append(dict(candle=bar, formation=fmt(bar["bucketStartTimeMsc"]), evaluations=evaluations))

# Explicit plateau hypothesis: last member of consecutive equal prices, strict outer neighbors,
# available only when the first bar to its right has closed. Diagnostic only, not implemented.
ties, plateau_candidates = [], []
for kind, key in [("TOP", "high"), ("BOTTOM", "low")]:
    i = 0
    while i < len(bars):
        end = i
        while end+1 < len(bars) and bars[end+1][key] == bars[i][key]:
            end += 1
        if end > i:
            left = bars[i-1] if i else None
            right = bars[end+1] if end+1 < len(bars) else None
            strict = bool(left and right and (bars[i][key] > max(left[key], right[key]) if key == "high" else bars[i][key] < min(left[key], right[key])))
            run = dict(type=kind, price=bars[i][key], start=fmt(bars[i]["bucketStartTimeMsc"]), end=fmt(bars[end]["bucketStartTimeMsc"]),
                strictOuterNeighbors=strict, hypotheticalConfirmedAtMsc=right["bucketStartTimeMsc"]+300000 if strict else None)
            ties.append(run)
            if strict:
                plateau_candidates.append(run)
        i = end+1

excluded = []
for t, originals in raw_at.items():
    if len(originals) == 2:
        excluded.append(dict(formation=fmt(t), confirmation=fmt(originals[0]["extreme"]["confirmedAtMsc"]),
            category="EXTREMO_DUPLO", candidate=" / ".join(point(p) for p in originals),
            absent="Entrada e saida da vela de extremo duplo",
            explanation="Top and bottom confirmed in one M5 bar; intrabar order unknown. Originals marked, neither is a chain endpoint."))
        continue
    p = originals[0]
    if p["extreme"]["id"] in selected:
        continue
    decision = next(d for d in micro["decisions"] if d["pivotId"] == p["extreme"]["id"])
    previous = by_id.get(decision["previousTerminalId"])
    replacement = next((d for d in micro["decisions"] if d["previousTerminalId"] == p["extreme"]["id"] and d["action"] == "TERMINAL_REPLACED"), None)
    old_edges = [m for m in micro["movementHistory"] if p["extreme"]["id"] in (m["fromId"], m["toId"])]
    excluded.append(dict(formation=fmt(t), confirmation=fmt(p["extreme"]["confirmedAtMsc"]),
        category="SUBSTITUICAO" if replacement else "MESMO_TIPO_MENOS_EXTREMO",
        candidate=point(p), absent="; ".join(point(by_id[m["fromId"]])+" -> "+point(by_id[m["toId"]]) for m in old_edges) if old_edges else "Extremo nao utilizado como ponta de segmento",
        previousSelectedTerminal=point(previous) if previous else None,
        replacement=point(by_id[replacement["pivotId"]]) if replacement else None,
        replacementKnownAt=fmt(replacement["atMsc"]) if replacement else None,
        explanation=decision["reason"], historicalSegments=old_edges))
excluded.sort(key=lambda d: d["formation"])
regions = []
for name, begin, end in [("initial", "09:00", "11:00"), ("central", "11:00", "14:00"), ("right", "14:00", "17:35")]:
    rows = [r for r in candle_audit if begin <= r["formation"] < end]
    ids = {p["extreme"]["id"] for p in micro["pivots"] if begin <= fmt(p["extreme"]["extremeTimeMsc"]) < end}
    regions.append(dict(name=name, range=[begin, end], matchesWhiteChecks=None, raw=len(ids), selected=len(ids & selected),
        segmentsEndingInRegion=sum(m["toId"] in ids for m in micro["movements"]),
        candles=rows, excluded=[d for d in excluded if begin <= d["formation"] < end]))
summary = dict(source=source, cursor=fmt(view["availableAtMsc"]), first=fmt(bars[0]["bucketStartTimeMsc"]), last=fmt(bars[-1]["bucketStartTimeMsc"]),
    closedBars=len(bars), originalFractals=len(micro["pivots"]), selectedFractals=len(selected), segments=len(micro["movements"]),
    historicalVersions=len(micro["movementHistory"]), decisions=dict(Counter(d["action"] for d in micro["decisions"])),
    adjacentEqualPriceRuns=len(ties), additionalPlateauCandidates=len(plateau_candidates),
    equalityPolicyImplemented=False, sourceMatchesUserCapture=None, matchesWhiteChecks=None)
(ROOT / "audit.json").write_text(json.dumps(dict(summary=summary, excluded=excluded, regions=regions, candleAudit=candle_audit,
    equalityHypothesis=dict(policy="Last tied bar, strict outer neighbors, confirmation after closed right bar; offline only", ties=ties, candidates=plateau_candidates)), indent=2), encoding="utf-8")
with (ROOT / "excluded.csv").open("w", newline="", encoding="utf-8-sig") as f:
    w=csv.writer(f);w.writerow(["formation","confirmation","category","candidate","absent","previousSelectedTerminal","replacement","replacementKnownAt"])
    for d in excluded:w.writerow([d.get(k,"") for k in ["formation","confirmation","category","candidate","absent","previousSelectedTerminal","replacement","replacementKnownAt"]])
print(json.dumps({**summary, "source":source["source"]}, indent=2))
for d in excluded:print(d["formation"],d["confirmation"],d["category"],d["candidate"],d.get("replacement"),d.get("replacementKnownAt"))
