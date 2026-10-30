import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const base=new URL('fibonacci/renewal/',import.meta.url);
const pageUrl=process.argv[2]??'http://127.0.0.1:5173/three-candles';
const template=await readFile(new URL('../../../../scripts/captureThreeCandlesStructure.mjs',import.meta.url),'utf8');
for(const clock of ['0955','1000','1035']) {
  process.argv=['node','capture',fileURLToPath(new URL(`${clock}.json`,base)),fileURLToPath(new URL(`${clock}.png`,base)),pageUrl];
  let source=template.replace('width:1520,height:fixture.structure?.preparation?.experimental?1480:1100','width:1700,height:2100')
    .replace('i<100;i++','i<500;i++').replace('},10000);','},30000);');
  const begin=source.indexOf("  const screenshot=await send('Page.captureScreenshot'");
  const end=source.indexOf("  await send('Browser.close')",begin);assert.ok(begin>0&&end>begin);
  source=source.slice(0,begin)+`
  await waitFor("!!document.querySelector('[data-testid=fibonacci-current]')");
  const current=await evaluate("document.querySelector('[data-testid=fibonacci-current]').textContent");
  if(!current.includes('${clock==='0955'?'209.025':clock==='1000'?'209.055':'209.165'}'))throw new Error(current);
  const shot=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});
  await writeFile(outputFile,Buffer.from(shot.data,'base64'));
  await writeFile(outputFile.replace('.png','-browser.json'),JSON.stringify({current,errors,source:'Real frontend with causal Java Replay prefix; API intercepted'},null,2));
  if(errors.length)throw new Error(JSON.stringify(errors));
  console.log(current);
`+source.slice(end);
  try { await import('data:text/javascript;base64,'+Buffer.from(source+`\n// ${clock}`).toString('base64')); }
  catch(error) { console.error(error.message); process.exitCode=1; break; }
}
