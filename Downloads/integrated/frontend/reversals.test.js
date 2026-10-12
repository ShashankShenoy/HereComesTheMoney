import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

const storage=new Map();
globalThis.sessionStorage={getItem:key=>storage.get(key)||null,setItem:(key,value)=>storage.set(key,value),removeItem:key=>storage.delete(key)};
const requireJet=(modules,ready)=>ready({}, {whenDocumentReady:()=>Promise.resolve()}, class {}, class {});
requireJet.config=()=>{};
globalThis.window={require:requireJet};
globalThis.document={documentElement:{lang:''},addEventListener:()=>{}};
const {saveSession}=await import('./api.js');
const {bankingScreen,bindBankingForms,configureBanking}=await import('./banking.js');
const {toast}=await import('./ui.js');
function checker(id){saveSession({accessToken:'test',user:{userId:id,userType:'EMPLOYEE',roles:['BANK_CHECKER'],permissions:['TXN_READ','TXN_REVERSE_APPROVE']}});}
const request={TXN_ID:27,REVERSAL_OF_TXN_ID:15,SOURCE_ACCOUNT_NUMBER:'MB002',TARGET_ACCOUNT_NUMBER:'MB001',AMOUNT:'12.50',CURRENCY_CODE:'INR',REASON_TEXT:'<Review reason>',MAKER_ID:'maker',REQUESTED_AT:'2026-10-12T10:00:00Z'};
test('checker opens pending reversals with contextual approve and reject buttons',async()=>{
 checker('queue-checker');
 globalThis.fetch=async url=>{assert.equal(url,'/api/v1/transactions/reversal-requests');return {ok:true,status:200,headers:new Headers(),json:async()=>[request]};};
 const html=await bankingScreen('transactions');
 assert.match(html,/id="transaction-section-reversals"[^>]*><section/);
 assert.match(html,/data-reversal-id="27" data-reversal-decision="APPROVED"/);
 assert.match(html,/data-reversal-id="27" data-reversal-decision="REJECTED"/);
 assert.match(html,/INR 12\.50/);
 assert.match(html,/&lt;Review reason&gt;/);
 assert.ok(html.indexOf('MB001')<html.indexOf('MB002'));
 assert.doesNotMatch(html,/name="id"/);
});
test('own requests stay visible with disabled decisions',async()=>{
 checker('maker');
 globalThis.fetch=async()=>({ok:true,status:200,headers:new Headers(),json:async()=>[request]});
 const html=await bankingScreen('transactions');
 assert.match(html,/data-reversal-decision="APPROVED" disabled/);
 assert.match(html,/data-reversal-decision="REJECTED" disabled/);
});
test('pending reversal queue distinguishes empty results from load failures',async()=>{
 checker('queue-empty');
 globalThis.fetch=async()=>({ok:true,status:200,headers:new Headers(),json:async()=>[]});
 assert.match(await bankingScreen('transactions'),/No pending reversal requests/);
 globalThis.fetch=async()=>({ok:false,status:503,json:async()=>({message:'Queue unavailable'})});
 const html=await bankingScreen('transactions');
 assert.match(html,/role="alert">Queue unavailable/);
 assert.match(html,/id="reversals-refresh"/);
 assert.doesNotMatch(html,/No pending reversal requests/);
});

const source=await readFile(new URL('./banking.js',import.meta.url),'utf8');
const binding=source.slice(source.indexOf('function bindReversalForms(){'),source.indexOf('function transactionTabs(){'));
function bind(api){
 const approve={dataset:{reversalId:'27',reversalDecision:'APPROVED'},disabled:false};
 const reject={dataset:{reversalId:'27',reversalDecision:'REJECTED'},disabled:false};
 const refresh={disabled:false};
 const error={textContent:''};
 const panel={querySelector:selector=>selector==='#reversals-refresh'?refresh:error,querySelectorAll:selector=>selector==='button'?[approve,reject,refresh]:[approve,reject]};
 let renders=0;
 vm.runInNewContext(binding+'\nbindReversalForms();',{document:{querySelector:()=>panel},api,renderApp:async()=>renders++,toast:()=>{},errorText:error=>error.message,encodeURIComponent});
 return {approve,reject,error,renders:()=>renders};
}
test('each decision submits its row ID and refreshes the pending queue',async()=>{
 for(const action of ['approve','reject']){
  const calls=[];
  const ui=bind(async(path,options)=>calls.push({path,options}));
  await ui[action].onclick();
  assert.equal(calls[0].path,'/transactions/27/reversal-decision');
  assert.equal(calls[0].options.method,'POST');
  assert.equal(calls[0].options.body.decision,action==='approve'?'APPROVED':'REJECTED');
  assert.equal(ui.renders(),1);
 }
});
test('failed reversal decisions remain visible and can be retried',async()=>{
 const ui=bind(async()=>{throw new Error('Already decided');});
 await ui.approve.onclick();
 assert.equal(ui.error.textContent,'Already decided');
 assert.equal(ui.renders(),0);
 assert.equal(ui.approve.disabled,false);
 assert.equal(ui.reject.disabled,false);
});

test('normal banking page setup binds both reversal buttons and removes decided requests',async()=>{
 const originalDocument=globalThis.document;
 try{
  for(const decision of ['APPROVED','REJECTED']){
   checker('page-setup-'+decision);
   const button={dataset:{reversalId:'27',reversalDecision:decision},disabled:false};
   const refresh={disabled:false};
   const error={textContent:''};
   const notification={textContent:'',className:''};
   const panel={querySelector:selector=>selector==='#reversals-refresh'?refresh:error,querySelectorAll:selector=>selector==='button'?[button,refresh]:[button]};
   globalThis.document={...originalDocument,querySelector:selector=>selector==='#pending-reversals'?panel:selector==='#toast'?notification:null,querySelectorAll:()=>[]};
   const calls=[];
   globalThis.fetch=async(url,options)=>{
    calls.push({url,options});
    return {ok:true,status:200,headers:new Headers(),json:async()=>options.method==='POST'?{transactionId:27,status:decision==='APPROVED'?'POSTED':'REJECTED'}:[]};
   };
   let rendered='';
   configureBanking(async()=>{rendered=await bankingScreen('transactions');});
   bindBankingForms();
   assert.equal(typeof button.onclick,'function','Page setup must attach the decision handler');
   await button.onclick();
   assert.equal(calls[0].url,'/api/v1/transactions/27/reversal-decision');
   assert.equal(JSON.parse(calls[0].options.body).decision,decision);
   assert.match(rendered,/No pending reversal requests/);
   assert.doesNotMatch(rendered,/data-reversal-id="27"/);
   assert.equal(error.textContent,'');
   clearTimeout(toast.timer);
  }
 }finally{globalThis.document=originalDocument;clearTimeout(toast.timer);}
});
