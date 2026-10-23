import test from 'node:test';
import assert from 'node:assert/strict';
const storage=new Map();globalThis.sessionStorage={getItem:k=>storage.get(k)||null,setItem:(k,v)=>storage.set(k,v),removeItem:k=>storage.delete(k)};
const requireJet=(modules,ready)=>ready({cleanNode(){}},{whenDocumentReady:()=>Promise.resolve()},class{},class{});requireJet.config=()=>{};
globalThis.window={require:requireJet};
globalThis.document={documentElement:{lang:''},addEventListener(){}};
const {saveSession}=await import('./api.js');
const {fundingRequest,demoFundingPanel,bindDemoFunding}=await import('./demo-funding.js');
const accounts=[{ACCOUNT_ID:1,ACCOUNT_NUMBER:'DEMO001',CURRENCY_CODE:'INR',ACCOUNT_STATUS:'ACTIVE'},{ACCOUNT_ID:2,ACCOUNT_NUMBER:'DEMO002',CURRENCY_CODE:'INR',ACCOUNT_STATUS:'PENDING_OPEN'},{ACCOUNT_ID:3,ACCOUNT_NUMBER:'CLOSED003',CURRENCY_CODE:'INR',ACCOUNT_STATUS:'CLOSED'}];
function setup(request){
 const owner={user:{userType:'EMPLOYEE',permissions:['TXN_POST']}};saveSession(owner);
 const nodes=Object.fromEntries(['[name="accountId"]','[name="amount"]','[type="submit"]','[data-funding-edit]','[data-funding-review]','.form-error','[data-funding-result]'].map(key=>[key,{value:'',textContent:'',disabled:false,hidden:true,dataset:{},closest:()=>null,dispatchEvent(){}}]));
 nodes['[name="accountId"]'].value='1';nodes['[name="amount"]'].value='100.50';
 const form={dataset:{accounts:JSON.stringify(accounts)},isConnected:true,querySelector:key=>nodes[key]};
 bindDemoFunding(form,{request});return {form,nodes,submit:()=>form.onsubmit({preventDefault(){}})};
}
test('accepts pending funding and rejects unavailable accounts and invalid money',()=>{
 assert.deepEqual(fundingRequest(accounts,'2','12.30'),{accountId:2,amount:'12.30'});
 for(const amount of ['0','-1','1.001','1e3','10000000.01'])assert.throws(()=>fundingRequest(accounts,'1',amount));
 assert.throws(()=>fundingRequest(accounts,'3','1'));assert.throws(()=>fundingRequest(accounts,'99','1'));
});
test('review makes no request; confirmation credits only once and displays the returned balance',async()=>{
 const calls=[];const {nodes,submit}=setup(async(path,options)=>{calls.push({path,...options});return {balance:{postedBalance:1100.50},transaction:{transactionId:42}};});
 await submit();assert.equal(calls.length,0);assert.match(nodes['[data-funding-review]'].textContent,/DEMO001/);assert.equal(nodes['[name="amount"]'].disabled,true);
 await submit();assert.equal(calls.length,1);assert.equal(calls[0].path,'/accounts/demo-funding');assert.equal(calls[0].body.amount,'100.50');
 assert.match(nodes['[data-funding-result]'].textContent,/42/);assert.equal(nodes['[name="amount"]'].disabled,false);assert.equal(nodes['[name="amount"]'].value,'');
});
test('uncertain retries keep the same request key and prevent concurrent submits',async()=>{
 let calls=0,release;const keys=[];
 const {nodes,submit}=setup(async(path,{body})=>{keys.push(body.requestKey);calls++;if(calls===1)throw new Error('Connection lost');return new Promise(resolve=>{release=()=>resolve({balance:{postedBalance:1},transaction:{transactionId:9}});});});
 await submit();await submit();assert.match(nodes['.form-error'].textContent,/Connection lost/);
 const pending=submit();await submit();assert.equal(calls,2);assert.equal(keys[0],keys[1]);release();await pending;
});
test('signing out before confirm prevents the write',async()=>{
 let calls=0;const {submit}=setup(async()=>{calls++;});await submit();saveSession(null);await submit();assert.equal(calls,0);
});
test('panel hides disabled demo funding and excludes closed accounts',async()=>{
 setup();assert.equal(await demoFundingPanel(async()=>({enabled:false})), '');
 const html=await demoFundingPanel(async path=>path.endsWith('/availability')?{enabled:true}:accounts);
 assert.match(html,/Add demo money/);assert.match(html,/DEMO001/);assert.match(html,/DEMO002/);assert.doesNotMatch(html,/CLOSED003/);
 saveSession({user:{userType:'CUSTOMER',permissions:['TXN_POST']}});assert.equal(await demoFundingPanel(()=>assert.fail('Customer must not load funding')), '');
});
