import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {pathToFileURL} from 'node:url';

// No customer data, statement issuance, permissions or financial records are changed.
export const staffStatementPlan=Object.freeze({
 purpose:{code:'STAFF_ACCOUNT_STATEMENT',name:'Account-holder requested staff statement assistance',ownerService:'M10_STATEMENTS',legalBasisCode:'CONTRACT'},
 masking:{code:'STAFF_WEB_STATEMENTS',channel:'WEB',audience:'STAFF',rulesJson:JSON.stringify({showBalances:true,showSourceReferences:false})},
 narrations:['NORMAL','REVERSAL','RETURN','ADJUSTMENT'].map(code=>({code,locale:'en-IN',audience:'STAFF',text:code==='NORMAL'?'Account transaction':code==='REVERSAL'?'Transaction reversal':code==='RETURN'?'Returned transaction':'Account adjustment'}))
});
const rows=value=>Array.isArray(value)?value:value?.items??[];
const current=(row,now)=>Date.parse(row.effectiveFrom)<=now&&(!row.effectiveTo||Date.parse(row.effectiveTo)>now);

export async function configureStaffStatements(call,maker,checker,now=new Date()){
 assert.ok(maker?.user?.roles.includes('BANK_ADMIN'),'A bank administrator must create the drafts');
 assert.ok(checker?.user?.roles.includes('BANK_CHECKER'),'A bank checker must approve the drafts');
 assert.notEqual(maker.user.userId,checker.user.userId,'Independent maker and checker are required');
 const effectiveFrom=new Date(now.getTime()-60000).toISOString(),changes=[];
 const purposes=rows(await call('/privacy/purposes?size=200',maker));
 let purpose=purposes.find(p=>p.ownerService==='M10_STATEMENTS'&&p.status==='ACTIVE'&&current(p,now.getTime()));
 if(!purpose){
  const definition=staffStatementPlan.purpose;
  purpose=await call('/privacy/purposes',maker,'POST',{...definition,version:1+Math.max(0,...purposes.filter(p=>p.code===definition.code).map(p=>p.version)),effectiveFrom});
  purpose=await call('/privacy/purposes/'+encodeURIComponent(purpose.id)+'/approval',checker,'POST',undefined,{'If-Match':String(purpose.versionNo)});
  assert.equal(purpose.status,'ACTIVE');changes.push({type:'PURPOSE',id:purpose.id});
 }
 const masks=rows(await call('/catalog/masking-profiles',maker));
 if(!masks.some(p=>p.CHANNEL_CODE==='WEB'&&p.AUDIENCE_CODE==='STAFF'&&p.STATUS==='APPROVED')){
  const definition=staffStatementPlan.masking;
  const draft=await call('/catalog/masking-profiles',maker,'POST',{...definition,version:1+Math.max(0,...masks.filter(p=>p.PROFILE_CODE===definition.code).map(p=>p.VERSION_NO))});
  await call('/catalog/masking-profiles/'+encodeURIComponent(draft.id)+'/approve',checker,'POST');changes.push({type:'MASKING',id:draft.id});
 }
 const narrations=rows(await call('/catalog/narrations',maker));
 for(const definition of staffStatementPlan.narrations){
  const matching=narrations.filter(n=>n.AUDIENCE_CODE==='STAFF'&&n.LOCALE_CODE===definition.locale&&n.NARRATION_CODE===definition.code);
  if(matching.some(n=>n.APPROVAL_STATUS==='APPROVED'&&current({effectiveFrom:n.EFFECTIVE_FROM,effectiveTo:n.EFFECTIVE_TO},now.getTime())))continue;
  const draft=await call('/catalog/narrations',maker,'POST',{...definition,version:1+Math.max(0,...matching.map(n=>n.VERSION_NO)),effectiveFrom});
  await call('/catalog/narrations/'+encodeURIComponent(draft.id)+'/approve',checker,'POST');changes.push({type:'NARRATION',code:definition.code,id:draft.id});
 }
 return {purposeCode:purpose.code,changes};
}

if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
 if(!process.argv.includes('--apply'))console.log(JSON.stringify({mode:'PLAN',independentApproval:true,statementCreation:false,financialWrites:false,...staffStatementPlan},null,2));
 else{
  let input='';for await(const part of process.stdin)input+=part;
  const credentials=JSON.parse(input);input='';const sessions=[];
  const base='http://127.0.0.1:8091/api/v1';
  async function call(path,session,method='GET',body,headers={}){
   const response=await fetch(base+path,{method,signal:AbortSignal.timeout(30000),headers:{Accept:'application/json',...(session?{Authorization:'Bearer '+session.accessToken}:{}),...(body===undefined?{}:{'Content-Type':'application/json','Idempotency-Key':randomUUID()}),...headers},...(body===undefined?{}:{body:JSON.stringify(body)})});
   const value=response.status===204?null:await response.json();assert.ok(response.ok,path+': HTTP '+response.status+' '+(value?.code??''));return value?.data??value;
  }
  try{
   for(const credential of credentials){const session=await call('/auth/login',null,'POST',{username:credential.username,password:credential.password,clientId:'STATEMENT_CONFIGURATION_REPAIR'});sessions.push(session);}
   const result=await configureStaffStatements(call,sessions.find(s=>s.user.roles.includes('BANK_ADMIN')),sessions.find(s=>s.user.roles.includes('BANK_CHECKER')));console.log(JSON.stringify(result,null,2));
  }finally{for(const s of sessions)await call('/auth/logout',s,'POST').catch(()=>{});}
 }
}
