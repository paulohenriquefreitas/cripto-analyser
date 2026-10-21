"""Offline comparison of native MQL5 TSV and Python clock probe JSON."""
import argparse
from collections import defaultdict
import csv
import hashlib
import json
from pathlib import Path

from mt5_clock_probe import summary_values

FIELDS=('time','time_msc','bid','ask','last','volume','volume_real','flags')


def payload(raw):
    return tuple(int(raw[k]) if k in ('time','time_msc','volume','flags') else float(raw[k]) for k in FIELDS)


def compare(native_path,python_path):
    native_path,python_path=Path(native_path),Path(python_path)
    before={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in (native_path,python_path)}
    with native_path.open(encoding='utf-8-sig',newline='') as f: native=list(csv.DictReader(f,delimiter='\t'))
    python=json.loads(python_path.read_text(encoding='utf-8-sig'))
    if python.get('symbol')!='WINZ26' or any(r['symbol']!='WINZ26' for r in native):
        raise ValueError('Contratos inesperados')
    ng,pg=defaultdict(list),defaultdict(list)
    for r in native:
        if int(r['ok']): ng[payload(r)].append(r)
    for b in python['batches']:
        for t in b.get('ticks',[]): pg[payload(t['raw'])].append(t)
    common=sorted(ng.keys() & pg.keys())
    metadata=python.get('native_comparison_metadata',{}).get('terminal') or {}
    native_servers=sorted({r['server'] for r in native})
    native_paths=sorted({r['terminal_data_path'] for r in native})
    verified=(native_servers==[python.get('mt5',{}).get('server')] and
              len(native_paths)==1 and metadata.get('data_path') is not None and
              native_paths[0].replace('\\','/').casefold()==metadata['data_path'].replace('\\','/').casefold())
    # Timestamp-independent keys are candidates only, never certified same deals.
    nc,pc=defaultdict(set),defaultdict(set)
    for key in ng: nc[key[2:]].add(key[:2])
    for key in pg: pc[key[2:]].add(key[:2])
    candidates=[]
    for fingerprint in sorted(nc.keys() & pc.keys()):
        if len(nc[fingerprint])==len(pc[fingerprint])==1:
            nt,pt=next(iter(nc[fingerprint])),next(iter(pc[fingerprint]))
            if nt!=pt:
                candidates.append({'fingerprint':dict(zip(FIELDS[2:],fingerprint)),
                    'native_time_msc':nt[1],'python_time_msc':pt[1],
                    'python_minus_native_ms':pt[1]-nt[1], 'same_tick_established':False})
    raw_diffs={name:[] for name in ('time_current_minus_gmt_s','time_trade_server_minus_gmt_s','tick_minus_gmt_ms')}
    for r in native:
        if not int(r['ok']): continue
        gmt=int(r['time_gmt_after'])
        raw_diffs['time_current_minus_gmt_s'].append(int(r['time_current'])-gmt)
        raw_diffs['time_trade_server_minus_gmt_s'].append(int(r['time_trade_server'])-gmt)
        raw_diffs['tick_minus_gmt_ms'].append(int(r['time_msc'])-gmt*1000)
    for p in (native_path,python_path):
        if before[str(p)]!=hashlib.sha256(p.read_bytes()).hexdigest(): raise ValueError('Fonte alterada')
    return {'analysis':'native_python_clock_comparison','source_sha256':before,
        'same_terminal_and_server_evidence':verified,
        'native_observations':len(native),'native_errors':sum(not int(r['ok']) for r in native),
        'native_distinct_payloads':len(ng),'python_distinct_payloads':len(pg),
        'exact_payload_agreement_groups':len(common),
        'exact_matches':[{'raw':dict(zip(FIELDS,k)), 'native_observations':len(ng[k]),
                          'python_observations':len(pg[k]),'individual_deal_identity_established':False} for k in common],
        'timestamp_difference_candidates':candidates,
        'native_clock_differences':{k:summary_values(v) for k,v in raw_diffs.items()},
        'python_observed_differences_ms':python.get('observed_differences_ms'),
        'conclusion':'raw_payload_agreement_observed' if common and verified else 'insufficient_verified_overlap',
        'limitations':['Igualdade de payload confirma valores observados, nao identidade unica de negocio; duplicatas legitimas existem.',
            'Diferencas sem timestamp comum sao candidatos, nao pares comprovados.',
            'TimeGMT e TimeTradeServer dependem do computador/terminal; nao sao NTP independente.',
            'TimeCurrent pode referir-se a outro simbolo do Market Watch; funcoes datetime MQL5 tem resolucao de segundos.',
            'SymbolInfoTick por polling pode perder ticks intermediarios; nao comparar consultas por proximidade apenas.',
            'Sem correcao de timestamp; diferenca entre relogios nao e latencia comprovada.']}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('native',type=Path)
    parser.add_argument('python',type=Path)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    with args.output.open('x',encoding='utf-8') as out:
        out.write(json.dumps(compare(args.native,args.python),ensure_ascii=False,allow_nan=False,indent=2)+'\n')


if __name__=='__main__': main()
