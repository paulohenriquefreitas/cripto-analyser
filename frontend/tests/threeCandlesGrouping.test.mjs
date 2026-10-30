import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import ts from 'typescript';
import * as grouping from '../src/features/threecandles/models/m5DisplayGroups.ts';

const { groupM5ForDisplay: group, m5GroupLine: line } = grouping;
const pivot = (i, price, changes = {}) => ({ id: `WIN$N:5:${i * 300000}:TOP`, type: 'TOP', price,
  timeframeMinutes: 5, extremeTimeMsc: i * 300000, confirmedAtMsc: (i + 3) * 300000,
  state: 'CONFIRMED', brokenAtMsc: null, ...changes });
function controller() {
  const source = readFileSync(new URL('../src/features/threecandles/models/candleChartController.ts', import.meta.url), 'utf8');
  const { outputText } = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } });
  const exports = {};
  runInNewContext(outputText, { exports, require: () => grouping });
  return exports.createCandleChartController();
}

test('latest confirmed exact price wins; chains do not resurrect removed lines', () => {
  const levels = [pivot(1, 172000), pivot(2, 172025), pivot(3, 172045), pivot(4, 172070)];
  const original = JSON.stringify(levels);
  const groups = group(levels, 30);
  assert.equal(groups.length, 1);
  assert.equal(line(groups[0]), levels.at(-1));
  assert.equal(groups[0].price, 172070);
  assert.deepEqual(groups[0].members, levels);
  assert.equal(groups[0].replacements.length, 3);
  assert.equal(JSON.stringify(levels), original);
  assert.deepEqual(group([...levels].reverse(), 30), groups);
});

test('29 and 30 points replace, 31 points do not; all type combinations', () => {
  for (const distance of [29,30,31]) for (const a of ['TOP','BOTTOM']) for (const b of ['TOP','BOTTOM']) {
    const levels=[pivot(1,100,{type:a}),pivot(2,100+distance,{type:b})];
    const groups=group(levels,30);
    assert.equal(groups.length,distance<=30?1:2);
    assert.equal(line(groups.at(-1)),levels[1]);
    assert.equal(groups.at(-1).type,b);
    if(distance<=30)assert.equal(groups[0].replacements[0].distancePoints,distance);
  }
});

test('a new pivot replaces ALL nearby represented lines, preserving every hidden pivot and event', () => {
  const levels=[pivot(1,100),pivot(2,140),pivot(3,120,{type:'BOTTOM'})];
  const groups=group(levels,30);
  assert.equal(groups.length,1);
  assert.equal(line(groups[0]),levels[2]);
  assert.deepEqual(groups[0].members,levels);
  assert.deepEqual(groups[0].replacements.map(r=>[r.oldPivot.id,r.newPivot.id,r.distancePoints,r.atMsc,r.reason]),
    levels.slice(0,2).map(p=>[p.id,levels[2].id,20,levels[2].confirmedAtMsc,'SUBSTITUIDO_POR_NIVEL_M5_MAIS_RECENTE']));
});

test('only active representatives are compared, never the hidden historical price range', () => {
  const levels=[pivot(1,100),pivot(2,125),pivot(3,80)];
  const groups=group(levels,30);
  assert.deepEqual(groups.map(g=>g.price),[125,80]);
  assert.equal(groups.flatMap(g=>g.members).length,3);
});

test('no future confirmation, deterministic simultaneous ties and original break metadata', () => {
  const a=pivot(1,100,{state:'BROKEN',brokenAtMsc:2000000}),b=pivot(2,120);
  assert.equal(line(group([a,b],30,b.confirmedAtMsc-1)[0]),a);
  assert.equal(line(group([a,b],30,b.confirmedAtMsc)[0]),b);
  assert.equal(group([a,b],30)[0].members[0].brokenAtMsc,2000000);
  const levels=[a,{...b,confirmedAtMsc:a.confirmedAtMsc},
    {...b,id:b.id+':z',type:'BOTTOM',price:110,confirmedAtMsc:a.confirmedAtMsc}];
  assert.deepEqual(group(levels,30),group([...levels].reverse(),30));
  assert.equal(line(group(levels,30)[0]).id,b.id+':z');
});

test('H1 and symbols remain independent; disabled compaction preserves equal-price originals', () => {
  const levels=[pivot(1,100),pivot(2,100),pivot(3,100,{type:'BOTTOM'}),
    pivot(4,100,{id:'OTHER:5:1200000:TOP'}),pivot(5,100,{timeframeMinutes:60})];
  assert.equal(group(levels,30).length,2);
  assert.equal(group(levels,0).length,4);
  assert.equal(group([levels[0],levels[0]],0).length,1);
  assert.throws(()=>group(levels,-1));assert.throws(()=>group(levels,Infinity));
});

test('breaks never exempt a nearby old line; movement count remains an audit observation', () => {
  const a=pivot(1,100,{state:'BROKEN',brokenAtMsc:1500000}),b=pivot(10,110);
  assert.equal(group([a,b],30)[0].movementCount,2);
  assert.deepEqual(group([a,b],30,Infinity,true),group([a,b],30));
});

test('incremental prefixes and shuffled reconstruction agree for 0, 15, 30 and 50 points', () => {
  const levels=[pivot(1,100),pivot(2,105),pivot(4,140),pivot(6,145),pivot(9,110)];
  for(const tolerance of [0,15,30,50])for(let i=0;i<levels.length;i++) {
    const prefix=levels.slice(0,i+1);
    assert.deepEqual(group(levels,tolerance,levels[i].confirmedAtMsc),group(prefix,tolerance));
    assert.deepEqual(group([...levels].reverse(),tolerance,levels[i].confirmedAtMsc),group(prefix,tolerance));
    assert.equal(group(prefix,tolerance).flatMap(g=>g.members).length,prefix.length);
  }
});

test('controller changes tolerance, visibility and movement policy without rebuilding candles or mutating H1/raw memory', () => {
  const levels = [pivot(1, 100), pivot(2, 120), pivot(3, 140), pivot(4, 100, { id: 'h1', timeframeMinutes: 60 })];
  const before = JSON.stringify(levels);
  const c = controller(), received = [];
  let histories = 0;
  const sink = { setHistory() { histories++; }, updateLast() {}, setStructureLevels: l => received.push(l) };
  c.attachChart(sink); c.setVisibility(true, true);
  c.apply({ symbol: 'WIN$N', timeframeMinutes: 5, availableAtMsc: 3000000, candles: [], structure: { levels } });
  const historyCount = histories;
  for (const tolerance of [0, 15, 30, 50, 30]) {
    c.setM5Grouping(tolerance);
    assert.equal(received.at(-1).length, group(levels, tolerance).length + 1);
    assert.equal(received.at(-1).find(l => l.timeframeMinutes === 60), levels[3]);
    assert.equal(histories, historyCount);
  }
  c.setVisibility(false, true); assert.equal(received.at(-1).length, 1);
  c.setVisibility(true, false); assert.equal(received.at(-1).length, 1);
  c.setM5Grouping(0); assert.equal(received.at(-1).length, 3);
  c.attachChart(sink); assert.equal(received.at(-1).length, 3);
  assert.equal(JSON.stringify(levels), before);
  c.reset(); assert.equal(received.at(-1).length, 0);
});
