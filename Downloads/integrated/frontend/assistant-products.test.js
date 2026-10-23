import test from 'node:test';
import assert from 'node:assert/strict';
const storage=new Map();globalThis.sessionStorage={getItem:k=>storage.get(k)||null,setItem:(k,v)=>storage.set(k,v),removeItem:k=>storage.delete(k)};
const requireJet=(modules,ready)=>ready({cleanNode(){}},{whenDocumentReady:()=>Promise.resolve()},class{},class{});requireJet.config=()=>{};
globalThis.window={require:requireJet};let nodes={};globalThis.document={documentElement:{lang:''},addEventListener(){},querySelector:s=>nodes[s]||null};
const {saveSession}=await import('./api.js');const {prepareProductWorkflow,validAmount,openProductWorkflow}=await import('./assistant-products.js');
const user={userId:'customer',sessionId:'session',userType:'CUSTOMER',cifIds:['cif']};
saveSession({accessToken:'fixture',user});
const account={ACCOUNT_ID:1,ACCOUNT_NUMBER:'PRIVATE-1234',ACCOUNT_STATUS:'ACTIVE',ACCOUNT_OPERATION_MODE:'SELF_OPERATED',CURRENCY_CODE:'INR',PRODUCT_TYPE:'SAVINGS',PRIMARY_CIF_ID:'cif'};
const card={CARD_ID:'card',DISPLAY_ENDING:'9876',PRODUCT_NAME:'Classic',STATUS:'ACTIVE',ROW_VERSION:2,AVAILABLE_CREDIT:10000,PAYOFF_AMOUNT:500,BILLING_REQUIRED:false,NEXT_STATEMENT_DATE:'2026-10-01'};
const product={PRODUCT_ID:'product',PRODUCT_NAME:'Classic',STATUS:'APPROVED',MINIMUM_LIMIT:1000,MAXIMUM_LIMIT:20000,ANNUAL_RATE_PCT:24};
const interest={INTEREST_TYPE:'FIXED',INTEREST_METHOD:'SIMPLE',COMPOUND_FREQUENCY:'NONE',PAYOUT_FREQUENCY:'MONTHLY',ROUNDING_MODE:'HALF_EVEN',DAY_COUNT_BASIS:'ACT/365',FIXED_RATE_PCT:12};
const loan={product:{PRODUCT_ID:2,PRODUCT_NAME:'Personal loan',PRODUCT_TYPE:'LOAN'},version:{PRODUCT_VERSION_ID:2},rules:{loan:[{MIN_LOAN_AMOUNT:1000,MAX_LOAN_AMOUNT:20000,MIN_TENURE_MONTHS:1,MAX_TENURE_MONTHS:24,LOAN_CATEGORY:'PERSONAL',ALLOWED_INTEREST_TYPE:'FIXED',REPAYMENT_FREQUENCY:'MONTHLY',AMORTIZATION_METHOD:'REDUCING_BALANCE',DISBURSEMENT_MODE:'SINGLE'}],interest:[interest]}};
const term={product:{PRODUCT_ID:3,PRODUCT_NAME:'Fixed deposit',PRODUCT_TYPE:'DEPOSIT'},version:{PRODUCT_VERSION_ID:3},rules:{term:[{MIN_DEPOSIT_AMOUNT:1000,MAX_DEPOSIT_AMOUNT:50000,MIN_TENURE_MONTHS:1,MAX_TENURE_MONTHS:24}],interest:[{...interest,PAYOUT_FREQUENCY:'AT_MATURITY'}]}};
function data(values){const result=new FormData();Object.entries(values).forEach(([k,v])=>result.set(k,v));return result;}
function requests(overrides={}){return async path=>{
 const values={'/term-deposits/availability':{openingEnabled:true},'/banking/accounts':[account],'/banking/facilities':[{FACILITY_ID:5,FACILITY_NUMBER:'Loan5',STATUS:'ACTIVE'}],'/credit-cards/products':[product],'/credit-cards':[card],'/credit-cards/card':card,
  '/loans/my/applications':[{id:4,status:'OFFERED',cifId:'cif'}],'/loans/applications/4/offers':[{OFFER_ID:6,OFFER_NUMBER:'Offer6',STATUS:'ISSUED',SANCTIONED_AMOUNT:10000,SANCTIONED_TENURE_MONTHS:12,ANNUAL_RATE_PCT:12,EXPIRES_AT:'2999-01-01'}],
  '/loans/facilities/5/prepayment-quote':{partialPrepaymentAllowed:true,foreclosureAllowed:true,minimumPartialPayment:1,foreclosureAmount:12000},
  '/products/customer-offers?cifId=cif&channel=WEB&currency=INR':[loan,term],...overrides};
 if(!Object.hasOwn(values,path))throw new Error('Unexpected endpoint '+path);return values[path];};}
test('loan and deposit forms resolve eligible products and exact payloads without submitting',async()=>{
 const loanForm=await prepareProductWorkflow('LOAN_APPLY',requests());const proposed=await loanForm.prepare(data({product:0,amount:'12000.00',tenureMonths:12}));
 assert.equal(proposed.path,'/loans/my/applications');assert.equal(proposed.body.cifId,'cif');assert.equal(proposed.body.productVersionId,2);assert.match(proposed.summary,/bank review/);
 const fd=await prepareProductWorkflow('TERM_DEPOSIT_OPEN',requests());const deposit=await fd.prepare(data({product:0,fundingAccountId:1,amount:'1000',tenureMonths:1}));
 assert.equal(deposit.path,'/term-deposits');assert.equal(deposit.body.productId,3);assert.match(deposit.summary,/Early withdrawal is unavailable/);assert.doesNotMatch(deposit.summary,/PRIVATE-/);
 assert.throws(()=>fd.prepare(data({product:0,fundingAccountId:1,amount:'999',tenureMonths:1})),/outside/);
});
test('loan offer acceptance uses only owned accounts and preserves separate disbursement',async()=>{
 const form=await prepareProductWorkflow('LOAN_ACCEPT',requests());const p=await form.prepare(data({offerId:6,disbursementAccountId:1,repaymentAccountId:1}));
 assert.equal(p.path,'/loans/offers/6/accept');assert.match(p.summary,/disbursement separately/);
 assert.throws(()=>form.prepare(data({offerId:6,disbursementAccountId:99,repaymentAccountId:1})),/current option/);
});
test('loan repayments refresh current prepayment quotes and reject changed foreclosure totals',async()=>{
 const due=await prepareProductWorkflow('LOAN_REPAY',requests());assert.equal((await due.prepare(data({facilityId:5,amount:'100'}))).path,'/loans/facilities/5/repayments');
 let total=12000;const request=async path=>path.endsWith('prepayment-quote')?{partialPrepaymentAllowed:true,foreclosureAllowed:true,minimumPartialPayment:1,foreclosureAmount:total}:requests()(path);
 const early=await prepareProductWorkflow('LOAN_PREPAY',request);const p=await early.prepare(data({facilityId:5,amount:'12000',mode:'FORECLOSURE'}));total=12001;
 await assert.rejects(p.beforeSubmit(),/quote changed/);
});
test('card application requires disclosed terms and an approved product limit',async()=>{
 const form=await prepareProductWorkflow('CARD_APPLY',requests());
 assert.throws(()=>form.prepare(data({productId:'product',repaymentAccountId:1,requestedLimit:1000})),/Accept/);
 const p=await form.prepare(data({productId:'product',repaymentAccountId:1,requestedLimit:1000,termsAccepted:'on'}));
 assert.equal(p.body.termsAccepted,true);assert.equal(p.body.cifId,'cif');assert.match(p.summary,/independent/);
});
test('card controls reject permanent blocking and use the freshly read row version',async()=>{
 const form=await prepareProductWorkflow('CARD_CONTROL',requests());const p=await form.prepare(data({cardId:'card',action:'FREEZE',reason:'Customer choice'}));
 assert.equal(p.body.rowVersion,2);assert.equal(p.path,'/credit-cards/card/controls');
 await assert.rejects(form.prepare(data({cardId:'card',action:'BLOCK',reason:'no'})),/unavailable/);
});
test('card spending and repayments require PIN confirmation and enforce the current balance',async()=>{
 for(const [workflow,path] of [['CARD_PURCHASE','purchases'],['CARD_REPAY','repayments']]){
  const form=await prepareProductWorkflow(workflow,requests());const p=await form.prepare(data({cardId:'card',amount:'100',merchantName:'Synthetic merchant'}));
  assert.equal(p.path,'/credit-cards/card/'+path);assert.equal(p.pin,true);
 }
 const form=await prepareProductWorkflow('CARD_REPAY',requests());await assert.rejects(form.prepare(data({cardId:'card',amount:'501'})),/exceeds/);
});
test('card billing uses only the next due cutoff and blocks early generation',async()=>{
 let form=await prepareProductWorkflow('CARD_BILL',requests());await assert.rejects(form.prepare(data({cardId:'card'})),/no statement due/);
 form=await prepareProductWorkflow('CARD_BILL',requests({'/credit-cards/card':{...card,BILLING_REQUIRED:true}}));
 assert.equal((await form.prepare(data({cardId:'card'}))).body.statementDate,'2026-10-01');
});
test('invalid amounts, unavailable resources and unknown workflows fail without writes',async()=>{
 for(const value of ['0','-1','1e3','1.001','NaN'])assert.throws(()=>validAmount(value));
 await assert.rejects(prepareProductWorkflow('CARD_REPAY',requests({'/credit-cards':[]})),/No open card/);
 await assert.rejects(prepareProductWorkflow('UNKNOWN',requests()),/unavailable/);
 await assert.rejects(prepareProductWorkflow('TERM_DEPOSIT_OPEN',requests({'/term-deposits/availability':{openingEnabled:false}})),/not enabled/);
});
test('secure workflow reviews first and retries with the same request key after a lost response',async()=>{
 const Native=globalThis.FormData;globalThis.FormData=class extends Native{constructor(form){super();Object.entries(form?.values||{}).forEach(([k,v])=>this.set(k,v));}};
 const form={values:{cardId:'card',action:'FREEZE',reason:'Customer choice'},querySelectorAll:()=>[]},error={textContent:''},button={disabled:false};
 const el={open:false,querySelectorAll:()=>[],querySelector:s=>s==='form'?form:s==='.form-error'?error:button,showModal(){this.open=true;},close(){this.open=false;}};nodes['#dialog']=el;
 const writes=[];let completed='';const action={workflow:'CARD_CONTROL',status:'PENDING'};
 globalThis.fetch=async(url,options)=>{
  if(options.method==='GET')return {ok:true,status:200,headers:new Headers(),json:async()=>url.endsWith('/card')?card:[card]};
  writes.push(options);if(writes.length===1)throw new Error('Response lost');
  return {ok:true,status:200,headers:new Headers(),json:async()=>({...card,STATUS:'FROZEN'})};
 };
 try{
  await openProductWorkflow(action,{isOwner:()=>true,onComplete:text=>{completed=text;}});
  await form.onsubmit({target:form,preventDefault(){}});assert.equal(writes.length,0);assert.match(el.innerHTML,/Review and confirm/);
  const confirm=form.onsubmit;form.values={};await confirm({target:form,preventDefault(){}});assert.equal(action.status,'PENDING');
  await confirm({target:form,preventDefault(){}});assert.equal(action.status,'COMPLETED');assert.match(completed,/FROZEN/);
  assert.equal(writes[0].headers['Idempotency-Key'],writes[1].headers['Idempotency-Key']);assert.equal(writes[0].body,writes[1].body);
 }finally{globalThis.FormData=Native;}
});
