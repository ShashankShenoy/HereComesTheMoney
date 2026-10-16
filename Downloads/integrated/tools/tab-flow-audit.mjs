import {writeFile,readFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';

// Existing credentials arrive through stdin, never arguments, logs or reports.
let input='';for await(const chunk of process.stdin)input+=chunk;
const credentials=JSON.parse(input);input='';
const base='http://127.0.0.1:5174',results=[],sessions=new Map();
async function call(path,{session,method='GET',body,headers={}}={}){
 const response=await fetch(base+path,{method,signal:AbortSignal.timeout(30000),headers:{Accept:'application/json','X-Correlation-ID':randomUUID(),...(session?{Authorization:'Bearer '+session.accessToken}:{}),...(body!==undefined?{'Content-Type':'application/json','Idempotency-Key':randomUUID()}:{}),...headers},...(body!==undefined?{body:JSON.stringify(body)}:{})});
 const text=await response.text();let value;try{value=JSON.parse(text);}catch{value=null;}
 return {status:response.status,value,data:value?.data??value,bytes:text.length,contentType:response.headers.get('content-type')};
}
async function check(role,name,action){
 try{await action();results.push({role,name,status:'PASS'});console.log('PASS '+role+': '+name);}
 catch(error){results.push({role,name,status:'FAIL',reason:error.message});console.log('FAIL '+role+': '+name+': '+error.message);}
}
function ok(response,expected=200){assert.equal(response.status,expected,'HTTP '+response.status+'; '+(response.value?.code||'no error code'));return response.data;}
const list=data=>Array.isArray(data)?data:data?.items||data?.content||[];
try{
 await check('system','all six services ready',async()=>{assert.equal(ok(await call('/actuator/health')).status,'UP');});
 await check('system','merged API references resolve',async()=>{
  const spec=ok(await call('/v3/api-docs'));let references=0;
  function visit(value){if(!value||typeof value!=='object')return;if(value.$ref?.startsWith('#/')){let target=spec;for(const part of value.$ref.slice(2).split('/'))target=target?.[part.replaceAll('~1','/').replaceAll('~0','~')];assert.ok(target,'Unresolved schema '+value.$ref);references++;}for(const child of Object.values(value))visit(child);}
  visit(spec);assert.ok(references>100);
 });
 for(const credential of credentials.filter(c=>!process.argv.includes('--customer-statements-only')||c.role==='BANK_CUSTOMER')){
  await check(credential.role,'existing account login and canonical role',async()=>{
   const session=ok(await call('/api/v1/auth/login',{method:'POST',body:{username:credential.username,password:credential.password,clientId:'TAB_FLOW_AUDIT'}}));
   assert.ok(session.accessToken);assert.ok(session.user.roles.includes(credential.role));sessions.set(credential.username,session);
  });
  const session=sessions.get(credential.username);if(!session)continue;
  const role=credential.role,customer=session.user.userType==='CUSTOMER';
  const routes=['/auth/me','/iam/menus','/banking/accounts','/banking/branches','/beneficiaries','/payments/availability','/payments','/credit-cards/products','/credit-cards','/credit-cards/applications','/loans/applications','/banking/facilities','/currencies','/fx/rates','/fx/provider/status'];
  if(customer)routes.push('/auth/payment-pin','/banking/my-dashboard','/loans/my/applications','/term-deposits/my','/privacy/purposes/available');
  else routes.push('/banking/overview','/teller/tills','/teller/vaults','/teller/replenishments','/teller/cash-deliveries','/treasury/reserve-accounts','/catalog/masking-profiles','/catalog/narrations','/cif/customers','/products','/privacy/purposes','/iam/dashboard','/iam/users','/iam/roles','/iam/permissions','/iam/access-requests','/iam/audit');
  const responses=new Map();
  const selectedRoutes=process.argv.includes('--customer-statements-only')?['/banking/accounts']:routes;
  for(let offset=0;offset<selectedRoutes.length;offset+=4)
    await Promise.all(selectedRoutes.slice(offset,offset+4).map(path=>check(role,path,async()=>{responses.set(path,ok(await call('/api/v1'+path,{session})));})));
  const accounts=list(responses.get('/banking/accounts'));
  if(customer){
   const cifs=new Set(session.user.cifIds);assert.ok(accounts.every(account=>cifs.has(account.PRIMARY_CIF_ID)||account.ACCESS_ROLE==='JOINT_HOLDER'||account.ACCESS_ROLE==='AUTHORIZED_SIGNATORY'));
   if(accounts.length){
    const id=accounts[0].ACCOUNT_ID;
    for(const path of ['/accounts/'+id+'/position','/banking/transactions?accountId='+id,'/reporting/statement-requests?accountId='+id,'/reporting/statements?accountId='+id])
      await check(role,path.replaceAll(String(id),'{own account}'),async()=>{ok(await call('/api/v1'+path,{session}));});
    if(process.argv.includes('--verify-customer-statements'))await check(role,'own statement request, issue and three authenticated download formats',async()=>{
     const today=new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Kolkata',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date());
     const start=today.slice(0,8)+'01';
     const requests=list(ok(await call('/api/v1/reporting/statement-requests?accountId='+id,{session})));
     let request=requests.find(r=>r.statementType==='AD_HOC'&&r.periodStart===start&&r.periodEnd===today&&['AUTHORIZED','COMPLETED'].includes(r.status));
     if(!request)request=ok(await call('/api/v1/reporting/statement-requests',{session,method:'POST',body:{accountId:id,statementType:'AD_HOC',periodStart:start,periodEnd:today,format:'PDF',channel:'WEB',locale:'en-IN'}}),201);
     if(request.status!=='COMPLETED')request=ok(await call('/api/v1/reporting/statement-requests/'+request.requestId+'/process',{session,method:'POST'}));
     assert.equal(request.status,'COMPLETED',request.failureCode||'Statement did not complete');assert.ok(request.statementId);
     for(const format of ['PDF','CSV','HTML']){
      const response=await fetch(base+'/api/v1/reporting/statements/'+request.statementId+'/download?format='+format,{signal:AbortSignal.timeout(30000),headers:{Authorization:'Bearer '+session.accessToken}});
      assert.equal(response.status,200,'Download '+format+' HTTP '+response.status);
      const bytes=new Uint8Array(await response.arrayBuffer());assert.ok(bytes.length>100);
      const preview=new TextDecoder().decode(bytes.slice(0,200));
      if(format==='PDF')assert.ok(preview.startsWith('%PDF-'));
      if(format==='CSV')assert.ok(preview.includes(','));
      if(format==='HTML')assert.ok(/html/i.test(preview));
     }
    });
   }
  }
  if(!customer){
   for(const row of list(responses.get('/loans/applications'))){
    const id=row.id||row.applicationId;
    for(const suffix of ['', '/assessments','/decisions','/offers'])await check(role,'loan application '+suffix,async()=>{ok(await call('/api/v1/loans/applications/'+id+suffix,{session}));});
   }
  }
 }
 if(process.argv.includes('--configure-staff-statements')){
  const maker=[...sessions.values()].find(s=>s.user.roles.includes('BANK_ADMIN'));
  const checker=[...sessions.values()].find(s=>s.user.roles.includes('BANK_CHECKER'));
  assert.ok(maker&&checker&&maker.user.userId!==checker.user.userId,'Independent maker and checker required');
  const template=JSON.parse(await readFile('.runtime/tab-audit-oracle.json','utf8'));
  await check('configuration','STAFF WEB masking policy through independent approval',async()=>{
   const policies=ok(await call('/api/v1/catalog/masking-profiles',{session:maker}));
   if(policies.some(p=>p.AUDIENCE_CODE==='STAFF'&&p.CHANNEL_CODE==='WEB'&&p.STATUS==='APPROVED'))return;
   const rulesJson=template.statementPolicyTemplate[0]?.RULES_JSON;assert.ok(rulesJson);
   const code='DEMO_STAFF_WEB',version=1+Math.max(0,...policies.filter(p=>p.PROFILE_CODE===code).map(p=>p.VERSION_NO));
   const draft=ok(await call('/api/v1/catalog/masking-profiles',{session:maker,method:'POST',body:{code,version,channel:'WEB',audience:'STAFF',rulesJson}}));
   ok(await call('/api/v1/catalog/masking-profiles/'+draft.id+'/approve',{session:checker,method:'POST'}),204);
  });
  for(const row of template.statementNarrationTemplates){
   await check('configuration','STAFF '+row.LOCALE_CODE+' '+row.NARRATION_CODE+' narration through independent approval',async()=>{
    const narrations=ok(await call('/api/v1/catalog/narrations',{session:maker}));
    if(narrations.some(n=>n.AUDIENCE_CODE==='STAFF'&&n.LOCALE_CODE===row.LOCALE_CODE&&n.NARRATION_CODE===row.NARRATION_CODE&&n.APPROVAL_STATUS==='APPROVED'))return;
    const version=1+Math.max(0,...narrations.filter(n=>n.AUDIENCE_CODE==='STAFF'&&n.LOCALE_CODE===row.LOCALE_CODE&&n.NARRATION_CODE===row.NARRATION_CODE).map(n=>n.VERSION_NO));
    const draft=ok(await call('/api/v1/catalog/narrations',{session:maker,method:'POST',body:{code:row.NARRATION_CODE,locale:row.LOCALE_CODE,audience:'STAFF',version,text:row.RENDERED_TEXT,effectiveFrom:new Date(Date.now()-86400000).toISOString()}}));
    ok(await call('/api/v1/catalog/narrations/'+draft.id+'/approve',{session:checker,method:'POST'}),204);
   });
  }
 }
}finally{
 for(const session of sessions.values())try{await call('/api/v1/auth/logout',{session,method:'POST'});}catch{}
 await writeFile('.runtime/tab-audit-api.json',JSON.stringify({generatedAt:new Date().toISOString(),results},null,2));
}
console.log('Checks: '+results.length+'; failures: '+results.filter(r=>r.status==='FAIL').length);
process.exitCode=results.some(r=>r.status==='FAIL')?1:0;
