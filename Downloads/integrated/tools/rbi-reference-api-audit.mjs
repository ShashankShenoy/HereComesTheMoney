import assert from 'node:assert/strict';
import {writeFile} from 'node:fs/promises';
let raw='';for await(const part of process.stdin)raw+=part;
const credentials=JSON.parse(raw);raw='';
const results=[];
async function request(path,token,method='GET',body){
 const response=await fetch('http://127.0.0.1:5174/api/v1'+path,{method,signal:AbortSignal.timeout(30000),headers:{Accept:'application/json',...(token?{Authorization:'Bearer '+token}:{}),...(body?{'Content-Type':'application/json'}:{})},...(body?{body:JSON.stringify(body)}:{})});
 const json=await response.json();return {status:response.status,data:json?.data??json};
}
async function check(role,name,action){try{await action();results.push({role,name,status:'PASS'});}catch(error){results.push({role,name,status:'FAIL',reason:error.message});}}
async function get(path,token,expected=200){const response=await request(path,token);assert.equal(response.status,expected,path);return response.data;}
const paths=['/treasury/work-owners','/treasury/settlement-evidence','/banking/journals'];
try{
 for(const credential of credentials){
  const login=await request('/auth/login',null,'POST',{username:credential.username,password:credential.password,clientId:'RBI_REFERENCE_FIX_CHECK'});assert.equal(login.status,200);
  const token=login.data.accessToken,role=credential.role;
  try{
   if(role==='BANK_CUSTOMER'){for(const path of paths)await check(role,path+' denies customer',async()=>{await get(path,token,403);});continue;}
   await check(role,'treasury owners contain active staff only',async()=>{
    const owners=await get(paths[0],token);assert.ok(owners.length>0);
    assert.ok(owners.some(owner=>owner.username==='admin'));
    assert.ok(owners.some(owner=>owner.username==='checker1'));
    const directory=await get('/iam/users?size=100',token),users=directory.items??directory.content??directory;
    assert.ok(Array.isArray(users));
    for(const owner of owners){const user=users.find(user=>user.userId===owner.userId);assert.ok(user,'Owner absent from user directory');assert.equal(user.userType,'EMPLOYEE');assert.equal(user.status,'ACTIVE');}
    assert.equal(new Set(owners.map(owner=>owner.userId)).size,owners.length);
   });
   await check(role,'journal selector contains the opening journal and resolves balanced control',async()=>{
    const journals=await get(paths[2],token);assert.ok(journals.length>0);
    const opening=journals.find(journal=>Number(journal.JOURNAL_ID)===227);assert.ok(opening);assert.equal(opening.JOURNAL_TYPE,'OPENING_FUNDING');
    const control=await get('/journals/'+opening.JOURNAL_ID+'/control',token);assert.equal(control.IS_BALANCED,'Y');assert.equal(Number(control.DEBIT_TOTAL),500000);assert.equal(Number(control.CREDIT_TOTAL),500000);
   });
   await check(role,'every listed settlement evidence resolves to the matching record',async()=>{
    const proofs=await get(paths[1],token);assert.ok(proofs.length>0);
    for(const proof of proofs){const detail=await get(paths[1]+'/'+proof.id,token);assert.equal(detail.id,proof.id);assert.equal(detail.externalSettlementRef,proof.externalSettlementRef);assert.equal(detail.status,proof.status);assert.equal(Number(detail.amount),Number(proof.amount));}
   });
  }finally{await request('/auth/logout',token,'POST',{});}
 }
 for(const path of paths)await check('anonymous',path+' requires sign-in',async()=>{await get(path,null,401);});
 await check('contract','evidence list and detail operations have distinct names',async()=>{
  const response=await fetch('http://127.0.0.1:5174/v3/api-docs',{signal:AbortSignal.timeout(30000)});assert.equal(response.status,200);
  const contract=await response.json(),list=contract.paths['/api/v1/treasury/settlement-evidence'].get,detail=contract.paths['/api/v1/treasury/settlement-evidence/{id}'].get;
  assert.equal(list.summary,'List settlement evidence');assert.notEqual(list.summary,detail.summary);
  assert.equal(contract.paths['/api/v1/treasury/work-owners'].get.summary,'List eligible treasury work owners');
 });
}finally{
 await writeFile('.runtime/rbi-ui-fixes-api-results.json',JSON.stringify({checkedAt:new Date().toISOString(),financialWrites:false,results},null,2));
 console.log(JSON.stringify({checks:results.length,passed:results.filter(row=>row.status==='PASS').length,failed:results.filter(row=>row.status==='FAIL').length}));
 for(const failure of results.filter(row=>row.status==='FAIL'))console.log(JSON.stringify(failure));
}
if(results.some(row=>row.status==='FAIL'))process.exitCode=1;
