"""Replay accepted observer inputs offline and compare decisions exactly. No MT5 import."""
import argparse
from dataclasses import asdict
import json
from pathlib import Path
import time

from experimental_absorption import Detector, INSUFFICIENT
from mt5_dom_probe import json_safe


def restore(value):
    if isinstance(value, dict):
        if set(value) == {'non_finite'} and value['non_finite'] in ('nan', 'inf', '-inf'):
            return float(value['non_finite'])
        return {key: restore(item) for key, item in value.items()}
    if isinstance(value, list):
        return [restore(item) for item in value]
    return value


def verify(records):
    started = time.perf_counter()
    detector = Detector()
    expected_transitions, actual_transitions = [], []
    expected_blocks, actual_blocks = [], []
    session = None
    count = 0
    last_kind = None
    for envelope in records:
        kind = envelope.get('kind')
        if kind == 'detector_input':
            if envelope.get('schema_version') != 1:
                raise ValueError('Unsupported detector_input schema')
            if envelope.get('config') != asdict(detector.c):
                raise ValueError('Detector configuration differs from recording')
            session = session or envelope['session']
            count += 1
            if envelope['session'] != session or envelope['order'] != count:
                raise ValueError('Input session/order discontinuity')
            record, guard = restore(envelope['record']), envelope['guard']
            last_kind = record['kind']
            before = len(detector.events)
            # Recorded observer guards are causal inputs too: never use today's
            # connection or delivery clock, nor infer missing historical state.
            for reason in guard['reasons']:
                if guard['known'] is not None:
                    detector.capture_bounds = [guard['known'], guard['known'] + 1]
                    detector.axis_known = guard['axis']
                detector.fault(reason, guard['known'])
            detector.feed(record)
            for event in detector.events[before:]:
                actual_transitions.append(dict(session=session, order=count,
                    state='INCONCLUSIVA' if event['state'] == INSUFFICIENT else event['state'],
                    evidence=json_safe(event)))
            actual_blocks.append(dict(session=session, order=count,
                blocked=bool(detector.faults), faults=sorted(detector.faults)))
        elif kind == 'transition':
            expected_transitions.append({key: envelope[key] for key in ('session','order','state','evidence')})
        elif kind == 'observation':
            expected_blocks.append({key: envelope[key] for key in ('session','order','blocked','faults')})
    if not count:
        raise ValueError('No detector_input records: source inputs are unavailable')
    if last_kind != 'summary':
        raise ValueError('Incomplete recording: final detector summary input is missing')
    differences = []
    for label, expected, actual in (('transitions', expected_transitions, actual_transitions),
                                    ('blocks', expected_blocks, actual_blocks)):
        if len(expected) != len(actual):
            differences.append(dict(component=label, expected_count=len(expected), actual_count=len(actual)))
        for index, (left, right) in enumerate(zip(expected, actual)):
            if left != right:
                differences.append(dict(component=label, index=index, expected=left, actual=right))
                break
    first_block = next((r['order'] for r in actual_blocks if r['blocked']), None)
    return dict(equivalent=not differences, inputs=count, transitions=len(actual_transitions),
                events=len({r['evidence']['event_id'] for r in actual_transitions}),
                block_checks=len(actual_blocks), first_block_order=first_block,
                faults=sorted(detector.faults), differences=differences,
                duration_seconds=time.perf_counter()-started)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True)
    args = parser.parse_args()
    try:
        with args.input.open(encoding='utf-8-sig') as source:
            result = verify(json.loads(line) for line in source if line.strip())
    except (ValueError, KeyError, TypeError) as exc:
        print(json.dumps(dict(equivalent=False, error=str(exc)), ensure_ascii=False))
        return 2
    print(json.dumps(result, ensure_ascii=False))
    return 0 if result['equivalent'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
