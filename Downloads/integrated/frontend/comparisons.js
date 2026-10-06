import {esc,table} from './ui.js';
export function mountComparison(element,result){
 const rows=Array.isArray(result)?result.flatMap((r,i)=>Object.entries(r).map(([k,v])=>[`${i+1} · ${k}`,v])):Object.entries(result);
 element.innerHTML=table(['Field','Value'],rows.map(([k,v])=>[esc(k),esc(typeof v==='object'?JSON.stringify(v):v)]));
 return ()=>{element.replaceChildren();};
}
