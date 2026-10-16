import {api} from './api.js';
import {formatAmount,formatDate} from './presentation.js';

const cents=value=>{
 const text=String(value??'').trim();
 if(!/^\d{1,16}(\.\d{1,2})?$/.test(text))throw new Error('Enter a positive amount with at most two decimal places.');
 const [whole,fraction='']=text.split('.');return BigInt(whole)*100n+BigInt(fraction.padEnd(2,'0'));
};
export function validatePrepayment(quote,mode,amount){
 const paid=cents(amount),payoff=cents(quote.foreclosureAmount);
 if(mode==='FORECLOSURE'){
  if(!quote.foreclosureAllowed)throw new Error('Foreclosure is not enabled for this product.');
  if(paid!==payoff)throw new Error('The foreclosure quote changed. Review the updated total and submit again.');
 }else if(mode==='PARTIAL'){
  if(!quote.partialPrepaymentAllowed)throw new Error('Partial prepayment is not enabled for this product.');
  if(paid<cents(quote.minimumPartialPayment)||paid>=payoff)throw new Error('Enter at least the quoted minimum and less than the foreclosure total.');
 }else throw new Error('Choose partial prepayment or foreclosure.');
}

/** Quotes are refreshed on selection and again before submission; no money moves here. */
export function bindLoanPrepayment(form,request=api){
 const id=form?.querySelector('[name=id]'),mode=form?.querySelector('[name=mode]'),amount=form?.querySelector('[name=amount]');
 const notice=form?.querySelector('[data-loan-prepayment-quote]');
 if(!id||!mode||!amount||!notice)return;
 const submit=form.querySelector('[type=submit]'),error=form.querySelector('.form-error'),original=form.onsubmit;
 let generation=0,quote=null,quotedId='';
 function setAmount(value){
  amount.value=String(value);
  amount.dispatchEvent(new Event('change',{bubbles:true}));
 }
 function display(value){
  notice.textContent='As of '+formatDate(value.valueDate)+': interest through today '+formatAmount(value.interestDue,'INR')+'. Minimum partial payment '+formatAmount(value.minimumPartialPayment,'INR')+'. Full foreclosure '+formatAmount(value.foreclosureAmount,'INR')+'. The amount is the total debit from the repayment account.';
  for(const option of mode.options)if(option.value==='PARTIAL')option.disabled=!value.partialPrepaymentAllowed;else if(option.value==='FORECLOSURE')option.disabled=!value.foreclosureAllowed;
  amount.readOnly=mode.value==='FORECLOSURE';
  const visible=amount.closest?.('label.field')?.querySelector('oj-input-text');
  if(visible)visible.readonly=amount.readOnly;
  if(amount.readOnly)setAmount(value.foreclosureAmount);
  amount.min=mode.value==='FORECLOSURE'?value.foreclosureAmount:value.minimumPartialPayment;
  amount.max=value.foreclosureAmount;amount.step='0.01';
 }
 async function refresh(){
  const version=++generation,selected=id.value;quote=null;submit.disabled=true;
  if(!selected){notice.textContent='Choose a loan facility to see its current early repayment quote.';return;}
  notice.textContent='Loading the current early repayment quote…';
  try{
   const value=await request('/loans/facilities/'+encodeURIComponent(selected)+'/prepayment-quote');
   if(version!==generation||id.value!==selected)return;
   quote=value;quotedId=selected;display(value);submit.disabled=!(value.partialPrepaymentAllowed||value.foreclosureAllowed);
  }catch(e){if(version===generation)notice.textContent=e.message||'The quote could not be loaded.';}
 }
 id.addEventListener('change',()=>{setAmount('');refresh();});
 mode.addEventListener('change',()=>{if(quote)display(quote);});
 form.onsubmit=async event=>{
  event.preventDefault();error.textContent='';
  if(!quote||quotedId!==id.value){error.textContent='Load a quote for the selected facility before submitting.';return;}
  submit.disabled=true;
  try{
   const selected=id.value,selectedMode=mode.value,entered=amount.value;
   const latest=await request('/loans/facilities/'+encodeURIComponent(selected)+'/prepayment-quote');
   if(id.value!==selected||mode.value!==selectedMode||amount.value!==entered)throw new Error('The payment selection changed. Review it and submit again.');
   quote=latest;display(latest);validatePrepayment(latest,selectedMode,entered);
   const payload=selected+'|'+selectedMode+'|'+entered,key=form.querySelector('[name=requestKey]');
   if(form.dataset.prepaymentPayload&&form.dataset.prepaymentPayload!==payload&&key)key.value=crypto.randomUUID();
   form.dataset.prepaymentPayload=payload;
   await original(event);
  }catch(e){error.textContent=e.message||'The early repayment could not be submitted.';}
  finally{submit.disabled=false;}
 };
 refresh();
}
