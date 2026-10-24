"""Read-only HTTP validation of the running backend. Never imports or starts MT5."""
import argparse
from collections import Counter
from datetime import datetime, timezone
from decimal import Decimal
import json
import math
from pathlib import Path
import time
from urllib.request import urlopen

from mt5_live_symbol import configured_symbol

ZERO = Decimal(0)
SIDES = ('buyVolume', 'sellVolume', 'ambiguousVolume')


def utc():
    return datetime.now(timezone.utc).isoformat(timespec='milliseconds')


def number(obj, key, nonnegative=False):
    value = obj[key]
    if isinstance(value, bool) or not isinstance(value, (int, Decimal)):
        raise ValueError(f'{key}: número ausente/inválido')
    value = Decimal(value)
    if not value.is_finite() or (nonnegative and value < 0):
        raise ValueError(f'{key}: número não finito/negativo')
    return value


def inspect(view, expected_symbol):
    """Schema inspected in the actual /api/mt5/volume-profile response and Java DTOs."""
    problems = []
    if not isinstance(view, dict) or type(view['connected']) is not bool:
        raise ValueError('Resposta sem objeto/connected booleano')
    source_faults = view['sourceFaults']
    if not isinstance(source_faults, list) or any(not isinstance(f, str) for f in source_faults):
        raise ValueError('sourceFaults inválido')
    received = view['lastReceivedUtcMsc']
    if received is not None and (type(received) is not int or received < 0):
        raise ValueError('lastReceivedUtcMsc inválido')
    if not view['connected']:
        problems.append('ERROR: backend informa fluxo desconectado')
    if source_faults:
        problems.append('ERROR: sourceFaults=' + ','.join(source_faults))
    p = view['profile']
    if p is None:
        problems.append('ERROR: perfil ausente; não representa volume zero do pregão')
        return None, problems
    if p['symbol'] != expected_symbol:
        problems.append(f"ERROR: símbolo {p['symbol']} difere do esperado {expected_symbol}")
    if not isinstance(p['session'], str):
        raise ValueError('session inválida')
    if p['timezone'] != 'America/Sao_Paulo' or p['volumeSource'] != 'MT5_VOLUME_REAL' or p['volumeUnit'] != 'MT5_VOLUME_REAL_UNITS':
        problems.append('ERROR: fuso/proveniência/unidade inesperados')
    if type(p['partial']) is not bool or type(p['integrityCompromised']) is not bool:
        raise ValueError('partial/integrityCompromised inválidos')
    if not isinstance(p['coverageReasons'], list) or not isinstance(p['faults'], list):
        raise ValueError('coverageReasons/faults inválidos')
    if p['status'] not in ('COMPLETE', 'PARTIAL', 'INCOMPLETE'):
        raise ValueError('status desconhecido')
    compromised = bool(p['faults']) or p['integrityCompromised']
    if compromised:
        problems.append('ERROR: integridade comprometida: ' + ','.join(p['faults']))
    expected_status = 'INCOMPLETE' if compromised else 'PARTIAL' if p['partial'] else 'COMPLETE'
    if p['status'] != expected_status or p['partial'] != bool(p['coverageReasons']) or bool(p['faults']) != p['integrityCompromised']:
        problems.append('ERROR: status, cobertura parcial e falhas inconsistentes')
    if source_faults and not set(source_faults).issubset(p['faults']):
        problems.append('ERROR: falhas da fonte não aparecem no perfil')
    total, delta, tick = number(p,'totalVolume',True), number(p,'delta'), number(p,'tickSize',True)
    if tick != 5:
        problems.append('ERROR: tickSize WIN diferente de 5')
    if tick <= 0:
        raise ValueError('tickSize deve ser positivo')
    trades = p['trades']
    if type(trades) is not int or trades < 0:
        raise ValueError('trades inválido')
    levels = {}
    sums = {name: ZERO for name in SIDES}
    level_total, level_delta, level_trades = ZERO, ZERO, 0
    for level in p['levels']:
        price = number(level,'price',True)
        volume = number(level,'totalVolume',True)
        sides = {key: number(level,key,True) for key in SIDES}
        d = number(level,'delta')
        count = level['trades']
        if type(count) is not int or count < 1:
            raise ValueError(f'Contagem de negócios inválida em {price}')
        if price <= 0 or price % tick or price in levels:
            problems.append(f'ERROR: preço fora da grade/duplicado: {price}')
        if volume <= 0 or volume != sum(sides.values(),ZERO) or d != sides['buyVolume']-sides['sellVolume']:
            problems.append(f'ERROR: soma/delta inconsistente em {price}')
        for key in SIDES:
            sums[key] += sides[key]
            percent_key = key.replace('Volume','Percent')
            expected = sides[key]*100/volume if volume else ZERO
            if abs(number(level,percent_key)-expected) > Decimal('0.000001'):
                problems.append(f'ERROR: {percent_key} inconsistente em {price}')
        levels[price] = dict(totalVolume=volume,delta=d,trades=count,**sides)
        level_total += volume; level_delta += d; level_trades += count
    if total != level_total or delta != level_delta or trades != level_trades:
        problems.append('ERROR: totais/Delta/negócios divergem da soma dos níveis')
    if not levels:
        problems.append('WARN: nenhum negócio aceito; lastReceivedUtcMsc não comprova avanço do perfil')
        if any(p[k] is not None for k in ('poc','vah','val')) or number(p,'valueAreaVolume') or number(p,'valueAreaPercent'):
            problems.append('ERROR: perfil vazio com POC/área de valor inventados')
    else:
        # Independent deterministic recomputation over aggregated prices, not source trades.
        prices = sorted(levels)
        poc = min(prices,key=lambda price:(-levels[price]['totalVolume'],price))
        low = high = prices.index(poc)
        area = levels[poc]['totalVolume']
        while area < total*Decimal('0.70'):
            below, above = low-1, high+1
            if below < 0 and above >= len(prices):
                problems.append('ERROR: volume agregado insuficiente para a área de valor declarada')
                break
            if above >= len(prices) or (below >= 0 and levels[prices[below]]['totalVolume'] >= levels[prices[above]]['totalVolume']):
                low = below; area += levels[prices[low]]['totalVolume']
            else:
                high = above; area += levels[prices[high]]['totalVolume']
        if p['poc'] != poc or p['val'] != prices[low] or p['vah'] != prices[high]:
            problems.append('ERROR: POC/VAH/VAL divergem do algoritmo documentado')
        if number(p,'valueAreaVolume') != area or abs(number(p,'valueAreaPercent')-area*100/total) > Decimal('0.000001'):
            problems.append('ERROR: volume/percentual da área de valor inconsistente')
    for key in ('firstTimeMsc','lastTimeMsc'):
        value = p[key]
        if value is not None and (type(value) is not int or value < 0):
            raise ValueError(key + ' inválido')
    if trades and (p['firstTimeMsc'] is None or p['lastTimeMsc'] is None or p['firstTimeMsc'] > p['lastTimeMsc']):
        problems.append('ERROR: intervalo temporal dos negócios inválido')
    if not trades and (p['firstTimeMsc'] is not None or p['lastTimeMsc'] is not None or not p['partial']):
        problems.append('ERROR: perfil sem negócios com timestamps/cobertura completa indevidos')
    return dict(symbol=p['symbol'],session=p['session'],status=p['status'],partial=p['partial'],
                coverage=p['coverageReasons'],faults=p['faults'],totalVolume=total,delta=delta,trades=trades,
                poc=p['poc'],vah=p['vah'],val=p['val'],levels=levels,received=received,
                lastTimeMsc=p['lastTimeMsc'],**sums), problems


def changes(previous, current):
    problems = []
    for key in ('totalVolume','buyVolume','sellVolume','ambiguousVolume','trades'):
        if current[key] < previous[key]:
            problems.append(f'ERROR: {key} diminuiu na mesma sessão; possível reset/perda de estado')
    if not set(previous['faults']).issubset(current['faults']):
        problems.append('ERROR: falha de integridade desapareceu na mesma sessão')
    if previous['lastTimeMsc'] is not None and (current['lastTimeMsc'] is None or current['lastTimeMsc'] < previous['lastTimeMsc']):
        problems.append('ERROR: último timestamp do negócio retrocedeu')
    changed = sorted(price for price in previous['levels'].keys() | current['levels'].keys()
                     if previous['levels'].get(price) != current['levels'].get(price))
    for price, old in previous['levels'].items():
        new = current['levels'].get(price)
        if new is None or any(new[key] < old[key] for key in ('totalVolume',*SIDES,'trades')):
            problems.append(f'ERROR: nível {price} removido/reduzido na mesma sessão')
    return changed, problems


def brief(sample):
    keys = ('symbol','session','status','partial','trades','totalVolume',*SIDES,'delta','poc','vah','val','lastTimeMsc')
    return ' '.join(f'{key}={sample[key]}' for key in keys)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url',default='http://localhost:8080')
    parser.add_argument('--seconds',type=float,default=300)
    parser.add_argument('--interval',type=float,default=5)
    parser.add_argument('--symbol',default=configured_symbol())
    parser.add_argument('--output',type=Path,default=Path(__file__).resolve().parents[1]/'dom-captures/volume-profile-validation.txt')
    args = parser.parse_args()
    if not all(math.isfinite(v) and v > 0 for v in (args.seconds,args.interval)):
        parser.error('seconds/interval devem ser positivos e finitos')
    endpoint = args.base_url.rstrip('/')+'/api/mt5/volume-profile'
    args.output.parent.mkdir(parents=True,exist_ok=True)
    start = time.monotonic(); deadline = start+args.seconds; scheduled = start
    count = success = failures = unchanged = marker_changes = 0
    issues = Counter(); sessions = {}; previous_key = None
    began = utc()
    # Refuse overwrite: preserve prior validation evidence.
    with args.output.open('x',encoding='utf-8') as out:
        def log(message):
            out.write(message+'\n');out.flush()
        log(f'Validação HTTP Volume Profile\nInício UTC: {began}\nAPI: {endpoint}\nSímbolo esperado: {args.symbol}\nDuração: {args.seconds}s; intervalo: {args.interval}s')
        try:
            while scheduled < deadline:
                time.sleep(max(0,scheduled-time.monotonic()))
                remaining = deadline-time.monotonic()
                if remaining <= 0:break
                count += 1; observed = utc(); problems = []
                try:
                    with urlopen(endpoint,timeout=min(4,args.interval,remaining)) as response:
                        view = json.loads(response.read().decode('utf-8'),parse_float=Decimal)
                    sample, problems = inspect(view,args.symbol)
                    success += 1
                    if sample is not None:
                        key = (sample['symbol'],sample['session'])
                        if previous_key is not None and key != previous_key:
                            problems.append(f'WARN: mudança de símbolo/sessão {previous_key} -> {key}; volumes comparados separadamente')
                        previous_key = key
                        state = sessions.setdefault(key,dict(first=sample,last=None,stagnant_since=None,changes=Counter(),unchanged=0))
                        old = state['last']
                        if old is not None:
                            changed, more = changes(old,sample);problems.extend(more)
                            state['changes'].update(changed)
                            if any(old[k] != sample[k] for k in ('poc','vah','val')):
                                marker_changes += 1
                                log(f'  Marcos: POC {old["poc"]}->{sample["poc"]}; VAH {old["vah"]}->{sample["vah"]}; VAL {old["val"]}->{sample["val"]}')
                            if sample['totalVolume'] == old['totalVolume'] and sample['trades'] == old['trades']:
                                unchanged += 1;state['unchanged'] += 1
                                state['stagnant_since'] = state['stagnant_since'] if state['stagnant_since'] is not None else state['last_monotonic']
                                problems.append(f'WARN: sem avanço de volume/negócios há {time.monotonic()-state["stagnant_since"]:.1f}s; não prova ausência de negócios no mercado')
                            else:state['stagnant_since'] = None
                            log(f'  Níveis alterados: {len(changed)}; preços: '+','.join(map(str,changed[:20]))+('…' if len(changed)>20 else ''))
                        state['last'] = sample;state['last_monotonic'] = time.monotonic()
                        if sample['received'] is None or int(time.time()*1000)-sample['received'] > args.interval*1000:
                            problems.append('WARN: último recebimento do backend ausente/antigo')
                        log(f'Consulta {count} {observed}: '+brief(sample))
                        log('  Cobertura: '+','.join(sample['coverage'])+'; falhas: '+','.join(sample['faults']))
                    else:log(f'Consulta {count} {observed}: perfil ausente')
                except Exception as exc:
                    failures += 1
                    problems.append(f'ERROR: consulta/schema não validável: {type(exc).__name__}: {exc}')
                    log(f'Consulta {count} {observed}: FALHOU')
                for problem in problems:
                    issues[problem] += 1;log('  '+problem)
                scheduled += args.interval
                # Never issue bursts of catch-up requests after slow/error responses.
                while scheduled < time.monotonic() and scheduled < deadline:scheduled += args.interval
            time.sleep(max(0,deadline-time.monotonic()))
        except KeyboardInterrupt:
            issues['WARN: validação interrompida pelo usuário'] += 1
        finally:
            log(f'\nFim UTC: {utc()}\nDuração real: {time.monotonic()-start:.3f}s\nConsultas: {count}; respostas validadas: {success}; erros de consulta/schema: {failures}\nComparações sem avanço: {unchanged}; mudanças POC/VAH/VAL: {marker_changes}')
            if not sessions:
                log('Nenhum perfil validável; volumes iniciais/finais indisponíveis.')
            for key,state in sessions.items():
                log(f'\nSímbolo/sessão: {key}\nInicial: '+brief(state['first'])+'\nFinal: '+brief(state['last']))
                log('Níveis alterados (preço: quantidade de alterações observadas): '+', '.join(f'{p}: {n}' for p,n in sorted(state['changes'].items())))
                for price in sorted(state['first']['levels'].keys() | state['last']['levels'].keys()):
                    before=state['first']['levels'].get(price);after=state['last']['levels'].get(price)
                    if before != after:log(f'  Preço {price}: inicial={before}; final={after}')
            log('\nProblemas encontrados:')
            for problem,n in issues.items():log(f'  [{n}x] {problem}')
            if not issues:log('  Nenhuma inconsistência encontrada nas amostras consultadas.')
            log('Limite: amostragem HTTP; não comprova completude do pregão nem valida recuperação do cursor MT5.')
    print(args.output)
    return 1 if failures or any(p.startswith('ERROR:') for p in issues) else 0


if __name__=='__main__':
    raise SystemExit(main())
