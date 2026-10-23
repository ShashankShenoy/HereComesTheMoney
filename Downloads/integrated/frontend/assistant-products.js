import {api,getSession} from './api.js';
import {esc,field,select,dialog} from './ui.js';
import {formatAmount} from './presentation.js';
import {paymentPinField,withPaymentPin} from './payment-pin.js';
import {validatePrepayment} from './loan-prepayments.js';

const amount=()=>field('Amount (INR)','amount','number',{min:'0.01',step:'0.01'});
const tenure=()=>field('Term in months','tenureMonths','number',{min:1,max:9999,step:1});
const money=value=>esc(formatAmount(value,'INR'));
const accountChoices=accounts=>accounts.map(a=>({value:String(a.ACCOUNT_ID),label:`Account ending ${String(a.ACCOUNT_NUMBER).slice(-4)} · ${a.PRODUCT_NAME||a.PRODUCT_TYPE}`}));
const activeAccounts=accounts=>accounts.filter(a=>a.ACCOUNT_STATUS==='ACTIVE'&&a.CURRENCY_CODE==='INR'&&['SAVINGS','CURRENT'].includes(a.PRODUCT_TYPE)&&!['JOINTLY','GUARDIAN_OPERATED'].includes(a.ACCOUNT_OPERATION_MODE));
const eligibleLoan=o=>{
 const r=o.rules?.loan?.[0]||{},i=o.rules?.interest?.find(i=>i.INTEREST_TYPE==='FIXED')||{};
 return o.product.PRODUCT_TYPE==='LOAN'&&['FIXED','BOTH'].includes(r.ALLOWED_INTEREST_TYPE)&&r.REPAYMENT_FREQUENCY==='MONTHLY'&&r.AMORTIZATION_METHOD==='REDUCING_BALANCE'&&['SINGLE','BOTH'].includes(r.DISBURSEMENT_MODE)&&Number(r.GRACE_DAYS||0)===0&&['SIMPLE','REDUCING_BALANCE'].includes(i.INTEREST_METHOD)&&i.COMPOUND_FREQUENCY==='NONE'&&i.PAYOUT_FREQUENCY==='MONTHLY'&&i.ROUNDING_MODE==='HALF_EVEN'&&['ACT/365','ACT_365','ACTUAL_365','30/360'].includes(i.DAY_COUNT_BASIS)&&Number(r.MIN_TENURE_MONTHS)<=600;
};
const nonempty=(items,message)=>{if(!items.length)throw new Error(message);return items;};
const chosen=(items,key,value)=>{const item=items.find(item=>String(item[key])===String(value));if(!item)throw new Error('Choose a current option from the form.');return item;};
const productTerms=p=>`<p>${esc(p.PRODUCT_NAME)} · version ${esc(p.VERSION_NO)}</p><p>${esc(p.DESCRIPTION||'')}</p><p>Limit ${money(p.MINIMUM_LIMIT)}–${money(p.MAXIMUM_LIMIT)}. Annual interest ${esc(p.ANNUAL_RATE_PCT)}%. Minimum payment ${esc(p.MIN_PAYMENT_PCT)}% or ${money(p.MIN_PAYMENT_FLOOR)}, capped at balance. Billing day ${esc(p.BILLING_DAY)}; due after ${esc(p.PAYMENT_DUE_DAYS)} days.</p><p>${esc(p.INTEREST_POLICY||'')}</p>`;
async function offers(request){
 const groups=await Promise.all((getSession()?.user?.cifIds||[]).map(async cifId=>(await request('/products/customer-offers?'+new URLSearchParams({cifId,channel:'WEB',currency:'INR'}))).map(o=>({...o,cifId}))));
 return groups.flat();
}
export const productWorkflowNames=Object.freeze({LOAN_APPLY:'Apply for a loan',LOAN_ACCEPT:'Accept a loan offer',LOAN_REPAY:'Repay a loan',LOAN_PREPAY:'Early loan repayment',CARD_APPLY:'Apply for a credit card',CARD_CONTROL:'Manage a credit card',CARD_REPAY:'Repay a credit card',CARD_PURCHASE:'Simulate a card purchase',CARD_BILL:'Generate a card statement',TERM_DEPOSIT_OPEN:'Open a fixed deposit'});

/** Fixed routes and explicit payloads only. No model-provided URL or executable form schema. */
export async function prepareProductWorkflow(workflow,request=api){
 // The injectable request is used by focused, API-key-free contract tests.
 const bank=()=>request('/banking/accounts');
 if(workflow==='LOAN_APPLY'||workflow==='TERM_DEPOSIT_OPEN'){
  if(workflow==='TERM_DEPOSIT_OPEN'&&!(await request('/term-deposits/availability')).openingEnabled)throw new Error('Fixed deposit opening is not enabled by the bank.');
  const all=await offers(request);
  const isLoan=workflow==='LOAN_APPLY';
  const products=nonempty(all.filter(o=>isLoan?eligibleLoan(o):o.product.PRODUCT_TYPE==='DEPOSIT'&&o.rules?.term?.length&&o.rules?.interest?.length===1&&o.rules.interest[0].INTEREST_TYPE==='FIXED'&&o.rules.interest[0].INTEREST_METHOD==='SIMPLE'&&o.rules.interest[0].PAYOUT_FREQUENCY==='AT_MATURITY'),'No eligible products are available for this profile.');
  const accounts=isLoan?[]:nonempty(activeAccounts(await bank()),'An active INR savings or current account is required.');
  const choices=products.map((o,index)=>({value:String(index),label:`${o.product.PRODUCT_NAME} · version ${o.version.PRODUCT_VERSION_ID} · profile ${o.cifId}`}));
  const terms=products.map(o=>{const r=isLoan?o.rules.loan[0]:o.rules.term[0],rate=o.rules.interest?.find(i=>i.INTEREST_TYPE==='FIXED')?.FIXED_RATE_PCT;return `<details><summary>${esc(o.product.PRODUCT_NAME)} · version ${esc(o.version.PRODUCT_VERSION_ID)} terms</summary><p>Amount ${money(isLoan?r.MIN_LOAN_AMOUNT:r.MIN_DEPOSIT_AMOUNT)}–${money(isLoan?r.MAX_LOAN_AMOUNT:r.MAX_DEPOSIT_AMOUNT)}. Term ${esc(r.MIN_TENURE_MONTHS)}–${esc(isLoan?Math.min(600,Number(r.MAX_TENURE_MONTHS)):r.MAX_TENURE_MONTHS)} months. Published fixed annual rate ${esc(rate??'See product terms')}%.</p></details>`;}).join('');
  return {title:productWorkflowNames[workflow],content:select('Product','product',choices)+(isLoan?'':select('Funding account','fundingAccountId',accountChoices(accounts)))+amount()+tenure()+terms,
   prepare(data){
    const o=products[Number(data.get('product'))];if(!o)throw new Error('Choose an available product.');
    const value=validAmount(data.get('amount')),months=Number(data.get('tenureMonths')),r=isLoan?o.rules.loan[0]:o.rules.term[0];
    const minimum=isLoan?r.MIN_LOAN_AMOUNT:r.MIN_DEPOSIT_AMOUNT,maximum=isLoan?r.MAX_LOAN_AMOUNT:r.MAX_DEPOSIT_AMOUNT;
    if(Number(value)<Number(minimum)||Number(value)>Number(maximum)||!Number.isInteger(months)||months<Number(r.MIN_TENURE_MONTHS)||months>Number(r.MAX_TENURE_MONTHS)||(isLoan&&months>600))throw new Error('The amount or term is outside the selected product limits.');
    const rate=o.rules.interest?.find(i=>i.INTEREST_TYPE==='FIXED')?.FIXED_RATE_PCT;
    const summary=`<p>${esc(o.product.PRODUCT_NAME)} · version ${esc(o.version.PRODUCT_VERSION_ID)}. Amount ${money(value)}, ${months} months. Published fixed rate: ${esc(rate??'See product terms')}%.</p>`;
    const base={productId:Number(o.product.PRODUCT_ID),productVersionId:Number(o.version.PRODUCT_VERSION_ID),amount:value,tenureMonths:months};
    if(isLoan)return {path:'/loans/my/applications',body:{...base,cifId:o.cifId,purposeCode:r.LOAN_CATEGORY||'PERSONAL'},summary:summary+'<p>This submits an application for bank review. Approval, agreed loan terms and disbursement happen later.</p>',success:r=>`Loan application ${r.number||r.id} submitted for bank review.`};
    const account=chosen(accounts,'ACCOUNT_ID',data.get('fundingAccountId'));if(account.PRIMARY_CIF_ID!==o.cifId)throw new Error('Choose a funding account for the selected product profile.');
    return {path:'/term-deposits',body:{...base,fundingAccountId:Number(account.ACCOUNT_ID)},summary:summary+`<p>Debit account ending ${esc(String(account.ACCOUNT_NUMBER).slice(-4))}. Principal and fixed interest return to this account at maturity. Early withdrawal is unavailable.</p>`,success:r=>`Fixed deposit ${r.TERM_DEPOSIT_ID} opened. Status: ${r.STATUS}.`};
   }};
 }
 if(workflow==='LOAN_ACCEPT'){
  const [applications,accounts]=await Promise.all([request('/loans/my/applications'),bank()]);const current=activeAccounts(accounts);let choices=[];
  for(const application of applications.filter(a=>['OFFERED','ACCEPTED'].includes(a.status))){
   const records=await request(`/loans/applications/${encodeURIComponent(application.id)}/offers`);
   choices.push(...records.filter(o=>o.STATUS==='ISSUED'&&Date.parse(o.EXPIRES_AT)>Date.now()).map(o=>({...o,cifId:application.cifId})));
  }
  nonempty(choices,'No current issued loan offer is available to accept.');nonempty(current,'An active INR account is required.');
  return {title:productWorkflowNames[workflow],content:select('Issued offer','offerId',choices.map(o=>({value:String(o.OFFER_ID),label:`${o.OFFER_NUMBER} · INR ${o.SANCTIONED_AMOUNT} · ${o.SANCTIONED_TENURE_MONTHS} months · ${o.ANNUAL_RATE_PCT}%`})))+select('Receive loan funds into','disbursementAccountId',accountChoices(current))+select('Repayment account','repaymentAccountId',accountChoices(current)),prepare(data){
   const o=chosen(choices,'OFFER_ID',data.get('offerId')),receive=chosen(current,'ACCOUNT_ID',data.get('disbursementAccountId')),repay=chosen(current,'ACCOUNT_ID',data.get('repaymentAccountId'));
   if(receive.PRIMARY_CIF_ID!==o.cifId||repay.PRIMARY_CIF_ID!==o.cifId)throw new Error('Choose accounts owned by the borrower.');
   return {path:`/loans/offers/${encodeURIComponent(o.OFFER_ID)}/accept`,body:{disbursementAccountId:Number(receive.ACCOUNT_ID),repaymentAccountId:Number(repay.ACCOUNT_ID)},summary:`<p>Accept ${esc(o.OFFER_NUMBER)}: ${money(o.SANCTIONED_AMOUNT)}, ${esc(o.SANCTIONED_TENURE_MONTHS)} months, ${esc(o.ANNUAL_RATE_PCT)}% annual interest. Expires ${esc(o.EXPIRES_AT)}.</p><p>Receive funds into account ending ${esc(String(receive.ACCOUNT_NUMBER).slice(-4))}; repay from account ending ${esc(String(repay.ACCOUNT_NUMBER).slice(-4))}.</p><p>The bank completes documentation and disbursement separately.</p>`,success:()=>`Loan offer ${o.OFFER_NUMBER} accepted. Bank processing and disbursement remain separate.`};
  }};
 }
 if(workflow==='LOAN_REPAY'||workflow==='LOAN_PREPAY'){
  const facilities=nonempty((await request('/banking/facilities')).filter(f=>['ACTIVE','DELINQUENT'].includes(f.STATUS)),'No active loan is available for repayment.');
  const early=workflow==='LOAN_PREPAY';
  return {title:productWorkflowNames[workflow],content:select('Loan','facilityId',facilities.map(f=>({value:String(f.FACILITY_ID),label:f.FACILITY_NUMBER})))+(early?select('Repayment mode','mode',['PARTIAL','FORECLOSURE']):'')+amount()+'<p>For foreclosure, first ask the assistant for the current early repayment quote and enter its exact total. Interest and eligibility are checked again before confirmation.</p>',async prepare(data){
   const f=chosen(facilities,'FACILITY_ID',data.get('facilityId')),value=validAmount(data.get('amount')),mode=String(data.get('mode'));
   const path=`/loans/facilities/${encodeURIComponent(f.FACILITY_ID)}`;
   if(early){const quote=await request(path+'/prepayment-quote');validatePrepayment(quote,mode,value);}
   return {path:path+(early?'/prepayments':'/repayments'),body:{amount:value,...(early?{mode}:{})},summary:`<p>${early?esc(mode):'Due repayment'} of ${money(value)} for loan ${esc(f.FACILITY_NUMBER)}. This debits the loan's agreed repayment account. Outstanding dues and interest are checked by the bank.</p>`,
    async beforeSubmit(){if(early)validatePrepayment(await request(path+'/prepayment-quote'),mode,value);},success:r=>`Loan repayment ${r.transactionId} posted. Check the current loan balance and schedule.`};
  }};
 }
 if(workflow==='CARD_APPLY'){
  const [items,allAccounts]=await Promise.all([request('/credit-cards/products'),bank()]);const products=nonempty(items.filter(p=>p.STATUS==='APPROVED'),'No approved card products are available.'),accounts=nonempty(activeAccounts(allAccounts),'An active repayment account is required.');
  return {title:productWorkflowNames[workflow],content:select('Card product','productId',products.map(p=>({value:p.PRODUCT_ID,label:p.PRODUCT_NAME})))+select('Repayment account','repaymentAccountId',accountChoices(accounts))+field('Requested limit (INR)','requestedLimit','number',{min:1000,step:'0.01'})+products.map(p=>`<details><summary>${esc(p.PRODUCT_NAME)} terms</summary>${productTerms(p)}</details>`).join('')+'<label><input name="termsAccepted" type="checkbox" required> I accept the selected product’s disclosed terms.</label>',prepare(data){
   const p=chosen(products,'PRODUCT_ID',data.get('productId')),a=chosen(accounts,'ACCOUNT_ID',data.get('repaymentAccountId')),limit=validAmount(data.get('requestedLimit'));
   if(!data.get('termsAccepted'))throw new Error('Accept the selected card terms before applying.');
   if(Number(limit)<Number(p.MINIMUM_LIMIT)||Number(limit)>Number(p.MAXIMUM_LIMIT))throw new Error('The requested limit is outside the selected product range.');
   return {path:'/credit-cards/applications',body:{productId:p.PRODUCT_ID,cifId:a.PRIMARY_CIF_ID,repaymentAccountId:Number(a.ACCOUNT_ID),requestedLimit:limit,termsAccepted:true},summary:productTerms(p)+`<p>Requested limit ${money(limit)}. Repayments use account ending ${esc(String(a.ACCOUNT_NUMBER).slice(-4))}. An independent employee reviews the application; a submitted application is not an active card.</p>`,success:r=>`Credit card application ${r.APPLICATION_ID} submitted. Status: ${r.STATUS}.`};
  }};
 }
 if(['CARD_CONTROL','CARD_REPAY','CARD_PURCHASE','CARD_BILL'].includes(workflow)){
  const cards=nonempty((await request('/credit-cards')).filter(c=>c.STATUS!=='CLOSED'),'No open card is available.');
  const extra=workflow==='CARD_CONTROL'?select('Action','action',['ACTIVATE','FREEZE','UNFREEZE','CLOSE'])+field('Reason','reason','text',{maxlength:500}):workflow==='CARD_BILL'?'':amount()+(workflow==='CARD_PURCHASE'?field('Simulated merchant','merchantName','text',{maxlength:120}):'');
  return {title:productWorkflowNames[workflow],content:select('Card','cardId',cards.map(c=>({value:c.CARD_ID,label:`${c.PRODUCT_NAME} · ending ${c.DISPLAY_ENDING} · ${c.STATUS}`})))+extra,async prepare(data){
   const selected=chosen(cards,'CARD_ID',data.get('cardId')),c=await request(`/credit-cards/${encodeURIComponent(selected.CARD_ID)}`),base=`/credit-cards/${encodeURIComponent(c.CARD_ID)}`;
   if(workflow==='CARD_CONTROL'){
    const action=String(data.get('action')),reason=String(data.get('reason')||'').trim();
    const permitted={ACTIVATE:['ISSUED'],FREEZE:['ACTIVE'],UNFREEZE:['FROZEN'],CLOSE:['ISSUED','ACTIVE','FROZEN','BLOCKED','EXPIRED']};
    if(!permitted[action]?.includes(c.STATUS)||!reason)throw new Error('This action is unavailable in the current card status, or its reason is missing.');
    return {path:base+'/controls',body:{rowVersion:c.ROW_VERSION,action,reason},summary:`<p>${esc(action)} card ending ${esc(c.DISPLAY_ENDING)}. Reason: ${esc(reason)}.</p>`,success:r=>`Card ending ${r.DISPLAY_ENDING} updated. Status: ${r.STATUS}.`};
   }
   if(workflow==='CARD_BILL'){
    if(!c.BILLING_REQUIRED)throw new Error('This card has no statement due for generation.');
    return {path:base+'/statements',body:{statementDate:c.NEXT_STATEMENT_DATE},summary:`<p>Generate the due statement for card ending ${esc(c.DISPLAY_ENDING)}, cutoff ${esc(c.NEXT_STATEMENT_DATE)}.</p>`,success:r=>`Credit card statement ${r.STATEMENT_ID} generated.`};
   }
   const value=validAmount(data.get('amount')),purchase=workflow==='CARD_PURCHASE',merchant=String(data.get('merchantName')||'').trim();
   if(c.BILLING_REQUIRED)throw new Error('Generate due card statements before a purchase or repayment.');
   if(purchase&&(c.STATUS!=='ACTIVE'||!merchant))throw new Error('Choose an active card and enter a simulated merchant.');
   if(Number(value)>Number(purchase?c.AVAILABLE_CREDIT:c.PAYOFF_AMOUNT))throw new Error('The amount exceeds available credit or the current repayment total.');
   return {path:base+(purchase?'/purchases':'/repayments'),pin:true,body:{amount:value,...(purchase?{merchantName:merchant}:{})},summary:`<p>${purchase?'Simulated purchase':'Repayment'} of ${money(value)} on card ending ${esc(c.DISPLAY_ENDING)}${purchase?' at '+esc(merchant):' from its agreed repayment account'}. This posts immediately after PIN confirmation.</p>`,success:r=>`Card operation completed. Reference: ${r.ENTRY_ID||r.entryId||c.CARD_ID}. Check current balances and transactions.`};
  }};
 }
 throw new Error('This assistant workflow is unavailable.');
}
export function validAmount(value){const text=String(value||'').trim();if(!/^\d{1,16}(?:\.\d{1,2})?$/.test(text)||Number(text)<=0)throw new Error('Enter a positive INR amount with at most two decimal places.');return text;}

export async function openProductWorkflow(action,{isOwner,onComplete}){
 const spec=await prepareProductWorkflow(action.workflow);if(!isOwner())return;
 dialog(spec.title,spec.content,async data=>{
  if(!isOwner())throw new Error('Your sign-in changed. Open a new assistant conversation.');
  const proposal=await spec.prepare(data);if(!isOwner())return false;
  const fingerprint=JSON.stringify({path:proposal.path,body:proposal.body});
  if(!action.request||action.request.fingerprint!==fingerprint)action.request={fingerprint,key:crypto.randomUUID()};
  const key=action.request.key;
  dialog('Review and confirm',proposal.summary+paymentPinFieldFor(proposal),async confirmation=>{
   if(!isOwner())throw new Error('Your sign-in changed. Open a new assistant conversation.');
   if(action.status!=='PENDING'||action.submitting)return false;
   action.submitting=true;
   try{
    // After an uncertain response, retry the identical command. The domain rechecks
    // eligibility or returns its saved result even if a completed foreclosure closed the loan.
    if(!action.request.attempted)await proposal.beforeSubmit?.();if(!isOwner())throw new Error('Your sign-in changed. Open a new assistant conversation.');
    const send=headers=>{action.request.attempted=true;return api(proposal.path,{method:'POST',headers:{...headers,'Idempotency-Key':key},body:{...proposal.body,requestKey:key}});};
    const result=proposal.pin?await withPaymentPin(confirmation,send):await send({});
    if(isOwner()){action.status='COMPLETED';onComplete(proposal.success(result));}
   }finally{action.submitting=false;}
  });
  const confirmButton=document.querySelector('#dialog [type=submit]');if(confirmButton)confirmButton.textContent='Confirm operation';
  return false;
 });
 const reviewButton=document.querySelector('#dialog [type=submit]');if(reviewButton)reviewButton.textContent='Review choices';
}
const paymentPinFieldFor=proposal=>proposal.pin?paymentPinField():'';
