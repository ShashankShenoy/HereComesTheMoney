import {api,getSession} from './api.js';
import {esc,field,select,table,badge,dialog,toast,errorText} from './ui.js';
import {formatAmount,formatDate} from './presentation.js';

let accounts=[],beneficiaries=[],payments=[],actorId;
const money=value=>esc(formatAmount(value,'INR'));
const eligible=account=>account.ACCOUNT_STATUS==='ACTIVE'&&account.CURRENCY_CODE==='INR'&&['SAVINGS','CURRENT'].includes(account.PRODUCT_TYPE)&&!['JOINTLY','GUARDIAN_OPERATED'].includes(account.ACCOUNT_OPERATION_MODE);
export const paymentStatus=status=>({RECEIVED:'Awaiting independent review',AUTHORIZED:'Authorized locally',HELD:'Funds held locally',DEBIT_POSTED:'Debited locally',DISPATCHED:'Sent to simulated rail',ACCEPTED:'Accepted in simulation',UNKNOWN:'Outcome under investigation',REJECTED:'Rejected in simulation',REFUNDED:'Refunded locally',SETTLEMENT_PENDING:'Awaiting interbank settlement',SETTLED:'Interbank settlement completed; beneficiary credit handled by receiving bank.'}[status]||String(status||'Unknown').replaceAll('_',' '));

export async function customerPaymentsScreen(){
  const actor=getSession()?.user?.userId;
  if(actor!==actorId){actorId=actor;accounts=[];beneficiaries=[];payments=[];}
  const permissions=getSession()?.user?.permissions||[];
  const canCreate=permissions.includes('PAYMENT_CREATE'),canTransfer=permissions.includes('TXN_POST'),canReadTransfers=permissions.includes('TXN_READ');
  const loaded=await Promise.all([canCreate||canTransfer||canReadTransfers?api('/banking/accounts'):Promise.resolve([]),api('/beneficiaries'),api('/payments'),api('/payments/availability')]);
  if(getSession()?.user?.userId!==actor)return '';
  const [,,,availability]=loaded;
  [accounts,beneficiaries,payments]=loaded;
  const sourceAccounts=accounts.filter(eligible),activeRecipients=beneficiaries.filter(b=>b.STATUS==='ACTIVE');
  const accountChoices=sourceAccounts.map(a=>({value:String(a.ACCOUNT_ID),label:`${a.ACCOUNT_NUMBER} · ${a.PRODUCT_NAME||a.PRODUCT_TYPE}`}));
  const transferHistory=canReadTransfers?(await Promise.all(accounts.map(async account=>(await api(`/banking/transactions?accountId=${encodeURIComponent(account.ACCOUNT_ID)}`)).filter(t=>t.type==='INTERNAL_TRANSFER').map(t=>({...t,accountNumber:account.ACCOUNT_NUMBER}))))).flat():[];
  if(getSession()?.user?.userId!==actor)return '';
  transferHistory.sort((a,b)=>b.transactionId-a.transactionId);
  const internalForm=canTransfer&&sourceAccounts.length
    ?`<form id="customer-internal-payment-form"><div class="grid2">${select('From account','sourceAccountId',accountChoices)}${field('Recipient account number','targetAccountNumber','text',{maxlength:20,placeholder:'Enter the other user’s bank account number'})}${field('Amount (INR)','amount','number',{min:'0.01',step:'0.01',placeholder:'0.00'})}</div><p class="hint">Use the account number shown in the recipient’s My accounts tab. Transfers between accounts in this demo bank update both balances immediately.</p><p class="form-error" role="alert"></p><button type="submit" class="btn primary">Review transfer</button></form>`
    :`<div class="empty"><p>${!canTransfer?'Your sign-in can view transfers but cannot send money.':'You need an active INR savings or current account that you can operate.'}</p></div>`;
  const transferRows=transferHistory.map(t=>[esc(t.transactionId),esc(t.accountNumber),t.accountRole==='SOURCE'?'Sent':'Received',money(t.amount),badge(t.status==='POSTED'?'Completed':t.status),esc(formatDate(t.createdAt))]);
  const recipientChoices=activeRecipients.map(b=>({value:b.BENEFICIARY_ID,label:`${b.DISPLAY_NAME} · ${b.BANK_CODE}`}));
  const form=!availability.initiationEnabled
    ?'<div class="empty"><h3>External payment simulation is unavailable</h3><p>You can still transfer between accounts in this demo bank above and review existing external requests below.</p></div>'
    :canCreate&&sourceAccounts.length&&activeRecipients.length
    ?`<form id="customer-payment-form"><div class="grid2">${select('From account','sourceAccountId',accountChoices)}${select('Verified recipient','beneficiaryId',recipientChoices)}${select('Payment method','railCode',['UPI','IMPS','NEFT','RTGS'])}${field('Amount (INR)','amount','number',{min:'0.01',step:'0.01',placeholder:'0.00'})}</div><p class="hint">UPI maximum: INR 1,00,000. IMPS maximum: INR 5,00,000. IMPS and NEFT above INR 1,00,000 require a checker. RTGS minimum: INR 2,00,000; checker approval above INR 10,00,000. All payments require funds, eligibility, routing and liquidity checks.</p><p class="form-error" role="alert"></p><button type="submit" class="btn primary">Review payment request</button></form>`
    :`<div class="empty"><h3>Payment setup needed</h3><p>${!canCreate?'Your sign-in can view payments but cannot create them.':''} ${canCreate&&!sourceAccounts.length?'You need an active INR savings or current account that you can operate.':''} ${canCreate&&!activeRecipients.length?'Add a recipient and wait for independent verification before sending.':''}</p></div>`;
  const recipientRows=beneficiaries.map(b=>[esc(b.DISPLAY_NAME),esc(b.BANK_CODE),badge(b.STATUS),esc(formatDate(b.CREATED_AT))]);
  const paymentRows=payments.map(p=>[esc(p.paymentId),money(p.amount),esc(p.railCode),badge(p.status)+'<br><small>'+esc(paymentStatus(p.status))+'</small>',`<button class="btn small" type="button" data-customer-payment-history="${esc(p.paymentId)}">View progress</button>`]);
  return `<section class="panel"><div class="panel-heading"><h3>Send to another bank account</h3></div><div class="panel-body">${internalForm}</div></section>`+
    (canReadTransfers?`<section class="panel"><div class="panel-heading"><h3>Sent and received transfers</h3><button class="btn small" type="button" id="customer-refresh-transfers">Refresh</button></div><div class="panel-body">${table(['Reference','Your account','Direction','Amount','Status','Time'],transferRows,'No transfers yet',{mobileCards:true})}</div></section>`:'')+
    `<section class="panel"><div class="panel-heading"><h3>External payment simulation</h3></div><div class="panel-body">`+
    (availability.initiationEnabled?`<div class="notice"><strong>Local payment simulation.</strong> Eligible payments process automatically; payments above the approval threshold enter the checker queue. Interbank settlement follows the rail demo cycle or individual RTGS settlement. Use the account transfer above to send money to another demo user.</div>`:'')+`${form}</div></section>`+
    `<section class="panel"><div class="panel-heading"><h3>Recipients</h3>${canCreate?'<button class="btn small" type="button" id="customer-add-beneficiary">Add recipient</button>':''}</div><div class="panel-body"><p class="hint">A different authorized employee must verify each recipient before you can use it.</p>${table(['Recipient','Bank code','Status','Added'],recipientRows,'No recipients yet')}</div></section>`+
    `<section class="panel"><div class="panel-heading"><h3>Payment requests</h3><button class="btn small" type="button" id="customer-refresh-payments">Refresh</button></div><div class="panel-body">${table(['Reference','Amount','Method','Progress','Action'],paymentRows,'No payment requests yet')}</div></section>`;
}

export function bindCustomerPayments(render){
  const internalForm=document.querySelector('#customer-internal-payment-form');
  if(internalForm)internalForm.onsubmit=event=>{
    event.preventDefault();
    const data=new FormData(internalForm),amount=String(data.get('amount')),target=String(data.get('targetAccountNumber')).trim();
    const error=internalForm.querySelector('.form-error');error.textContent='';
    if(!Number.isFinite(Number(amount))||Number(amount)<0.01||!/^\d+(?:\.\d{1,2})?$/.test(amount))return void(error.textContent='Enter a positive amount with at most two decimal places.');
    const account=accounts.find(a=>String(a.ACCOUNT_ID)===data.get('sourceAccountId'));
    if(!account||!eligible(account))return void(error.textContent='Refresh this page and choose an eligible account.');
    if(!target||target===account.ACCOUNT_NUMBER)return void(error.textContent='Enter a different recipient account number.');
    // Keep the same key while confirmation is open, including a retry after a lost response.
    const body={sourceAccountNumber:account.ACCOUNT_NUMBER,targetAccountNumber:target,amount,requestKey:crypto.randomUUID(),correlationId:crypto.randomUUID()};
    dialog('Confirm account transfer',`<p>From <strong>${esc(account.ACCOUNT_NUMBER)}</strong> to <strong>${esc(target)}</strong>.</p><p>Amount: <strong>${money(amount)}</strong></p><p>Check the recipient account number carefully. Confirming debits your account and credits the recipient immediately.</p>`,async()=>{
      const transfer=await api('/transactions/transfers/by-number',{method:'POST',body});
      toast(`Transfer ${transfer.transactionId} completed. Both account balances have been updated.`);
      await render();
    });
  };
  const form=document.querySelector('#customer-payment-form');
  if(form)form.onsubmit=event=>{
    event.preventDefault();
    const data=new FormData(form),amount=Number(data.get('amount')),rail=String(data.get('railCode'));
    const error=form.querySelector('.form-error');error.textContent='';
    if(!Number.isFinite(amount)||amount<0.01||!/^\d+(?:\.\d{1,2})?$/.test(String(data.get('amount'))))return void(error.textContent='Enter a positive amount with at most two decimal places.');
    if(rail==='RTGS'&&amount<200000||rail==='UPI'&&amount>100000||rail==='IMPS'&&amount>500000)return void(error.textContent='Amount is outside the simulated limit for this payment method.');
    const account=accounts.find(a=>String(a.ACCOUNT_ID)===data.get('sourceAccountId'));
    const recipient=beneficiaries.find(b=>b.BENEFICIARY_ID===data.get('beneficiaryId'));
    if(!account||!eligible(account)||!recipient||recipient.STATUS!=='ACTIVE')return void(error.textContent='Refresh this page and choose an eligible account and verified recipient.');
    const body={sourceAccountId:Number(account.ACCOUNT_ID),beneficiaryId:recipient.BENEFICIARY_ID,railCode:rail,amount:String(data.get('amount')),requestKey:crypto.randomUUID()};
    dialog('Confirm simulated payment',`<p>From <strong>${esc(account.ACCOUNT_NUMBER)}</strong> to <strong>${esc(recipient.DISPLAY_NAME)}</strong> (${esc(recipient.BANK_CODE)}).</p><p>Amount: <strong>${money(body.amount)}</strong> · Method: ${esc(rail)}</p><div class="notice">Eligible payments process automatically, including a customer debit. Payments above the rail approval threshold await an independent checker before processing. This is a simulated external payment.</div>`,async()=>{
      const payment=await api('/payments/initiate',{method:'POST',body});
      toast(`Payment request ${payment.paymentId} created. Follow its progress below.`);
      await render();
    });
  };
  const add=document.querySelector('#customer-add-beneficiary');
  if(add)add.onclick=()=>dialog('Add recipient',`<p>This recipient will remain pending until an independent employee verifies it.</p><div class="grid2">${field('Recipient name','displayName','text',{maxlength:100})}${field('Account number','accountToken','text',{maxlength:120,minlength:4})}${field('Bank code','bankCode','text',{maxlength:11,placeholder:'ABCD0123456'})}</div>`,async data=>{
    await api('/beneficiaries',{method:'POST',body:{displayName:String(data.get('displayName')).trim(),accountToken:String(data.get('accountToken')).trim(),bankCode:String(data.get('bankCode')).trim().toUpperCase()}});
    toast('Recipient added and awaiting independent verification.');await render();
  });
  document.querySelectorAll('#customer-refresh-payments,#customer-refresh-transfers').forEach(refresh=>{
    refresh.onclick=async()=>{refresh.disabled=true;try{await render();}catch(error){toast(errorText(error),true);refresh.disabled=false;}};
  });
  document.querySelectorAll('[data-customer-payment-history]').forEach(button=>button.onclick=async()=>{
    button.disabled=true;
    try{
      const id=button.dataset.customerPaymentHistory;
      const [payment,history]=await Promise.all([api(`/payments/${encodeURIComponent(id)}`),api(`/payments/${encodeURIComponent(id)}/history`)]);
      dialog(`Payment ${id}`,`<p>${money(payment.amount)} · ${esc(payment.railCode)} · ${esc(paymentStatus(payment.status))}</p>`+table(['Time','Progress','Reason'],history.map(h=>[esc(formatDate(h.CHANGED_AT)),esc(paymentStatus(h.TO_STATUS)),esc(h.REASON_CODE||'—')]),'No progress recorded'));
    }catch(error){toast(errorText(error),true);}finally{button.disabled=false;}
  });
}
