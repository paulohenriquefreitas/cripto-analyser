"""Offline DOM gap audit. Reads capture/log/source files; never imports MT5.

Intervals are open query-start boundaries. Market-window counts use a separate,
explicit seconds-resolution TimeCurrent bracket, never a clock conversion.
"""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import re
import statistics


def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for block in iter(lambda: f.read(1024*1024), b''):
            h.update(block)
    return h.hexdigest()


def stats(values):
    v = sorted(values)
    return dict(count=len(v), min=v[0] if v else None,
                median=statistics.median(v) if v else None,
                p90=v[math.ceil(.9*len(v))-1] if v else None,
                p99=v[math.ceil(.99*len(v))-1] if v else None,
                max=v[-1] if v else None,
                total=sum(v))


def wall(seconds, utc=False):
    if seconds is None:
        return None
    text = datetime.fromtimestamp(seconds, timezone.utc).strftime('%Y-%m-%dT%H:%M:%S')
    return text+'Z' if utc else text  # local/server epoch encodings are NOT UTC


def start(r):
    return r['query_start_us'] if r['kind'] in ('book_snapshot', 'tick_batch') else r['handler_start_us']


def cadence(rows, lo, hi):
    points = sorted(start(r) for r in rows)
    return dict(count=len(points), rate_hz=len(points)*1e6/(hi-lo),
                interarrival_ms=stats([(b-a)/1000 for a, b in zip(points, points[1:])]),
                max_interval_including_edges_ms=max((b-a)/1000 for a, b in
                                                    zip([lo]+points, points+[hi])))


def union_us(spans, lo, hi):
    merged = []
    for a, b in sorted((max(lo, a), min(hi, b)) for a, b in spans if b>lo and a<hi):
        if b <= a:
            continue
        if merged and a <= merged[-1][1]:
            merged[-1][1] = max(merged[-1][1], b)
        else:
            merged.append([a, b])
    return sum(b-a for a, b in merged)


def snapshot(r, origin):
    bids = [x for x in r['raw_levels'] if x['type']==2]
    asks = [x for x in r['raw_levels'] if x['type']==1]
    bid = max(bids, key=lambda x: x['price'], default=None)
    ask = min(asks, key=lambda x: x['price'], default=None)
    return dict(sequence=r['sequence'], trigger=r['trigger'],
                monotonic_query_start_us=r['query_start_us'],
                since_capture_start_us=r['query_start_us']-origin,
                query_end_us=r['query_end_us'], query_duration_us=r['query_duration_us'],
                local_raw_s=r['time_local_before'], local_wall=wall(r['time_local_before']),
                gmt_raw_s=r['time_gmt_before'], utc=wall(r['time_gmt_before'], True),
                server_last_quote_raw_s=r['time_current'],
                server_last_quote_wall=wall(r['time_current']), dom_market_time_msc=r['dom_market_time_msc'],
                ok=r['ok'], error=r['error'], levels=len(r['raw_levels']),
                best_bid=bid, best_ask=ask,
                spread=ask['price']-bid['price'] if bid and ask else None,
                bid_visible_volume=sum(x['volume_dbl'] for x in bids),
                ask_visible_volume=sum(x['volume_dbl'] for x in asks),
                write_start_us=r['write_start_us'], queue_delay_us=r['queue_delay_us'],
                raw_levels=r['raw_levels'])


def window(rows, lo, hi):
    selected = [r for r in rows if lo < start(r) < hi]
    batches = [r for r in selected if r['kind']=='tick_batch']
    ticks = [t for r in batches for t in r['ticks']]
    return dict(start_us=lo, end_us=hi, duration_s=(hi-lo)/1e6,
                record_counts=dict(Counter(r['kind'] for r in selected)),
                recorded_ticks=len(ticks), recorded_trades=sum(bool(t['flags']&24) for t in ticks),
                recorded_quotes=sum(not bool(t['flags']&24) for t in ticks),
                trade_last_range=[min(t['last'] for t in ticks if t['flags']&24),
                                  max(t['last'] for t in ticks if t['flags']&24)] if any(t['flags']&24 for t in ticks) else None,
                tick_market_range_ms=[min(t['time_msc'] for t in ticks), max(t['time_msc'] for t in ticks)] if ticks else None,
                on_tick=cadence([r for r in selected if r['kind']=='tick_trigger'], lo, hi),
                on_timer=cadence([r for r in selected if r['kind']=='timer_handler'], lo, hi),
                copy_ticks=cadence(batches, lo, hi),
                copy_duration_us=stats([r['query_duration_us'] for r in batches]),
                returned_rows_histogram=dict(Counter(r['returned_rows'] for r in batches)),
                nonprogress_batches=sum(not r['ticks'] for r in batches),
                empty_api_replies=sum(r['returned_rows']==0 for r in batches),
                cursor_first=batches[0]['cursor_before'] if batches else None,
                cursor_last=batches[-1]['cursor_after'] if batches else None,
                on_tick_execution_us=[r['handler_start_us'] for r in selected if r['kind']=='tick_trigger'],
                server_time_current_histogram=dict(Counter(r['time_current'] for r in selected if r['time_current']>0)),
                handler_duration_us_by_kind={k:stats([r['handler_duration_us'] for r in selected if r['kind']==k])
                                             for k in sorted({r['kind'] for r in selected})},
                max_queue_delay_us=max((r['queue_delay_us'] for r in selected), default=None),
                errors=[dict(sequence=r['sequence'], kind=r['kind'], error=r['error'], ok=r['ok'])
                        for r in selected if not r['ok'] or r['error']],
                handler_union_covered_us=union_us([(r['handler_start_us'], r['handler_end_us']) for r in rows], lo, hi),
                copy_union_covered_us=union_us([(r['query_start_us'], r['query_end_us']) for r in rows if r['kind']=='tick_batch'], lo, hi))


def log_events(path):
    raw = Path(path).read_bytes()
    text = raw.decode('utf-16' if raw.startswith((b'\xff\xfe', b'\xfe\xff')) else 'utf-8-sig')
    date = datetime.strptime(Path(path).stem, '%Y%m%d').strftime('%Y-%m-%d')
    result = []
    for number, line in enumerate(text.splitlines(), 1):
        parts = line.split('\t', 4)
        if len(parts)!=5 or not re.fullmatch(r'\d\d:\d\d:\d\d\.\d{3}', parts[2]):
            continue
        message = parts[4]
        category = next((kind for kind, pattern in (
            ('connection_lost', 'connection to .* lost'),
            ('authorized', 'authorized on '),
            ('synchronized', 'terminal synchronized with '),
            ('access_point_switch', 'auto connecting to a better access point'),
            ('capture_start', 'Experimental capture started'),
            ('capture_stop', 'Experimental capture stopped')) if re.search(pattern, message)), None)
        if not category:
            continue
        # Logs use local wall time, interpreted only as an epoch-shaped wall encoding.
        ms = round(datetime.fromisoformat(date+'T'+parts[2]).replace(tzinfo=timezone.utc).timestamp()*1000)
        message = re.sub(r"'\d+'", "'[account redacted]'", message)
        result.append(dict(line=number, local_wall=date+'T'+parts[2], local_encoded_ms=ms,
                           category=category, component=parts[3], message=message))
    return result


def network_evidence(events, before, after):
    lo, hi = before['time_local_before']*1000, (after['time_local_before']+1)*1000
    evidence = [e for e in events if lo<=e['local_encoded_ms']<hi]
    losses = [e for e in evidence if e['category']=='connection_lost']
    # Endpoint clocks have one-second precision. A loss in the starting second
    # coincides with onset to available precision, never an exact causal timestamp.
    onset = any(lo<=e['local_encoded_ms']<lo+1000 for e in losses)
    resumed = any(e['category']=='authorized' for e in evidence)
    classification = 'EXPLICADA' if onset and resumed else 'INDETERMINADA'
    return dict(classification=classification, events=evidence,
                confirmed_connection_loss=bool(losses),
                first_loss_after_start_range_s=[(losses[0]['local_encoded_ms']-lo-1000)/1000,
                                               (losses[0]['local_encoded_ms']-lo)/1000] if losses else None,
                explanation='Connection loss/reconnection coincides with gap onset at available wall-clock precision.'
                if classification=='EXPLICADA' else 'Connection loss explains part of gap; onset not established.'
                if losses else 'No cause demonstrated by available events.')


def audit(path, terminal_log=None, experts_log=None, ea_sources=(), gap_seconds=10):
    if not math.isfinite(gap_seconds) or gap_seconds<=0:
        raise ValueError('gap_seconds must be finite and positive')
    paths = [Path(p) for p in (path, terminal_log, experts_log, *ea_sources, __file__) if p]
    provenance = {str(p.resolve()):dict(sha256=sha(p), bytes=p.stat().st_size) for p in paths}
    with Path(path).open(encoding='utf-8-sig') as f:
        records = [json.loads(line) for line in f]
    session, summary = records[0], records[-1]
    if session['kind']!='session' or summary['kind']!='summary':
        raise ValueError('Session and terminal summary required')
    rows = records[1:-1]
    books = [r for r in rows if r['kind']=='book_snapshot']
    origin = summary['start_us']
    end = origin+session['requested_seconds']*1000000
    trades = [(r, t) for r in rows if r['kind']=='tick_batch' for t in r['ticks']]
    events = log_events(terminal_log) if terminal_log else []
    expert_events = log_events(experts_log) if experts_log else []
    pairs = [(a, b) for a, b in zip(books, books[1:]) if b['query_start_us']-a['query_start_us']>gap_seconds*1e6]
    gaps = []
    for index, (a, b) in enumerate(pairs, 1):
        lo, hi = a['query_start_us'], b['query_start_us']
        item = dict(id=f'G{index}', before=snapshot(a, origin), after=snapshot(b, origin),
                    metrics=window(rows, lo, hi), network=network_evidence(events, a, b))
        # Separate market-time selection from acquisition-time selection. TimeCurrent
        # can be stale: this is a reproducible bracket, not the true DOM outage extent.
        ml, mh = a['time_current']*1000, b['time_current']*1000
        market = [(r, t) for r, t in trades if ml<=t['time_msc']<mh]
        late = [(r, t) for r, t in market if r['query_end_us']>hi]
        timestamps = sorted({t['time_msc'] for r, t in market})
        item['market_bracket'] = dict(start_inclusive_ms=ml, end_exclusive_ms=mh,
            definition='[before.TimeCurrent*1000, after.TimeCurrent*1000); raw server quote seconds, may be stale',
            ticks=len(market), trades=sum(bool(t['flags']&24) for r, t in market),
            ticks_acquired_after_gap=len(late), trades_acquired_after_gap=sum(bool(t['flags']&24) for r, t in late),
            last_acquisition_end_us=max((r['query_end_us'] for r, t in market), default=None),
            market_interarrival_ms=stats([b-a for a, b in zip(timestamps, timestamps[1:])]),
            trade_last_range=[min(t['last'] for r, t in market if t['flags']&24),
                              max(t['last'] for r, t in market if t['flags']&24)] if any(t['flags']&24 for r, t in market) else None)
        post = [r for r in rows if r['kind']=='tick_batch' and hi<r['query_start_us']<hi+10e6]
        item['post_gap_batches'] = [dict(sequence=r['sequence'], query_start_us=r['query_start_us'],
            query_end_us=r['query_end_us'], query_duration_us=r['query_duration_us'],
            returned_rows=r['returned_rows'], accepted_ticks=len(r['ticks']),
            accepted_trades=sum(bool(t['flags']&24) for t in r['ticks']),
            cursor_before=r['cursor_before'], cursor_after=r['cursor_after']) for r in post if r['query_duration_us']>=100000 or r['returned_rows']==session['copy_ticks_batch_limit']]
        item['post_gap_long_handlers'] = [dict(kind=r['kind'], sequence=r['sequence'],
            handler_start_us=r['handler_start_us'], handler_end_us=r['handler_end_us'],
            handler_duration_us=r['handler_duration_us']) for r in rows
            if hi<r['handler_start_us']<hi+10e6 and r['handler_duration_us']>=100000]
        item['comparison_before_30s'] = window(rows, max(origin, lo-30e6), lo)
        item['comparison_after_30s'] = window(rows, hi, min(end, hi+30e6))
        item['classification'] = item['network']['classification']
        gaps.append(item)
    all_intervals = [b['query_start_us']-a['query_start_us'] for a, b in zip(books, books[1:])]
    normal = [d/1000 for d in all_intervals if d<=gap_seconds*1e6]
    # An independent replay validates cursor positions/ordinals without deduplication.
    problems = Counter()
    previous = dict(time_msc=session['cursor_seed_ms'], ordinal=0)
    last_key = None
    for r in rows:
        if r['kind']!='tick_batch':
            continue
        if r['cursor_before']!=previous:
            problems['cursor_before_mismatch'] += 1
        expected = r['cursor_before']
        for t in r['ticks']:
            key = (t['time_msc'], t['cursor_ordinal'])
            ordinal = expected['ordinal']+1 if t['time_msc']==expected['time_msc'] else 1
            if t['cursor_ordinal']!=ordinal or last_key is not None and key<=last_key:
                problems['tick_order_or_ordinal'] += 1
            if t['time']!=t['time_msc']//1000:
                problems['tick_time_inconsistent'] += 1
            expected = dict(time_msc=t['time_msc'], ordinal=t['cursor_ordinal'])
            last_key = key
        if r['cursor_after']!=expected:
            problems['cursor_after_mismatch'] += 1
        previous = r['cursor_after']
    source_info = []
    for p in map(Path, ea_sources):
        text = p.read_text(encoding='utf-8-sig')
        version = re.search(r'#property version "([^"]+)"', text)
        source_info.append(dict(path=str(p.resolve()), version=version.group(1) if version else None,
                                recovery_code_present='WAITING_PREFIX' in text))
    spread_anomalies = []
    for b in books:
        bids = [x['price'] for x in b['raw_levels'] if x['type']==2]
        asks = [x['price'] for x in b['raw_levels'] if x['type']==1]
        if bids and asks and min(asks)<=max(bids):
            spread_anomalies.append(dict(sequence=b['sequence'], query_start_us=b['query_start_us'],
                best_bid=max(bids), best_ask=min(asks),
                anomaly='locked_spread' if min(asks)==max(bids) else 'crossed_spread'))
    seq = [r['sequence'] for r in rows]
    result = dict(analysis='dom_gap_offline', version=1, provenance=provenance,
        parameters=dict(gap_seconds=gap_seconds, comparison_seconds=30, percentile_method='nearest_rank except median=statistics.median'),
        session=session, summary=summary, ea_sources=source_info,
        expert_log_events=expert_events, gaps=gaps,
        global_metrics=window(rows, origin, end),
        dom=dict(snapshots=len(books), onbookevent=sum(b['trigger']=='OnBookEvent' for b in books),
                 intervals_ms=stats([d/1000 for d in all_intervals]),
                 intervals_excluding_selected_gaps_ms=stats(normal),
                 selected_gap_total_s=sum(g['metrics']['duration_s'] for g in gaps),
                 selected_gap_fraction_of_requested_window=sum(g['metrics']['duration_s'] for g in gaps)/session['requested_seconds'],
                 rate_excluding_selected_gaps_hz=len(normal)/(sum(normal)/1000) if sum(normal) else None,
                 spread_anomaly_counts=dict(Counter(b['anomaly'] for b in spread_anomalies)),
                 spread_anomalies=spread_anomalies,
                 valid_empty_init=sum(not b['raw_levels'] and b['trigger']=='OnInit' for b in books)),
        integrity=dict(sequence_gaps_or_reorder=sum(b!=a+1 for a, b in zip([0]+seq, seq)),
                       cursor_problems=dict(problems),
                       raw_ticks=len(trades), trade_ticks=sum(bool(t['flags']&24) for r, t in trades),
                       summary_ticks_match=len(trades)==summary['raw_ticks'],
                       summary_trades_match=sum(bool(t['flags']&24) for r, t in trades)==summary['trade_ticks'],
                       recovery_observation_fields_present=any('continuity_state' in r for r in rows),
                       recovery_summary_fields_present='recovery_episodes' in summary,
                       no_recovery_reported=summary.get('recovery_episodes'),
                       duplicates_removed=False),
        queue=dict(global_peak=summary['peak_queue'], capacity=session['queue_capacity'],
                   dropped=summary['dropped_observations'], per_gap_occupancy=None,
                   mql_event_queue_occupancy=None),
        limitations=['No exchange event count, packet trace or independent DOM stream.',
                     'DOM has no market timestamp. Wall clocks have one-second precision.',
                     'Recorded OnTick/OnTimer counts are executions, not generated event counts.',
                     'Timer parent includes child query/writer: durations cannot be summed without union.',
                     'Source/binary now on disk is not proof of code loaded at capture time.',
                     'Historical tick replay cannot certify exchange completeness or restore historical DOM.'])
    for p in paths:
        if sha(p)!=provenance[str(p.resolve())]['sha256']:
            raise ValueError(f'Input changed during audit: {p}')
    result['inputs_unchanged_after_analysis'] = True
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('input', type=Path)
    p.add_argument('--terminal-log', type=Path)
    p.add_argument('--experts-log', type=Path)
    p.add_argument('--ea-source', type=Path, action='append', default=[])
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--gap-seconds', type=float, default=10)
    args = p.parse_args()
    protected = [args.input, args.terminal_log, args.experts_log, *args.ea_source]
    if args.output.resolve() in {f.resolve() for f in protected if f} or args.output.exists():
        p.error('Output must be new and distinct from all inputs')
    result = audit(args.input, args.terminal_log, args.experts_log, args.ea_source, args.gap_seconds)
    with args.output.open('x', encoding='utf-8') as f:
        json.dump(result, f, ensure_ascii=False, allow_nan=False, indent=2)
        f.write('\n')
    print(json.dumps(dict(gaps=[dict(id=g['id'], duration_s=g['metrics']['duration_s'], classification=g['classification']) for g in result['gaps']], integrity=result['integrity']), ensure_ascii=False))


if __name__=='__main__':
    main()
