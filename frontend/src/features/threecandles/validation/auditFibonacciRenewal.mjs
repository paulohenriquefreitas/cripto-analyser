import { readFile, writeFile, mkdir } from 'node:fs/promises';
import assert from 'node:assert/strict';
import { analyzeFibonacci, FibonacciMovementEngine } from '../models/FibonacciMovementEngine.ts';

const base=new URL('fibonacci/',import.meta.url);
const out=new URL('renewal/',base);await mkdir(out,{recursive:true});
const view=JSON.parse(await readFile(new URL('replay.json',base),'utf8'));
const cache=JSON.parse(await readFile(new URL('../../../../../historical-data/WIN$N/three-candles-m5/2026-10-16.json',import.meta.url),'utf8'));
for(const bar of view.candles.filter(b=>b.closed)) {
  const raw=cache.candles.find(c=>c.time*1000===bar.candle.bucketStartTimeMsc);
  assert.ok(raw);for(const key of ['open','high','low','close'])assert.equal(bar.candle[key],raw[key]);
}
const full=analyzeFibonacci(view), stream=new FibonacciMovementEngine(5);
let prefixes=0;
for(const bar of view.candles.filter(b=>b.closed&&new Date(b.candle.bucketStartTimeMsc).toISOString().startsWith('2026-10-16'))) {
  const at=bar.availableAtMsc, known=analyzeFibonacci({...view,availableAtMsc:at});
  stream.advance(known.anchors,at);
  assert.deepEqual(stream.versions,known.lanes[0].versions);
  assert.deepEqual(stream.events,known.lanes[0].events);
  assert.deepEqual(stream.versions,full.lanes[0].versions.filter(v=>v.decidedAtMsc<=at));prefixes++;
}
const checkpoints=[];
for(const clock of ['0930','0945','0955','1000','1035']) {
  const at=Date.parse(`2026-10-16T${clock.slice(0,2)}:${clock.slice(2)}:00Z`);
  const known=p=>({...p,...(p.brokenAtMsc>at?{state:'CONFIRMED',brokenAtMsc:null}:{})});
  const prefix={...view,availableAtMsc:at,candles:view.candles.filter(b=>b.availableAtMsc<=at),structure:{...view.structure,
    levels:view.structure.levels.filter(p=>p.confirmedAtMsc<=at).map(known),
    confirmedPivots:view.structure.confirmedPivots.filter(p=>p.confirmedAtMsc<=at).map(known)}};
  await writeFile(new URL(`${clock}.json`,out),JSON.stringify(prefix));
  checkpoints.push({clock,current:analyzeFibonacci(prefix).lanes[0].versions.at(-1)??null});
}
const lane=full.lanes[0],initial=lane.versions[0],renewed=lane.versions[1];
assert.equal(initial.direction,'DOWN');assert.equal(renewed.direction,'UP');
assert.equal(initial.low.id,renewed.low.id);assert.equal(renewed.high.price,209435);
assert.equal(renewed.decidedAtMsc,renewed.high.confirmedAtMsc);
await writeFile(new URL('report.json',out),JSON.stringify({symbol:view.symbol,date:'2026-10-16',
  source:'Java Replay snapshot checked against local MT5 M5 cache; UI uses intercepted API, not a live backend',
  clock:'MT5 chart clock encoded as UTC; no conversion to workstation timezone',prefixes,checkpoints,
  initial,renewed,versions:lane.versions,events:lane.events,
  discrepancy:'User example A=209190 B=208430 and C around 10:25 does not match local source. No image was attached to this turn. Do not treat these captures as reproduction of those exact prices.',
  provisional:'Not implemented: this closed-M5 validation cannot establish the intrabar time of the high. Observed-only running extremes could be added separately with provisional state and a tick-prefix audit.',
  ambiguities:['Internal or equal pivots do not establish a structural reversal.','Older or simultaneous origins cannot establish a new leg.','The terminal anchor stays fixed during extensions; selecting internal retracement pivots would require a separately validated rule.']},null,2));
console.log(JSON.stringify({prefixes,initial:[initial.high.price,initial.low.price,initial.fib50],renewed:[renewed.high.price,renewed.low.price,renewed.fib50]}));
