import {readFile,writeFile} from 'node:fs/promises';
import {runInNewContext} from 'node:vm';
import {createHash} from 'node:crypto';
import ts from 'typescript';
import assert from 'node:assert/strict';
import {groupM5ForDisplay,m5GroupLine} from '../src/features/threecandles/models/m5DisplayGroups.ts';
const root=new URL('../src/features/threecandles/validation/',import.meta.url),out=new URL('m5-latest/',root);
const snapshot=JSON.parse(await readFile(new URL('oct28/session.json',root),'utf8'));
const source=JSON.parse(await readFile(new URL('oct28/source.json',root),'utf8'));
const exports={};const code=ts.transpileModule(await readFile(new URL('m5DisplayGroups-before.txt',out),'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText;
runInNewContext(code,{exports,Set,Math,Number,Infinity});
const original=JSON.stringify(snapshot),levels=snapshot.structure.levels;
const previous=exports.groupM5ForDisplay(levels,30,snapshot.availableAtMsc);
const current=groupM5ForDisplay(levels,30,snapshot.availableAtMsc);
for(const p of levels.filter(p=>p.timeframeMinutes===5)) {
  const cutoff=p.confirmedAtMsc;
  assert.deepEqual(groupM5ForDisplay(levels,30,cutoff),groupM5ForDisplay(levels.filter(p=>p.confirmedAtMsc<=cutoff),30));
}
assert.equal(JSON.stringify(snapshot),original);
const hashes=JSON.parse(await readFile(new URL('protected-hashes.json',out),'utf8'));
for(const [path,expected] of Object.entries(hashes))assert.equal(createHash('sha256').update(await readFile(new URL('../../'+path.replaceAll('\\','/'),import.meta.url))).digest('hex'),expected);
const report={source,imageMatchConfirmed:false,sourceNote:'Frozen native MT5 history; user image date/contract still awaiting confirmation.',tolerance:30,pivots:levels.filter(l=>l.timeframeMinutes===5).length,
 beforeLines:previous.length,afterLines:current.length,eliminatedVersusPrevious:previous.length-current.length,
 hiddenOriginalPivots:current.reduce((n,g)=>n+g.members.length-1,0),
 before:previous.map(g=>exports.m5GroupLine(g)),after:current.map(m5GroupLine),replacements:current.flatMap(g=>g.replacements),
 reconstructionMatchesPrefixes:true,originalSnapshotUnchanged:true,protectedHashesUnchanged:true};
await writeFile(new URL('report.json',out),JSON.stringify(report,null,2));
console.log(JSON.stringify({pivots:report.pivots,before:report.beforeLines,after:report.afterLines,eliminated:report.eliminatedVersusPrevious,hidden:report.hiddenOriginalPivots}));
