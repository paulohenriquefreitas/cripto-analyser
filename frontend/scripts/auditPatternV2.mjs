import {readFile,writeFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
const root=new URL('../src/features/threecandles/validation/pattern-detector-v2/',import.meta.url);
const s=JSON.parse(await readFile(new URL('WINZ26-2026-10-30-study.json',root),'utf8'));
const candidates=s.candidates,reached=candidates.filter(c=>c.trigger.state==='ALCANCADO_HIPOTETICO');
for(const c of candidates){
 assert.ok(c.context.availableAtMsc<=c.candle1.candle.bucketStartTimeMsc);
 assert.equal(c.context.flow,c.direction==='BUY'?'DOWN':'UP');
 assert.ok(c.context.from.availableAtMsc<=c.context.availableAtMsc&&c.context.to.availableAtMsc<=c.context.availableAtMsc);
 const p=s.visual.patterns.find(p=>p.id===c.id);
 if(c.recognizedAtMsc==null)assert.equal(p,undefined);
 else{assert.ok(p&&p.stages.length>=2);assert.equal(c.recognizedAtMsc,c.candle2.availableAtMsc);assert.equal(p.stages[0].availableAtMsc,c.recognizedAtMsc);}
 if(c.trigger.placedAtMsc!=null){
  const test=c.events.find(e=>e.kind==='REGIAO_TESTADA');assert.ok(test);
  assert.equal(c.trigger.placedAtMsc,test.atMsc);
  assert.ok(c.trigger.referenceKnownAtMsc<=test.atMsc);
  assert.equal(c.trigger.level,c.trigger.referencePrice+(c.direction==='BUY'?20:-20));
 }
 if(c.trigger.reachedAtMsc!=null){
  assert.ok(c.trigger.reachedAtMsc>c.trigger.placedAtMsc);
  assert.ok(c.events.some(e=>e.kind==='REJEICAO_OBSERVADA'&&e.atMsc<=c.trigger.reachedAtMsc));
 }
 assert.ok(c.events.every(e=>e.atMsc<=s.availableAtMsc));
}
const count=fn=>Object.fromEntries([...new Set(candidates.map(fn))].map(k=>[k,candidates.filter(c=>fn(c)===k).length]));
const report={criterion:s.criterionId,symbol:s.symbol,sessionDate:s.sessionDate,availableAtMsc:s.availableAtMsc,
 source:'Frozen native MT5 M1/M5 from src/test/resources/threecandles/pattern-detector',candidateReferences:candidates.length,
 recognizedReferences:s.visual.patterns.length,hypotheticalReachedReferences:reached.length,
 distinctReachedCandleDirectionLevel:new Set(reached.map(c=>[c.candle1Id,c.direction,c.trigger.level].join('|'))).size,
 states:count(c=>c.state),triggerStates:count(c=>c.trigger.state),rejectedContacts:s.rejectedContacts.length,
 causalChecksPassed:true,noPerformanceClaim:true,examples:reached.slice(0,3).map(c=>({id:c.id,context:c.context,recognized:c.recognizedAtMsc,trigger:c.trigger}))};
await writeFile(new URL('verification-v2.json',root),JSON.stringify(report,null,2));
console.log(JSON.stringify({candidates:report.candidateReferences,recognized:report.recognizedReferences,reached:report.hypotheticalReachedReferences,distinctLevels:report.distinctReachedCandleDirectionLevel}));
