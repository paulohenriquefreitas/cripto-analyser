// Input is a full, closed-session snapshot emitted by M5SessionReplayTest (real MT5 cache).
// Run with Node 24 --experimental-strip-types; no backend/MT5 connection is made here.
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import assert from 'node:assert/strict';
import ts from 'typescript';
import { runInNewContext } from 'node:vm';
import * as grouping from '../models/m5DisplayGroups.ts';

const input = process.argv[2];
if (!input) throw new Error('Pass the final Java Replay snapshot, e.g. panic-scanner/target/threecandles-m5-validation/1825.json');
const view = JSON.parse(await readFile(input, 'utf8'));
const out = resolve(import.meta.dirname, 'grouping');
await mkdir(out, { recursive: true });
assert.equal(view.symbol, 'WIN$N');
assert.equal(view.structure.preparation.sessionDate, '2026-10-16');
const all = view.structure.levels, raw = all.filter(l => l.timeframeMinutes === 5);
assert.equal(raw.length, 31);
const before = JSON.stringify(view);
const cutoffs = view.candles.filter(c => c.closed && new Date(c.candle.bucketStartTimeMsc).toISOString().startsWith('2026-10-16')).map(c => c.availableAtMsc);
assert.equal(cutoffs.length, 113);
const { outputText } = ts.transpileModule(await readFile(new URL('../models/candleChartController.ts', import.meta.url), 'utf8'),
  { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } });
const exports = {};
runInNewContext(outputText, { exports, require: () => grouping });
const reports = [];
const asOf = (l, time) => l.brokenAtMsc != null && l.brokenAtMsc > time ? { ...l, state: 'CONFIRMED', brokenAtMsc: null } : l;
const key = l => l.id;
for (const separateMovements of [false, true]) for (const tolerance of [0, 15, 30, 50]) {
  const controller = exports.createCandleChartController();
  let delivered = [], histories = 0;
  const sink = { setHistory() { histories++; }, updateLast() {}, setStructureLevels: l => { delivered = l; } };
  controller.setM5Grouping(tolerance, separateMovements); controller.setVisibility(true, true); controller.attachChart(sink);
  let previous = [];
  for (const cutoff of cutoffs) {
    const known = raw.filter(l => l.confirmedAtMsc <= cutoff).map(l => asOf(l, cutoff));
    const groups = grouping.groupM5ForDisplay(known, tolerance, cutoff, separateMovements);
    // Full-day future pivots/breaks have no influence on any earlier prefix.
    const fromFull = grouping.groupM5ForDisplay(raw, tolerance, cutoff, separateMovements);
    assert.deepEqual(groups.map(g => [g.id, g.price, g.members.map(key), g.movementCount]),
      fromFull.map(g => [g.id, g.price, g.members.map(key), g.movementCount]));
    assert.deepEqual(groups.flatMap(g => g.members.map(key)).sort(), known.map(key).sort());
    assert.equal(new Set(groups.map(g => g.id)).size, groups.length);
    for (const g of groups) assert.ok(g.high - g.low <= tolerance || tolerance === 0);
    for (const old of previous) {
      const current = groups.find(g => g.id === old.id);
      assert.equal(current.price, old.price);
      for (const m of old.members) assert.ok(current.members.some(l => l.id === m.id));
    }
    const prefix = { ...view, availableAtMsc: cutoff, candles: view.candles.filter(c => c.availableAtMsc <= cutoff),
      structure: { ...view.structure, levels: [...known, ...all.filter(l => l.timeframeMinutes === 60)] } };
    controller.apply(prefix);
    assert.deepEqual(Array.from(delivered.filter(l => l.timeframeMinutes === 5).map(key)), groups.map(g => g.members[0].id));
    assert.deepEqual(Array.from(delivered.filter(l => l.timeframeMinutes === 60)), all.filter(l => l.timeframeMinutes === 60));
    previous = groups;
  }
  // Reconnect/remount and replay reset reconstruct, retaining raw memory in the view.
  controller.attachChart(sink);
  const finalLines = delivered.map(key);
  controller.reset(); controller.apply(view);
  assert.deepEqual(delivered.map(key), finalLines);
  const historyCount = histories;
  controller.setVisibility(false, true); controller.setVisibility(true, true);
  assert.equal(histories, historyCount);
  const groups = grouping.groupM5ForDisplay(raw, tolerance, view.availableAtMsc, separateMovements);
  reports.push({ tolerance, separateMovements, pivots: raw.length, lines: groups.length,
    reductionPercent: Math.round((1 - groups.length / raw.length) * 1000) / 10,
    reusedRegions: groups.filter(g => g.movementCount > 1).length, groups });
}
assert.equal(JSON.stringify(view), before, 'original structural record must be unchanged');
const report = { symbol: view.symbol, date: '2026-10-16', availableAtMsc: view.availableAtMsc,
  closedBars: cutoffs.length, originalPivots: raw.length, h1Lines: all.filter(l => l.timeframeMinutes === 60).length,
  causalPrefixesPerConfiguration: cutoffs.length, configurationsChecked: reports.length,
  futureIndependence: true, reconstruction: true, duplicateIds: false, structuralMemoryUnchanged: true, reports };
await writeFile(resolve(out, 'report.json'), JSON.stringify(report, null, 2));
await writeFile(resolve(out, 'replay.json'), before);
const fmt = t => new Date(t).toISOString().slice(11, 16);
const md = ['# Agrupamento visual M5 — WIN$N, 16/10/2026', '',
  `Replay completo: ${raw.length} pivôs M5, ${cutoffs.length} candles fechados; ${report.h1Lines} linhas H1 preservadas.`, '',
  '| Tolerância | Linhas por região | Redução | Separando movimentos |', '|---|---:|---:|---:|'];
for (const r of reports.filter(r => !r.separateMovements)) md.push(`| ${r.tolerance || 'Sem agrupamento'} | ${r.lines} | ${r.reductionPercent}% | ${reports.find(s => s.separateMovements && s.tolerance === r.tolerance).lines} |`);
md.push('', '## Critério', '',
  'Somente visual: mesmo símbolo, sessão e tipo; H1 não participa. Ordem de confirmação, origem e ID. A largura total máximo–mínimo deve caber na tolerância (sem encadeamento). Se mais de um grupo servir, vence a menor distância ao representante; empate favorece o primeiro confirmado e depois origem/ID. O representante é o preço real do primeiro pivô confirmado e permanece fixo. Zero desativa o agrupamento.', '',
  'Regiões de preço podem ser retomadas por movimentos posteriores. Isso é adequado ao modo padrão como região visual, mas não equivale a um único movimento estrutural. A auditoria conta um novo movimento quando um membro do episódio anterior foi rompido por fechamento até a origem do novo extremo. A opção “Separar movimentos apos rompimento” impede essa associação. Não inferimos outros movimentos a partir de tempo ou distância arbitrários, e esta contagem não gera sinais operacionais.', '',
  'Rompimentos posteriores à origem do novo pivô não alteram sua associação anterior. IDs, preços, origens, confirmações e estados individuais continuam no registro original. O gráfico recebe somente o primeiro pivô de cada região; a API e a memória estrutural não são agrupadas.', '',
  '## Pivôs associados — padrão 30 pontos', '',
  '| Tipo | Linha | Faixa | Movimentos | Membros: preço / origem / confirmação |', '|---|---:|---|---:|---|');
for (const g of reports.find(r => r.tolerance === 30 && !r.separateMovements).groups)
  md.push(`| ${g.type} | ${g.price} | ${g.low}–${g.high} | ${g.movementCount} | ${g.members.map(m => `${m.price} / ${fmt(m.extremeTimeMsc)} / ${fmt(m.confirmedAtMsc)}`).join('; ')} |`);
md.push('', 'Todos os IDs, estados e horários de rompimento de cada membro, para todas as tolerâncias e ambas as políticas de movimento, constam em [report.json](report.json).', '',
  '## Validação e capturas', '',
  '113 prefixos × 8 configurações: independência de níveis/rompimentos futuros, cobertura integral dos pivôs, largura máxima, ausência de IDs duplicados, representantes e associações anteriores estáveis, igualdade do fluxo incremental com reconstrução, remount e reset. H1 e memória bruta permanecem iguais. Mudanças de tolerância/visibilidade não recriam os candles.', '',
  '[Antes, 31 linhas](before.png) · [15 pontos](15.png) · [30 pontos](30.png) · [50 pontos](50.png) · [30 pontos separando movimentos](30-separated.png).', '',
  'Capturas da mesma página real, mesmo cursor e viewport, em Edge headless com API interceptada entregando o snapshot do Replay Java sobre histórico local MT5. Não representam acompanhamento de pregão ao vivo. O controle é alterado sem reiniciar Replay. Cores e critérios H1 2+2/1+1 permanecem intactos.', '',
  '## Como repetir', '',
  'Na raiz do repositório, com JDK 21, Node 24 e o frontend em localhost:5173:', '',
  '```powershell',
  "mvn -pl panic-scanner -am test '-Dtest=M5SessionReplayTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dthreecandles.m5Cache=C:/Users/paulo/project/cripto-analyser/historical-data/WIN$N/three-candles-m5/2026-10-16.json'",
  'node --experimental-strip-types frontend/src/features/threecandles/validation/auditM5Grouping.mjs panic-scanner/target/threecandles-m5-validation/1825.json',
  'node frontend/src/features/threecandles/validation/captureM5Grouping.mjs',
  '```', '',
  'Nesta execução, os 123 testes de frontend e TypeScript passaram. O Replay Java passou novamente, incluindo a igualdade com os nove candidatos H1 anteriores. ESLint dos três arquivos TypeScript alterados: zero erros e um aviso preexistente no cleanup do polling.');
await writeFile(resolve(out, 'README.md'), md.join('\n') + '\n');
console.log(JSON.stringify({ output: out, originalPivots: raw.length, comparisons: reports.map(({ tolerance, separateMovements, lines, reductionPercent, reusedRegions }) => ({ tolerance, separateMovements, lines, reductionPercent, reusedRegions })) }));
