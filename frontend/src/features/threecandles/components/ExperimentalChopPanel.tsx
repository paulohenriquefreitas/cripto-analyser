import {useState} from 'react';
import type {CandleView} from '../api/threeCandlesApi';

/** Independent lower panel. No analytical inputs or changes to the candle chart. */
export function ExperimentalChopPanel({snapshot,zone}:{snapshot:NonNullable<CandleView['chop']>;zone:string}) {
  const [short,setShort]=useState(false),[long,setLong]=useState(false);
  const points=[...snapshot.confirmed,...(snapshot.provisional?[snapshot.provisional]:[])];
  const latest=points.at(-1),first=points[0]?.candleTimeMsc??0,last=latest?.candleTimeMsc??first;
  const x=(t:number)=>45+(t-first)/Math.max(300000,last-first)*890;
  const y=(v:number)=>180-v*1.5;
  const clock=(t:number)=>new Intl.DateTimeFormat('pt-BR',{timeZone:zone,hour:'2-digit',minute:'2-digit'}).format(t);
  const colors:Record<number,string>={7:'#38BDF8',14:'#FBBF24',21:'#C084FC'};
  const periods=[...(short?[7]:[]),14,...(long?[21]:[])];
  return <section data-testid="chop-panel" aria-label="CHOP experimental" style={{border:'1px solid #334155',borderRadius:8,padding:12}}>
    <strong>CHOP experimental — não direcional</strong>{' '}
    <label><input type="checkbox" checked={short} onChange={e=>setShort(e.target.checked)}/> CHOP(7)</label>{' '}
    <label><input type="checkbox" checked={long} onChange={e=>setLong(e.target.checked)}/> CHOP(21)</label>
    <div>{latest?`${clock(latest.availableAtMsc)} · ${latest.provisional?'PROVISÓRIO — M5 em formação':'CONFIRMADO — M5 fechado'}`:'Histórico ainda indisponível'}</div>
    <svg viewBox="0 0 980 215" role="img" aria-label="Histórico CHOP confirmado e ponto provisório" style={{width:'100%',maxHeight:240,display:'block'}}>
      {[0,38.2,61.8,100].map(v=><g key={v}><line x1="45" x2="935" y1={y(v)} y2={y(v)} stroke="#64748B" strokeDasharray="4 5"/><text x="2" y={y(v)+4} fill="#94A3B8" fontSize="11">{v}</text></g>)}
      {periods.map(n=>{
        let path='';let connected=false;
        for(const p of snapshot.confirmed){const v=p.readings.find(r=>r.period===n)?.value;if(v==null){connected=false;continue;}path+=`${connected?'L':'M'}${x(p.candleTimeMsc)},${y(v)} `;connected=true;}
        const p=snapshot.provisional,r=p?.readings.find(r=>r.period===n),previous=snapshot.confirmed.at(-1),prev=previous?.readings.find(r=>r.period===n);
        return <g key={n}><path d={path} stroke={colors[n]} fill="none" strokeWidth={n===14?2:1}/>
          {p&&r?.value!=null&&<><line x1={previous?x(previous.candleTimeMsc):x(p.candleTimeMsc)} x2={x(p.candleTimeMsc)} y1={prev?.value!=null?y(prev.value):y(r.value)} y2={y(r.value)} stroke={colors[n]} strokeDasharray="3 4"/><circle cx={x(p.candleTimeMsc)} cy={y(r.value)} r="4" fill="none" stroke={colors[n]}/></>}
        </g>;
      })}
      {points.length>0&&<><text x="45" y="205" fill="#94A3B8" fontSize="11">{clock(first)}</text><text x="900" y="205" fill="#94A3B8" fontSize="11">{clock(last)}</text></>}
    </svg>
    {latest&&<table><thead><tr><th>Período</th><th>Valor</th><th>Δ vs. M5 confirmado anterior</th><th>Estado exploratório</th></tr></thead><tbody>{latest.readings.filter(r=>periods.includes(r.period)).map(r=><tr key={r.period} style={{color:colors[r.period]}}><td>{r.period}</td><td>{r.value?.toFixed(2)??'—'}</td><td>{r.delta?.toFixed(2)??'—'} · {r.slope}</td><td>{r.status==='VALID'?r.regime:r.status}</td></tr>)}</tbody></table>}
    <small>38,2 / 61,8 são faixas exploratórias, sem validação para WIN. CHOP não indica compra/venda. Histórico contínuo disponível; inclui TR entre sessões. Traço descontínuo e círculo vazio: parcial provisória.</small>
  </section>;
}
