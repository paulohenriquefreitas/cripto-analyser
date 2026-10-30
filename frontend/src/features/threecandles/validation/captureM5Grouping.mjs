// Reuse the controlled Edge/API harness; exercise the actual local visual controls.
// Usage from repository root: node frontend/src/features/threecandles/validation/captureM5Grouping.mjs
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const fixture = fileURLToPath(new URL('grouping/replay.json', import.meta.url));
const output = fileURLToPath(new URL('grouping/before.png', import.meta.url));
process.argv = ['node', 'capture', fixture, output, process.argv[2] ?? 'http://localhost:5173/three-candles'];
let source = await readFile(new URL('../../../../scripts/captureThreeCandlesStructure.mjs', import.meta.url), 'utf8');
source = source.replace('height:fixture.structure?.preparation?.experimental?1480:1100', 'height:1400');
const begin = source.indexOf("  const screenshot=await send('Page.captureScreenshot'");
const end = source.indexOf("  await send('Browser.close')", begin);
assert.ok(begin > 0 && end > begin, 'capture harness changed');
source = source.slice(0, begin) + `
  for(let z=0;z<4;z++) {
    await send('Input.dispatchMouseEvent',{type:'mouseWheel',x:1200,y:850,deltaX:0,deltaY:100});
    await delay(100);
  }
  await send('Input.dispatchMouseEvent',{type:'mouseWheel',x:1200,y:850,deltaX:500,deltaY:0});
  await delay(400);
  const captures=[];
  const choose=async tolerance=>{
    await evaluate("Array.from(document.querySelectorAll('label')).find(l=>l.textContent==='Agrupamento M5 (pontos)').parentElement.querySelector('[role=combobox]').dispatchEvent(new MouseEvent('mousedown',{bubbles:true,button:0}))");
    const label=tolerance===0?'Sem agrupamento':tolerance+' pontos';
    await waitFor("Array.from(document.querySelectorAll('[role=option]')).some(o=>o.textContent==="+JSON.stringify(label)+")");
    await evaluate("Array.from(document.querySelectorAll('[role=option]')).find(o=>o.textContent==="+JSON.stringify(label)+").click()");
    await delay(400);
  };
  const capture=async(name,expected)=>{
    await waitFor("document.body.textContent.includes('31 pivos M5 → "+expected+" linhas')");
    const shot=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});
    const path=outputFile.replace(/before\\.png$/,name+'.png');
    await mkdir(dirname(path),{recursive:true});await writeFile(path,Buffer.from(shot.data,'base64'));
    captures.push({name,path,pivots:31,lines:expected});
  };
  for(const [tolerance,name,count] of [[0,'before',31],[15,'15',28],[30,'30',26],[50,'50',23]]) {
    await choose(tolerance);await capture(name,count);
  }
  await choose(30);
  await evaluate("Array.from(document.querySelectorAll('label')).find(l=>l.textContent.includes('Separar movimentos apos rompimento')).querySelector('input').click()");
  await capture('30-separated',30);
  const controls=await evaluate("({cursor:document.querySelector('input[type=time]').value,originalPivots:document.body.textContent.includes('31 pivos M5'),h1:document.body.textContent.includes('9 niveis exibidos H1')})");
  if(errors.length || !controls.originalPivots || !controls.h1) throw new Error(JSON.stringify({errors,controls}));
  await writeFile(outputFile.replace(/before\\.png$/,'captures.json'),JSON.stringify({captures,controls,errors,source:'real page, Java Replay snapshot from MT5 cache, API intercepted'},null,2));
  console.log(JSON.stringify({captures,controls,errors}));
` + source.slice(end);
try { await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64')); }
catch (error) { console.error(error.message); process.exitCode = 1; }
