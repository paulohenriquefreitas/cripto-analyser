// Same causal native snapshot in all three UI modes; no detector or fixture edits.
import {readFile,writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
const root=new URL('../src/features/threecandles/validation/pattern-detector/',import.meta.url);
const fixture=JSON.parse(await readFile(new URL('1123.json',root),'utf8'));
const candidate=fixture.patternStudy.candidates.find(c=>c.direction==='BUY'&&c.region.low===170115&&new Date(c.candle1.candle.bucketStartTimeMsc).toISOString().includes('T11:10'));
const template=await readFile(new URL('captureThreeCandlesStructure.mjs',import.meta.url),'utf8');
for(const mode of ['SELECTED','TRIGGERS','ALL']) {
  process.argv=['node','capture',fileURLToPath(new URL('1123.json',root)),fileURLToPath(new URL(`mode-${mode.toLowerCase()}.png`,root)),process.argv[2]?.startsWith('http')?process.argv[2]:'http://localhost:5173/three-candles'];
  let source=template.replace("?? '2026-10-20'","?? '2026-10-30'")
    .replace('width:1520,height:fixture.structure?.preparation?.experimental?1480:1100','width:1700,height:1500')
    .replace('i<100;i++','i<500;i++').replace('},10000);','},30000);')
    .replace(/  await waitFor\("document.body.textContent.includes\([^\n]+exibidos[^\n]+\n/,`  await waitFor("document.body.textContent.includes('Replay: PAUSED')");\n`);
  const begin=source.indexOf("  const screenshot=await send('Page.captureScreenshot'"),end=source.indexOf("  await send('Browser.close')",begin);
  source=source.slice(0,begin)+`
  await evaluate("document.querySelectorAll('[data-testid=pattern-display-controls] [role=combobox]')[1].dispatchEvent(new MouseEvent('mousedown',{bubbles:true,button:0}))");
  await delay(200);
  await evaluate(${JSON.stringify(`Array.from(document.querySelectorAll('[role=option]')).find(o=>o.getAttribute('data-value')===${JSON.stringify(candidate.id)}).click()`)});
  await delay(200);
  await evaluate("document.querySelector('[data-testid=pattern-display-controls] [role=combobox]').dispatchEvent(new MouseEvent('mousedown',{bubbles:true,button:0}))");
  await delay(200);
  await evaluate(${JSON.stringify(`document.querySelector('[role=option][data-value=${mode}]').click()`)});
  ${mode==='SELECTED'?'':`await evaluate("Array.from(document.querySelectorAll('button')).find(b=>b.textContent.includes('Ajustar')).click()");`}
  await delay(500);
  const controls=await evaluate("document.querySelector('[data-testid=pattern-display-controls]').textContent");
  const rows=await evaluate("document.querySelector('[data-testid=pattern-candidates] tbody').children.length");
  const selected=await evaluate("document.querySelectorAll('[data-testid=pattern-candidates] [aria-selected=true]').length");
  const shot=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});
  await writeFile(outputFile,Buffer.from(shot.data,'base64'));
  await writeFile(outputFile.replace('.png','-browser.json'),JSON.stringify({mode:${JSON.stringify(mode)},candidateId:${JSON.stringify(candidate.id)},availableAtMsc:fixture.availableAtMsc,candidateRows:rows,selectedRows:selected,controls,errors,source:'Same Java detector snapshot 1123.json; actual UI controls only'},null,2));
  if(errors.length)throw new Error(JSON.stringify(errors));console.log(outputFile);
`+source.slice(end);
  await import('data:text/javascript;base64,'+Buffer.from(source+`\n// ${mode}`).toString('base64'));
}
