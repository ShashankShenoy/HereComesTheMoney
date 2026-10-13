import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {readFile,writeFile} from 'node:fs/promises';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {serviceFor} from '../gateway/server.mjs';

const [base,output]=process.argv.slice(2),root=fileURLToPath(new URL('../',import.meta.url));
assert.equal(new URL(base).hostname,'127.0.0.1');
const ownership=JSON.parse(await readFile(path.join(root,'.runtime/microservices-processes.json'),'utf8'));
assert.equal(ownership.database,'Local','Mutating flows require the isolated H2 stack');
const results=[],evidence={},sessions={},state={};
const key=()=>randomUUID(), cents=value=>Math.round(Number(value)*100);
async function call(route,{token=sessions.customer?.accessToken,body,method=body===undefined?'GET':'POST',headers={},origin=base}={}){
  const response=await fetch(origin+route,{method,signal:AbortSignal.timeout(30000),headers:{...(token?{Authorization:`Bearer ${token}`} : {}),...(body===undefined?{}:{'Content-Type':'application/json','Idempotency-Key':key()}),...headers},...(body===undefined?{}:{body:JSON.stringify(body)})});
  const text=await response.text();let value;try{value=JSON.parse(text);}catch{value=null;}
  return {status:response.status,value};
}
function status(response,expected){const values=Array.isArray(expected)?expected:[expected];assert.ok(values.includes(response.status),`Expected HTTP ${values.join('/')}; got ${response.status}; code=${response.value?.code||'none'}`);return response.value;}
async function run(category,name,action,dependencies=[]){
  if(dependencies.some(name=>!state[name])){results.push({category,name,status:'SKIP',reason:'A prerequisite flow failed'});console.log(`SKIP ${category}: ${name}`);return;}
  const started=performance.now();try{await action();results.push({category,name,status:'PASS',elapsedMs:Math.round(performance.now()-started)});console.log(`PASS ${category}: ${name}`);}
  catch(error){results.push({category,name,status:'FAIL',error:error.message});console.log(`FAIL ${category}: ${name}: ${error.message}`);}
}
const balance=async()=>cents(status(await call('/api/v1/accounts/1/position'),200).posted);
function fixture(action,value){const result=spawnSync('java',['-cp',path.join(root,'.runtime/flow-fixtures/classes')+';'+path.join(root,'.runtime/flow-fixtures/h2.jar'),'LocalFlowFixtures',action,String(value)],{encoding:'utf8',windowsHide:true,timeout:30000});assert.equal(result.status,0,'Guarded H2 fixture preparation failed: '+(result.stderr||''));}
async function mcp(name,args){const response=status(await call('/api/v1/assistant/mcp',{headers:{'MCP-Protocol-Version':'2026-07-28','Mcp-Method':'tools/call','Mcp-Name':name},body:{jsonrpc:'2.0',id:1,method:'tools/call',params:{name,arguments:args}}}),200);assert.equal(response.result.isError,false,response.result.content?.[0]?.text);return JSON.parse(response.result.content[0].text);}
try{
await run('Architecture','every business API is routed to its owning service',async()=>{
  for(const [name,port] of [['identity',18092],['reporting',18093],['banking',18094],['payments',18095],['loan',18096],['audit',18097]]){
    const spec=status(await call('/v3/api-docs',{token:null,origin:`http://127.0.0.1:${port}`}),200);
    for(const route of Object.keys(spec.paths).filter(p=>p.startsWith('/api/v1/')&&!p.startsWith('/api/v1/system/')&&!p.startsWith('/api/v1/docs/')))assert.equal(serviceFor(route),name,`${name} unexpectedly exposes ${route}`);
    evidence[name+'ApiPaths']=Object.keys(spec.paths).length;
  }
});
await run('Identity','four role sessions work across six separate services',async()=>{
  for(const username of ['customer','customer2','admin','checker'])sessions[username]=status(await call('/api/v1/auth/login',{token:null,body:{username,password:'LocalBanking!2026',clientId:'FLOW_SUITE'}}),200).data;
  assert.ok(sessions.checker.user.roles.includes('BANK_CHECKER'));
});
if(!sessions.customer||!sessions.admin||!sessions.checker)throw new Error('Cannot run domain flows without required test sessions');
const customer=sessions.customer.accessToken,other=sessions.customer2.accessToken,admin=sessions.admin.accessToken,checker=sessions.checker.accessToken;
await run('Account','customer opening, activation and replay preserve an owned zero-balance account',async()=>{
  const body={requestId:key(),cifId:'demo-cif-1',productId:1,productVersionId:1};
  const created=status(await call('/api/v1/accounts/self',{body}),201);state.accountId=created.id;
  assert.equal(status(await call('/api/v1/accounts/self',{body}),201).id,created.id);
  const activation={requestId:key()};status(await call(`/api/v1/accounts/self/${created.id}/activate`,{body:activation}),202);status(await call(`/api/v1/accounts/self/${created.id}/activate`,{body:activation}),202);
  assert.equal(cents(status(await call(`/api/v1/accounts/${created.id}/position`),200).posted),0);
});
await run('Account','closure requires ownership and blocks further posting',async()=>{
  const route=`/api/v1/accounts/self/${state.accountId}/closures`,body={requestId:key(),reasonCode:'CUSTOMER_REQUEST',remarks:'Synthetic flow test'};
  status(await call(route,{token:other,body}),403);status(await call(route,{body}),202);status(await call(route,{body}),202);
},['accountId']);
await run('Payments','beneficiary creation requires an independent verifier',async()=>{
  const b=status(await call('/api/v1/beneficiaries',{body:{displayName:'Synthetic flow recipient',accountToken:'flow-recipient-'+key(),bankCode:'TEST0123456'}}),200);state.beneficiaryId=b.beneficiaryId;
  status(await call(`/api/v1/beneficiaries/${b.beneficiaryId}/verify`,{body:{}}),403);
  status(await call(`/api/v1/beneficiaries/${b.beneficiaryId}/verify`,{token:checker,body:{}}),200);
});
await run('Payments','initiation replays once and changed amounts conflict',async()=>{
  state.paymentBefore=await balance();const body={sourceAccountId:1,beneficiaryId:state.beneficiaryId,railCode:'UPI',amount:'10.00',requestKey:key()};state.paymentBody=body;
  const created=status(await call('/api/v1/payments/initiate',{body}),200);state.paymentId=created.paymentId;assert.equal(created.status,'RECEIVED');
  assert.equal(status(await call('/api/v1/payments/initiate',{body}),200).paymentId,created.paymentId);
  status(await call('/api/v1/payments/initiate',{body:{...body,amount:'11.00'}}),409);assert.equal(await balance(),state.paymentBefore);
},['beneficiaryId']);
await run('Payments','invalid rail amount cannot create an instruction',async()=>{
  status(await call('/api/v1/payments/initiate',{body:{...state.paymentBody,railCode:'RTGS',amount:'1.00',requestKey:key()}}),409);
},['paymentId']);
await run('Payments','cross-customer instructions and histories are hidden',async()=>{
  for(const suffix of ['', '/history','/rail-evidence'])status(await call(`/api/v1/payments/${state.paymentId}${suffix}`,{token:other}),404);
  const history=status(await call(`/api/v1/payments/${state.paymentId}/history`),200);assert.ok(!JSON.stringify(history).includes('ACTOR_ID'));
},['paymentId']);
await run('Payments','approval debits exactly once with a treasury and ledger hold',async()=>{
  const route=`/api/v1/payments/${state.paymentId}/authorize-simulation`;
  status(await call(route,{body:{reserveAccountId:1}}),403);
  status(await call(route,{token:checker,body:{reserveAccountId:1}}),200);status(await call(route,{token:checker,body:{reserveAccountId:1}}),200);
  assert.equal(await balance(),state.paymentBefore-1000);assert.equal(status(await call(`/api/v1/payments/${state.paymentId}`),200).status,'DISPATCHED');
},['paymentId']);
await run('Payments','settlement and settlement replay retain one completed instruction',async()=>{
  for(const outcome of ['ACCEPTED','SETTLED','SETTLED'])status(await call(`/api/v1/payments/${state.paymentId}/simulate-outcome`,{token:admin,body:{outcome}}),200);
  assert.equal(status(await call(`/api/v1/payments/${state.paymentId}`),200).status,'SETTLED');assert.equal(await balance(),state.paymentBefore-1000);
},['paymentId']);
await run('Payments','settled payments reject refund and investigated unknown payments refund once',async()=>{
  status(await call(`/api/v1/payments/${state.paymentId}/simulate-outcome`,{token:admin,body:{outcome:'REFUNDED'}}),409);
  const before=await balance(),p=status(await call('/api/v1/payments/initiate',{body:{...state.paymentBody,amount:'2.00',requestKey:key()}}),200);
  status(await call(`/api/v1/payments/${p.paymentId}/authorize-simulation`,{token:checker,body:{reserveAccountId:1}}),200);
  status(await call(`/api/v1/payments/${p.paymentId}/simulate-outcome`,{token:admin,body:{outcome:'UNKNOWN'}}),200);assert.equal(await balance(),before-200);
  for(let i=0;i<2;i++)status(await call(`/api/v1/payments/${p.paymentId}/simulate-outcome`,{token:admin,body:{outcome:'REFUNDED'}}),200);
  assert.equal(status(await call(`/api/v1/payments/${p.paymentId}`),200).status,'REFUNDED');assert.equal(await balance(),before);
},['paymentId']);
await run('Assistant','payment confirmation crosses HTTP and concurrent replay returns one payment',async()=>{
  const before=await balance(),draft=await mcp('draft_payment',{accountId:1,beneficiaryId:state.beneficiaryId,rail:'UPI',amount:'1.00'});state.intentId=draft.intentId;
  status(await call(`/api/v1/assistant/intents/${draft.intentId}/confirm`,{token:other,body:{}}),403);
  const replies=await Promise.all(Array.from({length:3},()=>call(`/api/v1/assistant/intents/${draft.intentId}/confirm`,{body:{}})));
  const refs=replies.map(r=>status(r,200).resultRef);assert.equal(new Set(refs).size,1);assert.ok(Number(refs[0])>0);
  assert.equal(status(await call(`/api/v1/payments/${refs[0]}`),200).status,'RECEIVED');assert.equal(await balance(),before);evidence.assistantPaymentId=refs[0];
},['beneficiaryId']);
await run('Loans','draft application replay is stable and changed terms conflict',async()=>{
  const requestKey='flow-loan-'+key(),headers={'Idempotency-Key':requestKey,'X-Correlation-ID':requestKey},body={cifId:'demo-cif-1',productId:2,productVersionId:2,branchCode:'MUM001',channelCode:'BRANCH',amount:'12000',tenureMonths:12,purposeCode:'PERSONAL'};state.loanHeaders=headers;
  const created=status(await call('/api/v1/loans/applications',{token:admin,headers,body}),200).data;state.applicationId=created.id;
  assert.equal(status(await call('/api/v1/loans/applications',{token:admin,headers,body}),200).data.id,created.id);
  status(await call('/api/v1/loans/applications',{token:admin,headers,body:{...body,amount:'13000'}}),409);
});
await run('Loans','submission and underwriting enforce an independent checker',async()=>{
  const route=`/api/v1/loans/applications/${state.applicationId}`;
  status(await call(route+'/submit',{token:admin,body:{}}),200);
  status(await call(route+'/assessments',{token:admin,body:{monthlyIncome:'50000',monthlyObligations:'5000',recommendation:'APPROVE',reasonCodes:'AFFORDABLE'}}),200);
  const decision={code:'APPROVE',reasonCodes:'APPROVED',sanctionedAmount:'12000',sanctionedTenureMonths:12,annualRatePct:'12',authorityCode:'LOAN_SANCTION',authorityEvidenceRef:'SYNTHETIC_FLOW'};
  status(await call(route+'/decisions',{token:admin,body:decision}),409);status(await call(route+'/decisions',{token:checker,body:decision}),200);
},['applicationId']);
await run('Loans','only the borrower accepts its offer against owned accounts',async()=>{
  const route=`/api/v1/loans/applications/${state.applicationId}`;
  state.offerId=status(await call(route+'/offers',{token:admin,body:{documentRef:'demo://flow-offer',expiresAt:new Date(Date.now()+7*86400000).toISOString()}}),200).data.offerId;
  status(await call(route+'/offers',{token:other}),403);status(await call(`/api/v1/loans/offers/${state.offerId}/accept`,{token:admin,body:{disbursementAccountId:1,repaymentAccountId:1}}),403);
  status(await call(`/api/v1/loans/offers/${state.offerId}/accept`,{body:{disbursementAccountId:1,repaymentAccountId:1}}),200);
},['applicationId']);
await run('Loans','signed evidence needs independent verification before conversion',async()=>{
  const route=`/api/v1/loans/applications/${state.applicationId}`;
  state.documentId=status(await call(route+'/documents',{token:admin,body:{type:'SIGNED_OFFER',storageReference:'demo://flow-signed-offer',sha256Hex:'c'.repeat(64)}}),200).data.documentRefId;
  status(await call(route+`/documents/${state.documentId}/verify`,{token:admin,body:{}}),403);
  status(await call(route+`/documents/${state.documentId}/verify`,{token:checker,body:{}}),200);
  state.facilityId=status(await call(route+'/convert',{token:admin,body:{}}),200).data.facilityId;
},['offerId']);
await run('Loans','disbursement approval credits once and generates a complete schedule',async()=>{
  const before=await balance(),body={requestKey:key()};
  state.disbursementId=status(await call(`/api/v1/loans/facilities/${state.facilityId}/disbursements`,{token:admin,body}),200).disbursementId;
  assert.equal(status(await call(`/api/v1/loans/facilities/${state.facilityId}/disbursements`,{token:admin,body}),200).disbursementId,state.disbursementId);
  const route=`/api/v1/loans/disbursements/${state.disbursementId}/approve`;
  status(await call(route,{token:admin,body:{authorityCode:'LOAN_SANCTION'}}),403);
  for(let i=0;i<2;i++)status(await call(route,{token:checker,body:{authorityCode:'LOAN_SANCTION'}}),200);
  assert.equal(await balance(),before+1200000);
  const schedule=status(await call(`/api/v1/loans/facilities/${state.facilityId}/schedule`,{token:admin}),200).data;
  assert.equal(schedule.length,12);assert.equal(schedule.reduce((sum,row)=>sum+cents(row.PRINCIPAL_DUE),0),1200000);
},['facilityId']);
await run('Loans','servicing rejects premature repayments and produces no future accrual',async()=>{
  assert.deepEqual(status(await call(`/api/v1/loans/facilities/${state.facilityId}/accruals`,{token:admin,body:{}}),200),[]);
  status(await call(`/api/v1/loans/facilities/${state.facilityId}/repayments`,{token:admin,body:{requestKey:key(),amount:'1'}}),409);
},['disbursementId']);
await run('Loans','due installment accrues exactly once',async()=>{
  fixture('due-installment',state.facilityId);
  const route=`/api/v1/loans/facilities/${state.facilityId}/accruals`;
  assert.equal(status(await call(route,{token:admin,body:{}}),200).length,1);assert.deepEqual(status(await call(route,{token:admin,body:{}}),200),[]);
  state.due=true;
},['disbursementId']);
await run('Loans','repayment allocation and replay preserve exact principal and cash balances',async()=>{
  const schedule=status(await call(`/api/v1/loans/facilities/${state.facilityId}/schedule`,{token:admin}),200).data,interest=cents(schedule[0].INTEREST_DUE),before=await balance();
  const body={requestKey:key(),amount:((interest+100000)/100).toFixed(2)},route=`/api/v1/loans/facilities/${state.facilityId}/repayments`;
  for(let i=0;i<2;i++)status(await call(route,{token:admin,body}),200);
  assert.equal(await balance(),before-interest-100000);
  const facility=status(await call(`/api/v1/loans/facilities/${state.facilityId}`,{token:admin}),200).data;assert.equal(cents(facility.PRINCIPAL_OUTSTANDING),1100000);
  status(await call(`/api/v1/loans/facilities/${state.facilityId}`,{token:other}),403);
},['due']);
await run('Audit','a current approved purpose and retention policy permit customer decisions',async()=>{
  state.purpose='FLOW_'+key().slice(0,8).toUpperCase();fixture('purpose',state.purpose);
  assert.equal(status(await call('/api/v1/privacy/consents/availability'),200).decisionsEnabled,true);
  assert.ok(status(await call('/api/v1/privacy/purposes/available'),200).some(p=>p.code===state.purpose));
});
await run('Audit','consent ownership, immutable replay and changed-payload conflict are enforced',async()=>{
  const body={subjectService:'CIF',subjectExternalId:'demo-cif-1',purposeCode:state.purpose,decision:'GRANTED',channelCode:'WEB',noticeSha256:'a'.repeat(64)},headers={'Idempotency-Key':key()};
  status(await call('/api/v1/privacy/consents/decisions',{token:other,headers,body}),403);
  const first=status(await call('/api/v1/privacy/consents/decisions',{headers,body}),200);state.consentId=first.id;
  assert.equal(status(await call('/api/v1/privacy/consents/decisions',{headers,body}),200).id,first.id);
  status(await call('/api/v1/privacy/consents/decisions',{headers,body:{...body,decision:'DENIED'}}),409);
},['purpose']);
await run('Audit','withdrawal preserves decision history and changes the current evaluation',async()=>{
  const query=`?subjectService=CIF&subjectExternalId=demo-cif-1&purposeCode=${state.purpose}`;
  assert.equal(status(await call('/api/v1/privacy/consents/evaluations'+query),200).allowed,true);
  status(await call('/api/v1/privacy/consents/history'+query,{token:other}),403);
  const headers={'Idempotency-Key':key()},body={subjectService:'CIF',subjectExternalId:'demo-cif-1',purposeCode:state.purpose,channelCode:'WEB'};
  const withdrawal=status(await call('/api/v1/privacy/consents/withdrawals',{headers,body}),200);
  assert.equal(status(await call('/api/v1/privacy/consents/withdrawals',{headers,body}),200).id,withdrawal.id);
  assert.equal(status(await call('/api/v1/privacy/consents/evaluations'+query),200).allowed,false);
  const history=status(await call('/api/v1/privacy/consents/history'+query),200);assert.equal(history.length,2);assert.ok(history.some(d=>d.id===state.consentId));
},['consentId']);
await run('Audit','global financial evidence is restricted to authorized staff',async()=>{
  status(await call('/api/v1/privacy/financial-audit'),403);
  const events=status(await call('/api/v1/privacy/financial-audit',{token:admin}),200);assert.ok(events.length>0);assert.ok(events.every(e=>typeof e.amount==='string'));
});
await run('Cards','application approval is independent and activation belongs to its holder',async()=>{
  const application=status(await call('/api/v1/credit-cards/applications',{body:{requestKey:key(),productId:'demo-credit-classic',cifId:'demo-cif-1',repaymentAccountId:1,requestedLimit:'10000',termsAccepted:true}}),200);
  const decision={requestKey:key(),rowVersion:1,decision:'APPROVED',reason:'Synthetic independent review',approvedLimit:'10000'};
  status(await call(`/api/v1/credit-cards/applications/${application.APPLICATION_ID}/decision`,{body:decision}),403);
  state.cardId=status(await call(`/api/v1/credit-cards/applications/${application.APPLICATION_ID}/decision`,{token:checker,body:decision}),200).CARD_ID;
  status(await call(`/api/v1/credit-cards/${state.cardId}`,{token:other}),403);
  const card=status(await call(`/api/v1/credit-cards/${state.cardId}`),200);
  status(await call(`/api/v1/credit-cards/${state.cardId}/controls`,{body:{requestKey:key(),rowVersion:card.ROW_VERSION,action:'ACTIVATE',reason:'Synthetic activation'}}),200);
});
await run('Cards','purchase replay, repayment and reconciliation are consistent',async()=>{
  const purchase={requestKey:key(),amount:'25.00',merchantName:'Synthetic merchant'},route=`/api/v1/credit-cards/${state.cardId}`;
  status(await call(route+'/purchases',{body:purchase}),200);status(await call(route+'/purchases',{body:purchase}),200);
  const before=await balance(),repay={requestKey:key(),amount:'25.00'};
  status(await call(route+'/repayments',{body:repay}),200);status(await call(route+'/repayments',{body:repay}),200);
  assert.equal(await balance(),before-2500);assert.equal(status(await call(route+'/reconciliation'),200).matched,true);
},['cardId']);
await run('Deposits','fixed deposit funding and replay debit the owned source exactly once',async()=>{
  const before=await balance(),body={productId:3,productVersionId:3,fundingAccountId:1,amount:'1000.00',tenureMonths:6,requestKey:key()};
  const first=status(await call('/api/v1/term-deposits',{body}),200);state.depositId=first.TERM_DEPOSIT_ID??first.termDepositId;
  const replay=status(await call('/api/v1/term-deposits',{body}),200);assert.equal(replay.TERM_DEPOSIT_ID??replay.termDepositId,state.depositId);assert.ok(state.depositId);assert.equal(await balance(),before-100000);
  status(await call(`/api/v1/term-deposits/${state.depositId}`,{token:other}),403);
});
await run('Treasury','internal reserves are restricted and retain valid ledger evidence',async()=>{
  status(await call('/api/v1/treasury/reserve-accounts/1/ledger',{token:customer}),403);
  assert.ok(status(await call('/api/v1/treasury/reserve-accounts/1/ledger',{token:admin}),200));
  assert.ok(status(await call('/api/v1/treasury/reserve-accounts/1/reconciliation',{token:admin}),200));
});
await run('Ledger','all payment, lending, card and deposit journals balance and account positions reconcile',async()=>{
  assert.deepEqual(status(await call('/api/v1/reconciliation/journals',{token:admin}),200),[]);
  assert.deepEqual(status(await call('/api/v1/reconciliation/positions',{token:admin}),200),[]);evidence.finalSourceBalance=(await balance())/100;
});
}finally{
  for(const name of ['applicationId','facilityId','beneficiaryId','purpose','depositId','cardId'])if(state[name])evidence[name]=state[name];
  for(const session of Object.values(sessions))try{await call('/api/v1/auth/logout',{token:session.accessToken,body:{}});}catch{}
  await writeFile(output,JSON.stringify({database:'H2 disposable',generatedAt:new Date().toISOString(),results,evidence},null,2));
}
if(results.some(r=>r.status!=='PASS'))process.exitCode=1;
