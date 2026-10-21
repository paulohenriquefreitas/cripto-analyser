"""Offline diagnostic/replay of the experimental EA's consumed-prefix algorithm.

No MT5 calls. The failing response is NOT reconstructible from its row count.
Hypothetical responses are labeled synthetic and never presented as captured rows.
"""
import argparse
from copy import deepcopy
import hashlib
import json
import math
from pathlib import Path

FIELDS = ('time', 'time_msc', 'bid', 'ask', 'last', 'volume', 'volume_real', 'flags')
FLOAT_FIELDS = ('bid', 'ask', 'last', 'volume_real')


def raw(tick):
    return {name: tick[name] for name in FIELDS}


def same_tick(a, b):
    return (all(math.isfinite(t[k]) for t in (a, b) for k in FLOAT_FIELDS)
            and all(a[k] == b[k] for k in FIELDS))


def replay(cursor, prefix, rows, batch_size=2048):
    """Translation of nonempty, error-free RecoverTicks validation/cache commit.

    Preserves the EA's early-break precedence and last_group_start calculation.
    Does not execute MQL5, model allocation failures, timing or empty recovery.
    """
    cursor, prefix = tuple(cursor), deepcopy(prefix)
    previous, ordinal, seen, updated = -1, 0, 0, cursor
    last_group_start, reason = 0, ''
    accepted = []
    first_difference = None
    for i, row in enumerate(rows):
        t = row['time_msc']
        if t < previous or row['time'] != t // 1000:
            reason = 'TICK_ORDER_OR_TIME_ERROR'
            break
        if t != previous:
            last_group_start = i
        ordinal = ordinal+1 if t == previous else 1
        previous = t
        if t == cursor[0]:
            seen = ordinal
            if ordinal <= cursor[1]:
                if len(prefix) != cursor[1]:
                    reason = 'CURSOR_PREFIX_EVIDENCE_UNAVAILABLE'
                    break
                if not same_tick(row, prefix[ordinal-1]):
                    reason = 'CURSOR_PREFIX_CONTENT_MISMATCH'
                    first_difference = dict(response_index=i, prefix_ordinal=ordinal,
                        expected=prefix[ordinal-1], received=raw(row),
                        fields=[k for k in FIELDS if row[k] != prefix[ordinal-1][k]])
                    break
        if t < cursor[0] or t == cursor[0] and ordinal <= cursor[1]:
            continue
        accepted.append(raw(row))
        updated = (t, ordinal)
    if not reason and cursor[1] and seen < cursor[1]:
        reason = 'CURSOR_PREFIX_DISAPPEARED'
    if not reason and len(rows) == batch_size and updated == cursor:
        reason = 'FULL_BATCH_WITHOUT_PROGRESS'
    return dict(ok=not reason, reason=reason, prefix_rows_seen=seen,
                first_difference=first_difference,
                accepted=[] if reason else accepted,
                cursor=cursor if reason else updated,
                prefix=prefix if reason or not accepted else deepcopy(rows[last_group_start:]))


def fixture_from_capture(path):
    data = Path(path).read_bytes()
    records = [json.loads(line) for line in data.decode('utf-8-sig').splitlines()]
    return dict(origin='captured accepted ticks and metadata; rejected response payload absent',
                source=str(Path(path).resolve()), source_sha256=hashlib.sha256(data).hexdigest(),
                session=records[0],
                batches=[r for r in records if r['kind']=='tick_batch'], summary=records[-1])


def reconstruct(fixture):
    cursor = (fixture['session']['cursor_seed_ms'], 0)
    prefix, trace = [], []
    for batch in fixture['batches']:
        before = (batch['cursor_before']['time_msc'], batch['cursor_before']['ordinal'])
        if before != cursor:
            raise ValueError('Recorded cursor chain differs from replay')
        if not batch['ok']:
            return cursor, prefix, trace, batch
        # Previously consumed prefix was verified by successful EA observations.
        # Count equality is checked so this does not invent omitted extra rows.
        response = deepcopy(prefix) + [raw(t) for t in batch['ticks']]
        if len(response) != batch['returned_rows']:
            raise ValueError('Cannot reconstruct successful response from prefix + accepted rows')
        result = replay(cursor, prefix, response)
        after = (batch['cursor_after']['time_msc'], batch['cursor_after']['ordinal'])
        if not result['ok'] or result['cursor'] != after:
            raise ValueError('Replay differs from successful EA cursor')
        prefix, cursor = result['prefix'], result['cursor']
        trace.append(dict(sequence=batch['sequence'], cursor=cursor, prefix=deepcopy(prefix),
                          reconstructed_returned_rows=len(response),
                          last_timestamp_group_start=len(response)-len(prefix)))
    raise ValueError('No rejected batch found')


def synthetic_cases(prefix):
    a, b = deepcopy(prefix)
    # All tail rows are fabricated; none are the missing three/four actual rows.
    def tail(n):
        return [dict(a, time_msc=a['time_msc']+i, time=(a['time_msc']+i)//1000)
                for i in range(1, n+1)]
    return {
        'stable_prefix_plus_three_synthetic_rows': [a, b]+tail(3),
        'swapped_prefix_plus_three_synthetic_rows': [b, a]+tail(3),
        'first_row_ask_represented_as_later_ask': [dict(a, ask=b['ask']), b]+tail(3),
        'first_row_flags_changed_only': [dict(a, flags=b['flags']), b]+tail(3),
        'shorter_prefix_starting_with_second_original_row': [b]+tail(4),
        'missing_entire_anchor': tail(5),
    }


def diagnostic(fixture):
    cursor, prefix, trace, rejected = reconstruct(fixture)
    scenarios = {}
    for name, rows in synthetic_cases(prefix).items():
        result = replay(cursor, prefix, rows)
        scenarios[name] = dict(synthetic=True, returned_rows=len(rows), result=result)
    return dict(analysis='cursor_prefix_content_mismatch', source=fixture['source'],
        source_sha256=fixture['source_sha256'], session=fixture['session'], summary=fixture['summary'],
        valid_batch_replay=trace, expected_cursor=cursor, expected_prefix=prefix,
        expected_prefix_origin='reconstructed from accepted records and current algorithm, not a memory dump',
        rejected_batch=rejected, rejected_raw_response=None,
        observed=dict(first_comparison_at_anchor_failed=True,
                      total_anchor_rows_in_failed_response=None,
                      actual_differing_fields=None, lost_ticks=None,
                      source_cause='UNDETERMINED_WITHOUT_REJECTED_RAW_RESPONSE',
                      expected_prefix_construction_error_reproduced=False),
        synthetic_scenarios=scenarios,
        limitations=['Successful raw replies are reconstructed under the stored-prefix invariant and count equality.',
                     'The replay translates source behavior; it is not execution of compiled MQL5.',
                     'No invented failed response is evidence that CopyTicks reordered or changed rows.'])


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('input', type=Path)
    p.add_argument('--output', required=True, type=Path)
    p.add_argument('--fixture', type=Path)
    p.add_argument('--ea-source', action='append', type=Path, default=[])
    p.add_argument('--ex5', type=Path)
    args = p.parse_args()
    outputs = [args.output]+([args.fixture] if args.fixture else [])
    inputs = [args.input, *args.ea_source]+([args.ex5] if args.ex5 else [])
    if len({x.resolve() for x in outputs})!=len(outputs) or any(x.exists() for x in outputs) or any(x.resolve() in {i.resolve() for i in inputs} for x in outputs):
        p.error('Outputs must be new, distinct files, separate from inputs')
    hashes = {str(x.resolve()):hashlib.sha256(x.read_bytes()).hexdigest() for x in inputs}
    fixture = fixture_from_capture(args.input)
    result = diagnostic(fixture)
    result['artifact_sha256'] = hashes
    for x in inputs:
        if hashlib.sha256(x.read_bytes()).hexdigest()!=hashes[str(x.resolve())]:
            raise ValueError('Input changed during analysis')
    result['inputs_unchanged'] = True
    for path, data in [(args.output, result)]+([(args.fixture, fixture)] if args.fixture else []):
        with path.open('x', encoding='utf-8') as out:
            json.dump(data, out, ensure_ascii=False, allow_nan=False, indent=2)
            out.write('\n')
    print(json.dumps(result['observed'], ensure_ascii=False))


if __name__=='__main__':
    main()
