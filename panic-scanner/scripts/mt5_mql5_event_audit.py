"""Streaming offline quality audit of WinZ26EventJointProbe NDJSON; no MT5 access."""
import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path

from mt5_dom_temporal_audit import dist as base_dist, analyze_file as python_audit
from mt5_native_clock_compare import payload, FIELDS
from mt5_dom_probe import analyze as validate_book


def dist(values):
    values=sorted(values)
    return {**base_dist(values),'p99':values[math.ceil(.99*len(values))-1] if values else None}


def sha(path):
    digest=hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):digest.update(block)
    return digest.hexdigest()


def numeric_payload(t):
    try:
        key=payload(t)
        return key if all(math.isfinite(x) for x in key) else None
    except (TypeError, ValueError, OverflowError):return None


def finite(value):return type(value) in (int,float) and math.isfinite(value)


def audit(path,python_path=None,*,segment_seconds=10,gap_ms=500,blocking_ms=100):
    if not all(finite(v) and v>0 for v in (segment_seconds,gap_ms,blocking_ms)):
        raise ValueError('Segment/gap/blocking parameters must be finite and positive')
    path=Path(path);digest=sha(path)
    session=None;summary=None;issues=Counter();kinds=Counter();parse_errors=[]
    observations=[];books=[];batches=[];cursor_checks=[];blocking=[];gaps=[];record_errors=[]
    cursor_diagnostics=[]
    times=Counter();raw_counts=Counter();numeric_counts=Counter()
    tick_count=trade_count=invalid_numeric=0;previous_tick=None;previous_sequence=0;market_intervals=[]
    previous_cursor=None;cursor_verifiable=0;cursor_missing=0;full_observed=0
    invalid_books=empty_books=failed_books=valid_books=book_events=0
    per_record_marks=[];last_kind=None
    metrics={k:{'handler':[],'query':[],'queue':[]} for k in ('book_snapshot','tick_trigger','tick_batch','timer_handler')}
    with path.open(encoding='utf-8-sig') as stream:
        for line_number,line in enumerate(stream,1):
            try:
                r=json.loads(line,parse_constant=lambda x: (_ for _ in ()).throw(ValueError('Nonfinite JSON constant '+x)))
                if not isinstance(r,dict) or not isinstance(r.get('kind'),str):raise ValueError('Missing record kind')
            except (ValueError,TypeError) as exc:
                parse_errors.append({'line':line_number,'error':str(exc),'unterminated_line':not line.endswith('\n')})
                issues['unreadable_record']+=1;continue
            kind=r['kind'];kinds[kind]+=1;last_kind=kind
            if session is None:
                if kind!='session' or r.get('symbol')!='WINZ26':raise ValueError('Missing initial WINZ26 session')
                session=r;continue
            if kind=='session':issues['multiple_sessions_in_file']+=1;continue
            if summary is not None:issues['records_after_summary']+=1
            if kind=='summary':summary=r;continue
            # Evidence only; no observation sequence, market rows, timing metric or
            # validity promotion. The parent failed tick_batch retains its verdict.
            if kind=='cursor_prefix_mismatch_diagnostic':cursor_diagnostics.append(r);continue
            if kind not in metrics:issues['unknown_record_kind']+=1;continue
            if r.get('ok') is False or r.get('error',0)!=0:
                record_errors.append({'sequence':r.get('sequence'),'kind':kind,'ok':r.get('ok'),'error':r.get('error')})
            marks=[]
            if r.get('error',0)!=0:
                marks.append(('INCOMPLETO','record_reports_error'))
            sequence=r.get('sequence')
            if type(sequence) is not int or sequence!=previous_sequence+1:
                issues['observation_sequence_gap_or_order']+=1;marks.append(('INCOMPLETO','sequence_discontinuity'))
            if type(sequence) is int:previous_sequence=sequence
            if r.get('symbol','WINZ26')!='WINZ26':issues['mixed_symbol']+=1;marks.append(('INCOMPLETO','mixed_symbol'))
            required=('handler_start_us','handler_end_us','query_start_us','query_end_us','queue_delay_us')
            if not all(finite(r.get(k)) for k in required):
                issues['missing_or_invalid_timing_fields']+=1
                per_record_marks.append((None,('INDETERMINADO','timing_unavailable')));continue
            hs,he,qs,qe=(r[k] for k in required[:4])
            o={'kind':kind,'sequence':sequence,'handler_start_us':hs,'handler_end_us':he,
               'query_start_us':qs,'query_end_us':qe,'queue_delay_us':r['queue_delay_us']}
            observations.append(o)
            if he<hs:issues['negative_handler_duration']+=1;marks.append(('INCOMPLETO','negative_handler_duration'))
            else:metrics[kind]['handler'].append((he-hs)/1000)
            if qe<qs:issues['negative_query_duration']+=1;marks.append(('INCOMPLETO','negative_query_duration'))
            elif kind in ('book_snapshot','tick_batch'):metrics[kind]['query'].append((qe-qs)/1000)
            if r['queue_delay_us']<0:issues['negative_queue_delay']+=1;marks.append(('INCOMPLETO','negative_queue_delay'))
            else:metrics[kind]['queue'].append(r['queue_delay_us']/1000)
            if kind=='book_snapshot':
                o['valid']=False;o['status']='invalid';o['error']=r.get('error')
                if r.get('trigger')=='OnBookEvent':book_events+=1
                raw=r.get('raw_levels')
                if not r.get('ok') or r.get('error',0)!=0:
                    failed_books+=1;o['status']='failed';marks.append(('INCOMPLETO','dom_query_failed'))
                elif raw==[]:
                    empty_books+=1;o['status']='empty';marks.append(('INDETERMINADO','dom_empty_unknown_state'))
                elif not isinstance(raw,list):
                    invalid_books+=1;issues['missing_book_levels']+=1;marks.append(('INCOMPLETO','missing_book_levels'))
                else:
                    if r.get('dom_market_time_msc') is not None:
                        issues['unexpected_dom_market_timestamp']+=1;marks.append(('INCOMPLETO','invented_dom_timestamp'))
                    try:
                        anomalies=validate_book(raw)['anomalies']
                        if any(not finite(x['price']) for x in raw):issues['non_finite_or_invalid_book_price']+=1
                        ranking_ok=True
                        for label,t,reverse in (('best_bid_levels',2,True),('best_ask_levels',1,False)):
                            expected=sorted([x for x in raw if x['type']==t],key=lambda x:x['price'],reverse=reverse)[:10]
                            if r.get(label)!=expected:issues['incorrect_top10_selection']+=1;ranking_ok=False
                        if anomalies or not ranking_ok:
                            invalid_books+=1;marks.append(('INCOMPLETO','invalid_dom_or_ranking'))
                            o['anomalies']=anomalies
                        else:valid_books+=1;o['valid']=True;o['status']='valid'
                    except (KeyError,TypeError,ValueError):
                        invalid_books+=1;issues['invalid_book_payload']+=1;marks.append(('INCOMPLETO','invalid_book_payload'))
                for k in ('time_gmt_before','time_gmt_after','time_local_before','time_local_after'):
                    o[k]=r.get(k)
                books.append(o)
            if kind=='tick_batch':
                ok=r.get('ok') is True and r.get('rows_are_new',True) is True and r.get('error',0)==0
                o.update(ok=ok,error=r.get('error'),returned_rows=r.get('returned_rows'))
                if (qe-qs)/1000>=blocking_ms:
                    blocking.append({'sequence':sequence,'start_us':qs,'end_us':qe,'duration_ms':(qe-qs)/1000})
                    marks.append(('INDETERMINADO','long_synchronous_query'))
                before=r.get('cursor_before');after=r.get('cursor_after')
                cursor_present=all(isinstance(c,dict) and type(c.get('time_msc')) is int and type(c.get('ordinal')) is int for c in (before,after))
                o['cursor_verified']=cursor_present
                if not cursor_present:
                    cursor_missing+=1;marks.append(('INDETERMINADO','cursor_fields_unavailable'))
                if not ok:marks.append(('INCOMPLETO','tick_query_failed_or_provisional'))
                entries=r.get('ticks',[])
                if not isinstance(entries,list):entries=[];issues['invalid_tick_array']+=1;marks.append(('INCOMPLETO','invalid_tick_array'))
                if finite(r.get('returned_rows')) and r['returned_rows']<len(entries):
                    issues['returned_count_less_than_rows']+=1;marks.append(('INCOMPLETO','returned_count_inconsistent'))
                limit=session.get('copy_ticks_batch_limit')
                if limit is not None and r.get('returned_rows')==limit:full_observed+=1
                cursor_problems=[]
                if cursor_present:
                    cursor_verifiable+=1
                    bk=(before['time_msc'],before['ordinal']);ak=(after['time_msc'],after['ordinal'])
                    expected_before=previous_cursor
                    if expected_before is None and type(session.get('cursor_seed_ms')) is int:
                        expected_before=(session['cursor_seed_ms'],0)
                    if expected_before is not None and bk!=expected_before:cursor_problems.append('batch_cursor_before_mismatch')
                    expected_key=bk
                    if ok:
                        for t in entries:
                            if not all(type(t.get(k)) is int for k in ('time_msc','cursor_ordinal')):
                                cursor_problems.append('missing_tick_cursor_fields');continue
                            tk=(t['time_msc'],t['cursor_ordinal'])
                            ordinal=expected_key[1]+1 if tk[0]==expected_key[0] else 1
                            if tk<=expected_key or tk[1]!=ordinal:cursor_problems.append('ordinal_or_time_discontinuity')
                            expected_key=tk
                        if ak!=expected_key:cursor_problems.append('batch_cursor_after_mismatch')
                    elif ak!=bk:cursor_problems.append('failed_batch_advanced_cursor')
                    previous_cursor=ak
                    if cursor_problems:marks.append(('INCOMPLETO','cursor_discontinuity'))
                    cursor_checks.append({'sequence':sequence,'verified':not cursor_problems,'problems':cursor_problems})
                if ok:
                    for t in entries:
                        try:
                            key=(t['time_msc'],t['cursor_ordinal'])
                            if previous_tick is not None and key<=previous_tick:
                                issues['raw_tick_cursor_not_increasing']+=1;marks.append(('INCOMPLETO','tick_order_or_ordinal'))
                            if t['time']!=t['time_msc']//1000:
                                issues['time_time_msc_mismatch']+=1;marks.append(('INCOMPLETO','time_time_msc_mismatch'))
                            if previous_tick is not None:market_intervals.append(t['time_msc']-previous_tick[0])
                            previous_tick=key;times[t['time_msc']]+=1
                            raw_counts[json.dumps({k:t[k] for k in FIELDS},sort_keys=True)]+=1
                            numeric=numeric_payload(t)
                            if numeric is None:invalid_numeric+=1;marks.append(('INCOMPLETO','invalid_numeric_tick'))
                            else:numeric_counts[numeric]+=1
                            tick_count+=1;trade_count+=int(bool(t['flags']&24))
                        except (KeyError,TypeError,ValueError,OverflowError):
                            issues['invalid_tick_payload']+=1;marks.append(('INCOMPLETO','invalid_tick_payload'))
                batches.append(o)
            for mark in set(marks):per_record_marks.append((hs,mark))
    if session is None:raise ValueError('Empty capture')
    if summary is None:issues['missing_summary']+=1
    elif summary.get('fault') or summary.get('dropped_observations') or not summary.get('release_ok'):
        issues['summary_reports_incomplete_capture']+=1
    if summary and summary.get('raw_ticks')!=tick_count:issues['tick_count_summary_mismatch']+=1
    if last_kind!='summary' and summary is not None:issues['summary_not_last']+=1

    def cadence(events,source):
        intervals=[]
        for a,b in zip(events,events[1:]):
            delta=(b['query_start_us']-a['query_start_us'])/1000;intervals.append(delta)
            if delta>gap_ms:
                gaps.append({'source':source,'from_sequence':a['sequence'],'to_sequence':b['sequence'],
                    'start_us':a['query_start_us'],'end_us':b['query_start_us'],'duration_ms':delta,
                    'interpretation':'No observations in file between these queries; market activity/loss unknown'})
            if delta<0:issues['query_observation_regression']+=1
        span=(events[-1]['query_start_us']-events[0]['query_start_us'])/1000000 if len(events)>1 else None
        return {'interval_ms':dist(intervals),'observations_per_second':(len(events)-1)/span if span and span>0 else None,
                'gaps_over_500ms':sum(d>500 for d in intervals),'gaps_over_configured_threshold':sum(d>gap_ms for d in intervals)}
    dom_cadence=cadence(books,'DOM');tick_cadence=cadence(batches,'CopyTicks')
    peak=summary.get('peak_queue') if summary else None
    capacity=session.get('queue_capacity')
    dropped=summary.get('dropped_observations') if summary else None
    queue_quality={'peak_observed_in_summary':peak,'capacity':capacity,
        'capacity_reached':peak>=capacity if finite(peak) and finite(capacity) else None,
        'dropped_observations_reported':dropped,
        'overflow_reported':summary.get('fault')=='OBSERVATION_QUEUE_OVERFLOW' if summary else None,
        'saturation_event_count':None,'occupancy_time_series':None,
        'limitation':'Only final peak and aggregate drops exist; cannot locate/count saturation episodes or reconstruct occupancy.'}
    tail={'summary_present':summary is not None,'completed_window':summary.get('completed_window') if summary else None,
          'no_final_tick_drain':summary.get('no_final_tick_drain') if summary else None,
          'last_tick_query_end_us':batches[-1]['query_end_us'] if batches else None,
          'last_dom_query_end_us':books[-1]['query_end_us'] if books else None,
          'last_batch_full':batches[-1].get('returned_rows')==session.get('copy_ticks_batch_limit') if batches and session.get('copy_ticks_batch_limit') is not None else None,
          'missing_market_events':None}
    intended_end=None
    if summary and finite(summary.get('start_us')) and finite(session.get('requested_seconds')):
        intended_end=summary['start_us']+session['requested_seconds']*1000000
    tail['scheduled_end_us']=intended_end
    tail['tick_query_to_scheduled_end_ms']=(intended_end-tail['last_tick_query_end_us'])/1000 if intended_end is not None and tail['last_tick_query_end_us'] is not None else None
    tail['dom_query_to_scheduled_end_ms']=(intended_end-tail['last_dom_query_end_us'])/1000 if intended_end is not None and tail['last_dom_query_end_us'] is not None else None
    if not summary:tail['classification']='INCOMPLETO';tail['reason']='Summary absent: finalization not demonstrated'
    elif summary.get('fault') or dropped or summary.get('completed_window') is False:
        tail['classification']='INCOMPLETO';tail['reason']='Known fault/early stop/drops'
    elif tail['last_batch_full']:
        tail['classification']='INDETERMINADO';tail['reason']='Full last batch: remaining backlog unknown, not proved'
    else:tail['classification']='INDETERMINADO';tail['reason']='No final drain certificate; possible trailing ticks cannot be counted'

    anchor=min((r['handler_start_us'] for r in observations),default=None)
    segments=[]
    if anchor is not None:
        last=max(r['handler_end_us'] for r in observations)
        if summary and finite(summary.get('end_us')):last=max(last,summary['end_us'])
        width=segment_seconds*1000000
        if (last-anchor)/width>100000:raise ValueError('Implausible timeline/too many segments')
        segments=[{'start_us':anchor+i*width,'end_us':min(anchor+(i+1)*width,last),
                   'counts':Counter(),'reasons':set(),'severity':0} for i in range(int((last-anchor)//width)+1)]
        def mark_segment(when,severity,reason):
            index=min(len(segments)-1,max(0,int((when-anchor)//width)))
            s=segments[index];s['severity']=max(s['severity'],severity);s['reasons'].add(reason)
        for o in observations:
            index=min(len(segments)-1,max(0,int((o['handler_start_us']-anchor)//width)))
            segments[index]['counts'][o['kind']]+=1
        for when,(classification,reason) in per_record_marks:
            if when is not None:mark_segment(when,2 if classification=='INCOMPLETO' else 1,reason)
        for gap in gaps:
            for s in segments:
                if s['start_us']<gap['end_us'] and s['end_us']>gap['start_us']:
                    s['severity']=max(s['severity'],1);s['reasons'].add('observation_gap_'+gap['source'])
        unlocated=bool(parse_errors or (summary and dropped) or issues['observation_sequence_gap_or_order'] or issues['records_after_summary'] or issues['multiple_sessions_in_file'])
        for s in segments:
            if not s['counts']['book_snapshot'] or not s['counts']['tick_batch']:
                s['severity']=max(s['severity'],1);s['reasons'].add('no_joint_observations_in_segment')
            if unlocated:s['severity']=max(s['severity'],1);s['reasons'].add('unlocated_integrity_or_drop_evidence')
            if cursor_missing:s['severity']=max(s['severity'],1);s['reasons'].add('cursor_verification_not_complete')
        mark_segment(last,2 if tail['classification']=='INCOMPLETO' else 1,'tail_'+tail['classification'].lower())
        for s in segments:
            s['classification']=('UTILIZAVEL','INDETERMINADO','INCOMPLETO')[s.pop('severity')]
            s['reasons']=sorted(s['reasons']);s['counts']=dict(s['counts'])
    issues=Counter({k:v for k,v in issues.items() if v})
    if any(s['classification']=='INCOMPLETO' for s in segments) or issues or tail['classification']=='INCOMPLETO':overall='INCOMPLETO'
    elif not segments or any(s['classification']=='INDETERMINADO' for s in segments):overall='INDETERMINADO'
    else:overall='UTILIZAVEL'
    result={'analysis':'mql5_event_capture_integrity','report_version':2,'source':str(path.resolve()),'source_sha256':digest,
        'session':session,'summary':summary,'integrity_issues':dict(issues),'record_kinds':dict(kinds),
        'handler_metrics':{k:{'count':len(v['handler']),'handler_ms':dist(v['handler']),'query_ms':dist(v['query']),'queue_delay_ms':dist(v['queue'])} for k,v in metrics.items()},
        'dom_cadence':dom_cadence,'tick_query_cadence':tick_cadence,'dom_empty':empty_books,'dom_failed':failed_books,
        'dom_invalid':invalid_books,'dom_valid':valid_books,'dom_event_records':book_events,
        'raw_ticks':tick_count,'trade_ticks':trade_count,'quote_ticks':tick_count-trade_count,
        'repeated_market_timestamps':sum(n-1 for n in times.values()),'repeated_raw_payloads':sum(n-1 for n in raw_counts.values()),
        'invalid_numeric_tick_payloads':invalid_numeric,'deduplicated':False,'query_errors':sum(not r['ok'] for r in batches),
        'tick_market_intervals_ms':dist(market_intervals),'queue':queue_quality,
        'copy_ticks_potentially_blocking':{'threshold_ms':blocking_ms,'calls':blocking,
             'interpretation':'Long synchronous duration; cannot distinguish network synchronization, terminal work or scheduling.'},
        'cursor_continuity':{'verifiable_batches':cursor_verifiable,'batches_missing_fields':cursor_missing,'checks':cursor_checks,
             'limitation':'Positional cursor cannot certify stable feed prefix or absence of source losses.'},
        'observation_gaps':gaps,'parse_errors':parse_errors,'record_errors':record_errors,'tail':tail,'segments':segments,
        'cursor_prefix_mismatch_diagnostics':cursor_diagnostics,
        'quality':{'classification':overall,'segment_counts':dict(Counter(s['classification'] for s in segments))},
        'criteria':{'UTILIZAVEL':'Joint local records with valid DOM/ticks, verifiable cursor and no detected fault/gap in that segment; descriptive use only.',
             'INCOMPLETO':'Positive evidence of invalid records, cursor fault, query failure, interruption or missing finalization.',
             'INDETERMINADO':'No proof of failure but insufficient evidence: empty DOM, observation gaps, missing fields, long query or final tail.'},
        'parameters':{'segment_seconds':segment_seconds,'gap_ms':gap_ms,'blocking_ms':blocking_ms,'percentile_method':'nearest_rank'},
        'time_ranges':{'market_time_msc_min':min(times) if times else None,
            'market_time_msc_max':max(times) if times else None,
            'observation_ea_start_us':anchor,
            'observation_ea_last_handler_end_us':max((r['handler_end_us'] for r in observations),default=None),
            'book_query_first_time_gmt_s':books[0].get('time_gmt_before') if books else None,
            'book_query_last_time_gmt_s':books[-1].get('time_gmt_after') if books else None,
            'book_query_first_time_local_s':books[0].get('time_local_before') if books else None,
            'book_query_last_time_local_s':books[-1].get('time_local_after') if books else None},
        'time_semantics':{'market':'Original tick time/time_msc, not shifted or compared directly to EA-relative counter.',
            'observation':'Query/handler microseconds relative to EA; TimeGMT/TimeLocal are raw seconds.',
            'availability':'write_start_us/queue_delay mark deferred serialization start, not confirmed disk durability or exchange delivery.',
            'DOM_market_timestamp':None},
        'limitations':['Utilizable segment does not validate market/local clock alignment, causal order or complete exchange coverage.',
             'No observation is not proof of market inactivity or lost events. Missing event counts stay null.',
             'Timer spans recovery and writes; parent handler start may precede lower-sequence child records.',
             'Summary peak does not expose queue occupancy by segment. No final tick drain is a tail limitation.',
             'Exact payload overlap is not unique exchange deal identity; duplicates retained.']}
    if python_path:
        python_path=Path(python_path);py_hash=sha(python_path);baseline=python_audit(python_path)
        python_counts=Counter()
        with python_path.open(encoding='utf-8-sig') as stream:
            for line in stream:
                r=json.loads(line)
                if r['kind']=='tick_batch':
                    for t in r['ticks']:
                        key=numeric_payload(t['raw'])
                        if key is not None:python_counts[key]+=1
        local_windows=[(r['time_gmt_before']*1000,r['time_gmt_after']*1000+1000) for r in books if finite(r.get('time_gmt_before')) and finite(r.get('time_gmt_after'))]
        from mt5_dom_persistence import timestamp
        overlaps=bool(local_windows) and max(b for a,b in local_windows)>=timestamp(baseline['dom']['first_utc']) and min(a for a,b in local_windows)<=timestamp(baseline['dom']['last_utc'])
        result['python_comparison']={'source':str(python_path),'source_sha256':py_hash,'potential_local_overlap_seconds_precision':overlaps,
            'same_session_established':False,'python_dom_cadence_ms':baseline['dom']['start_intervals_ms'],
            'python_tick_query_cadence_ms':baseline['ticks']['start_intervals_ms'],'python_trade_count':baseline['ticks']['trade_ticks'],
            'exact_payload_multiset_overlap':sum((numeric_counts & python_counts).values()) if overlaps else None,
            'matching_status':'payload_overlap_only_not_certified_same_session' if overlaps else 'different_time_windows_no_event_comparison'}
        if py_hash!=sha(python_path):raise ValueError('Python source changed')
    if digest!=sha(path):raise ValueError('Native source changed')
    return result


def markdown(r):
    d=r['dom_cadence']['interval_ms'];c=r['handler_metrics']['tick_batch']['query_ms']
    lines=['# Qualidade da captura experimental MQL5','',f"**Classificacao: {r['quality']['classification']}** (observacao local; nao valida sincronizacao de mercado).",'',
        f"Fonte: {r['source']}",f"SHA-256: {r['source_sha256']}",'',
        '| Metrica | Valor |','|---|---:|',
        f"| Eventos DOM observados (trigger OnBookEvent) | {r['dom_event_records']} |",
        f"| Snapshots validos / vazios / falhos / invalidos | {r['dom_valid']} / {r['dom_empty']} / {r['dom_failed']} / {r['dom_invalid']} |",
        f"| Ticks / negocios por flags / cotacoes | {r['raw_ticks']} / {r['trade_ticks']} / {r['quote_ticks']} |",
        f"| Timestamps repetidos / payloads repetidos (preservados) | {r['repeated_market_timestamps']} / {r['repeated_raw_payloads']} |",
        '', '| Distribuicao (ms) | Mediana | P90 | P99 | Maximo |','|---|---:|---:|---:|---:|',
        f"| Intervalo DOM | {d['median']} | {d['p90']} | {d['p99']} | {d['max']} |",
        f"| CopyTicks duracao | {c['median']} | {c['p90']} | {c['p99']} | {c['max']} |",'',
        f"Fila: {r['queue']}",f"Chamadas potencialmente bloqueantes: {len(r['copy_ticks_potentially_blocking']['calls'])}; limiar diagnostico {r['parameters']['blocking_ms']} ms.",
        f"Integridade: {r['integrity_issues']}; erros de leitura: {r['parse_errors']}",f"Final da captura: {r['tail']}",'',
        '## Trechos (tempo relativo a instancia EA, microssegundos)','',
        '| Inicio us | Fim us | Classificacao | Motivos |','|---:|---:|---|---|']
    for s in r['segments']:lines.append(f"| {s['start_us']} | {s['end_us']} | {s['classification']} | {', '.join(s['reasons']) or 'Sem problema detectado nos campos verificaveis'} |")
    lines+=['','## Criterios e limites','']+[f'- {k}: {v}' for k,v in r['criteria'].items()]
    lines+=['']+['- '+x for x in r['limitations']]
    lines+=['','## Referencias temporais','',str(r['time_ranges']),'']+[f'- {k}: {v}' for k,v in r['time_semantics'].items()]
    return '\n'.join(lines)+'\n'


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('input',type=Path);p.add_argument('--python',type=Path);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--markdown',type=Path,help='Default: output JSON with .md suffix')
    p.add_argument('--segment-seconds',type=float,default=10);p.add_argument('--gap-ms',type=float,default=500);p.add_argument('--blocking-ms',type=float,default=100)
    args=p.parse_args();md=args.markdown or args.output.with_suffix('.md')
    protected={args.input.resolve()}
    if args.python:protected.add(args.python.resolve())
    if args.output.resolve() in protected or md.resolve() in protected or args.output.resolve()==md.resolve():p.error('Output must be distinct from input and Markdown')
    if args.output.exists() or md.exists():p.error('Outputs already exist')
    result=audit(args.input,args.python,segment_seconds=args.segment_seconds,gap_ms=args.gap_ms,blocking_ms=args.blocking_ms)
    with args.output.open('x',encoding='utf-8') as out:json.dump(result,out,ensure_ascii=False,allow_nan=False,indent=2);out.write('\n')
    with md.open('x',encoding='utf-8') as out:out.write(markdown(result))
    print(result['quality']['classification'])


if __name__=='__main__':main()
