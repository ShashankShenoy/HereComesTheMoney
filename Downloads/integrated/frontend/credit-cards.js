import {api,getSession} from './api.js';
import {esc,field,select,table,badge,dialog,reason,toast,errorText} from './ui.js';
import {formatAmount,formatDate} from './presentation.js';

let products=[],cards=[],applications=[],selectedId='',renderApp,actorId;
const can=permission=>getSession()?.user?.permissions?.includes(permission);
const customer=()=>getSession()?.user?.userType==='CUSTOMER';
const money=value=>esc(formatAmount(value,'INR'));
const button=(label,action,id='',primary=false)=>`<button type="button" class="btn ${primary?'primary':'small'}" data-cc-action="${esc(action)}" data-cc-id="${esc(id)}">${esc(label)}</button>`;
const panel=(title,body,actions='')=>`<section class="panel"><div class="panel-heading"><h3>${esc(title)}</h3>${actions}</div><div class="panel-body">${body}</div></section>`;
const productTerms=p=>`<p>${esc(p.DESCRIPTION)}</p><dl class="cc-terms"><div><dt>Credit limit range</dt><dd>${money(p.MINIMUM_LIMIT)} – ${money(p.MAXIMUM_LIMIT)}</dd></div><div><dt>Annual interest</dt><dd>${esc(p.ANNUAL_RATE_PCT)}%</dd></div><div><dt>Billing date</dt><dd>Day ${esc(p.BILLING_DAY)} every month</dd></div><div><dt>Payment due</dt><dd>${esc(p.PAYMENT_DUE_DAYS)} days after billing</dd></div><div><dt>Minimum payment</dt><dd>${esc(p.MIN_PAYMENT_PCT)}% or ${money(p.MIN_PAYMENT_FLOOR)}, capped at the balance</dd></div></dl><div class="notice">${esc(p.INTEREST_POLICY)}</div>`;

export async function creditCardsScreen(render) {
    renderApp=render;
    const currentActor=getSession().user.userId;
    if(actorId!==currentActor){actorId=currentActor;selectedId='';products=[];cards=[];applications=[];}
    const loaded=await Promise.all([api('/credit-cards/products'),api('/credit-cards'),api('/credit-cards/applications')]);
    if(getSession()?.user?.userId!==currentActor)return '';
    [products,cards,applications]=loaded;
    if(!cards.some(c=>c.CARD_ID===selectedId))selectedId=cards[0]?.CARD_ID||'';
    const current=cards.find(c=>c.CARD_ID===selectedId);
    const productRows=products.map(p=>[
        `<strong>${esc(p.PRODUCT_NAME)}</strong><br><small>${esc(p.PRODUCT_CODE)} · Version ${esc(p.VERSION_NO)}</small>`,badge(p.STATUS),money(p.MINIMUM_LIMIT)+' – '+money(p.MAXIMUM_LIMIT),esc(p.ANNUAL_RATE_PCT)+'%',
        button('View terms','terms',p.PRODUCT_ID)+(p.STATUS==='APPROVED'&&can('CC_APPLY')?button('Apply','apply',p.PRODUCT_ID):'')+
        (p.STATUS==='PENDING'&&can('CC_PRODUCT_APPROVE')&&p.MAKER_ID!==actorId?button('Review','product-decision',p.PRODUCT_ID):'')+
        (p.STATUS==='APPROVED'&&can('CC_PRODUCT_MANAGE')?button('Retire','retire',p.PRODUCT_ID):'')]);
    const appRows=applications.map(a=>[
        esc(a.PRODUCT_NAME),customer()?money(a.REQUESTED_LIMIT):esc(a.CIF_ID),badge(a.STATUS),a.APPROVED_LIMIT?money(a.APPROVED_LIMIT):'—',
        a.STATUS==='PENDING'?(can('CC_APPROVE')&&a.MAKER_ID!==actorId?button('Review application','application-decision',a.APPLICATION_ID):'')+(can('CC_APPLY')?button('Cancel','cancel',a.APPLICATION_ID):''):'—']);
    const chooser=cards.length?`<div class="cc-card-list">${cards.map(c=>`<button type="button" class="cc-card-choice ${c.CARD_ID===selectedId?'selected':''}" data-cc-action="select" data-cc-id="${esc(c.CARD_ID)}" aria-pressed="${c.CARD_ID===selectedId}"><span>${esc(c.PRODUCT_NAME)}</span><strong>SIM •••• ${esc(c.DISPLAY_ENDING)}</strong><span>${esc(c.STATUS)}${customer()?'':' · '+esc(c.CIF_ID)}</span></button>`).join('')}</div>`:'<div class="empty"><h3>No credit cards yet</h3><p>Choose an approved product below to apply.</p></div>';
    let detail='';
    if(current){
        const c=current,id=c.CARD_ID;
        const stats=[['Credit limit',c.CREDIT_LIMIT],['Available credit',c.AVAILABLE_CREDIT],['Outstanding balance',c.OUTSTANDING_BALANCE],['Unbilled interest',c.UNBILLED_INTEREST],['Payoff amount',c.PAYOFF_AMOUNT],['Overdue minimum',c.OVERDUE_MINIMUM]];
        const actions=[button('Agreed terms','card-terms',id),button('Transactions','transactions',id),button('Statements','statements',id),button('Reconcile balance','reconcile',id)];
        if(can('CC_MANAGE')&&c.STATUS!=='CLOSED'){
            if(c.STATUS==='ISSUED')actions.push(button('Activate','ACTIVATE',id,true));
            if(c.STATUS==='ACTIVE')actions.push(button('Freeze','FREEZE',id));
            if(c.STATUS==='FROZEN')actions.push(button('Unfreeze','UNFREEZE',id));
            if(can('CC_SERVICE')&&!['BLOCKED','CLOSED'].includes(c.STATUS))actions.push(button('Permanently block','BLOCK',id));
            actions.push(button('Close card','CLOSE',id));
            if(c.BILLING_REQUIRED)actions.push(button('Generate due statement','bill',id,true));
        }
        if(can('CC_SPEND')&&c.STATUS==='ACTIVE'&&!c.BILLING_REQUIRED)actions.push(button('Simulate purchase','purchase',id,true));
        if(can('CC_REPAY')&&Number(c.PAYOFF_AMOUNT)>0&&c.STATUS!=='CLOSED'&&!c.BILLING_REQUIRED)actions.push(button('Repay','repay',id,true));
        detail=panel('Card ending '+c.DISPLAY_ENDING,`<p>${badge(c.STATUS)} &nbsp; Next statement: ${esc(formatDate(c.NEXT_STATEMENT_DATE))} &nbsp; Expires: ${esc(formatDate(c.EXPIRY_DATE))}</p>${c.BILLING_REQUIRED?'<div class="notice">Billing is due. Generate each overdue statement in order before making a purchase, repayment or refund. The payoff shown is through the next statement cutoff.</div>':''}<div class="stats">${stats.map(([label,value])=>`<div class="stat"><div class="stat-title">${esc(label)}</div><div class="stat-value amount-value">${money(value)}</div></div>`).join('')}</div><div class="form-actions cc-actions">${actions.join('')}</div>`);
    }
    return `<div class="notice">Local card simulation · Purchases post immediately to the bank's simulated merchant settlement account. Card references and displayed digits are synthetic.</div>`+
        panel(customer()?'Your credit cards':'Credit card accounts',chooser,button('Refresh','refresh'))+detail+
        panel('Credit card products',table(['Product','Status','Credit limit range','APR','Actions'],productRows,'No credit card products available'),can('CC_PRODUCT_MANAGE')?button('Propose product','create-product','',true):'')+
        panel('Applications',table(['Product',customer()?'Requested limit':'Customer CIF','Status','Approved limit','Actions'],appRows,'No applications'));
}

function commandDialog(title,body,path,makeBody){
    const requestKey=crypto.randomUUID();
    dialog(title,body,async data=>{
        await api(path,{method:'POST',body:{requestKey,...makeBody(data)}});
        toast('Credit card operation completed.');await renderApp();
    });
}
const amountField=(label,value)=>field(label,'amount','number',{min:'0.01',step:'0.01',value});
function reviewApplication(a){
    dialog('Application review',`<p>Customer: ${esc(a.CIF_ID)} · Requested limit: ${money(a.REQUESTED_LIMIT)}</p><p>Check the applicant's eligibility and affordability before approving. Your credit limit authority is enforced by the bank.</p>`+
        select('Decision','decision',['APPROVED','REJECTED'])+field('Approved limit (leave empty to reject)','approvedLimit','number',{value:a.REQUESTED_LIMIT,min:1000,step:'0.01',required:false})+reason(),
        (()=>{const requestKey=crypto.randomUUID();return async data=>{
            const approved=data.get('decision')==='APPROVED';
            await api(`/credit-cards/applications/${a.APPLICATION_ID}/decision`,{method:'POST',body:{requestKey,rowVersion:a.ROW_VERSION,decision:data.get('decision'),approvedLimit:approved?data.get('approvedLimit'):null,reason:data.get('reason')}});
            toast(approved?'Application approved. The card is ready to activate.':'Application rejected.');await renderApp();
        };})());
}

async function act(action,id){
    const p=products.find(p=>p.PRODUCT_ID===id),c=cards.find(c=>c.CARD_ID===id),a=applications.find(a=>a.APPLICATION_ID===id);
    if(action==='refresh')return renderApp();
    if(action==='select'){selectedId=id;return renderApp();}
    if(action==='terms')return dialog(p.PRODUCT_NAME,productTerms(p));
    if(action==='card-terms')return dialog(c.PRODUCT_NAME+' · Agreed terms',productTerms(c.TERMS));
    if(action==='create-product')return commandDialog('Propose credit card terms',`<div class="grid2">`+
        field('Product code','productCode','text',{value:'MB_CLASSIC',maxlength:30})+field('Product name','productName','text',{maxlength:120})+
        field('Version','versionNumber','number',{value:1,min:1})+field('Minimum limit (INR)','minimumLimit','number',{value:10000,min:1000,step:'0.01'})+
        field('Maximum limit (INR)','maximumLimit','number',{value:200000,min:1000,step:'0.01'})+field('Annual interest (%)','annualRatePct','number',{value:24,min:0,step:'0.01'})+
        field('Minimum payment (%)','minimumPaymentPct','number',{value:5,min:1,step:'0.01'})+field('Minimum payment floor (INR)','minimumPaymentFloor','number',{value:200,min:0,step:'0.01'})+
        field('Billing day (1–28)','billingDay','number',{value:1,min:1})+field('Payment due after (7–25 days)','paymentDueDays','number',{value:20,min:7})+
        field('Description','description','text',{maxlength:1000})+`</div><div class="notice">Simple ACT/365 interest starts from purchase. No purchase grace period, compounding, annual fees or late fees. Another employee must approve these terms.</div>`,
        '/credit-cards/products',data=>{const value=Object.fromEntries(data);for(const key of ['versionNumber','billingDay','paymentDueDays'])value[key]=Number(value[key]);return value;});
    if(action==='product-decision')return commandDialog('Review '+p.PRODUCT_NAME,productTerms(p)+select('Decision','decision',['APPROVED','REJECTED'])+reason(),`/credit-cards/products/${id}/decision`,data=>({rowVersion:p.ROW_VERSION,decision:data.get('decision'),reason:data.get('reason')}));
    if(action==='retire')return commandDialog('Retire product',`<p>Stop new applications for ${esc(p.PRODUCT_NAME)} version ${esc(p.VERSION_NO)}. Existing cards keep their agreed terms.</p>`+reason(),`/credit-cards/products/${id}/retire`,data=>({rowVersion:p.ROW_VERSION,reason:data.get('reason')}));
    if(action==='apply'){
        const accounts=(await api('/banking/accounts')).filter(a=>a.ACCOUNT_STATUS==='ACTIVE');
        if(!accounts.length)return dialog('Account required','<p>Open an eligible savings/current account before applying for a credit card.</p>');
        return commandDialog('Apply for '+p.PRODUCT_NAME,productTerms(p)+select('Repayment account','repaymentAccountId',accounts.map(a=>({value:String(a.ACCOUNT_ID),label:a.ACCOUNT_NUMBER+(customer()?'':' · '+a.PRIMARY_CIF_ID)})))+
            field('Requested credit limit (INR)','requestedLimit','number',{value:p.MINIMUM_LIMIT,min:p.MINIMUM_LIMIT,step:'0.01'})+
            '<label class="cc-consent"><input name="termsAccepted" type="checkbox" required> I have read and accept this product version, interest policy and repayment terms.</label>',
            '/credit-cards/applications',data=>{const account=accounts.find(a=>String(a.ACCOUNT_ID)===data.get('repaymentAccountId'));return {productId:id,cifId:account.PRIMARY_CIF_ID,repaymentAccountId:Number(data.get('repaymentAccountId')),requestedLimit:data.get('requestedLimit'),termsAccepted:data.get('termsAccepted')==='on'};});
    }
    if(action==='application-decision')return reviewApplication(a);
    if(action==='cancel')return commandDialog('Cancel application',reason(),`/credit-cards/applications/${id}/cancel`,data=>({rowVersion:a.ROW_VERSION,reason:data.get('reason')}));
    if(['ACTIVATE','FREEZE','UNFREEZE','BLOCK','CLOSE'].includes(action))return commandDialog(action==='BLOCK'?'Permanently block card':action.charAt(0)+action.slice(1).toLowerCase()+' card',`<p>Card ending ${esc(c.DISPLAY_ENDING)}${action==='CLOSE'?'. All principal and chargeable interest must be repaid.':'.'}</p>`+reason(),`/credit-cards/${id}/controls`,data=>({rowVersion:c.ROW_VERSION,action,reason:data.get('reason')}));
    if(action==='purchase')return commandDialog('Simulate a card purchase',`<p>Available credit: ${money(c.AVAILABLE_CREDIT)}. This posts a local simulated purchase immediately.</p>`+amountField('Purchase amount (INR)','')+field('Merchant name','merchantName','text',{maxlength:120}),`/credit-cards/${id}/purchases`,data=>({amount:data.get('amount'),merchantName:data.get('merchantName')}));
    if(action==='repay')return commandDialog('Repay credit card',`<p>Payoff amount: ${money(c.PAYOFF_AMOUNT)}. Your agreed repayment account will be debited. Accrued interest is collected before principal.</p>`+amountField('Repayment amount (INR)',c.PAYOFF_AMOUNT),`/credit-cards/${id}/repayments`,data=>({amount:data.get('amount')}));
    if(action==='bill')return commandDialog('Generate credit card statement',`<p>Generate the statement dated ${esc(formatDate(c.NEXT_STATEMENT_DATE))}. The balance and transactions become an immutable billing record.</p>`,`/credit-cards/${id}/statements`,()=>({statementDate:c.NEXT_STATEMENT_DATE.slice(0,10)}));
    if(action==='transactions')return showTransactions(id,0);
    if(action==='statements'){
        const statements=await api(`/credit-cards/${id}/statements`);
        dialog('Credit card statements',table(['Statement date','Due date','Closing balance','Minimum due','Unpaid minimum',''],statements.map(s=>[esc(formatDate(s.STATEMENT_DATE)),esc(formatDate(s.DUE_DATE)),money(s.CLOSING_BALANCE),money(s.MINIMUM_DUE),money(s.REMAINING_MINIMUM_DUE),button('Read statement','read-statement',id+'|'+s.STATEMENT_ID)]),'No statements issued yet'));
        return bindCreditCards(renderApp);
    }
    if(action==='read-statement'){
        const [card,statementId]=id.split('|'),s=await api(`/credit-cards/${card}/statements/${statementId}`);
        dialog('Statement · '+formatDate(s.STATEMENT_DATE),`<p>Due: ${esc(formatDate(s.DUE_DATE))}</p>`+table(['Opening balance','Purchases','Interest','Repayments','Refunds','Closing balance'],[[s.OPENING_BALANCE,s.PURCHASE_AMOUNT,s.INTEREST_AMOUNT,s.REPAYMENT_AMOUNT,s.REFUND_AMOUNT,s.CLOSING_BALANCE].map(money)])+`<p>Minimum due: ${money(s.MINIMUM_DUE)} · Remaining: ${money(s.REMAINING_MINIMUM_DUE)}</p>`+entryTable(s.ENTRIES,false,card));return;
    }
    if(action==='more-transactions'){const [card,after]=id.split('|');return showTransactions(card,Number(after));}
    if(action==='refund'){
        const [card,entry]=id.split('|');return commandDialog('Refund simulated purchase',`<p>Refund the full purchase once. Any amount exceeding outstanding principal is credited to the linked bank account. Accrued interest is not waived.</p>`+reason(),`/credit-cards/${card}/purchases/${entry}/refund`,data=>({reason:data.get('reason')}));
    }
    if(action==='reconcile'){
        const r=await api(`/credit-cards/${id}/reconciliation`);return dialog('Card reconciliation',`<p>${badge(r.matched?'MATCHED':'MISMATCH')}</p>`+table(['Principal','Interest','Ledger receivable'],[[money(r.principal),money(r.interest),money(r.ledgerReceivable)]]));
    }
}

function entryTable(entries,refunds,card){return table(['Date','Type','Description','Amount','Deposit credit',''],entries.map(e=>[esc(formatDate(e.BUSINESS_DATE)),badge(e.ENTRY_TYPE),esc(e.DESCRIPTION),money(e.AMOUNT),money(e.DEPOSIT_CREDIT),refunds&&e.ENTRY_TYPE==='PURCHASE'&&can('CC_SERVICE')?button('Refund','refund',card+'|'+e.ENTRY_ID):'']),'No card transactions');}
async function showTransactions(id,after){
    const rows=await api(`/credit-cards/${id}/transactions?afterEntry=${after}&limit=50`);
    dialog('Credit card transactions',entryTable(rows,true,id)+(rows.length===50?button('Next 50 transactions','more-transactions',id+'|'+rows.at(-1).ENTRY_ID):''));bindCreditCards(renderApp);
}
export function bindCreditCards(render){
    renderApp=render;
    document.querySelectorAll('[data-cc-action]').forEach(b=>b.onclick=async()=>{
        b.disabled=true;
        try{await act(b.dataset.ccAction,b.dataset.ccId);}catch(error){toast(errorText(error),true);}finally{b.disabled=false;}
    });
}
