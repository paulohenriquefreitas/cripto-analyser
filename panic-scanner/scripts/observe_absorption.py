"""Explicit observation only. Reuses the joint probe; never sends orders."""
import argparse
from dataclasses import asdict
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import time
import uuid

from experimental_absorption import Config, Detector, INSUFFICIENT, timestamp
from mt5_dom_probe import analyze, json_safe, write_record
from mt5_dom_trades_probe import run


class Observation:
    def __init__(self, out, live=False, connected=None, record_inputs=False):
        self.detector = Detector()
        self.out = out
        self.live = live
        self.connected = connected
        self.session = str(uuid.uuid4())
        self.last_book = None
        self.last_start = {}
        self.order = 0
        self.expected_cursor = None
        self.record_inputs = record_inputs

    def receive(self, record):
        received = time.monotonic()
        received_utc = datetime.now(timezone.utc).isoformat(timespec="microseconds")
        self.order += 1
        kind = record.get("kind")
        start = record.get("monotonic_start")
        end = record.get("monotonic_end")
        interval = None
        if start is not None:
            previous = self.last_start.get(kind)
            interval = None if previous is None else (start - previous) * 1000
            self.last_start[kind] = start
        delivery = (received - end) * 1000 if self.live and end is not None else None
        # Python capture schema only. Replay uses source UTC, never today's wall clock.
        known = timestamp(record["completed_utc"]) if "completed_utc" in record else self.detector.last_known
        axis = end * 1000 if end is not None else known
        before = len(self.detector.events)
        guard_start = time.monotonic()
        reasons = []
        connection_state = None
        dom_diagnostic = None
        if self.connected is not None and kind != "summary":
            try:
                connection_state = bool(self.connected())
                if not connection_state:
                    reasons.append("observation_disconnected")
            except Exception:
                reasons.append("observation_connection_unknown")
        if kind == "snapshot":
            levels = record.get("levels")
            try:
                valid = bool(levels) and not record.get("error") and not analyze(levels)["anomalies"]
                valid = valid and all(level["type"] in (1, 2) for level in levels)
            except (KeyError, TypeError, ValueError):
                valid = False
            if valid:
                self.last_book = axis
            else:
                reasons.append("observation_dom_unusable")
                specific = []
                anomalies = []
                if levels is None:
                    specific.append("levels_missing")
                elif not levels:
                    specific.append("empty_book")
                if record.get("error"):
                    specific.append("dom_api_error")
                try:
                    anomalies = analyze(levels)["anomalies"] if levels is not None else []
                    types = sorted(set(level["type"] for level in levels)) if levels else []
                    if anomalies:
                        specific.append("book_anomalies")
                    if any(kind not in (1, 2) for kind in types):
                        specific.append("unsupported_level_type")
                except (KeyError, TypeError, ValueError) as exc:
                    types = [level.get("type") if isinstance(level, dict) else repr(level)
                             for level in levels] if isinstance(levels, list) else []
                    specific.append("malformed_levels:" + repr(exc))
                dom_diagnostic = dict(reasons=specific, level_count=len(levels) if levels is not None else None,
                    types=types, anomalies=anomalies, error=record.get("error"),
                    connected=connection_state, levels=levels)
        if kind in ("snapshot", "tick_batch"):
            if interval is not None and (interval < 0 or interval > self.detector.c.max_poll_gap_ms):
                reasons.append("observation_poll_gap")
            if self.last_book is not None and axis - self.last_book > self.detector.c.max_dom_age_ms:
                reasons.append("observation_dom_stale")
            if delivery is not None and delivery > self.detector.c.max_poll_gap_ms:
                reasons.append("observation_delivery_gap")
        if kind == "tick_batch":
            if self.expected_cursor is not None and record.get("cursor_before") != self.expected_cursor:
                reasons.append("observation_cursor_discontinuity")
            self.expected_cursor = record.get("cursor_after")
        for reason in reasons:
            if known is not None:
                self.detector.capture_bounds = [known, known + 1]
                self.detector.axis_known = axis
            self.detector.fault(reason, known)
        guard_ms = (time.monotonic() - guard_start) * 1000
        processing_start = time.monotonic()
        self.detector.feed(record)
        processing_ms = (time.monotonic() - processing_start) * 1000
        timing = dict(received_utc=received_utc, query_interval_ms=interval,
                      copy_ticks_api_ms=record.get("copy_ticks_api_ms"),
                      query_python_ms=record.get("query_python_ms"),
                      query_duration_ms=record.get("latency_ms") if kind in ("snapshot", "tick_batch") else None,
                      delivery_ms=delivery,
                      receipt_to_feed_ms=(processing_start-received)*1000,
                      processing_ms=processing_ms, guard_ms=guard_ms)
        # All detection completes before the first output write/flush.
        if self.record_inputs:
            write_record(self.out, dict(kind="detector_input", schema_version=1,
                session=self.session, order=self.order, received_utc=received_utc,
                config=asdict(self.detector.c), record=record,
                guard=dict(reasons=reasons, known=known, axis=axis,
                           connected=connection_state, query_interval_ms=interval, delivery_ms=delivery)))
        for event in self.detector.events[before:]:
            write_record(self.out, dict(kind="transition", session=self.session, order=self.order,
                state="INCONCLUSIVA" if event["state"] == INSUFFICIENT else event["state"],
                evidence=event, **timing))
        write_record(self.out, dict(kind="observation", session=self.session, order=self.order,
            source_kind=kind, source_sequence=record.get("capture_sequence", record.get("sequence")),
            capture_completed_utc=record.get("completed_utc"),
            blocked=bool(self.detector.faults), faults=sorted(self.detector.faults),
            dom_diagnostic=dom_diagnostic, cursor_validation=record.get("cursor_validation", []),
            source_error=record.get("error") if kind == "tick_query_error" else None,
            **timing))


class TimedMT5:
    """Instrument the existing query without changing its implementation."""
    def __init__(self, api, clock=time.monotonic):
        self.api, self.clock = api, clock
        self.copy_ms = None

    def __getattr__(self, name):
        return getattr(self.api, name)

    def copy_ticks_from(self, *args, **kwargs):
        start = self.clock()
        try:
            return self.api.copy_ticks_from(*args, **kwargs)
        finally:
            self.copy_ms = (self.clock() - start) * 1000


class PreparedObservation:
    """One capture subscription; no Detector exists before readiness."""
    def __init__(self, out, connected, warmup_seconds=30, observe_seconds=10,
                 clock=time.monotonic, timed_api=None, record_inputs=False):
        self.out, self.connected, self.clock = out, connected, clock
        self.warmup_seconds, self.observe_seconds = warmup_seconds, observe_seconds
        self.api = timed_api
        self.record_inputs = record_inputs
        self.config = Config()
        self.started = clock()
        self.observer = None
        self.activated = None
        self.header = None
        self.last_start = {}
        self.sequences = {}
        self.offsets = {}
        self.cursor = None
        self.last_trade = None
        self.last_book = None
        self.stable_start = None
        self.valid_counts = {"snapshot": 0, "tick_batch": 0}
        self.total_valid = {"snapshot": 0, "tick_batch": 0}
        self.last_reason = "waiting_for_capture"

    def done(self):
        now = self.clock()
        return now >= (self.activated + self.observe_seconds if self.activated is not None
                       else self.started + self.warmup_seconds)

    def receive(self, original):
        record = dict(original)
        now = self.clock()
        kind = record.get("kind")
        if kind == "tick_batch" and self.api is not None:
            record["copy_ticks_api_ms"] = self.api.copy_ms + sum(
                item["copy_ticks_api_ms"] for item in record.get("cursor_validation", []))
            record["query_python_ms"] = max(0, record["latency_ms"] - record["copy_ticks_api_ms"])
        if kind == "summary":
            if self.observer is not None:
                self.observer.receive(record)
            self.report()
            return
        if self.observer is not None:
            if kind in self.offsets:
                record["capture_sequence"] = record["sequence"]
                record["sequence"] -= self.offsets[kind]
            self.observer.receive(record)
            return
        if kind == "session":
            self.header = record
        try:
            connected = bool(self.connected())
        except Exception:
            connected = False
        reasons = [] if connected else ["disconnected_or_unknown"]
        start, end = record.get("monotonic_start"), record.get("monotonic_end")
        if kind in self.valid_counts:
            previous = self.last_start.get(kind)
            if start is None or end is None or end < start:
                reasons.append("invalid_capture_time")
            else:
                if (end-start)*1000 > self.config.max_poll_gap_ms:
                    reasons.append("query_latency_gap")
                if (now-end)*1000 > self.config.max_poll_gap_ms:
                    reasons.append("delivery_gap")
                if previous is not None and (start < previous or
                        (start-previous)*1000 > self.config.max_poll_gap_ms):
                    reasons.append("poll_gap")
                self.last_start[kind] = start
            seq = record.get("sequence")
            if seq != self.sequences.get(kind, 0) + 1:
                reasons.append("sequence_gap")
            self.sequences[kind] = seq
        if kind == "snapshot":
            levels = record.get("levels")
            try:
                if (not levels or record.get("error") or analyze(levels)["anomalies"] or
                        any(l["type"] not in (1, 2) for l in levels)):
                    reasons.append("dom_unusable")
                else:
                    self.last_book = end
            except (KeyError, TypeError, ValueError):
                reasons.append("dom_unusable")
        if kind == "tick_batch":
            if record.get("drained") is not True or record.get("error"):
                reasons.append("batch_incomplete")
            if self.cursor is not None and record.get("cursor_before") != self.cursor:
                reasons.append("cursor_discontinuity")
            self.cursor = record.get("cursor_after")
            for tick in record.get("ticks", []):
                raw = tick["raw"]
                if raw["flags"] & 24:
                    if not all(isinstance(raw.get(k), (int, float)) and
                               math.isfinite(raw[k]) and raw[k] > 0 for k in ("last", "volume_real")):
                        reasons.append("invalid_trade")
                    else:
                        self.last_trade = end
        if kind == "tick_query_error":
            reasons.append("tick_query_error")
        if reasons:
            self.valid_counts = dict.fromkeys(self.valid_counts, 0)
            self.stable_start = self.last_book = self.last_trade = None
            self.last_reason = ",".join(reasons)
        elif kind in self.valid_counts:
            self.valid_counts[kind] += 1
            self.total_valid[kind] += 1
            if self.stable_start is None:
                self.stable_start = now
        ready = (not reasons and self.header is not None and self.stable_start is not None
                 and now-self.stable_start >= self.config.max_poll_gap_ms/1000
                 and all(n >= 3 for n in self.valid_counts.values())
                 and self.last_book is not None and (now-self.last_book)*1000 <= self.config.max_dom_age_ms
                 and self.last_trade is not None and (now-self.last_trade)*1000 <= self.config.max_poll_gap_ms)
        write_record(self.out, dict(kind="warmup", received_utc=datetime.now(timezone.utc).isoformat(),
            elapsed_seconds=now-self.started, source_kind=kind, connected=connected,
            dom_empty=(not bool(record.get("levels"))) if kind == "snapshot" else None,
            reasons=reasons, valid_counts=dict(self.valid_counts), ready=ready, source=record))
        if ready and not self.done():
            # The triggering record stays in warm-up. Only future data fills windows.
            self.offsets = dict(self.sequences)
            self.observer = Observation(self.out, live=True, connected=self.connected,
                                        record_inputs=self.record_inputs)
            self.observer.receive(self.header)
            self.observer.last_start = dict(self.last_start)
            self.observer.expected_cursor = dict(self.cursor)
            self.activated = self.clock()
            write_record(self.out, dict(kind="observation_started", session=self.observer.session,
                warmup_seconds=self.activated-self.started, valid_counts=dict(self.valid_counts),
                sequence_offsets=self.offsets, cursor_reference=self.cursor))

    def report(self):
        now = self.clock()
        missing = []
        if self.observer is None:
            if self.header is None:
                missing.append("capture_session_missing")
            if self.stable_start is None or now-self.stable_start < self.config.max_poll_gap_ms/1000:
                missing.append("stable_interval_missing")
            missing.extend(kind + "_valid_streak_missing" for kind, n in self.valid_counts.items() if n < 3)
            if self.last_book is None or (now-self.last_book)*1000 > self.config.max_dom_age_ms:
                missing.append("recent_dom_missing")
            if self.last_trade is None or (now-self.last_trade)*1000 > self.config.max_poll_gap_ms:
                missing.append("recent_trade_missing")
        write_record(self.out, dict(kind="preparation_summary", ready=self.observer is not None,
            warmup_seconds=(self.activated or self.clock())-self.started,
            valid_counts=self.valid_counts, total_valid=self.total_valid,
            missing_requirements=missing,
            diagnosis="ready" if self.observer else "warmup_not_ready:" + self.last_reason))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--observe-live", action="store_true")
    mode.add_argument("--replay", type=Path)
    parser.add_argument("--seconds", type=float, default=10)
    parser.add_argument("--warmup-timeout", type=float, default=30)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--record-inputs", action="store_true",
                        help="Record accepted detector inputs for exact verified replay (live only)")
    args = parser.parse_args()
    if args.record_inputs and not args.observe_live:
        parser.error("--record-inputs requires --observe-live")
    if not all(math.isfinite(n) and n > 0 for n in (args.seconds, args.warmup_timeout)):
        parser.error("seconds and warmup-timeout must be finite and positive")
    with args.output.open("x", encoding="utf-8") as out:
        if args.replay:
            observer = Observation(out)
            with args.replay.open(encoding="utf-8-sig") as source:
                for line in source:
                    record = json.loads(line)
                    if record.get("kind") == "session" and "start_gmt_s" in record:
                        raise ValueError("Observation replay requires the Python joint capture schema")
                    observer.receive(record)
            result = dict(mode="incremental_replay", faults=sorted(observer.detector.faults),
                          records=observer.order, transitions=len(observer.detector.events))
        else:
            import MetaTrader5 as mt5
            def connected():
                info = mt5.terminal_info()
                return info is not None and bool(info.connected)
            api = TimedMT5(mt5)
            prepared = PreparedObservation(out, connected, args.warmup_timeout, args.seconds,
                                           timed_api=api, record_inputs=args.record_inputs)
            result = run(api, None, seconds=args.warmup_timeout+args.seconds,
                         on_record=prepared.receive, should_stop=prepared.done)
            observer = prepared.observer
            result["warmup_ready"] = observer is not None
            result["warmup_seconds"] = (prepared.activated or time.monotonic())-prepared.started
            result["warmup_valid_counts"] = prepared.valid_counts
            result["observation_transitions"] = len(observer.detector.events) if observer else 0
            result["observation_faults"] = sorted(observer.detector.faults) if observer else []
        # No finish(): EOF is not a newly captured market observation.
        write_record(out, dict(kind="run_summary", result=json_safe(result),
                               faults=sorted(observer.detector.faults) if observer else []))
    print(json.dumps(json_safe(result), ensure_ascii=False))
    return 2 if observer is None or observer.detector.faults or result.get("errors") else 0


if __name__ == "__main__":
    raise SystemExit(main())
