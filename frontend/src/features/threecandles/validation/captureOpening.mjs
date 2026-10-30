import {readFile} from 'node:fs/promises';
const root=new URL('opening/',import.meta.url);
const template=await readFile(new URL('../../../../scripts/captureThreeCandlesStructure.mjs',import.meta.url),'utf8');
for(const kind of process.argv.slice(2).length?process.argv.slice(2):['before','after']) {
  process.argv=['node','capture',new URL(kind+'.json',root).pathname.replace(/^\/(.:)/,'$1'),new URL(kind+'.png',root).pathname.replace(/^\/(.:)/,'$1'),'http://127.0.0.1:5174/three-candles'];
  let source=template.replace("'2026-10-20'","'2026-10-28'").replace('width:1520,height:fixture.structure?.preparation?.experimental?1480:1100','width:1600,height:1200');
  source=source.replace('i<100;i++','i<500;i++').replace('},10000);','},60000);').replace('i<120;i++','i<600;i++').replace('errors=${errors}', 'errors=${errors}; body=${await evaluate("document.body.innerText")}');
  source=source.replace("  await send('Page.navigate',{url:pageUrl});","  void send('Page.navigate',{url:pageUrl}).catch(e=>console.error(e.message));");
  source=source.split('\n').map(line=>line.includes('niveis exibidos') ? `  await waitFor("!!document.querySelector('[data-testid=dow-debug]')");` : line).join('\n');
  const start=source.indexOf("  const screenshot=await send('Page.captureScreenshot'");
  const end=source.indexOf("  await send('Browser.close')",start);
  source=source.slice(0,start)+`
  for(const label of ['Fibonacci','H1 -','Mostrar ondas Dow micro']) {
    await evaluate("Array.from(document.querySelectorAll('label')).find(l=>l.textContent.includes("+JSON.stringify(label)+")).querySelector('input').click()");
    await delay(300);
  }
  await evaluate("document.querySelector('[role=img]').scrollIntoView({block:'center'})");
  await evaluate("Array.from(document.querySelectorAll('button')).find(b=>b.textContent.trim()==='Ajustar gráfico').click()");
  await delay(600);
  const body=await evaluate('document.body.textContent');
  const shot=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});
  await writeFile(outputFile,Buffer.from(shot.data,'base64'));
  await writeFile(outputFile.replace('.png','-browser.json'),JSON.stringify({errors,body,fixture:fixture.symbol,cursor:fixture.availableAtMsc,source:'Real React chart with exported Java replay response; HTTP intercepted'},null,2));
  if(errors.length)throw new Error(JSON.stringify(errors));
  console.log(outputFile);
`+source.slice(end);
  try { await import('data:text/javascript;base64,'+Buffer.from(source+'\n//'+kind).toString('base64')); } catch(e) { console.error(e.message);process.exitCode=1; }
}
