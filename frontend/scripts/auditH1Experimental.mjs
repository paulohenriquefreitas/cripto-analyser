// Offline comparison against exported native MT5 H1. Does not contact MT5 or the backend.
import {readFile,writeFile,mkdir,mkdtemp} from 'node:fs/promises';
import {resolve,dirname} from 'node:path';
import {tmpdir} from 'node:os';
import {pathToFileURL} from 'node:url';
import {spawn} from 'node:child_process';
import assert from 'node:assert/strict';

const [nativeFile,fixtureFile,outputBase='panic-scanner/docs/three-candles-h1-experimental-oct16']=process.argv.slice(2);
if(!nativeFile || !fixtureFile) throw new Error('Usage: auditH1Experimental.mjs nativeH1.json replay.json [outputBase]');
const native=JSON.parse(await readFile(nativeFile,'utf8'));
const view=JSON.parse(await readFile(fixtureFile,'utf8'));
assert.equal(native.symbol,'WIN$N');assert.equal(view.symbol,'WIN$N');
const plan=view.structure.preparation,comparison=plan.experimental;
assert.equal(plan.detectionMode,'EXPERIMENTAL_1_1');
const date=msc=>new Date(msc).toISOString().slice(0,10);
const fmt=msc=>{const s=new Date(msc).toISOString();return s.slice(8,10)+'/'+s.slice(5,7)+' '+s.slice(11,16);};
const key=l=>`${l.type}:${l.extremeTimeMsc}:${l.price}`;
const known=native.series.H1.filter(c=>c.time*1000+3600000<=plan.sessionOpenMsc && date(c.time*1000)<plan.sessionDate);
const expected=[];
for(let i=1;i<known.length-1;i++) {
  const c=known[i];if(!plan.sourceSessions.includes(date(c.time*1000))) continue;
  for(const [type,field,sign] of [['TOP','high',1],['BOTTOM','low',-1]])
    if(sign*c[field]>sign*known[i-1][field] && sign*c[field]>sign*known[i+1][field])
      expected.push({type,price:c[field],extremeTimeMsc:c.time*1000,confirmedAtMsc:(known[i+1].time+3600)*1000});
}
assert.deepEqual(expected.map(key).sort(),comparison.oneOneCandidates.map(key).sort());
for(const level of comparison.oneOneCandidates) assert.equal(level.confirmedAtMsc,expected.find(l=>key(l)===key(level)).confirmedAtMsc);
assert.ok(plan.candidates.every(l=>l.confirmedAtMsc<=plan.sessionOpenMsc));
const rejected=comparison.evaluations.filter(e=>e.decision!=='CONFIRMED_1_1');
const report={symbol:view.symbol,asOf:plan.sessionOpenMsc,sourceSessions:plan.sourceSessions,nativeDiscrepancies:0,
  strictCount:comparison.strictCandidates.length,oneOneCount:comparison.oneOneCandidates.length,additionalCount:comparison.additionalCandidates.length,
  ...comparison};
await mkdir(dirname(outputBase),{recursive:true});
await writeFile(`${outputBase}.json`,JSON.stringify(report,null,2));
const md=['# Estudo experimental H1 1+1 — WIN$N, Replay de 16/10/2026','',
  'Modo opcional: `EXPERIMENTAL_1_1`; padrão mantido em `STRICT_2_2`. Nenhuma outra estratégia ou detector compartilhado foi alterado.',
  '',`Comparação com H1 nativo: ${comparison.strictCandidates.length} pivôs 2+2, ${comparison.oneOneCandidates.length} candidatos 1+1, ${comparison.additionalCandidates.length} adicionais; zero divergências de preço, origem ou confirmação. Todos confirmados até a abertura de 16/10. Sem score ou corte por relevância.`,
  '', '## Candidatos adicionais', '', '| Origem MT5 | Tipo | Preço | Confirmação 1+1 |','|---|---|---:|---|'];
for(const l of comparison.additionalCandidates) md.push(`| ${fmt(l.extremeTimeMsc)} | ${l.type} | ${l.price} | ${fmt(l.confirmedAtMsc)} |`);
md.push('', 'Nenhum adicional em 13/10. Dois em 14/10 e dois em 15/10. O topo de 14/10 às 18h confirma apenas às 10h de 15/10, após fechar o H1 seguinte. As quatro regiões falham no 2+2 por concorrência com um extremo mais forte a duas barras de distância; não foram rejeitadas pela seleção.',
  '', '## Referência preservada e antecipação do 1+1', '', '| Origem | Tipo | Preço | Confirmação 2+2 original | Confirmação 1+1 na auditoria |','|---|---|---:|---|---|');
for(const l of comparison.strictCandidates) {
  const earlier=comparison.oneOneCandidates.find(c=>comparison.baselineMatches[c.id]===l.id);
  md.push(`| ${fmt(l.extremeTimeMsc)} | ${l.type} | ${l.price} | ${fmt(l.confirmedAtMsc)} | ${fmt(earlier.confirmedAtMsc)} |`);
}
md.push('', 'As linhas da referência preservam seus IDs, preços, confirmações e estados 2+2. Os nove candidatos 1+1 equivalentes são auditados separadamente, sem desenhar uma segunda linha sobre o mesmo pivô.',
  '', '## Avaliações não confirmadas pelo 1+1', '', `${rejected.length} avaliações sem candidato: ${rejected.filter(e=>e.decision==='NOT_EXTREME_1_1').length} não satisfazem o extremo estrito dos vizinhos e ${rejected.filter(e=>e.decision==='UNCONFIRMED_1_1').length} não dispõem de H1 fechado à direita no corte. Igualdade também falha o critério estrito. Estas últimas são avaliações pendentes, não pivôs prometidos.`,
  '',`${comparison.outsideWindowCandidates} candidatos 1+1 confirmados no aquecimento foram excluídos por origem fora de 13–15/10. Nenhum dos 13 candidatos confirmados da janela foi descartado por score, distância, rompimento ou limite.`,
  '', '| Origem | Tipo | Preço | Decisão | Motivo |','|---|---|---:|---|---|');
for(const e of rejected)md.push(`| ${fmt(e.originMsc)} | ${e.type} | ${e.price} | ${e.decision} | ${e.reason} |`);
md.push('', '## Testes e reações por região', '',
  'Agrupamento descritivo com amplitude máxima de 50 pontos, sem encadeamento de preços distantes e sem esconder linhas. Neste conjunto os 13 preços estão separados por mais de 50 pontos: não há grupos com múltiplos pivôs. Retestes não geram novos pivôs automaticamente.',
  '', 'Testes começam somente após todos os pivôs da faixa estarem confirmados. Horas consecutivas que intersectam a faixa formam um episódio; lacunas e mudança de sessão encerram o episódio. As reações são a excursão máxima para cima/baixo nas próximas duas H1 disponíveis, podendo atravessar sessões, sempre fechadas antes da abertura. Sem direção, qualidade ou score presumidos.',
  '', '| Região | Pivôs | Episódios após confirmação |','|---|---:|---:|');
for(const r of comparison.regions)md.push(`| ${r.low}–${r.high} | ${r.pivotIds.length} | ${r.tests.length} |`);
md.push('', 'Os detalhes de cada episódio, excursões e instante final da medição estão no JSON e na auditoria da página. O modo continua experimental: o aumento de sensibilidade adiciona oscilações internas e confirma mais cedo, sem provar relevância operacional.',
  '', '## Artefatos visuais', '',
  '[Gráfico H1 nativo interativo](three-candles-h1-experimental-oct16.html). Candles exportados diretamente pelo MT5; linhas sólidas são referência 2+2 e tracejadas são adicionais 1+1, ambas fúcsia. Linhas começam na confirmação, marcadores apontam a origem. O gráfico é um estudo retrospectivo com corte na abertura de 16/10, não uma simulação de divulgação candle a candle.',
  '', '[Captura H1 nativo](three-candles-h1-experimental-oct16-native.png). [Captura M5](images/three-candles-h1-experimental-oct16.png). O preview M5 usa o frontend real com resposta Java validada e API interceptada; não certifica que o backend em execução foi reiniciado.',
  '', '## Validação', '', 'Testes de igualdade nativa 1+1, preservação 2+2, igualdade entre preparo com histórico completo e corte pré-abertura, fechamento do candle direito, igualdade de extremos, regiões sem futuro, episódios consecutivos, transporte, controles, avanço e reinício do Replay. O critério não foi promovido a padrão.');
await writeFile(`${outputBase}.md`,md.join('\n')+'\n');
const library=await readFile(new URL('../node_modules/lightweight-charts/dist/lightweight-charts.standalone.production.js',import.meta.url),'utf8');
const candles=known.filter(c=>plan.sourceSessions.includes(date(c.time*1000)));
const plotLevels=[...comparison.strictCandidates,...comparison.additionalCandidates];
const html=`<!doctype html><html lang="pt-BR"><meta charset="utf-8"><title>Comparação H1 nativo — WIN$N</title>
<style>body{margin:24px;background:#0c1420;color:#dce5f2;font:16px system-ui}#chart{height:720px}button{margin:10px;padding:8px}p{max-width:1450px}</style>
<h1>WIN$N · H1 nativo de 13, 14 e 15/10/2026</h1>
<p>Estudo disponível na abertura de 16/10: 9 pivôs 2+2 preservados e 4 adicionais 1+1. Fúcsia sólido = 2+2; tracejado = adicionais 1+1. Linhas começam na confirmação; marcadores indicam origem. Os candles vêm do H1 nativo, sem agregação ou preenchimento. Use zoom e rolagem.</p>
<button id="strict">Referência 2+2: visível</button><button id="experimental">Adicionais 1+1: visíveis</button><div id="chart"></div>
<script>${library.replace(/<\/script/gi,'<\\/script')}</script><script>
const candles=${JSON.stringify(candles)},levels=${JSON.stringify(plotLevels)};
const chart=LightweightCharts.createChart(document.getElementById('chart'),{autoSize:true,layout:{background:{color:'#0c1420'},textColor:'#dce5f2'},timeScale:{timeVisible:true},rightPriceScale:{minimumWidth:95}});
const series=chart.addSeries(LightweightCharts.CandlestickSeries,{upColor:'#20bc80',downColor:'#ef505d',borderVisible:false,wickUpColor:'#20bc80',wickDownColor:'#ef505d',priceFormat:{type:'price',precision:0,minMove:5}});
series.setData(candles);
const end=Math.max(...levels.map(l=>l.confirmedAtMsc/1000),candles.at(-1).time+3600);
const plots=levels.map(l=>{const additional=l.id.endsWith(':1+1');const line=chart.addSeries(LightweightCharts.LineSeries,{color:'#ff00ff',lineWidth:2,lineStyle:additional?2:0,priceLineVisible:false,lastValueVisible:true,title:(additional?'1+1 ':'2+2 ')+l.price});const start=l.confirmedAtMsc/1000;line.setData(start<end?[{time:start,value:l.price},{time:end,value:l.price}]:[{time:start,value:l.price}]);return{line,additional};});
const markers=LightweightCharts.createSeriesMarkers(series,levels.map(l=>({time:l.extremeTimeMsc/1000,position:l.type==='TOP'?'aboveBar':'belowBar',shape:l.type==='TOP'?'arrowDown':'arrowUp',color:'#ff00ff',text:(l.id.endsWith(':1+1')?'1+1 ':'2+2 ')+l.price})).sort((a,b)=>a.time-b.time));
const visible={strict:true,experimental:true};
document.getElementById('strict').onclick=()=>{visible.strict=!visible.strict;plots.filter(p=>!p.additional).forEach(p=>p.line.applyOptions({visible:visible.strict}));};
document.getElementById('experimental').onclick=()=>{visible.experimental=!visible.experimental;plots.filter(p=>p.additional).forEach(p=>p.line.applyOptions({visible:visible.experimental}));};
chart.timeScale().fitContent();window.auditReady=true;
</script></html>`;
await writeFile(`${outputBase}.html`,html);
// Isolated headless rendering of the standalone chart; does not touch running services.
const profile=await mkdtemp(resolve(tmpdir(),'h1-native-'));
const browser=spawn(process.env.EDGE_PATH??'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',[
  '--headless=new','--disable-gpu','--no-first-run',`--user-data-dir=${profile}`,
  '--window-size=1650,1080','--virtual-time-budget=2500',`--screenshot=${resolve(`${outputBase}-native.png`)}`,pathToFileURL(resolve(`${outputBase}.html`)).href,
],{windowsHide:true,stdio:'ignore'});
const timeout=setTimeout(()=>browser.kill(),20000);
await new Promise((resolve,reject)=>{browser.on('error',reject);browser.on('exit',code=>code===0?resolve():reject(new Error(`Native preview exit ${code}`)));}).finally(()=>clearTimeout(timeout));
assert.ok((await readFile(`${outputBase}-native.png`)).length>1000);
console.log(JSON.stringify({nativeDiscrepancies:0,strict:comparison.strictCandidates.length,oneOne:comparison.oneOneCandidates.length,additional:comparison.additionalCandidates.length,outputBase}));
