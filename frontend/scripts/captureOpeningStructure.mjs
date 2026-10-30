// Same native Java snapshots, viewport and layers; only the new context differs.
import {readFile,writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';
const root=new URL('../src/features/threecandles/validation/opening-structure/',import.meta.url);
const template=await readFile(new URL('captureThreeCandlesStructure.mjs',import.meta.url),'utf8');
const url=process.argv[2]??'http://localhost:5173/three-candles';
for(const clock of ['0910','0935','0955','1015']) for(const stage of ['before','after']) {
  const fixture=new URL(`${clock}-${stage}.json`,root), output=new URL(`${clock}-${stage}.png`,root);
  process.argv=['node','capture',fileURLToPath(fixture),fileURLToPath(output),url];
  let source=template.replace("?? '2026-10-20'","?? '2026-10-30'").replace('width:1520,height:fixture.structure?.preparation?.experimental?1480:1100','width:1700,height:1400');
  source=source.replace('  await waitFor("document.body.textContent.includes(\'n\\u00edveis exibidos\') || document.body.textContent.includes(\'niveis exibidos\')");', '  await waitFor("document.body.textContent.includes(\'Replay: PAUSED\')");').replace('i<100;i++','i<500;i++').replace('},10000);','},30000);').replace('errors=${errors}', 'errors=${errors}; body=${await evaluate("document.body.innerText")}');
  const begin=source.indexOf("  const screenshot=await send('Page.captureScreenshot'");
  const end=source.indexOf("  await send('Browser.close')",begin);
  if(begin<0||end<0)throw new Error('Capture template changed');
  source=source.slice(0,begin)+`
  await waitFor("!!document.querySelector('[data-testid=structure-summary]')");
  if(fixture.openingStructure)await waitFor("!!document.querySelector('[data-testid=opening-structure-panel]')");
  await evaluate("Array.from(document.querySelectorAll('button')).find(b=>b.textContent.includes('Ajustar')).click()");
  await delay(500);
  const summary=await evaluate("document.querySelector('[data-testid=structure-summary]').innerText");
  const opening=await evaluate("document.querySelector('[data-testid=opening-structure-panel]')?.innerText??null");
  const shot=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});
  await writeFile(outputFile,Buffer.from(shot.data,'base64'));
  const report={source:'Real React chart; intercepted API with Java snapshots over native MT5 M5; same viewport and analytical layers',summary,opening,errors};
  await writeFile(outputFile.replace('.png','-browser.json'),JSON.stringify(report,null,2));
  if(errors.length)throw new Error(JSON.stringify(errors));
  console.log(JSON.stringify({outputFile,errors}));
`+source.slice(end);
  for(let attempt=0;attempt<3;attempt++) {
    try {await import('data:text/javascript;base64,'+Buffer.from(source+`\n// ${clock}-${stage}-${attempt}`).toString('base64'));break;}
    catch(e){console.error(`${clock}-${stage}: ${e.message}`);if(attempt===2)throw new Error(`Capture failed: ${clock}-${stage}`);}
  }
}
for (const clock of ['0910','0935','0955','1015']) {
  const before=JSON.parse(await readFile(new URL(`${clock}-before-browser.json`,root),'utf8'));
  const after=JSON.parse(await readFile(new URL(`${clock}-after-browser.json`,root),'utf8'));
  assert.deepEqual(after.errors,[]);assert.deepEqual(before.errors,[]);
  assert.equal(before.summary,after.summary,'Opening must preserve the existing analytical summary');
  assert.equal(before.opening,null);assert.ok(after.opening.includes('EM FORMAÇÃO'));
}
