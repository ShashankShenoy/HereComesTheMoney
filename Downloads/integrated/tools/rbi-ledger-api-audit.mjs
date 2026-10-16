import assert from 'node:assert/strict';
import {writeFile} from 'node:fs/promises';
import {randomUUID} from 'node:crypto';
let raw='';for await(const chunk of process.stdin)raw+=chunk;
const credentials=JSON.parse(raw);raw='';
const base='http://127.0.0.1:5174/api/v1',results=[],sessions=[];
async function request(path,token,method='GET',body){
 const start=Date.now();
 const response=await fetch(base+path,{method,signal:AbortSignal.timeout(30000),headers:{Accept:'application/json','X-Correlation-ID':randomUUID(),...(token?{Authorization:'Bearer '+token}:{}),...(body?{'Content-Type':'application/json'}:{})},...(body?{body:JSON.stringify(body)}:{})});
 const text=await response.text();let value;try{value=JSON.parse(text);}catch{value=null;}
 return {status:response.status,data:value?.data??value,code:value?.code,elapsedMs:Date.now()-start,json:!!value};
}
async function check(role,path,expected,token,validate,method='GET',body){
 let response;
 try{response=await request(path,token,method,body);assert.equal(response.status,expected,'HTTP '+response.status+'; '+(response.code||''));assert.ok(response.json,'Expected JSON response');if(validate)validate(response.data);results.push({role,path,method,status:'PASS',httpStatus:response.status,elapsedMs:response.elapsedMs});}
 catch(error){results.push({role,path,method,status:'FAIL',reason:error.message,...(response?{httpStatus:response.status,code:response.code,responseKeys:Object.keys(response.data??{})}:{})});console.log('FAIL '+role+' '+method+' '+path+': '+error.message);}
}
try{
 for(const credential of credentials){
  const login=await request('/auth/login',null,'POST',{username:credential.username,password:credential.password,clientId:'RBI_LEDGER_AUDIT'});
  assert.equal(login.status,200,'Login failed for '+credential.role);const session=login.data;sessions.push(session);
  const token=session.accessToken,role=credential.role,customer=role==='BANK_CUSTOMER';
  const reads=['/treasury/reserve-accounts','/treasury/settlement-cycles','/treasury/liquidity-holds','/treasury/reconciliation-exceptions','/treasury/work-items','/treasury/reserve-openings','/payments/simulator','/banking/gl-accounts','/reconciliation/positions','/reconciliation/journals'];
  for(const path of reads)await check(role,path,customer?403:200,token);
  if(customer){
   await check(role,'/banking/journals/20',403,token);
   await check(role,'/journals/20/control',200,token,data=>assert.deepEqual(Object.keys(data),['journalId']));
   await check(role,'/banking/gl-accounts/1/ledger',403,token);
   await check(role,'/treasury/reserve-accounts/1/ledger',403,token);
   continue;
  }
  const reserves=(await request('/treasury/reserve-accounts',token)).data;
  for(const reserve of reserves){
   for(const suffix of ['','/position','/reconciliation','/ledger?limit=1&offset=0','/ledger?limit=1&offset=1','/ledger?limit=200&offset=999999'])
    await check(role,'/treasury/reserve-accounts/'+reserve.id+suffix,200,token);
   for(const query of ['limit=0','limit=201','offset=-1','limit=bad'])
    await check(role,'/treasury/reserve-accounts/'+reserve.id+'/ledger?'+query,query==='limit=bad'?400:422,token);
  }
  for(const path of ['/treasury/reserve-accounts/999999999','/treasury/reserve-accounts/999999999/position','/treasury/settlement-cycles/999999999','/treasury/liquidity-holds/999999999','/treasury/work-items/999999999','/treasury/reconciliation-exceptions/999999999','/treasury/settlement-evidence/999999999'])await check(role,path,404,token);
  for(const [path,idField] of [['/treasury/settlement-cycles','id'],['/treasury/liquidity-holds','id'],['/treasury/work-items','id'],['/treasury/reconciliation-exceptions','id']]){
   const rows=(await request(path,token)).data;
   for(const row of rows.slice(0,3)){
    await check(role,path+'/'+row[idField],200,token);
    if(path.endsWith('cycles'))await check(role,path+'/'+row[idField]+'/items',200,token);
   }
  }
  const gls=(await request('/banking/gl-accounts',token)).data;
  for(const gl of gls){
   const path='/banking/gl-accounts/'+gl.GL_ACCOUNT_ID+'/ledger?limit=1';
   await check(role,path,200,token,data=>{assert.ok(Array.isArray(data.postings));assert.equal(data.account.GL_ACCOUNT_ID,gl.GL_ACCOUNT_ID);});
  }
  for(const query of ['limit=0','limit=201','offset=-1','limit=bad'])await check(role,'/banking/gl-accounts/1/ledger?'+query,400,token);
  const reserveLedger=(await request('/treasury/reserve-accounts/'+reserves[0].id+'/ledger',token)).data;
  for(const entry of reserveLedger){
   await check(role,'/treasury/settlement-evidence/'+entry.evidenceId,200,token);
   await check(role,'/banking/journals/'+entry.glJournalId,200,token,data=>{assert.equal(data.control.IS_BALANCED,'Y');assert.ok(data.lines.length>=2);});
   await check(role,'/journals/'+entry.glJournalId+'/control',200,token,data=>assert.equal(data.IS_BALANCED,'Y'));
  }
  if(role==='BANK_ADMIN'&&!process.argv.includes('--read-only'))await check(role,'/treasury/work-items',201,token,null,'POST',{
   workType:'RECONCILIATION',subjectType:'RESERVE_ACCOUNT',reserveAccountId:reserves[0].id,
   sourceReference:'QA-RBI-'+randomUUID(),expectedAmount:1,observedAmount:1,
   detailText:'QA treasury work-item creation test; no funds movement or adjustment requested.',ownerId:session.user.userId
  });
 }
 await check('anonymous','/treasury/reserve-accounts',401);
 await check('anonymous','/banking/gl-accounts',401);
}finally{
 for(const session of sessions)await request('/auth/logout',session.accessToken,'POST',{}).catch(()=>{});
 await writeFile(process.argv.includes('--read-only')?'.runtime/rbi-fixes-api-results.json':'.runtime/rbi-ledger-api-results.json',JSON.stringify({testedAt:new Date().toISOString(),financialWrites:false,operationalWorkItemCreationAttempt:!process.argv.includes('--read-only'),results},null,2));
 console.log(JSON.stringify({checks:results.length,passed:results.filter(r=>r.status==='PASS').length,failed:results.filter(r=>r.status==='FAIL').length}));
}
if(results.some(r=>r.status==='FAIL'))process.exitCode=1;
