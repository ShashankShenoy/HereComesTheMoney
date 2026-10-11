import {api,getSession} from './api.js';
import {esc,field,select,errorText} from './ui.js';
import {formatAmount,formatDecimal,formatDate} from './presentation.js';

let currencies=[],rates=[],actorId;

export async function customerFxScreen(){
  const actor=getSession()?.user?.userId;
  if(actor!==actorId){actorId=actor;currencies=[];rates=[];}
  const loaded=await Promise.all([api('/currencies'),api('/fx/rates')]);
  if(getSession()?.user?.userId!==actor)return '';
  currencies=loaded[0].filter(c=>c.ACTIVE_FLAG==='Y');rates=loaded[1];
  const active=new Set(currencies.map(c=>c.CURRENCY_CODE)),pairs=new Set();
  rates.forEach(rate=>{
    if(active.has(rate.BASE_CURRENCY)&&active.has(rate.QUOTE_CURRENCY)){
      pairs.add(`${rate.BASE_CURRENCY}|${rate.QUOTE_CURRENCY}`);
      pairs.add(`${rate.QUOTE_CURRENCY}|${rate.BASE_CURRENCY}`);
    }
  });
  const pairChoices=[...pairs].sort().map(pair=>({value:pair,label:pair.replace('|',' → ')}));
  const form=pairChoices.length?`<form id="customer-fx-form"><div class="grid2">${select('Currency pair','pair',pairChoices,pairChoices.find(c=>c.value.startsWith('INR|'))?.value||pairChoices[0].value)}${field('Amount to compare','amount','number',{min:'0.01',step:'0.01',placeholder:'0.00'})}</div><p class="form-error" role="alert"></p><button type="submit" class="btn primary">Calculate indicative amount</button></form>`:'<div class="empty">No current approved comparison rate is available. Ask the bank to add a supported currency and approve a rate.</div>';
  return `<div class="notice"><strong>Rate information only.</strong> This calculation uses an approved mid-market reference rate. It is not a guaranteed customer rate or an offer to exchange money. Foreign-currency accounts, conversion orders, and international transfers are unavailable.</div><section class="panel"><div class="panel-heading"><h3>Compare currencies</h3></div><div class="panel-body">${form}<div id="customer-fx-result" role="status" aria-live="polite"></div></div></section>`;
}

export function bindCustomerFx(){
  const form=document.querySelector('#customer-fx-form');
  if(!form)return;
  form.onsubmit=async event=>{
    event.preventDefault();
    const data=new FormData(form),pair=String(data.get('pair')),[base,quote]=pair.split('|'),amount=String(data.get('amount'));
    const error=form.querySelector('.form-error'),button=form.querySelector('[type=submit]'),result=document.querySelector('#customer-fx-result');
    error.textContent='';result.innerHTML='';
    if(!rates.some(rate=>rate.BASE_CURRENCY===base&&rate.QUOTE_CURRENCY===quote||rate.BASE_CURRENCY===quote&&rate.QUOTE_CURRENCY===base))return void(error.textContent='Refresh this page and choose an available currency pair.');
    if(!/^\d+(?:\.\d{1,2})?$/.test(amount)||Number(amount)<0.01)return void(error.textContent='Enter a positive amount with at most two decimal places.');
    button.disabled=true;
    try{
      const value=await api('/fx/quote?'+new URLSearchParams({base,quote,amount}));
      result.innerHTML=`<div class="notice"><strong class="amount-value">${esc(formatAmount(amount,base))} ≈ ${esc(formatAmount(value.amount,quote))}</strong><br>Reference rate: <span class="numeric-value">${esc(formatDecimal(value.rate))}</span>${value.reciprocal?' (calculated from the approved reverse rate)':''} · Source: ${esc(value.source)} · Valid until: ${esc(formatDate(value.validUntil))}<br>This amount is indicative. You cannot accept or transfer this quote.</div>`;
    }catch(e){error.textContent=errorText(e);}finally{button.disabled=false;}
  };
}
