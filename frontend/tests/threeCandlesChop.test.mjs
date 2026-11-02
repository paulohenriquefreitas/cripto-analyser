import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {runInNewContext} from 'node:vm';
import ts from 'typescript';
import {z} from 'zod';
const root='../src/features/threecandles/';
const read=name=>JSON.parse(readFileSync(new URL(`${root}validation/chop/${name}.json`,import.meta.url),'utf8'));
const compile=(path,require)=>{const exports={};runInNewContext(ts.transpileModule(readFileSync(new URL(root+path,import.meta.url),'utf8'),{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX}}).outputText,{exports,require,Intl,Math});return exports;};
const api=compile('api/threeCandlesApi.ts',n=>n==='zod'?{z}:{httpClient:{}});
test('CHOP transport accepts native confirmed and provisional prefixes, refusing future and fake confirmations',()=>{
  for(const name of ['0925','1055','1127','1128','1130','1145','1230','1610']){const r=api.viewSchema.safeParse(read(name));assert.equal(r.success,true,r.success?'':JSON.stringify(r.error.issues));}
  for(const mutate of [v=>v.chop.symbol='OTHER',v=>v.chop.availableAtMsc++,v=>v.chop.provisional.availableAtMsc++,v=>v.chop.confirmed[0].provisional=true,v=>v.chop.confirmed[0].readings[0].value=NaN,v=>v.chop.confirmed[0].availableAtMsc=v.chop.confirmed[0].candleTimeMsc]){
    const v=read('1127');mutate(v);assert.equal(api.viewSchema.safeParse(v).success,false);
  }
});
test('lower panel defaults to 14, keeps comparisons independent and distinguishes provisional from closed',()=>{
  let states=[],index=0;
  const react={useState:v=>{const i=index++;if(!(i in states))states[i]=v;return[states[i],v=>states[i]=v];}};
  const {ExperimentalChopPanel}=compile('components/ExperimentalChopPanel.tsx',n=>n==='react'?react:{jsx:(type,props)=>({type,props}),jsxs:(type,props)=>({type,props})});
  const render=name=>{index=0;return ExperimentalChopPanel({snapshot:read(name).chop,zone:'UTC'});};
  const nodes=t=>{const a=[];const walk=n=>{if(n==null)return;if(typeof n!=='object'){a.push(n);return;}if(Array.isArray(n))n.forEach(walk);else{a.push(n);walk(n.props?.children);}};walk(t);return a;};
  let a=nodes(render('1127'));assert.ok(a.some(n=>typeof n==='string'&&n.includes('PROVISÓRIO')));assert.equal(a.filter(n=>n?.type==='path').length,1);
  const inputs=a.filter(n=>n?.type==='input');assert.ok(inputs.every(n=>!n.props.checked));inputs[0].props.onChange({target:{checked:true}});inputs[1].props.onChange({target:{checked:true}});
  a=nodes(render('1130'));assert.equal(a.filter(n=>n?.type==='path').length,3);assert.ok(a.some(n=>typeof n==='string'&&n.includes('CONFIRMADO')));
  assert.equal(a.filter(n=>n?.type==='circle').length,0);
});
