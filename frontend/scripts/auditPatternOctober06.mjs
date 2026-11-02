import {readFile,writeFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
const root=new URL('../src/features/threecandles/validation/pattern-detector-oct06/',import.meta.url);
const s=JSON.parse(await readFile(new URL('study-after.json',root),'utf8'));
const before=JSON.parse(await readFile(new URL('../../panic-scanner/docs/three-candles-oct06-runtime-before.json',import.meta.url),'utf8')).patternStudy;
const raw=JSON.parse(await readFile(new URL('../../panic-scanner/src/test/resources/threecandles/pattern-detector/WINZ26-2026-10-06-M1.json',import.meta.url),'utf8'));
const minuteTimes=new Set(raw.map(m=>m.time*1000));
const reached=s.candidates.filter(c=>c.trigger.reachedAtMsc!=null);
assert.equal(s.m1Status,'NATIVE_M1_GAPS_INDETERMINATE');
assert.equal(before.m1Status,'M1_AUSENTE_SEQUENCIA_INDETERMINADA');
assert.equal(before.candidates.filter(c=>c.trigger.reachedAtMsc!=null).length,0);
assert.ok(reached.length>0);
for(const c of reached){
 assert.ok(c.context.availableAtMsc<=c.candle1.candle.bucketStartTimeMsc);
 assert.ok(c.context.anchor.confirmedAtMsc<=c.context.availableAtMsc);
 assert.ok(c.recognizedAtMsc===c.candle2.availableAtMsc);
 assert.ok(c.trigger.placedAtMsc<c.trigger.reachedAtMsc);
 assert.equal(c.trigger.level,c.trigger.referencePrice+(c.direction==='BUY'?20:-20));
 assert.ok(c.events.some(e=>e.kind==='REGIAO_TESTADA'&&e.atMsc<c.trigger.reachedAtMsc));
 assert.ok(c.events.some(e=>e.kind==='REJEICAO_OBSERVADA'&&e.atMsc<=c.trigger.reachedAtMsc));
 for(let t=c.candle3.candle.bucketStartTimeMsc;t<c.trigger.reachedAtMsc;t+=60000)assert.ok(minuteTimes.has(t),'Reached trigger crossed a native M1 gap');
}
const unique=[...new Map(reached.map(c=>[[c.candle1Id,c.direction,c.trigger.level].join('|'),c])).values()];
const clock=t=>new Date(t).toISOString().slice(11,16);
const report={symbol:s.symbol,date:s.sessionDate,nativeM1:raw.length,beforeM1Status:before.m1Status,afterM1Status:s.m1Status,
 beforeReachedReferences:0,afterReachedReferences:reached.length,distinctSequences:unique.length,
 unit:'C1 + direction + trigger price; confluences are reference records, not independent trades',
 examples:unique.map(c=>({c1:clock(c.candle1.candle.bucketStartTimeMsc),c2:clock(c.candle2.candle.bucketStartTimeMsc),c3:clock(c.candle3.candle.bucketStartTimeMsc),direction:c.direction,
 trigger:c.trigger.level,placed:clock(c.trigger.placedAtMsc),reached:clock(c.trigger.reachedAtMsc),reference:c.region,context:c.context})),
 causalAndGapChecksPassed:true,noWinRateOrExecutionClaim:true};
await writeFile(new URL('verification.json',root),JSON.stringify(report,null,2));
console.log(JSON.stringify({nativeM1:raw.length,reachedReferences:reached.length,distinctSequences:unique.length,examples:report.examples.map(({c1,direction,trigger,reached})=>({c1,direction,trigger,reached}))}));
