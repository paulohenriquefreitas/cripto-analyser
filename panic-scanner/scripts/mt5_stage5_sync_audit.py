"""Stage 5 offline session-aware DOM/T&T synchronization evidence audit."""
import argparse
from collections import Counter
import csv
import hashlib
import json
import math
from pathlib import Path

from mt5_dom_persistence import timestamp
from mt5_dom_temporal_audit import analyze_file as temporal_audit, association, dist, utc
from mt5_native_clock_compare import compare as native_compare

TOLERANCES=(25,50,100,250)


def sha(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def scenario(trades,dom,intervals,offset,tolerances=TOLERANCES):
    valid=[d for d in dom if d['valid']]
    results={}
    for tolerance in tolerances:
        proximity=Counter(); intervals_count=Counter(); differences=[]; asof_ages=[]
        future_at_event=0; future_at_receipt=0; nearest_ties=0; details=[]
        for t in trades:
            event=t['market_time_msc']+offset
            def distance(d):
                return max(d['start_ms']-event,event-d['end_ms'],0)
            near=[d for d in valid if distance(d)<=tolerance]
            proximity['none' if not near else 'unique' if len(near)==1 else 'ambiguous']+=1
            # Nearest only as a diagnostic; never proof of the event-time book state.
            if near:
                nearest_distance=min(distance(d) for d in near)
                nearest=[d for d in near if distance(d)==nearest_distance]
                nearest_ties+=int(len(nearest)>1)
                if len(nearest)==1:
                    d=nearest[0]
                    differences.append(d['start_ms']-event)
                    future_at_event+=int(d['end_ms']>event)
                    future_at_receipt+=int(d['end_ms']>t['available_local_ms'])
            # Strict as-of cannot cross a missing/empty/invalid snapshot or long gap.
            prior=[d for d in dom if d['end_ms']<=event]
            asof=prior[-1] if prior else None
            usable_asof=asof is not None and asof['valid'] and event-asof['end_ms']<=tolerance
            if usable_asof: asof_ages.append(event-asof['end_ms'])
            conditional=association(t['market_time_msc'],intervals,offset,tolerance)
            intervals_count[conditional['status']]+=1
            details.append({'trade_index':t['trade_index'],'normalized_experimental_ms':event,
                'near_snapshot_sequences':[d['sequence'] for d in near],
                'conditional_interval_status':conditional['status'],
                'asof_snapshot_sequence':asof['sequence'] if usable_asof else None})
        n=len(trades)
        results[str(tolerance)]={'tolerance_ms':tolerance,'eligible_events':n,
            'events_with_proximity_candidate':n-proximity['none'],
            'proximity_counts':dict(proximity),'proximity_ambiguous_pct':100*proximity['ambiguous']/n if n else None,
            'conditional_interval_counts':dict(intervals_count),
            'conditional_indeterminate_pct':100*(n-intervals_count['unique_conditional'])/n if n else None,
            'nearest_snapshot_start_minus_event_ms':dist(differences),
            'nearest_ties':nearest_ties,'nearest_uses_future_at_event':future_at_event,
            'nearest_unavailable_at_trade_receipt':future_at_receipt,
            'asof_eligible_count':len(asof_ages),'asof_age_ms':dist(asof_ages),'assignments':details}
    return {'market_to_local_offset_ms':offset,'offset_status':'hypothesis_only',
        'availability_minus_normalized_event_ms':dist([t['available_local_ms']-t['market_time_msc']-offset for t in trades]),
        'normalized_events_after_availability':sum(t['market_time_msc']+offset>t['available_local_ms'] for t in trades),
        'normalized_events':[{'trade_index':t['trade_index'],'original_market_ms':t['market_time_msc'],
            'experimental_normalized_ms':t['market_time_msc']+offset,
            'availability_local_ms':t['available_local_ms']} for t in trades],
        'tolerances':results}


def joint_session(path):
    rows=[json.loads(line) for line in Path(path).read_text(encoding='utf-8-sig').splitlines()]
    baseline=temporal_audit(path)
    dom=[]; batches={}; raw_ticks=[]
    for r in rows:
        if r['kind']=='snapshot':
            dom.append({'sequence':r['sequence'],'start_ms':timestamp(r['captured_utc']),
                        'end_ms':timestamp(r['completed_utc']),
                        'valid':bool(r['levels']) and r.get('error') is None and not r.get('anomalies',[])})
        if r['kind']=='tick_batch':
            batches[r['sequence']]=r
            raw_ticks.extend(t['raw'] for t in r['ticks'])
    eligible=[]
    for t in baseline['trades']:
        b=batches[t['batch_sequence']]
        raw=b['ticks'][t['index_in_batch']]['raw']
        if not all(math.isfinite(float(raw[k])) and float(raw[k])>0 for k in ('last','volume_real')):
            continue
        eligible.append({**t,'observation_local_start_ms':timestamp(b['captured_utc']),
                         'available_local_ms':timestamp(b['completed_utc'])})
    result={'session_id':str(Path(path).name),'dom_interval_utc':[utc(dom[0]['start_ms']),utc(dom[-1]['end_ms'])],
        'eligible_trade_events':len(eligible),'excluded_invalid_trade_events':len(baseline['trades'])-len(eligible),
        'baseline':{k:v for k,v in baseline.items() if k not in ('trades','dom_intervals','conditional_association_scenarios')},
        'integrity':{'duplicate_market_timestamps':sum(n-1 for n in Counter(t['time_msc'] for t in raw_ticks).values()),
            'repeated_raw_payloads':sum(n-1 for n in Counter(json.dumps(t,sort_keys=True) for t in raw_ticks).values()),
            'deduplicated':False,'sequence_gaps':0,'out_of_order':0,
            'note':'Existing strict auditor rejects order/sequence errors before association.'},
        'scenarios':{'A':scenario(eligible,dom,baseline['dom_intervals'],0),
                     'B':scenario(eligible,dom,baseline['dom_intervals'],10800000),
                     'C':{'status':'unavailable_same_session_clock_evidence',
                          'reason':'No native or independent clock calibration in this DOM session; reception residual is not an offset estimator.'}}}
    return result


def build_report(directory,native_path):
    directory=Path(directory)
    sources=[];inventory=[]
    original_hashes={}
    for p in sorted(directory.glob('*')):
        if not p.is_file() or p.suffix not in ('.json','.ndjson'):continue
        if p.name.startswith('WINZ26-stage5-'):continue
        if p.suffix=='.ndjson':
            rows=[json.loads(x) for x in p.read_text(encoding='utf-8-sig').splitlines()]
            kinds=Counter(r['kind'] for r in rows)
            snapshots=[r for r in rows if r['kind']=='snapshot']
            entry={'file':p.name,'role':'joint_dom_ticks' if kinds['tick_batch'] else 'dom_only',
                'local_start':snapshots[0]['captured_utc'] if snapshots else None,
                'local_end':snapshots[-1]['completed_utc'] if snapshots else None,'kinds':dict(kinds)}
        else:
            r=json.loads(p.read_text(encoding='utf-8-sig'))
            if 'windows_clock' in r:
                entry={'file':p.name,'role':'python_clock_no_dom','local_start':r['windows_clock']['utc'],
                    'local_end':r.get('finished_clock',{}).get('utc'),'counts':r.get('counts')}
            else:entry={'file':p.name,'role':'derived_report_not_new_capture'}
        entry['sha256']=sha(p);inventory.append(entry);sources.append(p)
        original_hashes[str(p)]=entry['sha256']
    native_path=Path(native_path)
    python_path=directory/'WINZ26-python-native-check.json'
    original_hashes[str(native_path)]=sha(native_path)
    clocks=native_compare(native_path,python_path)
    with native_path.open(encoding='utf-8-sig',newline='') as f:native=list(csv.DictReader(f,delimiter='\t'))
    native_start=min(int(r['time_gmt_before'])*1000 for r in native)
    native_end=max(int(r['time_gmt_after'])*1000 for r in native)+1000
    python=json.loads(python_path.read_text(encoding='utf-8-sig'))
    pstart=timestamp(python['batches'][0]['query_start']['utc'])
    pend=timestamp(python['batches'][-1]['query_end']['utc'])
    overlap=max(0,min(native_end,pend)-max(native_start,pstart))
    estimate=clocks['native_clock_differences']['time_current_minus_gmt_s']['median']
    def sequence_quality(values):
        return {'observations':len(values),
            'repeated_timestamps':sum(n-1 for n in Counter(values).values()),
            'out_of_order':sum(b<a for a,b in zip(values,values[1:])),
            'interval_ms':dist([b-a for a,b in zip(values,values[1:])])}
    pyraw=[t['raw'] for b in python['batches'] for t in b.get('ticks',[])]
    native_integrity={'native_market':sequence_quality([int(r['time_msc']) for r in native if int(r['ok'])]),
        'python_market':sequence_quality([int(r['time_msc']) for r in pyraw]),
        'native_observation_gmt':sequence_quality([int(r['time_gmt_before'])*1000 for r in native]),
        'native_sequence_gaps':sum(int(b['sequence'])!=int(a['sequence'])+1 for a,b in zip(native,native[1:]))}
    native_evidence={'integrity':native_integrity,'comparison':clocks,'native_path':str(native_path),'native_sha256':sha(native_path),
        'local_overlap_ms_approx':overlap,
        'clock_scenario_C':{'session':'2026-10-16-native-python-no-DOM',
            'estimated_offset_ms':-estimate*1000 if estimate is not None and overlap>0 and clocks['same_terminal_and_server_evidence'] else None,
            'uncertainty_bound_ms':None,'identifiable_total_offset':False,
            'interpretation':'Median TimeGMT-TimeCurrent is a coarse same-session clock convention estimate; TimeGMT is PC-derived, TimeCurrent may refer to other symbols.',
            'transfer_to_other_sessions':False}}
    sessions=[]
    for item in inventory:
        if item['role']=='joint_dom_ticks':
            try:sessions.append(joint_session(directory/item['file']))
            except ValueError as exc:sessions.append({'session_id':item['file'],'integrity_error':str(exc),'scenarios':None})
    all_hashes=original_hashes
    prior_path=directory/'WINZ26-joint-probe-30s.temporal-audit.json'
    prior=json.loads(prior_path.read_text(encoding='utf-8-sig')) if prior_path.exists() else None
    report={'analysis':'stage5_session_aware_offline_synchronization','conclusion':'INDETERMINADO',
        'scope':'No stage 6, no operational normalization, no causal inference',
        'evidence_inventory':inventory,'native_python_session':native_evidence,'joint_sessions':sessions,
        'criteria':{'VALIDADO':'Requires same-session independent clock offset and finite uncertainty, valid continuity and causal as-of availability accounting. Not satisfied.',
            'PARCIALMENTE_VALIDADO':'Interface agreement and local cadence supported; alone do not validate market-time DOM association.',
            'INDETERMINADO':'No bounded market/local clock relation for the joint DOM session; conditional matching cannot establish reliability.'},
        'partial_findings':['Native/Python agreement can be checked within Oct 16 overlapping session only.',
            'Joint Oct 15 session provides actual DOM/tick co-observation but no native clock calibration.',
            'No transfer of Oct 16 residual to Oct 15. No estimate from median receive-minus-market used as calibration.'],
        'previous_audit_comparison':{'path':str(prior_path),'strict_association':prior.get('strict_association') if prior else None,
            'added':'25/50/100/250ms proximity versus interval uncertainty, future availability, eligible events and session isolation.',
            'unchanged':'No externally bounded clock calibration; earlier conclusion remains.'},
        'time_semantics':{'market':'original time_msc, not modified','observation':'local DOM/tick query start/end',
            'availability':'batch completed_utc is conservative local availability after processing; not exchange event time',
            'tolerance':'Proximity radius; also evaluated as hypothetical clock error bound in separate interval metric; not a measured bound.'},
        'limitations':['Nearest DOM is descriptive only, not the book at execution.',
            'Retrospective bracketing uses the following snapshot, which is future information at event time.',
            'Repeated timestamps and identical raw payloads are retained; not necessarily unique exchange deals.',
            'No gap over 500ms does not prove complete delivery; no final drain guarantees all trailing ticks.',
            'Native seconds-resolution clocks cannot certify 80ms residual or sub-100ms clock agreement.'],
        'required_new_capture':['One common session ID with DOM plus all raw ticks and native clock observations over the same interval.',
            'Same server, contract and terminal path; raw timestamps, flags, multiplicities, sequence and monotonic/UTC brackets preserved.',
            'Record independent Windows UTC reference with error bound and verify source/server time convention contemporaneously.',
            'Document or bound market/local offset separately from delivery delay; without that bound association remains conditional.',
            'Measure DOM polling availability and source age; evaluate multiple intervals without operational signals.'],
        'source_hashes':all_hashes}
    for p,h in all_hashes.items():
        if sha(p)!=h:raise ValueError('Source changed')
    return report


def markdown(r):
    n=r['native_python_session'];c=n['comparison']
    lines=['# Etapa 5 — auditoria offline por sessão','', '**Conclusão: INDETERMINADO para sincronização DOM ↔ negócios em tempo de mercado.**',
        'A concordância entre interfaces é parcialmente sustentada; não fornece calibração retroativa para outra sessão.','',
        f"Nativa: {c['native_observations']} registros; grupos brutos coincidentes: {c['exact_payload_agreement_groups']}; evidência mesmo terminal/servidor: {c['same_terminal_and_server_evidence']}.",
        f"Sobreposição nativo/Python aproximada: {n['local_overlap_ms_approx']:.0f} ms; sessão 16/10 sem DOM.",
        f"C nativo: offset estimado grosseiro {n['clock_scenario_C']['estimated_offset_ms']} ms, sem limite de incerteza; não transferido à sessão DOM.",'',
        '## Inventário','', '| Arquivo | Papel | Início local | Fim local |','|---|---|---|---|']
    for x in r['evidence_inventory']:lines.append(f"| {x['file']} | {x['role']} | {x.get('local_start','—')} | {x.get('local_end','—')} |")
    for s in r['joint_sessions']:
        lines+=['',f"## Sessão {s['session_id']}",'']
        if s.get('integrity_error'):lines.append(s['integrity_error']);continue
        b=s['baseline']
        lines += [f"{s['eligible_trade_events']} negócios elegíveis; DOM {b['dom']['statuses']}; intervalos DOM (ms): {b['dom']['start_intervals_ms']}.",
            f"Repetições e integridade: {s['integrity']}",
            f"Intervalos entre negocios (ms): {b['trade_market_intervals_ms']}",
            f"Inicio de consulta menos mercado (ms): {b['query_start_minus_market_ms']}",
            f"Intervalos entre consultas de ticks (ms): {b['ticks']['start_intervals_ms']}",'',
            '| Cenário | Tolerância ms | Com candidato próximo | Único próximo | Ambíguo próximo | Intervalo único condicional | Indeterminado % | Mais próximo futuro no evento / na recepção |',
            '|---|---:|---:|---:|---:|---:|---:|---:|']
        for name in ('A','B'):
            for tol,m in s['scenarios'][name]['tolerances'].items():
                lines.append(f"| {name} | {tol} | {m['events_with_proximity_candidate']} | {m['proximity_counts'].get('unique',0)} | {m['proximity_counts'].get('ambiguous',0)} | {m['conditional_interval_counts'].get('unique_conditional',0)} | {m['conditional_indeterminate_pct']:.2f} | {m['nearest_uses_future_at_event']} / {m['nearest_unavailable_at_trade_receipt']} |")
        lines+=['','A: bruto. B: +3h hipotético. C: indisponível nesta sessão; residual de recepção não é estimativa independente de offset.',
            'Distribuições completas das métricas resumidas e atribuições por evento/tolerância estão no JSON. Nenhum timestamp original é substituído.']
    lines+=['','## Diferencas entre candidatos proximos, cenario B','',
        '| Tolerancia ms | N sem empate | Snapshot inicio - evento: mediana / P90 / minimo / maximo (ms) | As-of elegiveis |',
        '|---:|---:|---|---:|']
    for session in r['joint_sessions']:
        if session.get('integrity_error'):continue
        for tol,m in session['scenarios']['B']['tolerances'].items():
            d=m['nearest_snapshot_start_minus_event_ms']
            lines.append(f"| {tol} | {d['count']} | {d['median']} / {d['p90']} / {d['min']} / {d['max']} | {m['asof_eligible_count']} |")
    lines+=['',
        'Proximidade e intervalo unico sao metricas distintas: aumentar a tolerancia aumenta candidatos, mas tambem ambiguidade. No intervalo conservador, a tolerancia e hipotese de incerteza do relogio, nunca erro medido.',
        'Diferencas positivas indicam observacao posterior ao evento hipotetico. Nenhuma dessas diferencas mede latencia.',
        'As-of exige observacao concluida antes do evento e idade dentro da tolerancia; nao atravessa ultimo DOM desconhecido. Nao comprova atualidade do feed.',
        'DOM com polling mediano de aproximadamente 100ms limita resolucao temporal; proximidade nao certifica estado do livro na execucao.',
        'C nao aplica o residual de 80ms observado em outra sessao. Sem calibracao contemporanea limitada, a conclusao anterior permanece.',
        '', '## Critérios, limitações e decisão','']
    for k,v in r['criteria'].items():lines.append(f'- {k}: {v}')
    lines+=['']+['- '+x for x in r['limitations']]
    lines+=['','## Comparação com auditoria anterior','',r['previous_audit_comparison']['added'],r['previous_audit_comparison']['unchanged'],
        '','## Captura ainda necessária','']+['- '+x for x in r['required_new_capture']]
    return '\n'.join(lines)+'\n'


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--directory',type=Path,default=Path('panic-scanner/dom-captures'))
    p.add_argument('--native',type=Path,required=True)
    p.add_argument('--output-prefix',type=Path,required=True)
    args=p.parse_args()
    jp=args.output_prefix.with_suffix('.json');mp=args.output_prefix.with_suffix('.md')
    if jp.exists() or mp.exists():p.error('Output exists; refusing overwrite')
    report=build_report(args.directory,args.native)
    with jp.open('x',encoding='utf-8') as f:json.dump(report,f,ensure_ascii=False,allow_nan=False,indent=2);f.write('\n')
    with mp.open('x',encoding='utf-8') as f:f.write(markdown(report))
    print(report['conclusion'])


if __name__=='__main__':main()
