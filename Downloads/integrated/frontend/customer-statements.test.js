import test from 'node:test';
import assert from 'node:assert/strict';
const storage=new Map();
globalThis.sessionStorage={getItem:k=>storage.get(k)||null,setItem:(k,v)=>storage.set(k,v),removeItem:k=>storage.delete(k)};
const requireJet=(modules,ready)=>ready({}, {whenDocumentReady:()=>Promise.resolve()}, class {}, class {});requireJet.config=()=>{};
globalThis.window={require:requireJet};
globalThis.document={documentElement:{lang:''},addEventListener:()=>{},getElementById:()=>null};
const {saveSession}=await import('./api.js');
const {customerStatementsScreen,generateCustomerStatement}=await import('./customer-statements.js');
const {bindLoanDecisionFields}=await import('./banking.js');
function mock(respond){const calls=[];globalThis.fetch=async(url,options)=>{const call={url,...options,body:options.body?JSON.parse(options.body):undefined};calls.push(call);const value=await respond(call);return {ok:true,status:200,headers:new Headers(),json:async()=>value};};return calls;}
const period={accountId:21,periodStart:'2026-01-01',periodEnd:'2026-01-31',format:'CSV'};
const request={requestId:'request-21',statementId:'statement-21',status:'COMPLETED',statementType:'AD_HOC',periodStart:period.periodStart,periodEnd:period.periodEnd,format:'PDF'};
test('statements use the signed-in account directory and show authenticated download buttons',async()=>{
 saveSession({accessToken:'fixture',user:{userId:'statement-customer',userType:'CUSTOMER'}});
 const calls=mock(({url})=>url==='/api/v1/banking/accounts'?[{ACCOUNT_ID:21,ACCOUNT_NUMBER:'OWN00021',PRODUCT_NAME:'Savings',ACCOUNT_STATUS:'ACTIVE'}]:[request]);
 const html=await customerStatementsScreen();
 assert.match(html,/<select name="accountId" required>/);assert.match(html,/<option value="21" selected>OWN00021/);
 assert.match(html,/Generate and download/);assert.match(html,/data-statement-download="statement-21"/);assert.doesNotMatch(html,/Enter an account number/);
 assert.equal(calls[1].url,'/api/v1/reporting/statement-requests?accountId=21&limit=100');
});
test('generation creates, processes and downloads one request without manual IDs',async()=>{
 const calls=mock(({url,method})=>url.includes('/download')?{downloaded:'statement.csv'}:url.endsWith('/process')?request:method==='POST'?{...request,status:'AUTHORIZED',statementId:null}:[]);
 await generateCustomerStatement(period,'request-key');
 assert.equal(calls.length,4);assert.equal(calls[1].headers['Idempotency-Key'],'request-key');
 assert.deepEqual(calls[1].body,{...period,statementType:'AD_HOC',locale:'en-IN',channel:'WEB',correlationId:'request-key'});
 assert.equal(calls[2].url,'/api/v1/reporting/statement-requests/request-21/process');
 assert.equal(calls[3].url,'/api/v1/reporting/statements/statement-21/download?format=CSV');
 assert.equal(calls[3].headers.Authorization,'Bearer fixture');
});
test('retry reuses an issued statement and processes an interrupted authorized request',async()=>{
 let pending=false;
 const calls=mock(({url})=>url.includes('/download')?{}:url.endsWith('/process')?request:[{...request,...(pending?{status:'AUTHORIZED',statementId:null}:{})}]);
 await generateCustomerStatement(period);assert.equal(calls.length,2);assert.equal(calls.filter(c=>c.method==='POST').length,0);
 pending=true;await generateCustomerStatement(period);assert.equal(calls.length,5);assert.equal(calls.filter(c=>c.method==='POST').length,1);
});
test('invalid statement periods never submit a request',async()=>{
 const calls=mock(()=>[]);
 await assert.rejects(generateCustomerStatement({...period,periodEnd:'2025-12-31'}),/valid date range/);
 await assert.rejects(generateCustomerStatement({...period,periodEnd:'2099-01-01'}),/future/);
 await assert.rejects(generateCustomerStatement({...period,periodStart:'2020-01-01'}),/one year/);
 assert.equal(calls.length,0);
});
test('a reconciliation failure explains the bank action and never downloads a quarantined statement',async()=>{
 const calls=mock(({url,method})=>url.endsWith('/process')?{...request,status:'QUARANTINED',failureCode:'SOURCE_MISMATCH',statementId:null}:method==='POST'?{...request,status:'AUTHORIZED',statementId:null}:[]);
 await assert.rejects(generateCustomerStatement(period),/bank must reconcile.*request-21/);
 assert.equal(calls.filter(call=>call.url.includes('/download')).length,0);
});
test('loan rejection omits approval terms and approval requires every sanction field',()=>{
 let change;const choice={value:'APPROVE',addEventListener:(name,fn)=>change=fn};
 const fields=Object.fromEntries(['sanctionedAmount','sanctionedTenureMonths','annualRatePct'].map(name=>[name,{value:'10',dataset:{},closest:()=>null,dispatchEvent:()=>{}}]));
 bindLoanDecisionFields({querySelector:selector=>selector.includes('code')?choice:fields[selector.match(/name="([^"]+)/)[1]]});
 for(const input of Object.values(fields)){assert.equal(input.required,true);assert.equal(input.disabled,false);}
 choice.value='REJECT';change();
 for(const input of Object.values(fields)){assert.equal(input.required,false);assert.equal(input.disabled,true);assert.equal(input.value,'');}
 choice.value='APPROVE';change();for(const input of Object.values(fields))assert.equal(input.required,true);
});
