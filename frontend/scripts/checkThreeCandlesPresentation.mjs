// Read-only structural check: layout migration must not change analytical expressions or feed effects.
import ts from 'typescript';
import {readFile,writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
const root=new URL('../src/features/threecandles/',import.meta.url);
const artifacts=new URL('validation/interface-reorg/',root);
const fingerprint=JSON.parse(await readFile(new URL('protected-fingerprints.json',artifacts),'utf8'));
for(const [path,expected] of Object.entries(fingerprint)) {
  const bytes=await readFile(new URL('../../'+path.replaceAll('\\','/'),import.meta.url));
  if(createHash('sha256').update(bytes).digest('hex')!==expected)throw new Error('Protected source changed: '+path);
}
const parse=async url=>ts.createSourceFile('source.tsx',await readFile(url,'utf8'),ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX);
const nodes=(sf,predicate)=>{const result=[];function visit(n){if(predicate(n))result.push(n);ts.forEachChild(n,visit);}visit(sf);return result;};
// Ignore formatter-only trivia, quote style and optional arrow parentheses.
const canonical=n=>{if(ts.isParenthesizedExpression(n))return canonical(n.expression);const children=[];ts.forEachChild(n,c=>{children.push(canonical(c));});return [n.kind,'text' in n?n.text:null,children];};
const print=(_sf,n)=>JSON.stringify(canonical(n));
const sameVariables=(oldSf,newSf,names)=>{
  for(const name of names){
    const lookup=sf=>nodes(sf,n=>ts.isVariableDeclaration(n)&&n.name.getText(sf)===name)[0];
    const old=lookup(oldSf),next=lookup(newSf);
    if(!old||!next||print(oldSf,old.initializer)!==print(newSf,next.initializer))throw new Error('Analytical expression changed: '+name);
  }
};
const oldWorkspace=await parse(new URL('ThreeCandlesWorkspace-before.txt',artifacts));
const workspace=await parse(new URL('components/ThreeCandlesWorkspace.tsx',root));
const analytical=['analysis','lane','automatic','manualKnown','manualCurrent','anchors','primary','comparison','overlay','selectAnchor','applyManual','audit','manualObservations','observations'];
sameVariables(oldWorkspace,workspace,analytical);
const oldPage=await parse(new URL('ThreeCandlesPage-before.txt',artifacts));
const page=await parse(new URL('pages/ThreeCandlesPage.tsx',root));
const feed=['loadReplay','command','m5Groups','m5Pivots','reusedM5Regions','contextSessions','gaps'];
sameVariables(oldPage,page,feed);
const effects=sf=>nodes(sf,n=>ts.isCallExpression(n)&&n.expression.getText(sf)==='useEffect').map(n=>print(sf,n));
if(JSON.stringify(effects(oldPage))!==JSON.stringify(effects(page)))throw new Error('Feed effect or dependency changed');
const result={protectedFiles:Object.keys(fingerprint).length,protectedHashesUnchanged:true,analyticalExpressionsUnchanged:analytical,feedExpressionsUnchanged:feed,feedEffectsUnchanged:true};
await writeFile(new URL('source-integrity.json',artifacts),JSON.stringify(result,null,2));
console.log(JSON.stringify(result));
