// Requires local Vite at 5173 and an isolated headless Chrome CDP at 9333.
import assert from 'node:assert/strict';
import { writeFileSync } from 'node:fs';

const page = await (await fetch('http://127.0.0.1:9333/json/new?http://localhost:5173/replay', { method: 'PUT' })).json();
const socket = new WebSocket(page.webSocketDebuggerUrl);
await new Promise(resolve => socket.addEventListener('open', resolve, { once: true }));
let nextId = 0;
const pending = new Map();
const exceptions = [];
socket.addEventListener('message', event => {
  const message = JSON.parse(event.data);
  if (message.method === 'Runtime.exceptionThrown') exceptions.push(message.params.exceptionDetails.text);
  if (message.id) { const task = pending.get(message.id); pending.delete(message.id); message.error ? task.reject(message.error) : task.resolve(message.result); }
});
const call = (method, params = {}) => new Promise((resolve, reject) => {
  const id = ++nextId; pending.set(id, { resolve, reject }); socket.send(JSON.stringify({ id, method, params }));
});
const evaluate = async expression => {
  const result = await call('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails));
  return result.result.value;
};
const wait = async predicate => {
  const deadline = Date.now()+20000;
  while (Date.now() < deadline) {
    if (await evaluate(predicate)) return;
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error('Browser wait timed out: '+predicate);
};
try {
  await call('Runtime.enable');
  await call('Emulation.setDeviceMetricsOverride', { width: 1280, height: 1600, deviceScaleFactor: 1, mobile: false });
  await wait(`document.body.innerText.includes('Replay de Absorção (experimental)')`);
  assert.equal(await evaluate(`document.querySelector('input[type="checkbox"]').checked`), false);
  await evaluate(`document.querySelector('input[type="checkbox"]').click()`);
  await wait(`document.body.innerText.includes('17 suspeitas') && document.body.innerText.includes('5 reações confirmadas')`);
  assert.equal(await evaluate(`document.querySelectorAll('[aria-label="Candlesticks WINZ26, absorção por captura UTC"]').length`), 1);
  assert.equal(await evaluate(`document.body.innerText.includes('Candles históricos WINZ26 compatíveis indisponíveis')`), true);
  await evaluate(`document.querySelector('[role="combobox"]').dispatchEvent(new MouseEvent('mousedown',{bubbles:true,button:0}))`);
  await wait(`!![...document.querySelectorAll('[role="option"]')].find(e=>e.textContent.includes('joint-probe-30s'))`);
  await evaluate(`[...document.querySelectorAll('[role="option"]')].find(e=>e.textContent.includes('joint-probe-30s')).click()`);
  await wait(`document.body.innerText.includes('Nenhuma transição exibida neste ponto')`);
  let found = false;
  for (let i=0; i<15; i++) {
    await evaluate(`[...document.querySelectorAll('button')].find(b=>b.textContent==='Próximo estado').click()`);
    await new Promise(resolve=>setTimeout(resolve,100));
    if (await evaluate(`document.body.innerText.includes('Estado conhecido até o cursor: POSSÍVEL_ABSORÇÃO')`)) { found=true; break; }
  }
  assert.equal(found, true, 'possible state must appear before its invalidation');
  await evaluate(`[...document.querySelectorAll('button')].find(b=>b.textContent==='Próximo estado').click()`);
  await wait(`document.body.innerText.includes('Estado conhecido até o cursor: INVALIDADA')`);
  // The selected possible episode stays in the known-marker list after failure.
  await evaluate(`[...document.querySelectorAll('[role="combobox"]')].at(-1).dispatchEvent(new MouseEvent('mousedown',{bubbles:true,button:0}))`);
  await wait(`!![...document.querySelectorAll('[role="option"]')].find(e=>e.textContent.includes('POSSÍVEL_ABSORÇÃO'))`);
  await evaluate(`document.querySelector('[role="listbox"]').dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}))`);
  await new Promise(resolve=>setTimeout(resolve,100));
  await evaluate(`document.querySelector('details').open=true`);
  // Real recovered M1 candles: switch to the session containing two reactions.
  await evaluate(`document.querySelector('[role="combobox"]').dispatchEvent(new MouseEvent('mousedown',{bubbles:true,button:0}))`);
  await wait(`!![...document.querySelectorAll('[role="option"]')].find(e=>e.textContent.includes('151129062'))`);
  await evaluate(`[...document.querySelectorAll('[role="option"]')].find(e=>e.textContent.includes('151129062')).click()`);
  await wait(`document.body.innerText.includes('16 suspeitas e 5 reações') && document.body.innerText.includes('WINZ26 · M1')`);
  assert.equal(await evaluate(`document.body.innerText.includes('Candles históricos WINZ26 compatíveis indisponíveis')`),false);
  let confirmed=false;
  for(let i=0;i<80;i++) {
    await evaluate(`[...document.querySelectorAll('button')].find(b=>b.textContent==='Próximo estado').click()`);
    await new Promise(resolve=>setTimeout(resolve,70));
    if(await evaluate(`document.body.innerText.includes('Estado conhecido até o cursor: REAÇÃO_CONFIRMADA')`)) {confirmed=true;break;}
  }
  assert.equal(confirmed,true,'confirmed state must be rendered on recovered M1 history');
  const screenshot = await call('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
  writeFileSync('.absorption-build/absorption-m1-recovered.png', Buffer.from(screenshot.data,'base64'));
  assert.deepEqual(exceptions, []);
  console.log('PASS: one existing chart; 16/17 possible + 5/5 confirmed placeable on real WINZ26 M1; Python limitation retained; causal navigation, real confirmed reaction and no browser exceptions.');
} finally {
  await call('Page.close').catch(()=>{});
  socket.close();
}
