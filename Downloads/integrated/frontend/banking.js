import {api,getSession,getCustomerHash,setCustomerHash} from './api.js';
import {esc,table,field,select,badge,errorText,toast,dialog} from './ui.js';
import {enhanceForms,validateJet,jetReady} from './jet.js';
import {formatAmount,formatDecimal,formatDate,fieldKind,rowCurrency} from './presentation.js';
import {tField} from './i18n.js';
export const bankingPages={
 banking:{title:'Bank overview',permission:null,icon:'▦',intro:'Customer relationships, money movement, and daily operations in one workspace.',prefix:[]},
 accounts:{title:'Accounts',permission:'ACCOUNT_READ',icon:'▤',intro:'Open accounts, manage holders and nominees, apply controls, and review closure requests.',prefix:['/accounts','/banking/accounts']},
 transactions:{title:'Transactions & ledger',permission:'TXN_READ',icon:'⇄',intro:'Review account transactions, status history and journals; post authorized transfers and reconcile the ledger.',prefix:['/transactions','/banking/transactions','/journals','/gl/','/banking/gl-accounts','/period-closes','/fees','/reconciliation/']},
 teller:{title:'Teller cash',permission:'TELLER_OPERATE',icon:'▣',intro:'Open your cash till, accept deposits, make withdrawals, and balance at close.',prefix:['/teller']},
 beneficiaries:{title:'Beneficiaries',permission:'PAYMENT_CREATE',icon:'♧',intro:'Register payment recipients and complete independent verification.',prefix:['/beneficiaries']},
 payments:{title:'Payments & clearing',permission:'PAYMENT_READ',icon:'↗',intro:'Initiate payments and follow simulated UPI, IMPS, NEFT, and RTGS processing.',prefix:['/payments','/dispatches','/clearing-batches','/reconciliation-exceptions','/approvals','/rail-messages']},
 treasury:{title:'Treasury & RBI ledger',permission:'TREASURY_RECONCILE',icon:'◈',intro:'Review the simulated local RBI reserve ledger, liquidity holds, settlement cycles, and reconciliation.',prefix:['/treasury/']},
 loans:{title:'Loans',permission:'LOAN_READ',icon:'⌂',intro:'Origination, assessment, sanction, offers, documentation, and servicing.',prefix:['/loans','/banking/facilities']},
 statements:{title:'Statements & documents',permission:'STATEMENT_READ',icon:'▧',intro:'Create date-range statements from the posted ledger and download PDF or CSV records.',prefix:['/reporting','/statements','/statement-','/catalog']},
 privacy:{title:'Privacy & compliance',permission:'PRIVACY_CONSENT_VIEW',icon:'◇',intro:'Manage processing purposes, consent, legal holds, privacy cases, and audit evidence.',prefix:['/privacy']},
 currency:{title:'Currency & rates',permission:'FX_READ',icon:'◎',intro:'Supported currencies, approved rates, explicit expiry, and indicative conversion.',prefix:['/currencies','/fx']},
 customerAccess:{title:'Account-holder access key',permission:null,icon:'⌑',intro:'Find out how to use or replace the key for protected account details.',prefix:[]}
};
let renderApp, contract, contractLoad, operations=[], selected={}, results={}, active='banking';
let transactionAccountNumber='',transactionAccountId='',transactionLegacy=false,transactionRows=null,transactionDetail=null,transactionVisibleCount=25;
let transactionOperationsOpen=false;
let keyHolderAccountNumber='',keyHolders=null,accountOptions=[],accountOptionsFetchedAt=0,accountOptionsPending=null,accountOptionsActor=null;
let reserveAccountId='',reserveAccounts=[],reservePosition=null,reserveReconciliation=null,reserveLedger=null,reserveOffset=0;
const transactionReadOperations=new Set(['GET /banking/transactions','GET /banking/transactions/{id}/details','GET /transactions/{id}']);
function accountHints(id){return '<datalist id="'+id+'">'+accountOptions.map(a=>'<option value="'+esc(a.ACCOUNT_NUMBER)+'">'+esc(a.ACCOUNT_STATUS||'Account')+'</option>').join('')+'</datalist>';}
function resetAccountOptionsForActor(){
 const actor=getSession()?.user?.userId;
 if(actor===accountOptionsActor)return;
 accountOptionsActor=actor;accountOptions=[];accountOptionsFetchedAt=0;accountOptionsPending=null;
 transactionAccountNumber='';transactionAccountId='';transactionLegacy=false;transactionRows=null;transactionDetail=null;transactionVisibleCount=25;transactionOperationsOpen=false;
 keyHolderAccountNumber='';keyHolders=null;reserveAccountId='';reserveAccounts=[];reservePosition=null;reserveReconciliation=null;reserveLedger=null;reserveOffset=0;
 selected={};results={};
}
function loadAccountOptions(){
 resetAccountOptionsForActor();
 if(accountOptionsPending||Date.now()-accountOptionsFetchedAt<60000)return;
 const actor=accountOptionsActor;
 accountOptionsPending=api('/banking/accounts').then(accounts=>{
  if(actor!==accountOptionsActor)return;
  accountOptions=accounts;accountOptionsFetchedAt=Date.now();
  for(const id of ['bank-account-numbers','key-account-numbers']){const list=document.getElementById(id);if(list)list.innerHTML=accountOptions.map(a=>'<option value="'+esc(a.ACCOUNT_NUMBER)+'">'+esc(a.ACCOUNT_STATUS||'Account')+'</option>').join('');}
 }).catch(()=>{if(actor===accountOptionsActor)accountOptionsFetchedAt=Date.now();}).finally(()=>{if(actor===accountOptionsActor)accountOptionsPending=null;});
}
async function legacyAccountId(number){const account=await api('/accounts/by-number/'+encodeURIComponent(number));return String(account.id);}
const depositAccountFields=new Set(['accountId','bankAccountId','sourceAccountId','targetAccountId','destinationAccountId','repaymentAccountId','disbursementAccountId','depositAccountId']);
function accountNumberInput(name,prefix=''){
 if(depositAccountFields.has(name))return true;
 return !prefix&&name==='id'&&/^\/accounts\/\{id\}(?:\/|$)/.test(operations.find(o=>o.id===selected[active])?.path||'');
}
async function resolveAccountNumbers(value){
 if(Array.isArray(value))return Promise.all(value.map(resolveAccountNumbers));
 if(!value||typeof value!=='object')return value;
 const entries=await Promise.all(Object.entries(value).map(async ([key,item])=>[key,depositAccountFields.has(key)&&item!==null&&item!==undefined&&item!==''?await legacyAccountId(String(item).trim()):await resolveAccountNumbers(item)]));
 return Object.fromEntries(entries);
}
export function configureBanking(render){renderApp=render;}
const displayLabels={ACCOUNT_NUMBER:'Account number',ACCOUNT_STATUS:'Status',CURRENCY_CODE:'Currency',BRANCH_CODE:'Branch',PRIMARY_CIF_ID:'Primary CIF',ACCOUNT_ID:'Account ID',TXN_ID:'Transaction ID',PRODUCT_VERSION_ID:'Product version',primaryCifId:'Primary CIF ID',productId:'Product ID',productVersionId:'Product version ID',branchCode:'Branch code',operationMode:'Operation mode',requestId:'Request reference',requestKey:'Request reference'};
const label=v=>tField(displayLabels[v]||v.replace(/([a-z])([A-Z])/g,'$1 $2').replace(/[_-]/g,' ').replace(/\b\w/g,c=>c.toUpperCase()));
const allow=p=>!p||getSession()?.user?.permissions?.includes(p);
const customerWorkflows=new Set([
 'GET /banking/transactions','GET /transactions/{id}','POST /transactions/transfers',
 'GET /beneficiaries','POST /beneficiaries',
 'GET /payments','GET /payments/{id}','GET /payments/{id}/history','POST /payments/initiate',
 'GET /banking/facilities','GET /loans/applications/{id}','GET /loans/applications/{id}/offers',
 'GET /loans/facilities/{id}','GET /loans/facilities/{id}/schedule','POST /loans/offers/{id}/accept',
 'GET /reporting/statement-requests','GET /reporting/statement-requests/{id}',
 'POST /reporting/statement-requests','POST /reporting/statement-requests/{id}/cancel',
 'POST /reporting/statement-requests/{id}/process','GET /reporting/statements',
 'GET /reporting/statements/{id}','GET /reporting/statements/{id}/download',
 'GET /privacy/purposes','GET /privacy/purposes/{id}',
 'GET /privacy/consents/evaluations','GET /privacy/consents/history',
 'POST /privacy/consents/decisions','POST /privacy/consents/withdrawals',
 'GET /currencies','GET /fx/rates','GET /fx/quote'
]);
const workflowTitles={
 'get /accounts':'List customer accounts','post /accounts':'Open account','get /accounts/{id}':'Account details','get /accounts/by-number/{number}':'Find account by number','get /accounts/{id}/{collection}':'Account records',
 'post /accounts/{id}/activate':'Activate account','post /accounts/{id}/cancel-opening':'Cancel pending opening','post /accounts/{id}/parties':'Add account party','delete /accounts/{id}/parties/{partyId}':'End account party role','put /accounts/{id}/nominees':'Update nominees',
 'post /accounts/{id}/restrictions':'Add account restriction','post /accounts/{id}/restrictions/{restrictionId}/release':'Release account restriction','post /accounts/{id}/limits':'Set account limit','post /accounts/{id}/interest-overrides':'Set interest override','post /accounts/{id}/product-version-adoptions':'Adopt product version',
 'post /accounts/{id}/closures':'Request account closure','post /accounts/{id}/closures/{requestId}/approve':'Approve account closure','post /accounts/{id}/closures/{requestId}/reject':'Reject account closure','post /accounts/{id}/majority-reviews':'Start majority review','post /accounts/{id}/majority-reviews/decisions':'Decide majority review',
 'get /banking/accounts':'Account directory','get /banking/transactions':'Transactions','get /banking/facilities':'Loan facilities','get /banking/gl-accounts':'General ledger accounts',
 'get /teller/tills':'List teller tills','post /teller/tills':'Open a teller till','post /teller/tills/{id}/close':'Independently close a teller till','post /teller/cash':'Post a cash deposit or withdrawal',
 'get /beneficiaries':'List beneficiaries','post /beneficiaries':'Register a beneficiary','post /beneficiaries/{id}/verify':'Verify a beneficiary',
 'post /payments/initiate':'Initiate a beneficiary payment','post /payments/{id}/authorize-simulation':'Authorize simulated payment','post /payments/{id}/simulate-outcome':'Record simulated rail outcome',
 'get /treasury/reserve-accounts':'List reserve accounts','post /treasury/reserve-accounts':'Create a reserve account and zero position',
 'get /treasury/reserve-accounts/{id}/position':'Get reserve position','get /treasury/reserve-accounts/{id}/ledger':'List reserve ledger entries','get /treasury/reserve-accounts/{id}/reconciliation':'Get reserve reconciliation',
 'get /treasury/liquidity-holds':'List liquidity holds','post /treasury/liquidity-holds':'Reserve liquidity','post /treasury/liquidity-holds/{id}/decision':'Decide liquidity hold',
 'get /treasury/settlement-cycles':'List settlement cycles','post /treasury/settlement-cycles':'Open settlement cycle','get /treasury/settlement-cycles/{id}/items':'List settlement cycle items','post /treasury/settlement-cycles/{id}/items':'Add settlement cycle items','post /treasury/settlement-cycles/{id}/close':'Close settlement cycle',
 'post /treasury/settlement-evidence':'Record settlement evidence','post /treasury/reserve-movements/confirm':'Confirm reserve movement',
 'get /treasury/reconciliation-exceptions':'List reserve reconciliation exceptions','post /treasury/reconciliation-exceptions':'Open reserve reconciliation exception','post /treasury/reconciliation-exceptions/{id}/resolution':'Resolve reserve reconciliation exception',
 'get /treasury/work-items':'List treasury work items','post /treasury/work-items':'Create treasury work item','post /treasury/work-items/{id}/submit':'Submit treasury work item','post /treasury/work-items/{id}/decision':'Decide treasury work item',
 'get /currencies':'List supported currencies','post /currencies':'Add a supported currency','get /fx/rates':'Review exchange rates','post /fx/rates':'Propose an exchange rate','post /fx/rates/{id}/decision':'Decide an exchange rate','get /fx/quote':'Calculate an indicative quote','get /fx/provider/status':'Check rate provider status','post /fx/provider/refresh':'Request a provider rate',
 'get /loans/facilities/{id}/disbursements':'List facility disbursements','post /loans/facilities/{id}/disbursements':'Request loan disbursement','post /loans/disbursements/{id}/approve':'Approve loan disbursement','post /loans/facilities/{id}/accruals':'Accrue due loan interest','post /loans/facilities/{id}/repayments':'Apply a due loan repayment','get /loans/collections':'Review overdue installments'
};
async function loadContract(){
 if(contract)return;
 if(!contractLoad)contractLoad=(async()=>{
  const response=await fetch('/v3/api-docs');if(!response.ok)throw new Error('The workflow catalog is unavailable.');
  const loadedContract=await response.json(),loadedOperations=[];
  for(const [path,item]of Object.entries(loadedContract.paths)){
   if(!path.startsWith('/api/v1/')||path.includes('/internal/')||path.includes('/controls'))continue;
   for(const [method,op]of Object.entries(item)){
    if(!['get','post','put','patch','delete'].includes(method))continue;
    loadedOperations.push({...op,path:path.slice(7),method:method.toUpperCase(),id:method+path,title:workflowTitles[method+' '+path.slice(7)]||op.summary?.split(/[;:]/)[0]||label(path.split('/').slice(3).filter(x=>!x.startsWith('{')).join(' '))+' · '+({get:'View',post:'Create or action',put:'Update',patch:'Update',delete:'Remove'}[method])});
   }
  }
  operations=loadedOperations;
  contract=loadedContract;
 })().catch(error=>{contractLoad=null;throw error;});
 await contractLoad;
}
function resolve(s){if(!s)return {};if(s.$ref)return resolve(contract.components.schemas[s.$ref.split('/').pop()]);return s;}
const primaryFields=['accountNumber','ACCOUNT_NUMBER','facilityNumber','FACILITY_NUMBER','name','NAME','status','STATUS','ACCOUNT_STATUS','PAYMENT_STATUS','type','TYPE','amount','AMOUNT','currency','CURRENCY_CODE','BRANCH_CODE','branchCode','PRIMARY_CIF_ID','primaryCifId','createdAt','CREATED_AT','transactionId','TXN_ID','paymentId','PAYMENT_ID','accountId','ACCOUNT_ID'];
const internalField=k=>/(PASSWORD|SECRET|TOKEN|CIPHERTEXT|HASH|REQUESTKEY|CORRELATIONID)/i.test(k);
function renderValue(v,key='',row={},depth=0){
 if(v===null||v===undefined)return '<span class="muted">—</span>';
 if(typeof v==='boolean')return v?'Yes':'No';
 if(Array.isArray(v))return v.length?'<details><summary>'+v.length+' records</summary>'+renderRows(v)+'</details>':'—';
 if(typeof v==='object')return '<details><summary>View details</summary><dl class="details">'+Object.entries(v).filter(([k])=>!internalField(k)).map(([k,x])=>'<div><dt>'+esc(label(k))+'</dt><dd>'+renderValue(x,k,v,depth+1)+'</dd></div>').join('')+'</dl></details>';
 const kind=fieldKind(key);
 if(kind==='amount')return '<span class="amount-value">'+esc(formatAmount(v,rowCurrency(row,key)))+'</span>';
 if(kind==='rate')return '<span class="numeric-value">'+esc(formatDecimal(v))+'</span>';
 if(kind==='date')return esc(formatDate(v));
 if(kind==='status')return badge(v);
 return esc(v);
}
function renderRows(value){
 const data=value?.items||value?.content||value;
 if(data&&typeof data==='object'&&!Array.isArray(data)&&Object.keys(data).length===1&&('transactionId' in data||'TXN_ID' in data))return '<div class="notice">Only the transaction reference is visible here. Open <strong>Transaction activity</strong> above, choose an account, and use your signed-in customer access or an account-holder key to see financial details.</div><dl class="details result-details"><div><dt>Transaction ID</dt><dd>'+esc(data.transactionId??data.TXN_ID)+'</dd></div></dl>';
 if(!Array.isArray(data))return '<dl class="details result-details">'+Object.entries(data||{}).filter(([k])=>!internalField(k)).map(([k,v])=>'<div><dt>'+esc(label(k))+'</dt><dd>'+renderValue(v,k,data)+'</dd></div>').join('')+'</dl>';
 if(!data.length)return '<div class="empty">No records yet. Choose a workflow above to get started.</div>';
 if(typeof data[0]!=='object')return '<ul>'+data.map(v=>'<li>'+esc(v)+'</li>').join('')+'</ul>';
 const allKeys=[...new Set(data.flatMap(r=>Object.keys(r)))].filter(k=>!internalField(k));
 const ordered=[...primaryFields.filter(k=>allKeys.includes(k)),...allKeys.filter(k=>!primaryFields.includes(k))];
 const keys=ordered.slice(0,5),extra=ordered.slice(5);
 const rows=data.map(row=>[
  ...keys.map(k=>renderValue(row[k],k,row)),
  ...(extra.length?['<details class="record-extra"><summary>View details</summary><dl class="details">'+extra.map(k=>'<div><dt>'+esc(label(k))+'</dt><dd>'+renderValue(row[k],k,row)+'</dd></div>').join('')+'</dl></details>']:[])
 ]);
 return '<div class="result-count">'+data.length+' '+(data.length===1?'record':'records')+' shown</div>'+table([...keys.map(label),...(extra.length?['Additional details']:[])],rows);
}
function transactionPanel(){
 const bankAdmin=getSession().user.roles?.includes('BANK_ADMIN');
 const hints=accountHints('bank-account-numbers');
 const visibleRows=transactionRows?.slice(0,transactionVisibleCount)||[];
 const rows=transactionRows===null?'<div class="empty">Choose an account to load its transactions.</div>':transactionRows.length?table(['Transaction','Type','Amount','Status','Value date','Details'],visibleRows.map(t=>[
  esc(t.transactionId),esc(t.type??'—'),t.amount==null?'Protected':esc(formatAmount(t.amount,t.currency)),badge(t.status),esc(formatDate(t.valueDate)),
  t.amount==null?'<span class="muted">Details protected</span>':'<button type="button" class="btn small" data-txn-detail="'+esc(t.transactionId)+'">View details</button>'
 ]))+'<div class="form-actions"><span class="muted">Showing '+visibleRows.length+' of '+transactionRows.length+' recent transactions</span>'+(visibleRows.length<transactionRows.length?'<button class="btn small" type="button" data-txn-more>Show more</button>':'')+'</div>':'<div class="empty">No transactions found for this account.</div>';
 const detail=transactionDetail?'<section class="panel"><div class="panel-heading"><div><p class="eyebrow">'+(transactionDetail.accountRole==='INTERNAL'?'INTERNAL LEDGER DETAIL':'ACCOUNT-SCOPED DETAIL')+'</p><h3>Transaction '+esc(transactionDetail.transactionId)+'</h3></div>'+badge(transactionDetail.STATUS)+'</div><div class="panel-body"><dl class="details result-details">'+[
  ['Account role',transactionDetail.accountRole],['Type',transactionDetail.TXN_TYPE],['Amount',formatAmount(transactionDetail.AMOUNT,transactionDetail.CURRENCY_CODE)],['Value date',formatDate(transactionDetail.VALUE_DATE)],['Created',formatDate(transactionDetail.CREATED_AT)],['Updated',formatDate(transactionDetail.UPDATED_AT)],['Failure code',transactionDetail.FAILURE_CODE],['Reversal of',transactionDetail.REVERSAL_OF_TXN_ID]
 ].map(([k,v])=>'<div><dt>'+esc(k)+'</dt><dd>'+esc(v??'—')+'</dd></div>').join('')+'</dl><h4>Status history</h4>'+table(['From','To','Reason','Changed'],(transactionDetail.statusHistory||[]).map(h=>[esc(h.FROM_STATUS??'Start'),badge(h.TO_STATUS),esc(h.REASON_TEXT||h.REASON_CODE||'—'),esc(formatDate(h.CHANGED_AT))]),'No status history recorded.')+'<h4>Linked journals</h4>'+(transactionDetail.journals||[]).map(j=>'<div class="ledger-journal"><div><strong>Journal '+esc(j.JOURNAL_ID)+'</strong> · '+esc(j.JOURNAL_TYPE)+' · '+esc(formatDate(j.BOOKED_AT))+' · '+(j.control?.IS_BALANCED==='Y'?'Balanced':'Check balance')+'</div><p class="hint">Debits '+esc(formatAmount(j.control?.DEBIT_TOTAL,'INR'))+' · Credits '+esc(formatAmount(j.control?.CREDIT_TOTAL,'INR'))+'</p>'+table(['Line','GL account','Side','Amount'],(j.accountPostings||[]).map(p=>[esc(p.LINE_NO),esc(p.GL_ACCOUNT_ID),badge(p.ENTRY_SIDE),esc(formatAmount(p.AMOUNT,'INR'))]),'No posting line belongs to the selected account.')+'</div>').join('')+'</div></section>':'';
 const internal=allow('GL_RECONCILE')?'<details class="ledger-internal"><summary>Look up an internal ledger transaction</summary><p class="hint">For a transaction with no customer account, such as a reserve opening or a bank adjustment. For account transactions, use the list above.</p><form id="internal-transaction-form" class="module-search"><label class="field">Transaction ID<input name="transactionId" inputmode="numeric" pattern="[0-9]+" required placeholder="Enter a transaction ID"></label><button type="submit" class="btn">View internal transaction</button></form><p id="internal-transaction-error" class="form-error" role="alert"></p></details>':'';
 return '<div id="transaction-activity"><section class="panel"><div class="panel-heading"><div><p class="eyebrow">TRANSACTION ACTIVITY</p><h3>Account transactions</h3></div></div><div class="panel-body"><p class="hint">'+(bankAdmin?'An active, global BANK_ADMIN assignment can review account transactions without a holder key. Access is recorded in the audit trail.':getSession().user.userType==='CUSTOMER'?'Your sign-in gives access to accounts linked to you.':'Your role and account scope are checked. An account holder must also provide a key for protected details.')+'</p>'+(!bankAdmin&&getSession().user.userType!=='CUSTOMER'?'<p class="hint">'+(getCustomerHash()?'A key is saved in this tab. Reload this account to check it.':'No account-holder key is saved in this tab.')+'</p><button type="button" class="btn small" data-action="nav" data-id="customerAccess">Open account-holder key section</button>':'')+'<form id="transaction-search-form" class="module-search"><label class="field">Account number<input name="accountNumber" list="bank-account-numbers" maxlength="20" required value="'+esc(transactionAccountNumber)+'" placeholder="Enter an account number"></label>'+hints+'<button type="submit" class="btn primary">Load transactions</button></form><p id="transaction-error" class="form-error" role="alert"></p>'+rows+internal+'</div></section>'+detail+'</div>';
}
function treasuryPanel(){
 const allowed=allow('TREASURY_RECONCILE');
 const options=reserveAccounts.map(a=>'<option value="'+esc(a.id)+'" '+(String(a.id)===String(reserveAccountId)?'selected':'')+'>'+esc(a.code)+' · '+esc(a.accountType)+'</option>').join('');
 const position=reservePosition?'<div class="stats"><div class="stat"><div class="stat-title">Confirmed balance</div><div class="stat-value">'+esc(formatAmount(reservePosition.confirmedBalance,reservePosition.currency))+'</div></div><div class="stat"><div class="stat-title">Active liquidity holds</div><div class="stat-value">'+esc(formatAmount(reservePosition.activeHoldAmount,reservePosition.currency))+'</div></div><div class="stat"><div class="stat-title">Available reserve</div><div class="stat-value">'+esc(formatAmount(reservePosition.availableBalance,reservePosition.currency))+'</div></div></div><p class="hint"><span>Position as of</span> '+esc(formatDate(reservePosition.asOf))+'</p>':'';
 const recon=reserveReconciliation?'<div class="notice">Reserve mirror reconciliation: <strong>'+esc(reserveReconciliation.isMatched==='Y'?'Matched':'Difference found')+'</strong> · Ledger-derived '+esc(formatAmount(reserveReconciliation.ledgerBalance,reserveReconciliation.currency))+' · Difference '+esc(formatAmount(reserveReconciliation.difference,reserveReconciliation.currency))+'</div>':'';
 const ledger=reserveLedger===null?'<div class="empty">Select a reserve account to view confirmed movements.</div>':reserveLedger.length?table(['Entry','Settlement time','Rail','Direction','Amount','Evidence','GL journal','External reference'],reserveLedger.map(e=>[esc(e.id),esc(formatDate(e.settledAt)),esc(e.railCode),badge(e.movementSide),esc(formatAmount(e.amount,e.currency)),badge(e.evidenceStatus),esc(e.glJournalId),esc(e.externalSettlementRef)])):'<div class="empty">No confirmed reserve movements for this account.</div>';
 return '<section class="panel"><div class="panel-heading"><div><p class="eyebrow">RBI LEDGER</p><h3>Local reserve ledger</h3></div></div><div class="panel-body"><div class="notice">This is the bank’s simulated local mirror of RBI/RTGS reserve movements. It is not a live RBI connection. Confirmed entries are backed by settlement evidence and a Module 5 GL journal.</div>'+(reserveAccounts.length?'<form id="reserve-ledger-form" class="module-search"><label class="field">Reserve account<select name="reserveAccountId" required>'+options+'</select></label><button type="submit" class="btn primary">View reserve</button></form>':'<div class="empty">No reserve account is configured.</div>')+'<p id="reserve-error" class="form-error" role="alert"></p>'+position+(allowed?recon+'<h4>Confirmed movements</h4>'+ledger+(reserveLedger!==null?'<div class="form-actions"><button class="btn small" type="button" data-reserve-page="previous" '+(reserveOffset===0?'disabled':'')+'>Previous</button><span class="muted"><span>Entries</span> '+esc(reserveOffset+1)+'–'+esc(reserveOffset+reserveLedger.length)+'</span><button class="btn small" type="button" data-reserve-page="next" '+(reserveLedger.length<50?'disabled':'')+'>Next</button></div>':''):'<p class="hint">Reserve ledger and reconciliation require Treasury Reconcile access.</p>')+'</div></section>';
}
function defaults(name,s){
 const n=name.split('.').pop();
 if(s.default!==undefined)return s.default;
 if(/requestKey|requestId|correlationId|endToEndId|postingKey|approvalKey|holdKey/.test(n))return crypto.randomUUID();
 if(n==='originatorId')return getSession().user.userId;
 return '';
}
function inputs(schema,prefix='',required=[],depth=0){
 schema=resolve(schema);if(depth>5)return '';
 return Object.entries(schema.properties||{}).map(([name,raw])=>{
  const s=resolve(raw),key=prefix+name,req=required.includes(name);
  if(s.readOnly)return '';
  if(!prefix&&/^(requestId|requestKey|correlationId|postingKey|approvalKey|holdKey)$/i.test(name))return '<input type="hidden" name="'+esc(key)+'" value="'+esc(defaults(key,s))+'">';
  if(!prefix&&name==='operationMode'&&active==='accounts')return select('Operation mode',key,[{value:'',label:'Select operation mode'},{value:'SELF_OPERATED',label:'Self operated'},{value:'ANYONE',label:'Any holder'},{value:'JOINTLY',label:'Jointly'},{value:'GUARDIAN_OPERATED',label:'Guardian operated'}],'',true);
  if(s.properties){
   const body='<div class="grid2">'+inputs(s,key+'.',s.required||[],depth+1)+'</div>';
   return req?'<fieldset><legend>'+esc(label(name))+'</legend>'+body+'</fieldset>':'<div class="optional-object"><label><input type="checkbox" data-bank-object="'+esc(key)+'"> Include '+esc(label(name))+'</label><fieldset data-object-body="'+esc(key)+'" hidden disabled><legend>'+esc(label(name))+'</legend>'+body+'</fieldset></div>';
  }
  if(s.type==='object'||s.additionalProperties)return '<label class="field">'+esc(label(name))+' (JSON object)<textarea name="'+esc(key)+'" '+(req?'required':'')+' placeholder="Enter a JSON object"></textarea></label>';
  if(s.type==='array'){
   const item=resolve(s.items);
   if(item.properties)return '<fieldset><legend>'+esc(label(name))+'</legend><div class="array-items" data-array="'+esc(key)+'">'+Array.from({length:Math.max(s.minItems||0,req?1:0)},(_,i)=>'<div class="grid2 array-row">'+inputs(item,key+'.'+i+'.',item.required||[],depth+1)+'</div>').join('')+'</div><button class="btn small" type="button" data-bank-add="'+esc(key)+'">Add '+esc(label(name))+'</button></fieldset>';
   return field(label(name)+' (comma separated)',key,'text',{required:req});
  }
  const values=s.enum || (s.pattern&&/^[A-Z0-9_]+(\|[A-Z0-9_]+)+$/.test(s.pattern)?s.pattern.split('|'):null);
  if(values)return select(label(name),key,[...(req?[]:[{value:'',label:'Not set'}]),...values],defaults(key,s),req);
  if(s.type==='boolean')return select(label(name),key,[{value:'',label:'Not set'},'true','false'],defaults(key,s),req);
  return field(accountNumberInput(name,prefix)?'Account number':label(name),key,s.format==='date'?'date':s.format==='password'?'password':'text',{required:req,value:!req&&['date','date-time'].includes(s.format)?'':defaults(key,s),maxlength:accountNumberInput(name,prefix)?20:s.maxLength,placeholder:accountNumberInput(name,prefix)?'Enter an account number':s.description||((s.type==='integer'||s.type==='number')?'Enter a number':'')});
 }).join('');
}
function schemaFor(op){
 const properties={},required=[];
 for(const p of op.parameters||[]){if(p.in==='header'&&p.name!=='If-Match')continue;properties[p.name]=p.schema;if(p.required)required.push(p.name);}
 let body=resolve(op.requestBody?.content?.['application/json']?.schema);if(body?.additionalProperties&&!body.properties)body={properties:{__body:body},required:op.requestBody.required?['__body']:[]};
 return {properties:{...properties,...body.properties},required:[...required,...body.required||[]],body};
}
function cast(s,v){
 s=resolve(s);if(v==='')return undefined;
 if(s.type==='boolean')return v==='true';
 if(s.type==='integer'){if(!/^-?\d+$/.test(v))throw new Error('Enter a whole number');return v;}
 if(s.type==='number'){if(!/^-?\d+(\.\d+)?$/.test(v))throw new Error('Enter a decimal amount');return v;}
 if(s.type==='array')return v.split(',').map(x=>cast(s.items,x.trim()));
 return v;
}
function collect(schema,data,prefix=''){
 const out={};for(const [key,raw]of Object.entries(resolve(schema).properties||{})){
 const s=resolve(raw),name=prefix+key;
 if(s.properties){if([...data.keys()].some(k=>k.startsWith(name+'.'))){const value=collect(s,data,name+'.');if(Object.keys(value).length)out[key]=value;}}
 else if(s.type==='object'||s.additionalProperties){const raw=data.get(name);if(raw){const value=JSON.parse(raw);if(value===null||Array.isArray(value)||typeof value!=='object')throw new Error(label(key)+' must be a JSON object');out[key]=value;}}
 else if(s.type==='array'&&resolve(s.items).properties){
  const indices=[...new Set([...data.keys()].filter(k=>k.startsWith(name+'.')).map(k=>Number(k.slice(name.length+1).split('.')[0])))].sort((a,b)=>a-b);
  out[key]=indices.map(i=>collect(s.items,data,name+'.'+i+'.'));
 }else{const rawValue=data.get(name)||'';const value=accountNumberInput(key,prefix)?rawValue.trim()||undefined:cast(s,rawValue);if(value!==undefined)out[key]=value;}
 }return out;
}
function staffAccessPage(head){
 const hints=accountHints('key-account-numbers');
 const holders=keyHolders===null?'':keyHolders.length?table(['Holder','Role','CIF ID','Customer sign-in'],keyHolders.map(h=>[esc(h.HOLDER_NAME||'Name unavailable'),esc(label(h.PARTY_ROLE)),esc(h.CIF_ID),h.CAN_SIGN_IN==='Y'?'Active · can generate a new key':h.CAN_SIGN_IN==='N'?'No active customer sign-in':'Check with account holder'])):'<div class="notice">No active holder who can provide a key was found for this account.</div>';
 const admin=getSession().user.roles?.includes('BANK_ADMIN');
 return head+'<section class="panel"><div class="panel-heading"><h3>Find who can provide a key</h3></div><div class="panel-body"><p>Enter the account number to identify its active holders. Existing keys cannot be looked up.</p><form id="key-holder-search-form" class="module-search"><label class="field">Account number<input name="accountNumber" list="key-account-numbers" maxlength="20" required value="'+esc(keyHolderAccountNumber||transactionAccountNumber)+'" placeholder="Enter an account number"></label>'+hints+'<button class="btn primary">Find account holders</button></form>'+holders+'</div></section>'+
  '<section class="panel"><div class="panel-heading"><h3>Use an account-holder key</h3></div><div class="panel-body"><div class="notice">'+(admin?'An active, global BANK_ADMIN assignment can review account transactions without a holder key. A key may still be required for other protected services.':'Ask an active holder for a key. A holder can sign in and generate a replacement here. Officers cannot retrieve an existing key.')+'</div><p>Saving a key does not grant account access by itself. Your role and account scope are checked. The key is kept only in this browser tab and cleared when you sign out.</p><form id="customer-hash-form">'+field('Account-holder key','customerHash','password',{required:true,value:getCustomerHash(),placeholder:'Paste the account-holder key'})+'<button class="btn primary">Use key for this tab</button><button type="button" class="btn" data-bank-clear>Clear key</button></form><div id="hash-result" role="status"></div></div></section>';
}
export async function bankingScreen(page){
 resetAccountOptionsForActor();
 if(page==='transactions'&&active!=='transactions')transactionOperationsOpen=false;
 active=page;const config=bankingPages[page];
 const customer=getSession().user.userType==='CUSTOMER';
 const head='<div class="page-heading"><div><p class="eyebrow">'+(customer?'YOUR BANKING':'BANKING OPERATIONS')+'</p><h1>'+esc(config.title)+'</h1><p>'+esc(customer&&page==='accounts'?'View your accounts and open an eligible savings or current account.':config.intro)+'</p></div></div>';
 if(page==='customerAccess')return customer
  ?head+'<section class="panel"><div class="panel-heading"><h3>Your account access key</h3></div><div class="panel-body"><div class="notice">Your sign-in already lets you view transactions for accounts linked to you. You do not need a key for your own details.</div><p>If an authorized bank officer needs to view your account details, you can generate a new key and give it to them privately. The previous key stops working immediately. An existing key cannot be displayed again.</p><button type="button" class="btn primary" data-bank-rotate>Generate a new account-holder key</button><div id="hash-result" role="status" aria-live="polite"></div></div></section>'
  :staffAccessPage(head);
 if(page==='banking'){
  const [d,self,selfService]=await Promise.all([api('/banking/overview'),customer?api('/banking/my-dashboard'):Promise.resolve(null),customer?api('/auth/signup-availability'):Promise.resolve(null)]);
  const accounts=self?.accounts||[];
  const balanceCards=accounts.map(a=>'<div class="stat"><div class="stat-title">Account ending '+esc(a.accountEnding)+' · '+esc(a.status)+'</div><div class="stat-value amount-value">'+(a.posted===undefined?'Balance unavailable':esc(formatAmount(a.posted,a.currency)))+'</div><small>Available balance: '+(a.spendable===undefined?'—':esc(formatAmount(a.spendable,a.currency)))+'</small></div>').join('');
  const recent=accounts.flatMap(a=>(a.recentTransactions||[]).map(t=>({...t,accountEnding:a.accountEnding}))).sort((a,b)=>b.transactionId-a.transactionId).slice(0,10);
  const customerSummary=customer?'<section class="panel"><div class="panel-heading"><h3>Your accounts</h3><span class="muted">Based on your signed-in identity</span></div><div class="panel-body">'+(accounts.length?'<div class="stats">'+balanceCards+'</div>':'<div class="empty">'+(selfService.enabled?'You do not have an account yet. Browse eligible products to open one.':'You do not have an account yet. Contact the bank to open one.')+'</div>')+(selfService.enabled?'<button class="btn primary" data-action="nav" data-id="accounts" type="button">'+(accounts.length?'Open another account':'Open an account')+'</button>':'')+(self.truncated?'<p class="hint">Showing the first 20 accounts.</p>':'')+'<h3>Recent transactions</h3>'+(recent.length?table(['Account','Transaction','Type','Amount','Status'],recent.map(t=>[esc(t.accountEnding),esc(t.transactionId),esc(t.type),'<span class="amount-value">'+esc(formatAmount(t.amount,t.currency))+'</span>',badge(t.status)])):'<div class="empty">No transactions yet.</div>')+'</div></section>':'';
  const metrics=[['customers','Customers'],['accounts','Accounts'],['transactions','Posted transactions'],['pendingPayments','Pending payments'],['pendingLoans','Pending loan applications']].filter(([key])=>typeof d[key]==='number');
  return head+customerSummary+'<section aria-label="Key figures"><div class="section-heading-row"><h2 class="section-heading">Current position</h2><span class="overview-mode">'+esc(d.mode)+'</span></div><div class="stats">'+metrics.map(([key,title])=>'<div class="stat"><div class="stat-title">'+title+'</div><div class="stat-value">'+Number(d[key]).toLocaleString('en-IN')+'</div></div>').join('')+'</div></section><section aria-label="Operational areas"><h2 class="section-heading">Operational areas</h2><div class="module-grid">'+Object.entries(bankingPages).filter(([k,v])=>!['banking','customerAccess'].includes(k)&&allow(v.permission)).map(([k,v])=>'<button class="module-card" data-action="nav" data-id="'+k+'"><span class="module-icon" aria-hidden="true">'+v.icon+'</span><strong>'+esc(v.title)+'</strong><span>'+esc(v.intro)+'</span><b>Open '+esc(v.title)+' →</b></button>').join('')+'</div></section>';
 }
 if(page==='accounts'&&customer)return head+await customerAccountsScreen();
 if(page==='treasury'&&!allow('TREASURY_RECONCILE')&&!allow('TREASURY_LIQUIDITY_MANAGE'))return head+'<section class="panel"><div class="panel-heading"><h3>Reserve ledger access</h3></div><div class="panel-body"><div class="notice">This bank-wide reserve ledger requires Treasury Reconcile access. Ask an administrator to assign that permission through the normal access request and approval process.</div></div></section>';
 if(page==='transactions'&&!transactionOperationsOpen)return head+transactionPanel()+'<section class="panel"><div class="panel-body"><h3>Ledger actions</h3><p class="hint">Transfers, reversals, fees, and reconciliation are available when you need them.</p><button class="btn" type="button" data-bank-show-operations>Show ledger actions</button></div></section>';
 await loadContract();
 if(page==='treasury'){
  reserveAccounts=await api('/treasury/reserve-accounts');
  if(reserveAccounts.length&&!reserveAccountId)await loadReserve(reserveAccounts[0].id);
  if(!allow('TREASURY_LIQUIDITY_MANAGE'))return head+treasuryPanel();
 }
 const choices=operations.filter(o=>config.prefix.some(p=>o.path===p||o.path.startsWith(p.endsWith('/')?p:p+'/'))&&(!customer||customerWorkflows.has(o.method+' '+o.path))&&(page!=='transactions'||!transactionReadOperations.has(o.method+' '+o.path)));
 const initial={accounts:'/banking/accounts',teller:'/teller/tills',beneficiaries:'/beneficiaries',payments:'/payments',treasury:'/treasury/reserve-accounts',loans:'/banking/facilities',currency:'/fx/rates',privacy:'/privacy/purposes'}[page];
 const op=choices.find(o=>o.id===selected[page])||choices.find(o=>o.method==='GET'&&o.path===initial)||choices.find(o=>o.method==='GET'&&!o.path.includes('{'))||choices[0];
 if(!op)return head+'<div class="notice">No workflow is available in this deployment.</div>';
 const firstLoad=!selected[page];selected[page]=op.id;
 if(firstLoad&&initial&&op.path===initial){try{results[page]=await api(initial);}catch{results[page]=null;}}
 const schema=schemaFor(op);
 const simpleInquiry=op.method==='GET'&&!Object.keys(schema.properties).length;
 const operationList=(items,group)=>items.length?'<div class="operation-group"><p class="operation-group-title">'+group+'</p>'+items.map(item=>'<button type="button" class="operation-choice '+(item.id===op.id?'active':'')+'" data-bank-operation="'+esc(item.id)+'" '+(item.id===op.id?'aria-current="true"':'')+'><span class="operation-choice-label">'+esc(item.title)+'</span></button>').join('')+'</div>':'';
 const catalog='<aside class="panel operation-catalog"><div class="panel-heading"><div><h3>Operations</h3><span class="muted">'+choices.length+' available</span></div><button id="operation-catalog-toggle" type="button" aria-expanded="false" aria-controls="operation-catalog-body">Choose operation</button></div><div class="panel-body" id="operation-catalog-body"><label class="field">Find an operation<input id="operation-search" type="search" placeholder="Search by name" autocomplete="off"></label><div class="operation-list">'+operationList(choices.filter(item=>item.method==='GET'),'View and inquire')+operationList(choices.filter(item=>item.method!=='GET'),'Create and maintain')+'</div><p class="operation-empty" hidden>No operations match this search.</p></div></aside>';
 const formPanel='<section class="panel workflow-panel"><div class="panel-heading"><div><p class="eyebrow">'+(op.method==='GET'?'INQUIRY':'MAINTENANCE')+'</p><h3>'+esc(op.title)+'</h3></div></div><div class="panel-body"><p class="hint">'+esc(op.description||'Complete the fields below. Your access is checked when you submit.')+'</p><form id="bank-workflow"><div class="grid2">'+inputs(schema,'',schema.required)+'</div><p class="form-error" role="alert"></p><div class="form-actions"><button type="submit" class="btn primary">'+(op.method==='GET'?'View records':'Submit operation')+'</button></div></form></div></section>';
 const resultPanel='<section class="panel result-panel" '+(op.method!=='GET'&&!results[page]?'hidden':'')+'><div class="panel-heading"><div><p class="eyebrow">'+(op.method==='GET'?'RECORDS':'TRANSACTION OUTCOME')+'</p><h3>'+esc(op.method==='GET'?op.title:'Operation result')+'</h3></div>'+(simpleInquiry?'<button class="btn small" id="bank-refresh" type="button">Refresh</button>':'<span class="muted">'+esc(op.title)+'</span>')+'</div><div class="panel-body" id="bank-result">'+(results[page]?renderRows(results[page]):'<div class="empty"><h3>No result to display</h3><p>Run the selected operation to view its result.</p></div>')+'</div></section>';
 const main=simpleInquiry?resultPanel:op.method==='GET'&&results[page]?resultPanel+formPanel:formPanel+resultPanel;
 return head+(page==='transactions'?transactionPanel()+'<div class="form-actions"><button class="btn small" type="button" data-bank-hide-operations>Hide ledger actions</button></div>':page==='treasury'?treasuryPanel():'')+'<div class="operation-layout">'+catalog+'<div class="operation-main">'+main+'</div></div>';
}
async function loadReserve(id,offset=0){
 const [position,reconciliation,ledger]=await Promise.all([
  api('/treasury/reserve-accounts/'+encodeURIComponent(id)+'/position'),
  allow('TREASURY_RECONCILE')?api('/treasury/reserve-accounts/'+encodeURIComponent(id)+'/reconciliation'):Promise.resolve(null),
  allow('TREASURY_RECONCILE')?api('/treasury/reserve-accounts/'+encodeURIComponent(id)+'/ledger?'+new URLSearchParams({limit:'50',offset:String(offset)})):Promise.resolve(null)
 ]);
 reserveAccountId=String(id);reserveOffset=offset;
 reservePosition=position;reserveReconciliation=reconciliation;reserveLedger=ledger;
}
async function customerAccountsScreen(){
 const [accounts,availability,offerGroups]=await Promise.all([
  api('/banking/accounts'),
  api('/auth/signup-availability'),
  Promise.all(getSession().user.cifIds.map(async cifId=>({cifId,offers:await api('/products/customer-offers?'+new URLSearchParams({cifId,channel:'BRANCH',currency:'INR'})).catch(()=>null)})))
 ]);
 const offers=offerGroups.flatMap(group=>(group.offers||[]).filter(o=>['SAVINGS','CURRENT'].includes(o.product.PRODUCT_TYPE)).map(o=>({...o,cifId:group.cifId})));
 const existing=table(['Account','Type','Status','Action'],accounts.map(a=>[
  esc(a.ACCOUNT_NUMBER),esc(a.CURRENCY_CODE),badge(a.ACCOUNT_STATUS),
  availability.enabled&&a.ACCOUNT_STATUS==='PENDING_OPEN'&&a.CREATED_BY_USER_ID===getSession().user.userId?'<button class="btn small" type="button" data-customer-activate="'+esc(a.ACCOUNT_ID)+'">Try activation</button>':'—'
 ]),'No accounts yet. Choose an eligible product below.');
 const available=!availability.enabled?'<div class="notice">Account opening is handled by a bank officer in this deployment.</div>':offerGroups.some(g=>g.offers===null)?'<div class="notice">Product offers are unavailable for your current access. Contact the bank to review your customer role.</div>':offers.length?table(['Product','Type','Version','Action'],offers.map(o=>[
  esc(o.product.PRODUCT_NAME),esc(o.product.PRODUCT_TYPE),esc(o.version.VERSION_NO),
  '<button class="btn small primary" type="button" data-customer-open="'+esc(o.cifId)+'" data-product="'+esc(o.product.PRODUCT_ID)+'" data-version="'+esc(o.version.PRODUCT_VERSION_ID)+'" data-product-name="'+esc(o.product.PRODUCT_NAME)+'">Open account</button>'
 ])):'<div class="empty">No eligible savings or current account products are available for your profile. Contact the bank to make a product available.</div>';
 return '<section class="panel"><div class="panel-heading"><h3>Your accounts</h3></div><div class="panel-body">'+existing+'</div></section>'+
  '<section class="panel"><div class="panel-heading"><h3>Open an account</h3></div><div class="panel-body"><p class="hint">Available products are checked against your verified customer profile and home branch. Accounts that require opening funding stay pending until that funding clears.</p>'+available+'</div></section>';
}
function refreshTransactionPanel(){
 const existing=document.querySelector('#transaction-activity');
 if(!existing)return;
 existing.outerHTML=transactionPanel();
 bindTransactionForms();
}
function bindTransactionForms(){
 const transactionForm=document.querySelector('#transaction-search-form');
 transactionForm?.querySelector('input[name="accountNumber"]')?.addEventListener('focus',loadAccountOptions,{once:true});
 if(transactionForm)transactionForm.onsubmit=async e=>{e.preventDefault();const button=transactionForm.querySelector('button');button.disabled=true;document.querySelector('#transaction-error').textContent='';try{const number=String(new FormData(transactionForm).get('accountNumber')).trim();let rows;transactionLegacy=false;transactionAccountId='';try{rows=await api('/banking/transactions?'+new URLSearchParams({accountNumber:number}));}catch(error){if(error.code!=='ACCOUNT_REQUIRED')throw error;transactionAccountId=await legacyAccountId(number);rows=await api('/banking/transactions?'+new URLSearchParams({accountId:transactionAccountId}));transactionLegacy=true;}transactionRows=rows;transactionAccountNumber=number;transactionDetail=null;transactionVisibleCount=25;refreshTransactionPanel();}catch(error){const output=document.querySelector('#transaction-error');if(output)output.textContent=errorText(error);}finally{button.disabled=false;}};
 const more=document.querySelector('[data-txn-more]');
 if(more)more.onclick=()=>{transactionVisibleCount+=25;refreshTransactionPanel();};
 const internalForm=document.querySelector('#internal-transaction-form');
 if(internalForm)internalForm.onsubmit=async e=>{e.preventDefault();const button=internalForm.querySelector('button');button.disabled=true;document.querySelector('#internal-transaction-error').textContent='';try{const id=new FormData(internalForm).get('transactionId');transactionDetail=await api('/banking/transactions/'+encodeURIComponent(id)+'/details');refreshTransactionPanel();}catch(error){const output=document.querySelector('#internal-transaction-error');if(output)output.textContent=error.code==='NOT_FOUND'?'No internal ledger record found for this ID. For an account transaction, use the list above.':errorText(error);button.disabled=false;}};
 document.querySelectorAll('[data-txn-detail]').forEach(button=>button.addEventListener('click',async()=>{button.disabled=true;try{const account=transactionLegacy?{accountId:transactionAccountId}:{accountNumber:transactionAccountNumber};transactionDetail=await api('/banking/transactions/'+encodeURIComponent(button.dataset.txnDetail)+'/details?'+new URLSearchParams(account));refreshTransactionPanel();document.querySelector('#transaction-activity .result-details')?.scrollIntoView({block:'nearest',behavior:'smooth'});}catch(error){toast(errorText(error),true);button.disabled=false;}}));
}
export function bindBankingForms(){
 bindTransactionForms();
 const showOperations=document.querySelector('[data-bank-show-operations]');
 if(showOperations)showOperations.onclick=async()=>{transactionOperationsOpen=true;await renderApp();};
 const hideOperations=document.querySelector('[data-bank-hide-operations]');
 if(hideOperations)hideOperations.onclick=async()=>{transactionOperationsOpen=false;await renderApp();};
 const reserveForm=document.querySelector('#reserve-ledger-form');
 if(reserveForm)reserveForm.onsubmit=async e=>{e.preventDefault();const button=reserveForm.querySelector('button');button.disabled=true;document.querySelector('#reserve-error').textContent='';try{await loadReserve(new FormData(reserveForm).get('reserveAccountId'));await renderApp();}catch(error){document.querySelector('#reserve-error').textContent=errorText(error);button.disabled=false;}};
 document.querySelectorAll('[data-reserve-page]').forEach(button=>button.addEventListener('click',async()=>{button.disabled=true;try{await loadReserve(reserveAccountId,Math.max(0,reserveOffset+(button.dataset.reservePage==='next'?50:-50)));await renderApp();}catch(error){toast(errorText(error),true);button.disabled=false;}}));
 const catalogToggle=document.querySelector('#operation-catalog-toggle');
 if(catalogToggle)catalogToggle.addEventListener('click',()=>{const expanded=catalogToggle.getAttribute('aria-expanded')!=='true';catalogToggle.setAttribute('aria-expanded',String(expanded));document.querySelector('.operation-catalog').classList.toggle('expanded',expanded);});
 document.querySelectorAll('[data-bank-operation]').forEach(button=>button.addEventListener('click',async()=>{selected[active]=button.dataset.bankOperation;results[active]=null;await renderApp();}));
 const search=document.querySelector('#operation-search');
 if(search)search.addEventListener('input',()=>{let visible=0;document.querySelectorAll('.operation-group').forEach(group=>{let count=0;group.querySelectorAll('.operation-choice').forEach(button=>{button.hidden=!button.textContent.toLowerCase().includes(search.value.trim().toLowerCase());if(!button.hidden)count++;});group.hidden=count===0;visible+=count;});document.querySelector('.operation-empty').hidden=visible>0;});
 const refresh=document.querySelector('#bank-refresh');
 if(refresh)refresh.addEventListener('click',async()=>{refresh.disabled=true;try{const op=operations.find(item=>item.id===selected[active]);results[active]=await api(op.path);document.querySelector('#bank-result').innerHTML=renderRows(results[active]);}catch(error){toast(errorText(error),true);}finally{refresh.disabled=false;}});
 const form=document.querySelector('#bank-workflow');
 if(form)form.dataset.requestKey=crypto.randomUUID();
 if(form)form.onsubmit=async e=>{
  e.preventDefault();if(!await validateJet(form))return;
  const button=form.querySelector('[type=submit]');button.disabled=true;const err=form.querySelector('.form-error');err.textContent='';
  try{
   const op=operations.find(o=>o.id===selected[active]),data=new FormData(form),schema=schemaFor(op);
   const values=await resolveAccountNumbers(collect(schema,data));
   if(op.path.startsWith('/accounts/{id}'))values.id=await legacyAccountId(String(values.id||'').trim());
   let path=op.path;const query=new URLSearchParams();
   for(const p of op.parameters||[]){
    if(p.in==='path')path=path.replace('{'+p.name+'}',encodeURIComponent(values[p.name]??''));
    if(p.in==='query'&&values[p.name]!==undefined)query.set(p.name,values[p.name]);
   }
   if(query.size)path+='?'+query;
   const collected=op.requestBody?await resolveAccountNumbers(collect(schema.body,data)):undefined;const body=collected?.__body??collected;const headers={'Idempotency-Key':body?.requestKey||body?.requestId||form.dataset.requestKey};for(const p of op.parameters||[])if(p.in==='header'&&p.name==='If-Match'&&values[p.name]!==undefined)headers[p.name]=values[p.name];
   const response=await api(path,{method:op.method,body,headers});
   results[active]=response??{message:'Operation completed'};
   document.querySelector('.result-panel').hidden=false;
   document.querySelector('#bank-result').innerHTML=renderRows(results[active]);
   if(op.method!=='GET')toast('Operation completed');
  }catch(error){err.textContent=errorText(error);}finally{button.disabled=false;}
 };
 const keyform=document.querySelector('#customer-hash-form');if(keyform)keyform.onsubmit=e=>{e.preventDefault();setCustomerHash(new FormData(keyform).get('customerHash'));transactionRows=null;transactionDetail=null;document.querySelector('#hash-result').textContent='Key saved for this tab. Return to Transactions & ledger and load the account again to check access.';};
 const holderform=document.querySelector('#key-holder-search-form');if(holderform)holderform.onsubmit=async e=>{e.preventDefault();const button=holderform.querySelector('button');button.disabled=true;try{const number=String(new FormData(holderform).get('accountNumber')).trim();let holders;try{holders=await api('/banking/accounts/by-number/'+encodeURIComponent(number)+'/key-holders');}catch(error){if(error.code!=='NOT_FOUND')throw error;const id=await legacyAccountId(number);try{holders=await api('/banking/accounts/'+encodeURIComponent(id)+'/key-holders');}catch(inner){if(inner.code!=='NOT_FOUND')throw inner;const parties=await api('/accounts/'+encodeURIComponent(id)+'/parties');holders=parties.filter(p=>p.IS_ACTIVE==='Y'&&['PRIMARY_HOLDER','JOINT_HOLDER','AUTHORIZED_SIGNATORY'].includes(p.PARTY_ROLE)).map(p=>({...p,CAN_SIGN_IN:'UNKNOWN'}));}}keyHolderAccountNumber=number;keyHolders=holders;await renderApp();}catch(error){toast(errorText(error),true);button.disabled=false;}};
 holderform?.querySelector('input[name="accountNumber"]')?.addEventListener('focus',loadAccountOptions,{once:true});
}
document.addEventListener('click',async e=>{
 const open=e.target.closest('[data-customer-open]');if(open){
  const requestId=crypto.randomUUID(),cifId=open.dataset.customerOpen,productId=Number(open.dataset.product),productVersionId=Number(open.dataset.version);
  dialog('Open '+open.dataset.productName,'<p>You are opening this account for your own verified customer profile. The product and branch will be checked again when you continue.</p>',async()=>{
   const account=await api('/accounts/self',{method:'POST',body:{requestId,cifId,productId,productVersionId}});
   try{await api('/accounts/self/'+account.id+'/activate',{method:'POST',body:{requestId:crypto.randomUUID()}});toast('Account opened and activated.');}
   catch(error){toast('Account opened, but activation is pending: '+errorText(error),true);}
   await renderApp();return true;
  });return;
 }
 const activate=e.target.closest('[data-customer-activate]');if(activate){activate.disabled=true;try{await api('/accounts/self/'+activate.dataset.customerActivate+'/activate',{method:'POST',body:{requestId:crypto.randomUUID()}});toast('Account activated.');await renderApp();}catch(error){toast(errorText(error),true);activate.disabled=false;}return;}
 const clear=e.target.closest('[data-bank-clear]');if(clear){setCustomerHash('');transactionRows=null;transactionDetail=null;await renderApp();}
 const rotate=e.target.closest('[data-bank-rotate]');if(rotate){rotate.disabled=true;try{const r=await api('/customer-access/rotate',{method:'POST'});document.querySelector('#hash-result').innerHTML='<div class="notice access-key-output"><strong>Copy this new key now. It is shown only once, and your previous key no longer works.</strong><br><code>'+esc(r.customerHash)+'</code></div>';}catch(error){toast(errorText(error),true);}finally{rotate.disabled=false;}}
 const add=e.target.closest('[data-bank-add]');if(add){
  const name=add.dataset.bankAdd,op=operations.find(o=>o.id===selected[active]);let schema=schemaFor(op);for(const segment of name.split('.')){schema=resolve(schema);schema=/^\d+$/.test(segment)?schema.items:schema.properties?.[segment];}schema=resolve(schema);const parent=document.querySelector('[data-array="'+CSS.escape(name)+'"]');const index=parent.children.length;
  const row=document.createElement('div');row.className='grid2 array-row';row.innerHTML=inputs(resolve(schema.items),name+'.'+index+'.',resolve(schema.items).required||[]);parent.append(row);await enhanceForms(row);
 }
});


document.addEventListener('change',e=>{const toggle=e.target.closest('[data-bank-object]');if(!toggle)return;const fieldset=document.querySelector('[data-object-body="'+CSS.escape(toggle.dataset.bankObject)+'"]');fieldset.hidden=!toggle.checked;fieldset.disabled=!toggle.checked;for(const input of fieldset.querySelectorAll('oj-input-text,oj-input-password,oj-input-date,oj-text-area,oj-select-single'))input.disabled=!toggle.checked;});
