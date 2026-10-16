import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
let raw='';for await(const chunk of process.stdin)raw+=chunk;
const credentials=JSON.parse(raw);raw='';
const funding=JSON.parse(await readFile('.runtime/rbi-fixes-demo-opening-applied.json','utf8'));
const baseline=JSON.parse(await readFile('.runtime/rbi-ledger-oracle-before.json','utf8'));
const reserve=baseline.reservePositions[0],results=[];
async function call(path,token,method='GET',body){
 const response=await fetch('http://127.0.0.1:5174/api/v1'+path,{method,signal:AbortSignal.timeout(30000),headers:{Accept:'application/json',...(token?{Authorization:'Bearer '+token}:{}),...(body?{'Content-Type':'application/json'}:{})},...(body?{body:JSON.stringify(body)}:{})});
 const value=await response.json();return {status:response.status,data:value?.data??value};
}
async function check(role,name,action){try{await action();results.push({role,name,status:'PASS'});}catch(error){results.push({role,name,status:'FAIL',reason:error.message});console.log('FAIL '+role+' '+name+': '+error.message);}}
async function get(path,token,expected=200){const response=await call(path,token);assert.equal(response.status,expected,'HTTP '+response.status);return response.data;}
try{
 for(const credential of credentials){
  const login=await call('/auth/login',null,'POST',{username:credential.username,password:credential.password,clientId:'RBI_FIX_VERIFICATION'});assert.equal(login.status,200);
  const token=login.data.accessToken,role=credential.role;
  try{
   if(role==='BANK_CUSTOMER'){
    await check(role,'opening journal control stays masked',async()=>assert.deepEqual(Object.keys(await get('/journals/'+funding.journalId+'/control',token)),['journalId']));
    for(const path of ['/banking/journals/'+funding.journalId,'/banking/gl-accounts/'+funding.syntheticFundingGlId+'/ledger','/reconciliation/positions'])await check(role,path,async()=>{await get(path,token,403);});
    continue;
   }
   await check(role,'no account position exceptions',async()=>assert.deepEqual(await get('/reconciliation/positions',token),[]));
   await check(role,'no unbalanced journals',async()=>assert.deepEqual(await get('/reconciliation/journals',token),[]));
   await check(role,'opening journal control is balanced and visible',async()=>{const row=await get('/journals/'+funding.journalId+'/control',token);assert.equal(row.IS_BALANCED,'Y');assert.equal(Number(row.DEBIT_TOTAL),500000);assert.equal(Number(row.CREDIT_TOTAL),500000);});
   await check(role,'opening journal detail has both entries',async()=>{const row=await get('/banking/journals/'+funding.journalId,token);assert.equal(row.control.IS_BALANCED,'Y');assert.equal(row.lines.length,2);});
   await check(role,'synthetic funding asset has one opening entry',async()=>{const row=await get('/banking/gl-accounts/'+funding.syntheticFundingGlId+'/ledger',token);assert.equal(Number(row.balance),500000);assert.equal(row.postings.length,1);});
   await check(role,'RBI reserve balance is unchanged',async()=>{const row=await get('/treasury/reserve-accounts/'+reserve.RESERVE_ACCOUNT_ID+'/position',token);assert.equal(Number(row.confirmedBalance),Number(reserve.CONFIRMED_BALANCE));});
   if(role==='BANK_ADMIN'){
    await check(role,'protected customer position still requires holder proof',async()=>{await get('/accounts/6/position',token,403);});
    await check(role,'one opening-funding activity is visible to the bank administrator',async()=>{const rows=await get('/banking/transactions?accountId=6',token);const entries=rows.filter(row=>Number(row.transactionId)===Number(funding.transactionId));assert.equal(entries.length,1);assert.equal(Number(entries[0].amount),500000);});
   }
  }finally{await call('/auth/logout',token,'POST',{});}
 }
}finally{await writeFile('.runtime/rbi-fixes-final-api-results.json',JSON.stringify({testedAt:new Date().toISOString(),financialWrites:false,results},null,2));console.log(JSON.stringify({checks:results.length,passed:results.filter(r=>r.status==='PASS').length,failed:results.filter(r=>r.status==='FAIL').length}));}
if(results.some(r=>r.status==='FAIL'))process.exitCode=1;
