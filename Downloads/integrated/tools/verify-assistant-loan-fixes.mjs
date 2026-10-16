import assert from 'node:assert/strict';
import {writeFile} from 'node:fs/promises';
let input='';for await(const part of process.stdin)input+=part;
const credentials=JSON.parse(input);input='';
const base='http://127.0.0.1:8091/api/v1',sessions=[],results=[];
async function call(path,session,method='GET',body,headers={}){
 const response=await fetch(base+path,{method,signal:AbortSignal.timeout(30000),headers:{Accept:'application/json',...(session?{Authorization:'Bearer '+session.accessToken}:{}),...(body?{'Content-Type':'application/json'}:{}),...headers},...(body?{body:JSON.stringify(body)}:{})});
 const data=response.status===204?null:await response.json();assert.equal(response.status,200,path+': HTTP '+response.status+' '+(data?.code??''));return data?.data??data;
}
const list=value=>Array.isArray(value)?value:value?.items??[];
async function check(role,name,action){try{const detail=await action();results.push({role,name,status:'PASS',...(detail?{detail}:{})});console.log('PASS '+role+' '+name);}catch(e){results.push({role,name,status:'FAIL',reason:e.message});console.log('FAIL '+role+' '+name+': '+e.message);}}
try{
 for(const credential of credentials)sessions.push(await call('/auth/login',null,'POST',{username:credential.username,password:credential.password,clientId:'FIX_READ_ONLY_VERIFICATION'}));
 for(const session of sessions){
  const role=session.user.username,checker=session.user.roles.includes('BANK_CHECKER')&&!session.user.roles.includes('BANK_ADMIN');
  await check(role,'assistant configuration and read-only tool discovery',async()=>{
   const status=await call('/assistant/status',session);assert.equal(status.configured,true);
   const rpc=await call('/assistant/mcp',session,'POST',{jsonrpc:'2.0',id:1,method:'tools/list',params:{}},{'MCP-Protocol-Version':'2026-07-28','Mcp-Method':'tools/list'});
   assert.ok(rpc.result.tools.length>0);
   if(checker)assert.ok(rpc.result.tools.every(t=>!t.name.startsWith('draft_')&&!t.name.startsWith('generate_')));
   return {configured:true,tools:rpc.result.tools.map(t=>t.name)};
  });
  const facilities=list(await call('/banking/facilities',session));
  for(const f of facilities.filter(f=>['ACTIVE','DELINQUENT'].includes(f.STATUS))){
   const id=f.FACILITY_ID;
   await check(role,'loan '+id+' current prepayment quote',async()=>{
    const quote=await call('/loans/facilities/'+id+'/prepayment-quote',session);assert.equal(quote.facilityId,id);assert.ok(Number(quote.foreclosureAmount)>=Number(quote.principalOutstanding));assert.equal(quote.partialPrepaymentAllowed,true);assert.equal(quote.foreclosureAllowed,true);return {facilityId:id,valueDate:quote.valueDate};
   });
  }
  if(!checker){
   await check(role,'effective staff statement purpose',async()=>{const purposes=list(await call('/privacy/purposes?status=ACTIVE&size=200',session)),now=Date.now();assert.ok(purposes.some(p=>p.ownerService==='M10_STATEMENTS'&&Date.parse(p.effectiveFrom)<=now&&(!p.effectiveTo||Date.parse(p.effectiveTo)>now)));});
   await check(role,'approved staff statement rendering policies',async()=>{
    const masks=list(await call('/catalog/masking-profiles',session)),narrations=list(await call('/catalog/narrations',session)),now=Date.now();
    assert.ok(masks.some(m=>m.CHANNEL_CODE==='WEB'&&m.AUDIENCE_CODE==='STAFF'&&m.STATUS==='APPROVED'));
    for(const code of ['NORMAL','REVERSAL','RETURN','ADJUSTMENT'])assert.ok(narrations.some(n=>n.NARRATION_CODE===code&&n.LOCALE_CODE==='en-IN'&&n.AUDIENCE_CODE==='STAFF'&&n.APPROVAL_STATUS==='APPROVED'&&Date.parse(n.EFFECTIVE_FROM)<=now&&(!n.EFFECTIVE_TO||Date.parse(n.EFFECTIVE_TO)>now)));
   });
  }
 }
}finally{
 for(const s of sessions)await call('/auth/logout',s,'POST').catch(()=>{});
 await writeFile('.runtime/assistant-fixes-live-readonly.json',JSON.stringify({checkedAt:new Date().toISOString(),providerCalls:false,statementIssuance:false,financialCommands:false,results},null,2));
 console.log(JSON.stringify({checks:results.length,passed:results.filter(r=>r.status==='PASS').length,failed:results.filter(r=>r.status==='FAIL').length}));
}
if(results.some(r=>r.status==='FAIL'))process.exitCode=1;
