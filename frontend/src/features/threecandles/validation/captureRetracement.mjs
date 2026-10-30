// Reuse the real-page causal Replay/manual-pick checks, preserving the previous captures.
import { readFile, mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
const out=fileURLToPath(new URL('retracement/',import.meta.url));await mkdir(out,{recursive:true});
let source=await readFile(new URL('captureFibonacci.mjs',import.meta.url),'utf8');
source=source.replace("new URL('fibonacci/',import.meta.url)",`new URL(${JSON.stringify(new URL('fibonacci/',import.meta.url).href)})`);
source=source.replace("new URL('../../../../scripts/captureThreeCandlesStructure.mjs',import.meta.url)",`new URL(${JSON.stringify(new URL('../../../../scripts/captureThreeCandlesStructure.mjs',import.meta.url).href)})`);
source=source.replace("directory+'initial.png'",JSON.stringify(out+'initial.png'));
source=source.replace("  await capture('expanded');", `
  await capture('expanded');
  {
    const bounds=await evaluate("(() => {const r=document.querySelector('[role=img]').getBoundingClientRect();return {x:r.left,y:r.top-30,width:r.width,height:r.height+30,scale:1};})()");
    const shot=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false,clip:bounds});
    const path=outputFile.replace('initial.png','complete.png');await writeFile(path,Buffer.from(shot.data,'base64'));
    saved.push({name:'complete',path,fibonacci:await text()});
  }
`);
source=source.replace('  const summary={saved,', `
  const plot=await evaluate("(() => {const r=document.querySelector('[role=img]').getBoundingClientRect();return {left:r.left,right:r.right,top:r.top,width:r.width,height:r.height};})()");
  const px=plot.left+plot.width*0.55,py=plot.top+plot.height*0.5;
  for(let i=0;i<3;i++){await send('Input.dispatchMouseEvent',{type:'mouseWheel',x:px,y:py,deltaX:0,deltaY:-100});await delay(120);}
  await capture('zoom-horizontal');
  await send('Input.dispatchMouseEvent',{type:'mousePressed',x:plot.right-25,y:py,button:'left',clickCount:1});
  await send('Input.dispatchMouseEvent',{type:'mouseMoved',x:plot.right-25,y:py+70,button:'left',buttons:1});
  await send('Input.dispatchMouseEvent',{type:'mouseReleased',x:plot.right-25,y:py+70,button:'left',clickCount:1});
  await capture('zoom-vertical');
  await send('Input.dispatchMouseEvent',{type:'mousePressed',x:px,y:py,button:'left',clickCount:1});
  await send('Input.dispatchMouseEvent',{type:'mouseMoved',x:px+100,y:py,button:'left',buttons:1});
  await send('Input.dispatchMouseEvent',{type:'mouseReleased',x:px+100,y:py,button:'left',clickCount:1});
  await capture('pan');
  await send('Emulation.setDeviceMetricsOverride',{width:1350,height:2000,deviceScaleFactor:1,mobile:false});
  await capture('resize');
  await clickButton('Ajustar gráfico');await capture('adjusted');
  if(await text()!==manualBefore)throw new Error('Chart interactions altered manual anchors');
  const summary={saved,zoomHorizontal:true,zoomVertical:true,pan:true,resize:true,anchorsUnchangedByViewport:true,
`);
// Keep failures readable instead of printing an entire data-URL stack.
try{await import('data:text/javascript;base64,'+Buffer.from(source).toString('base64'));}
catch(error){console.error(error.message);process.exitCode=1;}
