import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {runInNewContext} from 'node:vm';
import ts from 'typescript';
import {z} from 'zod';
const root='../src/features/threecandles/';
const compile=(path,require)=>{const exports={};runInNewContext(ts.transpileModule(readFileSync(new URL(root+path,import.meta.url),'utf8'),{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX}}).outputText,{exports,require,Intl,Math});return exports;};
const api=compile('api/threeCandlesApi.ts',n=>n==='zod'?{z}:{httpClient:{}});
const view=()=>{
 const v=JSON.parse(readFileSync(new URL(root+'validation/chop/1127.json',import.meta.url),'utf8'));
 const t=v.availableAtMsc;
 v.dmi={symbol:v.symbol,availableAtMsc:t,period:14,confirmed:[{candleTimeMsc:t-600000,availableAtMsc:t-300000,provisional:false,plusDi:35,minusDi:10,adx:30,regime:'UP'}],provisional:{candleTimeMsc:t-60000,availableAtMsc:t,provisional:true,plusDi:10,minusDi:35,adx:31,regime:'DOWN'}};
 return v;
};
test('DMI transport rejects future evidence, wrong context and premature confirmations',()=>{
 assert.equal(api.viewSchema.safeParse(view()).success,true);
 for(const mutate of [v=>v.dmi.symbol='OTHER',v=>v.dmi.availableAtMsc++,v=>v.dmi.provisional.availableAtMsc++,v=>v.dmi.confirmed[0].provisional=true,v=>v.dmi.confirmed[0].adx=NaN,v=>v.dmi.confirmed[0].availableAtMsc=v.dmi.confirmed[0].candleTimeMsc]) {
  const v=view();mutate(v);assert.equal(api.viewSchema.safeParse(v).success,false);
 }
});
test('DMI panel keeps confirmed direction distinct from partial direction and renders confirmed and provisional histogram bars',()=>{
 const {DmiPanel}=compile('components/DmiPanel.tsx',()=>({jsx:(type,props)=>({type,props}),jsxs:(type,props)=>({type,props})}));
 const tree=DmiPanel({snapshot:view().dmi,zone:'UTC'}),nodes=[];
 const walk=n=>{if(n==null)return;if(typeof n!=='object'){nodes.push(n);return;}if(Array.isArray(n))n.forEach(walk);else{nodes.push(n);walk(n.props?.children);}};walk(tree);
 assert.equal(nodes.filter(n=>n?.type==='rect').length,2);
 assert.equal(nodes.filter(n=>n?.type==='rect'&&n.props.fill==='none').length,1);
 assert.ok(nodes.includes('Alta'));assert.ok(nodes.includes('Baixa'));
 assert.ok(nodes.some(n=>typeof n==='string'&&n.includes('Estado confirmado')));
});

test('histogram is gray below ADX 20 while DI values without ADX remain visibly marked as warming',()=>{
 const {DmiPanel}=compile('components/DmiPanel.tsx',()=>({jsx:(type,props)=>({type,props}),jsxs:(type,props)=>({type,props})}));
 for(const plus of [10,35]) {
  const v=view().dmi;v.confirmed[0]={...v.confirmed[0],plusDi:plus,minusDi:20,adx:19.99,regime:'FLAT'};v.provisional=null;
  assert.equal(api.viewSchema.safeParse({...view(),dmi:v}).success,true);
  const nodes=[];const walk=n=>{if(!n||typeof n!=='object')return;if(Array.isArray(n))n.forEach(walk);else{nodes.push(n);walk(n.props?.children);}};
  walk(DmiPanel({snapshot:v,zone:'UTC'}));assert.equal(nodes.find(n=>n.type==='rect').props.fill,'#808080');
  v.confirmed[0]={...v.confirmed[0],adx:null,regime:'UNAVAILABLE'};nodes.length=0;
  walk(DmiPanel({snapshot:v,zone:'UTC'}));const early=nodes.filter(n=>n.type==='rect');assert.equal(early.length,1);assert.equal(early[0].props['data-warmup'],true);assert.equal(early[0].props.fillOpacity,0.4);
 }
});


test('transport rejects previous-session and preopen DMI points without removing chart history',()=>{
 const v=view(),sessionStart=Math.floor(v.availableAtMsc/86400000)*86400000+9*3600000;
 for(const t of [sessionStart-86400000,sessionStart-300000]) {
  const changed=structuredClone(v);changed.dmi.confirmed[0].candleTimeMsc=t;
  changed.dmi.confirmed[0].availableAtMsc=t+300000;
  assert.equal(api.viewSchema.safeParse(changed).success,false);
 }
 assert.equal(api.viewSchema.safeParse(v).success,true);
});

test('opening panel explicitly reports session warmup instead of claiming direction at 09:45',()=>{
 const {DmiPanel}=compile('components/DmiPanel.tsx',()=>({jsx:(type,props)=>({type,props}),jsxs:(type,props)=>({type,props})}));
 const snapshot=view().dmi;snapshot.provisional=null;
 snapshot.confirmed=Array.from({length:9},(_,i)=>({...snapshot.confirmed[0],candleTimeMsc:9*3600000+i*300000,availableAtMsc:9*3600000+(i+1)*300000,plusDi:null,minusDi:null,adx:null,regime:'UNAVAILABLE'}));
 const nodes=[];const walk=n=>{if(n==null)return;if(typeof n!=='object'){nodes.push(n);return;}if(Array.isArray(n))n.forEach(walk);else{nodes.push(n);walk(n.props?.children);}};
 walk(DmiPanel({snapshot,zone:'UTC'}));
 assert.ok(nodes.includes('Aquecendo sessão'));assert.equal(nodes.filter(n=>n?.type==='rect').length,0);
 assert.ok(nodes.some(n=>n?.props?.['data-testid']==='dmi-session-warmup'));
 assert.ok(nodes.some(n=>typeof n==='string'&&n.includes('/28 candles M5 fechados')));
});


test('histogram renders the 09:00 neutral anchor and early session moves without claiming an ADX regime',()=>{
 const {DmiPanel}=compile('components/DmiPanel.tsx',()=>({jsx:(type,props)=>({type,props}),jsxs:(type,props)=>({type,props})}));
 const snapshot=view().dmi;snapshot.provisional=null;
 const start=Math.floor(snapshot.availableAtMsc/86400000)*86400000+9*3600000;
 snapshot.confirmed=[0,1,2].map((i)=>({candleTimeMsc:start+i*300000,availableAtMsc:start+(i+1)*300000,provisional:false,
  plusDi:null,minusDi:null,adx:null,regime:'UNAVAILABLE',warmupPlusDi:i===1?50:0,warmupMinusDi:i===2?40:0}));
 const nodes=[];const walk=n=>{if(n==null)return;if(typeof n!=='object'){nodes.push(n);return;}if(Array.isArray(n))n.forEach(walk);else{nodes.push(n);walk(n.props?.children);}};
 walk(DmiPanel({snapshot,zone:'UTC'}));
 const bars=nodes.filter(n=>n?.type==='rect');assert.equal(bars.length,3);
 assert.equal(bars[0].props.height,1);assert.equal(bars[1].props.fill,'#22c55e');assert.equal(bars[2].props.fill,'#f87171');
 assert.ok(bars.every(b=>b.props['data-regime']==='UNAVAILABLE'&&b.props['data-warmup']&&b.props.fillOpacity===0.4));
 assert.ok(nodes.includes('09:00'));assert.ok(nodes.some(n=>typeof n==='string'&&n.includes('Estimativa em aquecimento')));
 assert.equal(api.viewSchema.safeParse({...view(),dmi:snapshot}).success,true);
 const invalid=structuredClone(snapshot);invalid.confirmed[1].warmupPlusDi=NaN;
 assert.equal(api.viewSchema.safeParse({...view(),dmi:invalid}).success,false);
});
