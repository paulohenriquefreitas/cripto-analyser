import { test } from 'node:test';
import assert from 'node:assert/strict';
import { FibonacciMovementEngine } from '../src/features/threecandles/models/FibonacciMovementEngine.ts';

const pivot = (id, type, price, origin, confirmation) => ({ id, type, price,
  symbol: 'WIN$N', timeframeMinutes: 5, extremeTimeMsc: origin, confirmedAtMsc: confirmation });

test('A → B becomes B → C only at confirmation, symmetrically, without rewriting A → B', () => {
  for (const mirror of [false, true]) {
    const p = (id, type, price, origin, confirmation) => pivot(id,
      mirror ? type === 'TOP' ? 'BOTTOM' : 'TOP' : type, mirror ? 420000-price : price, origin, confirmation);
    const a=p('A','TOP',209190,1,4), b=p('B','BOTTOM',208430,2,5), c=p('C','TOP',209500,6,9);
    const e=new FibonacciMovementEngine(5);
    e.advance([a,b,c],5);
    const original=structuredClone(e.versions[0]);
    e.advance([a,b,c],8);assert.equal(e.versions.length,1);
    e.advance([a,b,c],9);
    assert.equal(e.versions.length,2);
    const next=e.versions[1];
    assert.equal(next.direction,mirror?'DOWN':'UP');
    assert.equal(next.decidedAtMsc,9);
    assert.equal(next[mirror?'high':'low'].id,'B');
    assert.equal(next[mirror?'low':'high'].id,'C');
    assert.equal(next.fib50,mirror?420000-208965:208965);
    assert.deepEqual(e.versions[0],original);
    assert.deepEqual(next.previous.high,original.high);
    assert.deepEqual(next.previous.low,original.low);
    assert.deepEqual(e.events.map(x=>x.kind),['DIRECTION_CHANGED','ANCHORS_UPDATED']);
    e.advance([c,b,a],10);assert.equal(e.versions.length,2);
  }
});

test('internal/equal extrema do not reverse; delayed older and simultaneous origins stay ambiguous',()=>{
  const a=pivot('A','TOP',200,1,4), b=pivot('B','BOTTOM',100,2,5);
  const e=new FibonacciMovementEngine(5);e.advance([a,b],5);
  e.advance([pivot('inside','TOP',180,6,9),pivot('equal','TOP',200,7,10)],10);
  assert.equal(e.versions.length,1);
  e.advance([pivot('old','TOP',210,1,11),pivot('same','TOP',220,2,12)],12);
  assert.equal(e.versions.length,1);assert.equal(e.ambiguities.length,2);
});
