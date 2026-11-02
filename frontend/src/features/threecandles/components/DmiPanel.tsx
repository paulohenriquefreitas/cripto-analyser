import type {CandleView} from '../api/threeCandlesApi';

const regimes = {UNAVAILABLE:'Aquecendo sessão',FLAT:'Lateral',INDEFINITE:'Indefinido',UP:'Alta',DOWN:'Baixa'};
const colors = {UNAVAILABLE:'#94a3b8',FLAT:'#808080',INDEFINITE:'#808080',UP:'#22c55e',DOWN:'#f87171'};
export function DmiPanel({snapshot,zone}:{snapshot:NonNullable<CandleView['dmi']>;zone:string}) {
  const points=[...snapshot.confirmed,...(snapshot.provisional?[snapshot.provisional]:[])];
  const latest=points.at(-1), confirmed=snapshot.confirmed.at(-1);
  const first=points[0]?.candleTimeMsc??0, last=latest?.candleTimeMsc??first;
  const x=(t:number)=>55+(t-first)/Math.max(300000,last-first)*870;
  const y=(v:number)=>105-v*0.75;
  const width=Math.max(1,Math.min(18,870*300000/Math.max(300000,last-first)*0.75));
  const clock=(t:number)=>new Intl.DateTimeFormat('pt-BR',{timeZone:zone,hour:'2-digit',minute:'2-digit'}).format(t);
  const plus=latest?.plusDi??latest?.warmupPlusDi,minus=latest?.minusDi??latest?.warmupMinusDi;
  const diff=plus!=null&&minus!=null?plus-minus:null;
  return <section aria-label="Oscilador DMI com filtro ADX" data-testid="dmi-panel" style={{border:'1px solid #334155',borderRadius:8,padding:12}}>
    <strong>DMI: +DI − −DI · filtro ADX(14) ≥ 20</strong>
    <div>Estado confirmado: <strong style={{color:confirmed?colors[confirmed.regime]:undefined}}>{confirmed?regimes[confirmed.regime]:'Aquecendo sessão'}</strong>{confirmed&&` · ${clock(confirmed.availableAtMsc)}`}</div>
    {(!confirmed || confirmed.adx==null)&&<div data-testid="dmi-session-warmup">Aquecimento da sessão: {snapshot.confirmed.length}/28 candles M5 fechados. Sem direção confirmada durante o aquecimento.</div>}
    {latest?.provisional&&<div>Parcial provisória: {regimes[latest.regime]} · {clock(latest.availableAtMsc)}</div>}
    <div>DMI Diff: {diff?.toFixed(2)??'—'} · ADX: {latest?.adx?.toFixed(2)??'—'} · +DI: {plus?.toFixed(2)??'—'} · −DI: {minus?.toFixed(2)??'—'}{latest?.provisional?' (parcial)':''}{latest?.adx==null&&diff!=null?' (estimativa em aquecimento)':''}</div>
    <svg viewBox="0 0 980 215" role="img" aria-label="Histograma DMI: cinza lateral, verde alta, vermelho baixa; barras vazadas são provisórias" style={{width:'100%',maxHeight:240,display:'block'}}>
      {[-100,-50,0,50,100].map(v=><g key={v}><line x1="45" x2="935" y1={y(v)} y2={y(v)} stroke="#64748b" strokeDasharray={v===0?undefined:'4 5'}/><text x="2" y={y(v)+4} fill="#94a3b8" fontSize="10">{v}</text></g>)}
      {points.map(p=>{
        const plus=p.plusDi??p.warmupPlusDi,minus=p.minusDi??p.warmupMinusDi;
        if(plus==null||minus==null) return null;
        const value=plus-minus, warming=p.adx==null;
        const color=warming?(value>0?colors.UP:value<0?colors.DOWN:colors.UNAVAILABLE):colors[p.regime];
        return <rect key={p.candleTimeMsc} data-regime={p.regime} data-warmup={warming} x={x(p.candleTimeMsc)-width/2} y={Math.min(y(0),y(value))} width={width} height={Math.max(1,Math.abs(y(value)-y(0)))} fill={p.provisional?'none':color} fillOpacity={warming?0.4:1} stroke={color} strokeDasharray={p.provisional?'3 2':warming?'2 2':undefined}>
          <title>{clock(p.candleTimeMsc)} · {warming?'Estimativa em aquecimento':regimes[p.regime]} · Diff {value.toFixed(2)} · ADX {p.adx?.toFixed(2)??'aquecendo'}{p.provisional?' · provisório':''}</title>
        </rect>;
      })}
      {points.length>0&&<><text x="45" y="205" fill="#94a3b8" fontSize="11">{clock(first)}</text><text x="900" y="205" fill="#94a3b8" fontSize="11">{clock(last)}</text></>}
    </svg>
    <div><span style={{color:colors.FLAT}}>■ Lateral: ADX &lt; 20</span> · <span style={{color:colors.UP}}>■ Alta</span> · <span style={{color:colors.DOWN}}>■ Baixa</span> · ADX ≥ 20: direção pelo DI dominante; empate: indefinido.</div>
    <small>Classificação experimental; o limite 20 precisa de validação para WIN. A altura representa a diferença dos DIs, não o ADX. Reset diário: somente candles da sessão atual, a partir das 09:00 do feed. Estimativa visual desde a abertura: primeiro candle neutro; depois, DI acumulado da sessão em tons suaves. Confirmacao pelo ADX somente apos 28 candles M5 fechados (11:20 sem lacunas). Tons suaves e contorno pontilhado indicam aquecimento, sem confirmacao pelo ADX. Parcial vazada não altera o estado confirmado. Este painel não bloqueia os padrões.</small>
  </section>;
}
