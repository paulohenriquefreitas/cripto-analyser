"""Descriptive DISCOVERY-only research. No model training, trading rules or dependencies."""
import argparse
import csv
import json
from datetime import datetime
from pathlib import Path


def discovery_rows(path):
    with path.open(encoding="utf-8-sig", newline="") as source:
        for row in csv.DictReader(source):
            if row["temporal_split"] == "DISCOVERY":
                date = datetime.fromisoformat(row["session_date_utc"])
                if not 4 <= date.month <= 7:
                    raise ValueError("DISCOVERY outside April-July")
                yield row


def side(previous, current, event):
    field = "close_ema9" if event == "close-cross" else "ema9_delta"
    if not previous[field] or not current[field]:
        return None
    before, after = float(previous[field]), float(current[field])
    if event == "close-cross":
        return "UP" if before <= 0 < after else "DOWN" if before >= 0 > after else None
    return "UP" if before < 0 < after else "DOWN" if before > 0 > after else None


def distribution(events):
    return {str(points): {"hits": sum(event["favorable_points"] >= points for event in events),
                         "total": len(events),
                         "percent": (100 * sum(event["favorable_points"] >= points for event in events) / len(events)
                                     if events else None)}
            for points in (100, 200, 300, 500)}


def analyze(folder, event):
    features = {(r["session_date_utc"], int(r["bucket_start_msc"])): r
                for r in discovery_rows(folder / "features.csv")}
    labels = {(r["session_date_utc"], int(r["bucket_start_msc"])): r
              for r in discovery_rows(folder / "dataset.csv")}
    detected = 0
    eligible = []
    for key, row in sorted(features.items()):
        previous = features.get((key[0], key[1] - 300_000))
        if previous is None:
            continue
        direction = side(previous, row, event)
        if direction is None:
            continue
        detected += 1
        if key not in labels:
            continue
        label = labels[key]
        eligible.append({"session_date_utc": key[0], "timestamp_utc": row["timestamp_utc"],
                         "direction": direction,
                         "previous_ema9_delta": float(previous["ema9_delta"]),
                         "favorable_points": float(label["label_up_excursion"] if direction == "UP"
                                                   else label["label_down_excursion"]),
                         "adverse_points": float(label["label_down_excursion"] if direction == "UP"
                                                 else label["label_up_excursion"])})
    # This is the literal predeclared time interval, not a learned tree or optimized filter.
    interval = [e for e in eligible if 10 * 60 + 30 <= (
        datetime.fromisoformat(e["timestamp_utc"].replace("Z", "+00:00")).hour * 60
        + datetime.fromisoformat(e["timestamp_utc"].replace("Z", "+00:00")).minute) <= 13 * 60]
    return {"event": event, "partition": "DISCOVERY", "detected_crossings": detected,
            "eligible_examples": len(eligible), "excluded_no_context_or_horizon": detected - len(eligible),
            "distribution": distribution(eligible),
            "directions": {d: distribution([e for e in eligible if e["direction"] == d]) for d in ("UP", "DOWN")},
            "predeclared_time_interval": {"utc": "10:30-13:00 inclusive, anchor bucket start",
                                          "distribution": distribution(interval)},
            "model_trained": False, "events": eligible}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset_directory", type=Path)
    parser.add_argument("output_directory", type=Path)
    parser.add_argument("--event", choices=("close-cross", "ema-turn", "both"), default="both")
    args = parser.parse_args()
    if args.output_directory.exists():
        parser.error("Choose a new output directory to preserve reports")
    reports = [analyze(args.dataset_directory, event) for event in
               (("close-cross", "ema-turn") if args.event == "both" else (args.event,))]
    args.output_directory.mkdir(parents=True)
    lines = ["DISCOVERY crossing excursion analysis - descriptive only", "No training; August/September excluded.",
             "Excursions are from anchor close over the next 3 M5 candles; not trading win rates.",
             "Close cross: previous close-EMA <=0 -> >0 (UP), >=0 -> <0 (DOWN).",
             "EMA turn: strict opposite signs in consecutive closed EMA deltas; zero slopes excluded.", ""]
    for report in reports:
        lines.append(f"{report['event']}: detected {report['detected_crossings']}; eligible {report['eligible_examples']}; excluded {report['excluded_no_context_or_horizon']}")
        for points, value in report["distribution"].items():
            percent = "N/A" if value["percent"] is None else f"{value['percent']:.2f}%"
            lines.append(f"At least {points} points: {value['hits']}/{value['total']} = {percent}")
        value = report["predeclared_time_interval"]["distribution"]["300"]
        percent = "N/A" if value["percent"] is None else f"{value['percent']:.2f}%"
        lines.append(f"Predeclared UTC 10:30-13:00: 300 points {value['hits']}/{value['total']} = {percent}")
        lines.append("")
    lines.append("No EMA slope cutoff X or decision tree was fitted. Reported frequencies are in-sample DISCOVERY observations.")
    lines.append("Missing days and dataset limitations remain as documented in the source dataset report.")
    (args.output_directory / "analysis.json").write_text(json.dumps(reports, indent=2) + "\n", encoding="utf-8")
    (args.output_directory / "report.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
