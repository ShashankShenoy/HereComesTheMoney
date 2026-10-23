import {api,getSession} from './api.js';
import {esc,errorText} from './ui.js';
import {formatAmount} from './presentation.js';
import {syncConditionalField} from './jet.js';

export function fundingRequest(accounts,accountId,amount){
 const account=accounts.find(a=>String(a.ACCOUNT_ID)===String(accountId));
 if(!account||!['ACTIVE','PENDING_OPEN'].includes(account.LIFECYCLE_STATUS||account.ACCOUNT_STATUS)||account.CURRENCY_CODE!=='INR')throw new Error('Choose an active or pending INR account.');
 if(!/^\d+(?:\.\d{1,2})?$/.test(String(amount))||Number(amount)<=0||Number(amount)>10000000)throw new Error('Enter an amount from INR 0.01 to INR 10,000,000 with at most two decimal places.');
 return {accountId:Number(account.ACCOUNT_ID),amount:Number(amount).toFixed(2)};
}

export async function demoFundingPanel(request=api){
 if(getSession()?.user?.userType!=='EMPLOYEE'||!getSession()?.user?.permissions?.includes('TXN_POST'))return '';
 let available;try{available=await request('/accounts/demo-funding/availability');}catch{return '';}
 if(!available.enabled)return '';
 const accounts=(await request('/banking/accounts')).filter(a=>['ACTIVE','PENDING_OPEN'].includes(a.LIFECYCLE_STATUS||a.ACCOUNT_STATUS)&&a.CURRENCY_CODE==='INR');
 return '<section class="panel"><div class="panel-heading"><h3>Add demo money</h3></div><div class="panel-body"><p class="hint">Add simulated funds to an account for testing.</p><form id="demo-funding-form" data-accounts="'+esc(JSON.stringify(accounts))+'"><div class="grid2"><label class="field">Account<select name="accountId" required><option value="">Choose an account</option>'+accounts.map(a=>'<option value="'+esc(a.ACCOUNT_ID)+'">'+esc(a.ACCOUNT_NUMBER)+' · '+esc(a.PRODUCT_NAME||a.PRODUCT_TYPE||'Account')+'</option>').join('')+'</select></label><label class="field">Amount (INR)<input name="amount" type="number" min="0.01" max="10000000" step="0.01" required></label></div><p data-funding-review class="notice" hidden></p><p class="form-error" role="alert"></p><p data-funding-result role="status" aria-live="polite"></p><div class="form-actions"><button class="btn" type="button" data-funding-edit hidden>Edit</button><button class="btn primary" type="submit" '+(!accounts.length?'disabled':'')+'>Review deposit</button></div></form></div></section>';
}

export function bindDemoFunding(form,{request=api,session=getSession}={}){
 if(!form)return;
 const accounts=JSON.parse(form.dataset.accounts),account=form.querySelector('[name="accountId"]'),amount=form.querySelector('[name="amount"]');
 const button=form.querySelector('[type="submit"]'),edit=form.querySelector('[data-funding-edit]'),review=form.querySelector('[data-funding-review]'),error=form.querySelector('.form-error'),result=form.querySelector('[data-funding-result]');
 const owner=session();let proposal=null,busy=false;
 const fields=disabled=>{for(const input of [account,amount])syncConditionalField(input,{required:true,disabled});};
 edit.onclick=()=>{if(busy)return;proposal=null;fields(false);edit.hidden=review.hidden=true;button.textContent='Review deposit';error.textContent='';};
 form.onsubmit=async event=>{
  event.preventDefault();if(busy||session()!==owner)return;
  error.textContent='';let completed=false;
  try{
   if(!proposal){
    const body=fundingRequest(accounts,account.value,amount.value);proposal={...body,requestKey:crypto.randomUUID()};
    const selected=accounts.find(a=>Number(a.ACCOUNT_ID)===body.accountId);review.textContent='Add '+formatAmount(body.amount,'INR')+' of demo money to '+selected.ACCOUNT_NUMBER+'?';review.hidden=edit.hidden=false;fields(true);button.textContent='Confirm add money';result.textContent='';return;
   }
   busy=true;button.disabled=edit.disabled=true;
   const receipt=await request('/accounts/demo-funding',{method:'POST',body:proposal});
   if(session()!==owner||!form.isConnected)return;
   result.textContent='Demo money added. Balance: '+formatAmount(receipt.balance.postedBalance,'INR')+'. Transaction '+receipt.transaction.transactionId+'.';
   completed=true;
  }catch(cause){if(session()===owner&&form.isConnected)error.textContent=errorText(cause);}
  finally{busy=false;if(session()===owner&&form.isConnected){button.disabled=edit.disabled=false;if(completed){edit.onclick();syncConditionalField(amount,{required:true,disabled:false,clear:true});}}}
 };
}
