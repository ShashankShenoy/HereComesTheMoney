import {operationKey,requestPath,recordId,recordLabel,statusChoices} from './operation-state.js';
import {simulatorScreen,bindSimulator} from './settlement-simulator.js';
import {workspaceHero,icon,customerPageLabels,customerPageDescriptions} from './design.js';
import {creditCardsScreen,bindCreditCards} from './credit-cards.js';
import {customerPaymentsScreen,bindCustomerPayments,paymentStatus} from './customer-payments.js';
import {customerFxScreen,bindCustomerFx} from './customer-fx.js';
import {api,getSession,getCustomerHash,setCustomerHash} from './api.js';
import {esc,table,field,select,badge,errorText,toast,dialog} from './ui.js';
import {enhanceForms,validateJet,jetReady} from './jet.js';
import {formatAmount,formatDecimal,formatDate,fieldKind,rowCurrency,transactionOtherParty,transactionAccountImpact} from './presentation.js';
import {t,tField} from './i18n.js';
export const bankingPages={
 simulator:{title:'RBI / NPCI simulator',permission:'PAYMENT_READ',staffOnly:true,icon:'◈',intro:'Simulated participating bank reserves, incoming instructions and settlement confirmations.',prefix:[]},
 errorLogs:{title:'Error logs',permission:null,staffOnly:true,icon:'≡',intro:'Application failures and references for employee investigation.',prefix:[]},
 banking:{title:'Bank overview',permission:null,icon:'▦',intro:'Customer relationships, money movement, and daily operations in one workspace.',prefix:[]},
 accounts:{title:'Accounts',permission:'ACCOUNT_READ',icon:'▤',intro:'Open accounts, manage holders and nominees, apply controls, and review closure requests.',prefix:['/accounts','/banking/accounts','/deposit-interest','/term-deposits']},
 transactions:{title:'Transactions & ledger',permission:'TXN_READ',icon:'⇄',intro:'Review account transactions, status history and journals; post authorized transfers and reconcile the ledger.',prefix:['/transactions','/banking/transactions','/journals','/gl/','/banking/gl-accounts','/period-closes','/fees','/reconciliation/']},
 teller:{title:'Teller cash',permission:'TELLER_READ',icon:'▣',intro:'Open your cash till, accept deposits, make withdrawals, and balance at close.',prefix:['/teller']},
 beneficiaries:{title:'Beneficiaries',permission:'BENEFICIARY_VERIFY',icon:'♧',intro:'Register payment recipients and complete independent verification.',prefix:['/beneficiaries']},
 payments:{title:'Payments & clearing',permission:'PAYMENT_READ',icon:'↗',intro:'Initiate payments and follow simulated UPI, IMPS, NEFT, and RTGS processing.',prefix:['/payments','/dispatches','/clearing-batches','/reconciliation-exceptions','/approvals','/rail-messages']},
 treasury:{title:'Treasury & RBI ledger',permission:'TREASURY_READ',icon:'◈',intro:'Review the simulated local RBI reserve ledger, liquidity holds, settlement cycles, and reconciliation.',prefix:['/treasury/']},
 creditCards:{title:'Credit cards',permission:'CC_READ',icon:'▰',intro:'Apply for a card, review approved terms, manage spending, and repay your balance.',prefix:['/credit-cards']},
 loans:{title:'Loans',permission:'LOAN_READ',icon:'⌂',intro:'Origination, assessment, sanction, offers, documentation, and servicing.',prefix:['/loans','/banking/facilities']},
 statements:{title:'Statements & documents',permission:'STATEMENT_READ',icon:'▧',intro:'Create date-range statements from the posted ledger and download PDF or CSV records.',prefix:['/reporting','/statements','/statement-','/catalog']},
 privacy:{title:'Privacy & compliance',permission:'PRIVACY_CONSENT_VIEW',icon:'◇',intro:'Manage processing purposes, consent, legal holds, privacy cases, and audit evidence.',prefix:['/privacy']},
 currency:{title:'Currency & rates',permission:'FX_READ',icon:'◎',intro:'Supported currencies, approved rates, explicit expiry, and indicative conversion.',prefix:['/currencies','/fx']},
 customerAccess:{title:'Account-holder access key',permission:null,icon:'⌑',intro:'Find out how to use or replace the key for protected account details.',prefix:[]}
};
let operationResults={},operationValues={},operationRecords=[],operationCollection='',operationReferences={};
let renderApp, contract, contractLoad, operations=[], selected={}, results={}, active='banking';
let transactionAccountNumber='',transactionAccountId='',transactionLegacy=false,transactionRows=null,transactionDetail=null,transactionVisibleCount=25;
let transactionSection='accounts';
let keyHolderAccountNumber='',keyHolders=null,accountOptions=[],accountOptionsFetchedAt=0,accountOptionsPending=null,accountOptionsActor=null,accountOptionsError=false;
let reserveAccountId='',reserveAccounts=[],reservePosition=null,reserveReconciliation=null,reserveLedger=null,reserveOffset=0;
let glLedgerAccountId='',glLedger=null,glLedgerJournal=null,glLedgerAccounts=null,glLedgerOffset=0;
let customerPurposeNotices=[];
let cashLedgerAccounts=null,cashLedgerError='';
const transactionReadOperations=new Set(['GET /banking/transactions','GET /banking/transactions/{id}/details','GET /transactions/{id}','GET /banking/gl-accounts/{id}/ledger']);
function accountHints(id){return '<datalist id="'+id+'">'+accountOptions.map(a=>'<option value="'+esc(a.ACCOUNT_NUMBER)+'">'+esc(a.ACCOUNT_STATUS||'Account')+'</option>').join('')+'</datalist>';}
function resetAccountOptionsForActor(){
 const actor=getSession()?.user?.userId;
 if(actor===accountOptionsActor)return;
 accountOptionsActor=actor;accountOptions=[];accountOptionsFetchedAt=0;accountOptionsPending=null;accountOptionsError=false;
 transactionAccountNumber='';transactionAccountId='';transactionLegacy=false;transactionRows=null;transactionDetail=null;transactionVisibleCount=25;transactionSection=allow('TXN_REVERSE_APPROVE')?'reversals':'accounts';
 keyHolderAccountNumber='';keyHolders=null;reserveAccountId='';reserveAccounts=[];reservePosition=null;reserveReconciliation=null;reserveLedger=null;reserveOffset=0;
 glLedgerAccountId='';glLedger=null;glLedgerJournal=null;glLedgerAccounts=null;glLedgerOffset=0;
 cashLedgerAccounts=null;cashLedgerError='';
 selected={};results={};operationResults={};operationValues={};customerPurposeNotices=[];
}
function loadAccountOptions(force=false){
 resetAccountOptionsForActor();
 if(accountOptionsPending)return accountOptionsPending;
 if(!force&&Date.now()-accountOptionsFetchedAt<60000)return Promise.resolve();
 const actor=accountOptionsActor;
 accountOptionsPending=api('/banking/accounts').then(accounts=>{
  if(actor!==accountOptionsActor)return;
  accountOptions=accounts;accountOptionsFetchedAt=Date.now();accountOptionsError=false;
  if(getSession()?.user?.userType==='CUSTOMER'&&transactionAccountNumber&&!accounts.some(a=>a.ACCOUNT_NUMBER===transactionAccountNumber)){
   transactionAccountNumber='';transactionAccountId='';transactionRows=null;transactionDetail=null;
  }
  for(const id of ['bank-account-numbers','key-account-numbers']){const list=document.getElementById(id);if(list)list.innerHTML=accountOptions.map(a=>'<option value="'+esc(a.ACCOUNT_NUMBER)+'">'+esc(a.ACCOUNT_STATUS||'Account')+'</option>').join('');}
 }).catch(()=>{if(actor===accountOptionsActor){accountOptions=[];accountOptionsFetchedAt=0;accountOptionsError=true;}}).finally(()=>{if(actor===accountOptionsActor)accountOptionsPending=null;});
 return accountOptionsPending;
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
const displayLabels={ACCOUNT_NUMBER:'Account number',ACCOUNT_STATUS:'Status',CURRENCY_CODE:'Currency',BRANCH_CODE:'Branch',PRIMARY_CIF_ID:'Primary CIF',ACCOUNT_ID:'Account ID',TXN_ID:'Transaction ID',PRODUCT_VERSION_ID:'Product version',accountToken:'Account number',primaryCifId:'Primary CIF ID',productId:'Product ID',productVersionId:'Product version ID',branchCode:'Branch code',operationMode:'Operation mode',requestId:'Request reference',requestKey:'Request reference'};
const label=v=>tField(displayLabels[v]||v.replace(/([a-z])([A-Z])/g,'$1 $2').replace(/[_-]/g,' ').replace(/\b\w/g,c=>c.toUpperCase()));
const activityPaymentStatus=(status,role)=>status==='SETTLED'&&role==='TARGET'?'Interbank settlement completed; customer credited.':paymentStatus(status);
const allow=p=>!p||getSession()?.user?.permissions?.includes(p)||({TELLER_READ:'TELLER_OPERATE',BENEFICIARY_VERIFY:'PAYMENT_CREATE',TREASURY_READ:'TREASURY_RECONCILE'}[p]&&getSession()?.user?.permissions?.includes({TELLER_READ:'TELLER_OPERATE',BENEFICIARY_VERIFY:'PAYMENT_CREATE',TREASURY_READ:'TREASURY_RECONCILE'}[p]));
const checker=()=>getSession()?.user?.roles?.includes('BANK_CHECKER')&&!getSession()?.user?.roles?.includes('BANK_ADMIN');
const checkerDecisionPath=/^\/(?:iam\/access-requests\/[^/]+\/decision|products\/approvals\/[^/]+\/decision|transactions\/[^/]+\/reversal-decision|period-closes\/[^/]+\/decision|approvals\/[^/]+\/decision|payments\/[^/]+\/authorize-simulation|treasury\/(?:work-items\/[^/]+\/decision|reserve-openings\/[^/]+\/confirm)|accounts\/[^/]+\/(?:closures\/[^/]+\/(?:approve|reject)|majority-reviews\/decisions|override-approvals\/[^/]+\/decision)|cif\/(?:cases\/[^/]+\/review|documents\/[^/]+\/review)|loans\/(?:applications\/[^/]+\/(?:decisions|documents\/[^/]+\/verify)|disbursements\/[^/]+\/approve)|teller\/(?:tills\/[^/]+\/(?:close|replenish)|cash-deliveries\/[^/]+\/confirm)|beneficiaries\/[^/]+\/verify|fx\/rates\/[^/]+\/decision|privacy\/(?:purposes\/[^/]+\/approval|holds\/[^/]+\/(?:activation|release-approval)|evidence-exports\/[^/]+\/approval)|catalog\/(?:masking-profiles|narrations)\/[^/]+\/approve)$/;
const customerWorkflows=new Set([
 'GET /banking/transactions','GET /transactions/{id}',
 'GET /beneficiaries','POST /beneficiaries',
 'GET /payments','GET /payments/{id}','GET /payments/{id}/history',
 'GET /banking/facilities','GET /loans/applications/{id}','GET /loans/applications/{id}/offers',
 'GET /loans/facilities/{id}','GET /loans/facilities/{id}/schedule','POST /loans/offers/{id}/accept',
 'GET /reporting/statement-requests','GET /reporting/statement-requests/{id}',
 'POST /reporting/statement-requests','POST /reporting/statement-requests/{id}/cancel',
 'POST /reporting/statement-requests/{id}/process','GET /reporting/statements',
 'GET /reporting/statements/{id}','GET /reporting/statements/{id}/download',
 'GET /privacy/purposes/available',
 'GET /privacy/consents/evaluations','GET /privacy/consents/history',
 'POST /privacy/consents/decisions','POST /privacy/consents/withdrawals',
 'GET /currencies','GET /fx/rates','GET /fx/quote'
]);
function workflowAllowed(operation,customer){
 if(operation.path==='/transactions/reversal-requests'||operation.path==='/transactions/{id}/reversal-decision')return false;
 if(operation.path==='/payments/availability'||operation.path==='/privacy/consents/availability')return false;
 if(customer){if(operation.path.startsWith('/privacy/consents/')&&operation.method==='POST'&&!allow('PRIVACY_CONSENT_SELF'))return false;return customerWorkflows.has(operation.method+' '+operation.path);}
 if(/^\/(?:accounts\/self|loans\/my|term-deposits\/my)(?:\/|$)/.test(operation.path))return false;
 if(operation.path==='/privacy/purposes/available'||operation.path==='/transactions/transfers/by-number')return false;
 if(operation.path==='/term-deposits'&&operation.method==='POST')return false;
 if(operation.path==='/term-deposits/{id}'&&operation.method==='GET')return allow('GL_POST');
 if(operation.path==='/loans/collections')return allow('LOAN_SERVICE');
 return true;
}
const workflowTitles={
 'get /privacy/purposes/available':'View available privacy purposes',
 'get /accounts':'List customer accounts','post /accounts':'Open account','get /accounts/{id}':'Account details','get /accounts/by-number/{number}':'Find account by number','get /accounts/{id}/{collection}':'Account records',
 'post /accounts/{id}/activate':'Activate account','post /accounts/{id}/cancel-opening':'Cancel pending opening','post /accounts/{id}/parties':'Add account party','delete /accounts/{id}/parties/{partyId}':'End account party role','put /accounts/{id}/nominees':'Update nominees',
 'post /accounts/{id}/restrictions':'Add account restriction','post /accounts/{id}/restrictions/{restrictionId}/release':'Release account restriction','post /accounts/{id}/limits':'Set account limit','post /accounts/{id}/interest-overrides':'Set interest override','post /accounts/{id}/product-version-adoptions':'Adopt product version',
 'post /accounts/{id}/closures':'Request account closure','post /accounts/{id}/closures/{requestId}/approve':'Approve account closure','post /accounts/{id}/closures/{requestId}/reject':'Reject account closure','post /accounts/{id}/majority-reviews':'Start majority review','post /accounts/{id}/majority-reviews/decisions':'Decide majority review',
 'get /deposit-interest/accounts/{id}':'Savings interest credits','post /deposit-interest/runs':'Post monthly savings interest','get /term-deposits/my':'My fixed deposits','get /term-deposits/{id}':'Fixed deposit details','post /term-deposits':'Open fixed deposit','post /term-deposits/{id}/mature':'Pay matured fixed deposit',
 'get /banking/accounts':'Account directory','get /banking/transactions':'Transactions','get /banking/facilities':'Loan facilities','get /banking/gl-accounts':'General ledger accounts',
 'get /teller/tills':'List teller tills','post /teller/tills':'Open a teller till','post /teller/tills/{id}/close':'Independently close a teller till','post /teller/tills/{id}/replenish':'Replenish an open teller till','get /teller/replenishments':'List teller replenishments','post /teller/cash':'Post a cash deposit or withdrawal',
 'get /teller/vaults':'List branch vault cash','post /teller/vaults':'Register branch vault cash',
 'get /teller/cash-deliveries':'List RBI cash deliveries','post /teller/cash-deliveries':'Request simulated RBI cash delivery',
 'post /teller/cash-deliveries/{id}/confirm':'Confirm counted RBI cash delivery',
 'get /treasury/reserve-openings':'List synthetic reserve openings','post /treasury/reserve-openings':'Request synthetic opening reserve',
 'post /treasury/reserve-openings/{id}/confirm':'Approve opening reserve',
 'get /beneficiaries':'List beneficiaries','post /beneficiaries':'Register a beneficiary','post /beneficiaries/{id}/verify':'Verify a beneficiary',
 'post /payments/initiate':'Initiate a beneficiary payment','post /payments/{id}/authorize-simulation':'Authorize simulated payment','post /payments/{id}/simulate-outcome':'Record simulated rail outcome',
 'get /treasury/reserve-accounts':'List reserve accounts','post /treasury/reserve-accounts':'Create a reserve account and zero position',
 'get /treasury/reserve-accounts/{id}/position':'Get reserve position','get /treasury/reserve-accounts/{id}/ledger':'List reserve ledger entries','get /treasury/reserve-accounts/{id}/reconciliation':'Get reserve reconciliation',
 'get /treasury/liquidity-holds':'List liquidity holds','post /treasury/liquidity-holds':'Reserve liquidity','post /treasury/liquidity-holds/{id}/decision':'Decide liquidity hold',
 'get /treasury/settlement-cycles':'List settlement cycles','post /treasury/settlement-cycles':'Open settlement cycle','get /treasury/settlement-cycles/{id}/items':'List settlement cycle items','post /treasury/settlement-cycles/{id}/items':'Add settlement cycle items','post /treasury/settlement-cycles/{id}/close':'Close settlement cycle','post /treasury/settlement-cycles/{id}/finalize':'Finalize individually settled cycle',
 'post /treasury/settlement-evidence':'Record settlement evidence','post /treasury/reserve-movements/confirm':'Confirm reserve movement',
 'get /treasury/reconciliation-exceptions':'List reserve reconciliation exceptions','post /treasury/reconciliation-exceptions':'Open reserve reconciliation exception','post /treasury/reconciliation-exceptions/{id}/resolution':'Resolve reserve reconciliation exception',
 'get /treasury/work-items':'List treasury work items','post /treasury/work-items':'Create treasury work item','post /treasury/work-items/{id}/submit':'Submit treasury work item','post /treasury/work-items/{id}/decision':'Decide treasury work item',
 'get /currencies':'List supported currencies','post /currencies':'Add a supported currency','get /fx/rates':'Review exchange rates','post /fx/rates':'Propose an exchange rate','post /fx/rates/{id}/decision':'Decide an exchange rate','get /fx/quote':'Calculate an indicative quote','get /fx/provider/status':'Check rate provider status','post /fx/provider/refresh':'Request a provider rate',
 'get /loans/facilities/{id}/disbursements':'List facility disbursements','post /loans/facilities/{id}/disbursements':'Request loan disbursement','post /loans/disbursements/{id}/approve':'Approve loan disbursement','post /loans/facilities/{id}/accruals':'Accrue due loan interest','post /loans/facilities/{id}/repayments':'Apply a due loan repayment','get /loans/collections':'Review overdue installments',
 'post /loans/offers/{id}/expire':'Close an expired loan offer'
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
 const customer=getSession().user.userType==='CUSTOMER';
 const customerAccounts=customer?accountOptions.filter(a=>a.ACCOUNT_NUMBER):[];
 const hints=customer?'':accountHints('bank-account-numbers');
 const selectedAccount=customerAccounts.some(a=>a.ACCOUNT_NUMBER===transactionAccountNumber);
 const accountChoice=customer
  ?'<label class="field">'+esc(t('Account number'))+'<select name="accountNumber" required '+(customerAccounts.length?'':'disabled')+'><option value="" disabled '+(selectedAccount?'':'selected')+'>'+esc(t('Select an account'))+'</option>'+customerAccounts.map(a=>'<option value="'+esc(a.ACCOUNT_NUMBER)+'" '+(a.ACCOUNT_NUMBER===transactionAccountNumber?'selected':'')+'>'+esc(a.ACCOUNT_NUMBER)+' · '+esc(a.PRODUCT_NAME||a.PRODUCT_TYPE||t('Account'))+' · '+esc(t(a.ACCOUNT_STATUS||''))+'</option>').join('')+'</select></label>'
  :'<label class="field">Account number<input name="accountNumber" list="bank-account-numbers" maxlength="20" required value="'+esc(transactionAccountNumber)+'" placeholder="Enter an account number"></label>';
 const accountNotice=customer?(accountOptionsError?'<p class="form-error" role="alert">'+esc(t('Could not load your accounts.'))+' <button type="button" class="btn small" data-txn-accounts-retry>'+esc(t('Retry'))+'</button></p>':customerAccounts.length?'':'<p class="hint">'+esc(t('No linked accounts available.'))+'</p>'):'';
 const visibleRows=transactionRows?.slice(0,transactionVisibleCount)||[];
 const displayRows=visibleRows.map(row=>{
  const impact=row.amount==null?null:transactionAccountImpact(row);
  const amountCell=impact?'<span class="amount-value">'+esc(impact.text)+'</span><br><small class="muted">'+esc(impact.note)+'</small>':esc(t('Protected'));
  return [esc(row.transactionId),esc(row.type??'—'),impact?esc(transactionOtherParty(row)):esc(t('Protected')),amountCell,
   badge(row.status),esc(formatDate(row.valueDate)),
   impact?'<button type="button" class="btn small" data-txn-detail="'+esc(row.transactionId)+'">View details</button>':'<span class="muted">Details protected</span>'];
 });
 const rows=transactionRows===null?'<div class="empty">Choose an account to load its transactions.</div>':transactionRows.length?table(['Transaction','Type',t('Other party / description'),'Amount','Status','Value date','Details'],displayRows)+'<div class="form-actions"><span class="muted">Showing '+visibleRows.length+' of '+transactionRows.length+' recent transactions</span>'+(visibleRows.length<transactionRows.length?'<button class="btn small" type="button" data-txn-more>Show more</button>':'')+'</div>':'<div class="empty">No transactions found for this account.</div>';
 const detailImpact=transactionDetail&&transactionDetail.accountRole!=='INTERNAL'?transactionAccountImpact(transactionDetail):null;
 const detail=transactionDetail?'<section class="panel"><div class="panel-heading"><div><p class="eyebrow">'+(transactionDetail.accountRole==='INTERNAL'?'INTERNAL LEDGER DETAIL':'ACCOUNT-SCOPED DETAIL')+'</p><h3>Transaction '+esc(transactionDetail.transactionId)+'</h3></div>'+badge(transactionDetail.STATUS)+'</div><div class="panel-body"><dl class="details result-details">'+[
  ['Account role',transactionDetail.accountRole],['Type',transactionDetail.TXN_TYPE],['Other party / description',detailImpact?transactionOtherParty(transactionDetail):'—'],['Amount',detailImpact?detailImpact.text+' · '+detailImpact.note:formatAmount(transactionDetail.AMOUNT,transactionDetail.CURRENCY_CODE)],['Value date',formatDate(transactionDetail.VALUE_DATE)],['Created',formatDate(transactionDetail.CREATED_AT)],['Updated',formatDate(transactionDetail.UPDATED_AT)],['Failure code',transactionDetail.FAILURE_CODE],['Reversal of',transactionDetail.REVERSAL_OF_TXN_ID]
 ].map(([k,v])=>'<div><dt>'+esc(t(k))+'</dt><dd>'+esc(v??'—')+'</dd></div>').join('')+'</dl><h4>Status history</h4>'+table(['From','To','Reason','Changed'],(transactionDetail.statusHistory||[]).map(h=>[esc(h.FROM_STATUS??'Start'),badge(h.TO_STATUS),esc(h.REASON_TEXT||h.REASON_CODE||'—'),esc(formatDate(h.CHANGED_AT))]),'No status history recorded.')+'<h4>Linked journals</h4>'+(transactionDetail.journals||[]).map(j=>'<div class="ledger-journal"><div><strong>Journal '+esc(j.JOURNAL_ID)+'</strong> · '+esc(j.JOURNAL_TYPE)+' · '+esc(formatDate(j.BOOKED_AT))+' · '+(j.control?.IS_BALANCED==='Y'?'Balanced':'Check balance')+'</div><p class="hint">Debits '+esc(formatAmount(j.control?.DEBIT_TOTAL,'INR'))+' · Credits '+esc(formatAmount(j.control?.CREDIT_TOTAL,'INR'))+'</p>'+table(['Line','GL account','Side','Amount'],(j.accountPostings||[]).map(p=>[esc(p.LINE_NO),esc(p.GL_ACCOUNT_ID),badge(p.ENTRY_SIDE),esc(formatAmount(p.AMOUNT,'INR'))]),'No posting line belongs to the selected account.')+'</div>').join('')+'</div></section>':'';
 const rows=transactionRows===null?'<div class="empty">Choose an account to load its transactions.</div>':transactionRows.length?table(['Transaction','Type','Amount','Status','Value date','Details'],visibleRows.map(t=>[
  esc(t.transactionId),esc(t.type??'—')+(t.railCode?'<br><small>'+esc(t.railCode)+' · Payment '+esc(t.paymentId)+'</small>':''),t.amount==null?'Protected':esc(formatAmount(t.amount,t.currency)),badge(t.status)+(t.paymentStatus?'<br><small>'+esc(activityPaymentStatus(t.paymentStatus,t.accountRole))+'</small>':''),esc(formatDate(t.valueDate)),
  t.amount==null?'<span class="muted">Details protected</span>':'<button type="button" class="btn small" data-txn-detail="'+esc(t.transactionId)+'">View details</button>'
 ]))+'<div class="form-actions"><span class="muted">Showing '+visibleRows.length+' of '+transactionRows.length+' recent transactions</span>'+(visibleRows.length<transactionRows.length?'<button class="btn small" type="button" data-txn-more>Show more</button>':'')+'</div>':'<div class="empty">No transactions found for this account.</div>';
 const detail=transactionDetail?'<section class="panel"><div class="panel-heading"><div><p class="eyebrow">'+(transactionDetail.accountRole==='INTERNAL'?'INTERNAL LEDGER DETAIL':'ACCOUNT-SCOPED DETAIL')+'</p><h3>Transaction '+esc(transactionDetail.transactionId)+'</h3></div>'+badge(transactionDetail.STATUS)+'</div><div class="panel-body"><dl class="details result-details">'+[
  ['Account role',transactionDetail.accountRole],['Type',transactionDetail.TXN_TYPE],...(transactionDetail.PAYMENT_ID?[['Payment reference',transactionDetail.PAYMENT_ID],['Rail',transactionDetail.RAIL_CODE],['Interbank progress',activityPaymentStatus(transactionDetail.PAYMENT_STATUS,transactionDetail.accountRole)]]:[]),['Amount',formatAmount(transactionDetail.AMOUNT,transactionDetail.CURRENCY_CODE)],['Value date',formatDate(transactionDetail.VALUE_DATE)],['Created',formatDate(transactionDetail.CREATED_AT)],['Updated',formatDate(transactionDetail.UPDATED_AT)],['Failure code',transactionDetail.FAILURE_CODE],['Reversal of',transactionDetail.REVERSAL_OF_TXN_ID]
 ].map(([k,v])=>'<div><dt>'+esc(k)+'</dt><dd>'+esc(v??'—')+'</dd></div>').join('')+'</dl><h4>Status history</h4>'+table(['From','To','Reason','Changed'],(transactionDetail.statusHistory||[]).map(h=>[esc(h.FROM_STATUS??'Start'),badge(h.TO_STATUS),esc(h.REASON_TEXT||h.REASON_CODE||'—'),esc(formatDate(h.CHANGED_AT))]),'No status history recorded.')+'<h4>Linked journals</h4>'+(transactionDetail.journals||[]).map(j=>'<div class="ledger-journal"><div><strong>Journal '+esc(j.JOURNAL_ID)+'</strong> · '+esc(j.JOURNAL_TYPE)+' · '+esc(formatDate(j.BOOKED_AT))+' · '+(j.control?.IS_BALANCED==='Y'?'Balanced':'Check balance')+'</div><p class="hint">Debits '+esc(formatAmount(j.control?.DEBIT_TOTAL,'INR'))+' · Credits '+esc(formatAmount(j.control?.CREDIT_TOTAL,'INR'))+'</p>'+table(['Line','GL account','Side','Amount'],(j.accountPostings||[]).map(p=>[esc(p.LINE_NO),esc(p.GL_ACCOUNT_ID),badge(p.ENTRY_SIDE),esc(formatAmount(p.AMOUNT,'INR'))]),'No posting line belongs to the selected account.')+'</div>').join('')+'</div></section>':'';
 const internal=allow('GL_RECONCILE')?'<details class="ledger-internal"><summary>Look up an internal ledger transaction</summary><p class="hint">For a transaction with no customer account, such as a reserve opening or a bank adjustment. For account transactions, use the list above.</p><form id="internal-transaction-form" class="module-search"><label class="field">Transaction ID<input name="transactionId" inputmode="numeric" pattern="[0-9]+" required placeholder="Enter a transaction ID"></label><button type="submit" class="btn">View internal transaction</button></form><p id="internal-transaction-error" class="form-error" role="alert"></p></details>':'';
 return '<div id="transaction-activity"><section class="panel"><div class="panel-heading"><div><p class="eyebrow">TRANSACTION ACTIVITY</p><h3>Account transactions</h3></div></div><div class="panel-body"><p class="hint">'+(bankAdmin?'An active, global BANK_ADMIN assignment can review account transactions without a holder key. Access is recorded in the audit trail.':getSession().user.userType==='CUSTOMER'?'Your sign-in gives access to accounts linked to you.':'Your role and account scope are checked. An account holder must also provide a key for protected details.')+'</p>'+(!bankAdmin&&getSession().user.userType!=='CUSTOMER'?'<p class="hint">'+(getCustomerHash()?'A key is saved in this tab. Reload this account to check it.':'No account-holder key is saved in this tab.')+'</p><button type="button" class="btn small" data-action="nav" data-id="customerAccess">Open account-holder key section</button>':'')+'<form id="transaction-search-form" class="module-search">'+accountChoice+hints+'<button type="submit" class="btn primary" '+(customer&&!customerAccounts.length?'disabled':'')+'>'+esc(t('Load transactions'))+'</button></form>'+accountNotice+'<p id="transaction-error" class="form-error" role="alert"></p>'+rows+internal+'</div></section>'+detail+'</div>';
}
function glLedgerPanel(){
 if(!allow('GL_RECONCILE'))return '';
 const tr=value=>esc(t(value));
 const account=glLedger?.account,postings=glLedger?.postings||[];
 const options=(glLedgerAccounts||[]).map(a=>'<option value="'+esc(a.GL_ACCOUNT_ID)+'" label="'+esc(a.GL_CODE+' · '+a.GL_NAME)+'"></option>').join('');
 const summary=account?'<div class="gl-ledger-summary"><div><strong>'+esc(account.GL_CODE)+'</strong> · '+esc(account.GL_NAME)+'<p class="hint">'+tr('Account class')+': '+esc(account.ACCOUNT_CLASS)+' · '+tr('Normal side')+': '+esc(account.NORMAL_SIDE)+'</p></div><div><span class="muted">'+tr('Current GL balance')+'</span><strong class="amount-value">'+esc(formatAmount(glLedger.balance,account.CURRENCY_CODE))+'</strong></div></div>':'';
 const rows=glLedger===null?'<div class="empty">'+tr('Choose a GL account to view its posted entries.')+'</div>':postings.length?table(['Booked','Journal','Type','Debit','Credit','Running balance','Narrative','Details'].map(t),postings.map(p=>[
  esc(formatDate(p.BOOKED_AT)),esc(p.JOURNAL_ID),esc(p.JOURNAL_TYPE),p.ENTRY_SIDE==='DR'?esc(formatAmount(p.AMOUNT,'INR')):'—',p.ENTRY_SIDE==='CR'?esc(formatAmount(p.AMOUNT,'INR')):'—',esc(formatAmount(p.RUNNING_BALANCE,'INR')),esc(p.NARRATIVE||p.JOURNAL_DESCRIPTION||'—'),'<button type="button" class="btn small" data-gl-journal="'+esc(p.JOURNAL_ID)+'">'+tr('View journal')+'</button>'
 ]),t('No postings for this GL account.')):'<div class="empty">'+tr('No postings for this GL account.')+'</div>';
 const paging=glLedger&&glLedger.total>0?'<div class="form-actions"><button type="button" class="btn small" data-gl-page="previous" '+(glLedgerOffset===0?'disabled':'')+'>'+tr('Previous')+'</button><span class="muted">'+tr('Showing')+' '+esc(glLedgerOffset+1)+'–'+esc(glLedgerOffset+postings.length)+' '+tr('of')+' '+esc(glLedger.total)+' '+tr('postings')+'</span><button type="button" class="btn small" data-gl-page="next" '+(glLedgerOffset+postings.length>=glLedger.total?'disabled':'')+'>'+tr('Next')+'</button></div>':'';
 const detail=glLedgerJournal?(()=>{const j=glLedgerJournal.journal,c=glLedgerJournal.control;return '<div class="gl-journal-detail"><div class="section-heading-row"><h4>'+tr('Journal details')+' · '+esc(j.JOURNAL_ID)+'</h4><button type="button" class="btn small" data-gl-journal-close>'+tr('Close details')+'</button></div><p class="hint">'+esc(j.DESCRIPTION||'—')+' · '+esc(j.JOURNAL_TYPE)+' · '+esc(formatDate(j.BOOKED_AT))+'</p><dl class="details result-details">'+[
  [t('Value date'),formatDate(j.VALUE_DATE)],[t('Transaction ID'),j.TXN_ID],[t('Payment ID'),j.PAYMENT_ID],[t('Reversal of journal'),j.REVERSAL_OF_JOURNAL_ID]
 ].map(([key,value])=>'<div><dt>'+esc(key)+'</dt><dd>'+esc(value??'—')+'</dd></div>').join('')+'</dl><div class="notice">'+tr('Balance control')+': '+esc(c.IS_BALANCED==='Y'?t('Balanced'):t('Difference found'))+' · '+tr('Total debits')+' '+esc(formatAmount(c.DEBIT_TOTAL,'INR'))+' · '+tr('Total credits')+' '+esc(formatAmount(c.CREDIT_TOTAL,'INR'))+' · '+tr('Difference')+' '+esc(formatAmount(c.BALANCE_DIFFERENCE,'INR'))+'</div>'+table(['Line','GL account','Debit','Credit','Narrative','Customer account ID','Loan facility ID'].map(t),(glLedgerJournal.lines||[]).map(p=>[
  esc(p.LINE_NO),esc(p.GL_CODE)+' · '+esc(p.GL_NAME),p.ENTRY_SIDE==='DR'?esc(formatAmount(p.AMOUNT,'INR')):'—',p.ENTRY_SIDE==='CR'?esc(formatAmount(p.AMOUNT,'INR')):'—',esc(p.NARRATIVE||'—'),esc(p.BANK_ACCOUNT_ID??'—'),esc(p.LOAN_FACILITY_ID??'—')
 ]),t('No journal lines found.'))+'</div>';})():'';
 return '<section class="panel" id="gl-ledger-section"><div class="panel-heading"><div><p class="eyebrow">'+tr('GENERAL LEDGER')+'</p><h3>'+tr('GL account ledger')+'</h3></div></div><div class="panel-body"><p class="hint">'+tr('Review every posted entry for one GL account. Running balances follow the account’s normal debit or credit side.')+'</p><form id="gl-ledger-form" class="module-search"><label class="field">'+tr('GL account ID')+'<input data-jet-source="true" name="glAccountId" list="gl-ledger-accounts" inputmode="numeric" pattern="[0-9]+" required value="'+esc(glLedgerAccountId)+'" placeholder="'+tr('Enter a GL account ID')+'"></label><datalist id="gl-ledger-accounts">'+options+'</datalist><button type="submit" class="btn primary">'+tr('Load GL ledger')+'</button></form><p id="gl-ledger-error" class="form-error" role="alert"></p>'+summary+rows+paging+detail+'</div></section>';
}
async function pendingReversalsPanel(){
 let body;
 try{body=pendingReversalsTable(await api('/transactions/reversal-requests'));}
 catch(error){body='<div class="notice" role="alert">'+esc(errorText(error))+'</div>';}
 return '<section class="panel" id="pending-reversals"><div class="panel-heading"><h3>Pending reversal requests</h3><button class="btn small" type="button" id="reversals-refresh">Refresh</button></div><div class="panel-body"><p class="hint">Review the original transfer, amount and reason before deciding. Approval reverses the transfer. A different user must review the maker’s request.</p><p class="form-error" id="reversal-error" role="alert"></p>'+body+'</div></section>';
}
function pendingReversalsTable(rows){
 return table(['Request','Original transfer','From account','To account','Amount','Reason','Requested','Decision'],rows.map(row=>{
  const own=row.MAKER_ID===getSession()?.user?.userId;
  const actions=['APPROVED','REJECTED'].map(decision=>'<button type="button" class="btn small '+(decision==='APPROVED'?'primary':'')+'" data-reversal-id="'+esc(row.TXN_ID)+'" data-reversal-decision="'+decision+'" '+(own?'disabled':'')+'>'+(decision==='APPROVED'?'Approve':'Reject')+'</button>').join(' ');
  return [esc(row.TXN_ID),esc(row.REVERSAL_OF_TXN_ID),esc(row.TARGET_ACCOUNT_NUMBER||row.TARGET_ACCOUNT_ID),esc(row.SOURCE_ACCOUNT_NUMBER||row.SOURCE_ACCOUNT_ID),esc(formatAmount(row.AMOUNT,row.CURRENCY_CODE)),esc(row.REASON_TEXT),esc(formatDate(row.REQUESTED_AT)),actions+(own?'<span class="sub">Another checker must decide your request.</span>':'')];
 }),'No pending reversal requests.');
}
function bindReversalForms(){
 const panel=document.querySelector('#pending-reversals');
 if(!panel)return;
 panel.querySelector('#reversals-refresh').onclick=async()=>{await renderApp();};
 panel.querySelectorAll('[data-reversal-id]').forEach(button=>button.onclick=async()=>{
  const buttons=[...panel.querySelectorAll('button')];
  const disabled=buttons.map(item=>item.disabled);
  buttons.forEach(item=>item.disabled=true);
  panel.querySelector('#reversal-error').textContent='';
  try{
   await api('/transactions/'+encodeURIComponent(button.dataset.reversalId)+'/reversal-decision',{method:'POST',body:{decision:button.dataset.reversalDecision,reasonText:'Reviewed in pending reversal queue'}});
   toast(button.dataset.reversalDecision==='APPROVED'?'Reversal approved.':'Reversal rejected.');
   await renderApp();
  }catch(error){panel.querySelector('#reversal-error').textContent=errorText(error);buttons.forEach((item,index)=>item.disabled=disabled[index]);}
 });
}
function transactionTabs(){
 const tabs=[['accounts','Account transactions'],...(allow('TXN_REVERSE_APPROVE')?[['reversals','Pending reversals']]:[]),...(allow('GL_RECONCILE')?[['gl','GL account ledger']]:[]),...(getSession().user.userType==='CUSTOMER'?[]:[['operations','Operations']])];
 if(!tabs.some(([id])=>id===transactionSection))transactionSection='accounts';
 if(tabs.length===1)return '';
 return '<div class="transaction-tabs" role="tablist" aria-label="'+esc(t('Transactions & ledger sections'))+'">'+tabs.map(([id,title])=>'<button type="button" role="tab" id="transaction-tab-'+id+'" aria-controls="transaction-section-'+id+'" aria-selected="'+(transactionSection===id)+'" tabindex="'+(transactionSection===id?'0':'-1')+'" data-transaction-tab="'+id+'">'+esc(t(title))+'</button>').join('')+'</div>';
}
function transactionTabPanel(section,content){
 const sections=['accounts',...(allow('TXN_REVERSE_APPROVE')?['reversals']:[]),...(allow('GL_RECONCILE')?['gl']:[]),...(getSession().user.userType==='CUSTOMER'?[]:['operations'])];
 if(sections.length===1)return content;
 return sections.map(id=>'<div id="transaction-section-'+id+'" role="tabpanel" aria-labelledby="transaction-tab-'+id+'" tabindex="0"'+(id===section?'':' hidden')+'>'+(id===section?content:'')+'</div>').join('');
}
function treasuryPanel(){
 const allowed=allow('TREASURY_READ');
 const options=reserveAccounts.map(a=>'<option value="'+esc(a.id)+'" '+(String(a.id)===String(reserveAccountId)?'selected':'')+'>'+esc(a.code)+' · '+esc(a.accountType)+'</option>').join('');
 const position=reservePosition?'<div class="stats"><div class="stat"><div class="stat-title">Confirmed balance</div><div class="stat-value amount-value">'+esc(formatAmount(reservePosition.confirmedBalance,reservePosition.currency))+'</div></div><div class="stat"><div class="stat-title">Active liquidity holds</div><div class="stat-value amount-value">'+esc(formatAmount(reservePosition.activeHoldAmount,reservePosition.currency))+'</div></div><div class="stat"><div class="stat-title">Available reserve</div><div class="stat-value amount-value">'+esc(formatAmount(reservePosition.availableBalance,reservePosition.currency))+'</div></div></div><p class="hint"><span>Position as of</span> '+esc(formatDate(reservePosition.asOf))+'</p>':'';
 const recon=reserveReconciliation?'<div class="notice">Reserve mirror reconciliation: <strong>'+esc(reserveReconciliation.isMatched==='Y'?'Matched':'Difference found')+'</strong> · Ledger-derived '+esc(formatAmount(reserveReconciliation.ledgerBalance,reserveReconciliation.currency))+' · Difference '+esc(formatAmount(reserveReconciliation.difference,reserveReconciliation.currency))+'</div>':'';
 const ledger=reserveLedger===null?'<div class="empty">Select a reserve account to view confirmed movements.</div>':reserveLedger.length?table(['Entry','Settlement time','Rail','Direction','Amount','Evidence','GL journal','External reference'],reserveLedger.map(e=>[esc(e.id),esc(formatDate(e.settledAt)),esc(e.railCode),badge(e.movementSide),esc(formatAmount(e.amount,e.currency)),badge(e.evidenceStatus),esc(e.glJournalId),esc(e.externalSettlementRef)])):'<div class="empty">No confirmed reserve movements for this account.</div>';
 return '<section class="panel"><div class="panel-heading"><div><p class="eyebrow">RBI LEDGER</p><h3>Local reserve ledger</h3></div></div><div class="panel-body"><div class="notice">This is the bank’s simulated local mirror of RBI/RTGS reserve movements. It is not a live RBI connection. Confirmed entries are backed by settlement evidence and a Module 5 GL journal.</div>'+(reserveAccounts.length?'<form id="reserve-ledger-form" class="module-search"><label class="field">Reserve account<select name="reserveAccountId" required>'+options+'</select></label><button type="submit" class="btn primary">View reserve</button></form>':'<div class="empty">No reserve account is configured.</div>')+'<p id="reserve-error" class="form-error" role="alert"></p>'+position+(allowed?recon+'<h4>Confirmed movements</h4>'+ledger+(reserveLedger!==null?'<div class="form-actions"><button class="btn small" type="button" data-reserve-page="previous" '+(reserveOffset===0?'disabled':'')+'>Previous</button><span class="muted"><span>Entries</span> '+esc(reserveOffset+1)+'–'+esc(reserveOffset+reserveLedger.length)+'</span><button class="btn small" type="button" data-reserve-page="next" '+(reserveLedger.length<50?'disabled':'')+'>Next</button></div>':''):'<p class="hint">Reserve ledger and reconciliation require Treasury Read or Reconcile access.</p>')+'</div></section>';
}
function treasuryOperationAllowed(op){
 const path=op.path,method=op.method;
 if(!path.startsWith('/treasury/'))return true;
 if(path==='/treasury/reserve-openings')return method==='GET'?getSession()?.user?.permissions?.includes('TREASURY_READ'):allow('TREASURY_LIQUIDITY_MANAGE')&&allow('GL_ADMIN');
 if(path.startsWith('/treasury/reserve-openings/'))return allow('TREASURY_WORK_APPROVE');
 if(method==='GET'){
  if(path.startsWith('/treasury/liquidity-holds'))return allow('TREASURY_READ')||allow('TREASURY_LIQUIDITY_MANAGE');
  if(path==='/treasury/reserve-accounts'||path==='/treasury/reserve-accounts/{id}'||path==='/treasury/reserve-accounts/{id}/position')return allow('TREASURY_READ')||allow('TREASURY_LIQUIDITY_MANAGE');
  if(path==='/treasury/work-items/{id}')return allow('TREASURY_READ')||allow('TREASURY_WORK_APPROVE');
  return allow('TREASURY_READ');
 }
 if(path==='/treasury/work-items/{id}/decision')return allow('TREASURY_WORK_APPROVE')||allow('TREASURY_RECONCILE');
 if(path==='/treasury/reserve-accounts'||path.startsWith('/treasury/liquidity-holds'))return allow('TREASURY_LIQUIDITY_MANAGE');
 return allow('TREASURY_RECONCILE');
}
function defaults(name,s){
 const n=name.split('.').pop();
 const op=operations.find(o=>o.id===selected[active]);const saved=op&&operationValues[operationKey(op)]?.[name];if(saved!==undefined)return saved;
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
  const current=operations.find(o=>o.id===selected[active]);
  if(!prefix&&name==='subjectService'&&current?.path.startsWith('/privacy/consents/'))return select('Subject service',key,['CIF'],'CIF',true);
  if(!prefix&&operationReferences[name]){const source=operationReferences[name];return select(label(name==='id'?'Select record':name),key,[{value:'',label:req?'Choose a record':'All records'},...source.rows.map(row=>{const id=recordId(row,source.path,name);return {value:String(id),label:recordLabel(row,id)};})],String(defaults(key,s)),req);}
  if(!prefix&&/status|state/i.test(name)&&!s.enum&&!s.pattern){const states=statusChoices(current?.path||'');if(states.length)return select(label(name),key,[{value:'',label:'All states'},...states],defaults(key,s),req);}
  if(active==='privacy'&&getSession()?.user?.userType==='CUSTOMER'&&!prefix){
   const consent=operations.find(o=>o.id===selected[active])?.path.startsWith('/privacy/consents/');
   if(consent){
    const current=operations.find(o=>o.id===selected[active]);
    const purposes=current.method==='POST'?customerPurposeNotices.filter(p=>p.legalBasisCode==='CONSENT'):customerPurposeNotices;
    if(name==='subjectService'||name==='channelCode')return '<input type="hidden" name="'+esc(key)+'" value="'+(name==='subjectService'?'CIF':'WEB')+'">';
    if(name==='subjectExternalId'){const cifs=getSession().user.cifIds||[];return cifs.length===1?'<input type="hidden" name="'+esc(key)+'" value="'+esc(cifs[0])+'">':select('Your linked profile',key,cifs.map(cif=>({value:cif,label:cif})),cifs[0]||'',true);}
    if(name==='purposeCode')return select('Privacy choice',key,purposes.map(p=>({value:p.code,label:p.name})),purposes[0]?.code||'',true);
    if(name==='noticeSha256')return '<input type="hidden" name="'+esc(key)+'" value="'+esc(purposes[0]?.noticeSha256||'')+'">';
    if(name==='decision')return select('Your choice',key,[{value:'GRANTED',label:'Allow optional use'},{value:'DENIED',label:'Do not allow optional use'}],'GRANTED',true);
    if(['sourceConsentId','effectiveAt','expiresAt','evidenceUri','evidenceSha256'].includes(name))return '';
   }
  }
  if(!prefix&&name==='cashGlId'&&active==='teller'&&operations.find(o=>o.id===selected[active])?.path==='/teller/tills'){
   if(cashLedgerAccounts?.length===1){const account=cashLedgerAccounts[0];return '<input type="hidden" name="cashGlId" value="'+esc(account.GL_ACCOUNT_ID)+'"><div class="field"><span>Cash ledger account</span><strong>'+esc(account.GL_NAME)+' ('+esc(account.GL_CODE)+')</strong><small>Selected automatically for this till.</small></div>';}
   return select('Cash ledger account',key,[{value:'',label:'Choose a cash ledger account'},...(cashLedgerAccounts||[]).map(account=>({value:String(account.GL_ACCOUNT_ID),label:account.GL_NAME+' ('+account.GL_CODE+')'}))],'',true);
  }
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
async function loadOperationReferences(op){
 const values=operationValues[operationKey(op)]||={};
 const listRows=value=>{for(let depth=0;depth<3;depth++){if(Array.isArray(value))return value;value=value?.items||value?.content||value?.records||value?.data;}return [];};
 for(const parameter of (op.parameters||[]).filter(p=>p.in==='path')){
  let prefix=op.path.slice(0,op.path.indexOf('{'+parameter.name+'}')).replace(/\/$/,'');
  if(op.path==='/beneficiaries/{id}/verify')prefix='/beneficiaries/pending';
  const listPath=prefix==='/accounts'?'/banking/accounts':prefix;
  const listOp=operations.find(item=>item.method==='GET'&&item.path===listPath);
  if(!listOp)continue;
  const path=requestPath(listOp,values);if(!path)continue;
  const rows=listRows(await api(path));operationReferences[parameter.name]={rows,path:listPath};
  if(!rows.some(row=>String(recordId(row,prefix,parameter.name))===String(values[parameter.name])))values[parameter.name]=rows.length?recordId(rows[0],prefix,parameter.name):'';
  operationRecords=rows;operationCollection=prefix;
 }
 const sources={paymentId:'/payments',reserveAccountId:'/treasury/reserve-accounts',settlementCycleId:'/treasury/settlement-cycles',clearingBatchId:'/clearing-batches',dispatchId:'/dispatches',approvalId:'/approvals',exceptionId:'/reconciliation-exceptions',facilityId:'/banking/facilities',caseId:'/privacy/cases',purposeCode:'/privacy/purposes',subjectExternalId:'/cif/customers',ownerId:'/iam/users?size=100',actorExternalId:'/iam/users?size=100'};
 const fields=Object.keys(schemaFor(op).properties).filter(name=>sources[name]&&!operationReferences[name]);
 const loaded=await Promise.allSettled(fields.map(async name=>({name,rows:listRows(await api(sources[name])),path:sources[name]})));
 for(const result of loaded)if(result.status==='fulfilled'){
  const item=result.value;
  if(item.name==='purposeCode')item.rows=item.rows.map(row=>({...row,purposeCode:row.purposeCode||row.code||row.PURPOSE_CODE}));
  if(['ownerId','actorExternalId'].includes(item.name))item.rows=item.rows.map(row=>({...row,[item.name]:row.userId||row.USER_ID}));
  if(item.name==='subjectExternalId')item.rows=item.rows.map(row=>({...row,subjectExternalId:row.cifId||row.CIF_ID}));
  operationReferences[item.name]=item;
  const parameter=op.parameters?.find(p=>p.name===item.name);
  if(parameter?.required&&values[item.name]===undefined&&item.rows.length)values[item.name]=recordId(item.rows[0],item.path,item.name);
 }else throw result.reason;
 if(op.path.startsWith('/privacy/consents/')&&Object.hasOwn(schemaFor(op).properties,'subjectService'))values.subjectService='CIF';
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
 active=page;const config=bankingPages[page];
 const customer=getSession().user.userType==='CUSTOMER';
 const head='<div class="page-heading"><div><p class="eyebrow">'+(customer?'YOUR BANKING':'BANKING OPERATIONS')+'</p><h1>'+esc(customer?customerPageLabels[page]||config.title:config.title)+'</h1><p>'+esc(customer?customerPageDescriptions[page]||config.intro:config.intro)+'</p></div></div>';
 if(config.staffOnly&&customer)return head+'<p class="notice">Employee access required.</p>';
 if(page==='simulator')return head+await simulatorScreen();
 if(page==='errorLogs'){const logs=await api('/employee-errors');return head+'<section class="panel"><div class="panel-heading"><h3>Recent application errors</h3><button class="btn small" data-error-refresh>Refresh</button></div><div class="panel-body">'+table(['Time','Service','Error code','Message','Reference'],logs.map(row=>[esc(formatDate(row.OCCURRED_AT)),esc(row.SERVICE_NAME),esc(row.ERROR_CODE),esc(row.SAFE_MESSAGE),esc(row.CORRELATION_ID||row.ERROR_ID)]))+'</div></section>';}
 if(page==='creditCards')return head+await creditCardsScreen(renderApp);
 if(page==='payments'&&customer)return head+await customerPaymentsScreen();
 if(page==='currency'&&customer)return head+await customerFxScreen();
 if(page==='customerAccess')return customer
  ?head+'<section class="panel"><div class="panel-heading"><h3>Your account access key</h3></div><div class="panel-body"><div class="notice">Your sign-in already lets you view transactions for accounts linked to you. You do not need a key for your own details.</div><p>If an authorized bank officer needs to view your account details, you can generate a new key and give it to them privately. The previous key stops working immediately. An existing key cannot be displayed again.</p><button type="button" class="btn primary" data-bank-rotate>Generate a new account-holder key</button><div id="hash-result" role="status" aria-live="polite"></div></div></section>'
  :staffAccessPage(head);
 if(page==='banking'){
  const [d,self,selfService,extras]=await Promise.all([api('/banking/overview'),customer?api('/banking/my-dashboard'):Promise.resolve(null),customer?api('/auth/signup-availability'):Promise.resolve(null),customer?Promise.allSettled([allow('CC_READ')?api('/credit-cards'):Promise.resolve([]),allow('PAYMENT_READ')?api('/payments'):Promise.resolve([])]):Promise.resolve([])]);
  const accounts=self?.accounts||[];
  const cards=extras[0]?.status==='fulfilled'?extras[0].value:[];
  const payments=extras[1]?.status==='fulfilled'?extras[1].value:[];
  const balanceCards=accounts.map(a=>'<div class="stat"><div class="stat-title">'+esc(t('Account ending'))+' '+esc(a.accountEnding)+' · '+esc(a.status)+'</div><div class="stat-value amount-value">'+(a.posted===undefined?t('Balance unavailable'):esc(formatAmount(a.posted,a.currency)))+'</div><small class="stat-balance-detail"><span>'+esc(t('Available balance'))+'</span><span class="amount-value">'+(a.spendable===undefined?'—':esc(formatAmount(a.spendable,a.currency)))+'</span></small></div>').join('');
  const recent=accounts.flatMap(a=>(a.recentTransactions||[]).map(t=>({...t,accountEnding:a.accountEnding}))).sort((a,b)=>new Date(b.createdAt)-new Date(a.createdAt)).slice(0,10);
  const customerSummary=customer?'<section class="panel"><div class="panel-heading"><h3>Your accounts</h3><span class="muted">Based on your signed-in identity</span></div><div class="panel-body">'+(accounts.length?'<div class="stats">'+balanceCards+'</div>':'<div class="empty">'+(selfService.enabled?'You do not have an account yet. Browse eligible products to open one.':'You do not have an account yet. Contact the bank to open one.')+'</div>')+(selfService.enabled?'<button class="btn primary" data-action="nav" data-id="accounts" type="button">'+(accounts.length?'Open another account':'Open an account')+'</button>':'')+(self.truncated?'<p class="hint">Showing the first 20 accounts.</p>':'')+'<h3>Recent transactions</h3>'+(recent.length?table(['Account','Transaction','Type','Amount','Status'],recent.map(t=>[esc(t.accountEnding),esc(t.transactionId),esc(t.type),'<span class="amount-value">'+esc(formatAmount(t.amount,t.currency))+'</span>',badge(t.status)])):'<div class="empty">No transactions yet.</div>')+'</div></section>':'';
  const cardSummary=customer&&allow('CC_READ')?'<section class="panel"><div class="panel-heading"><h3>Your credit cards</h3><button type="button" class="btn small" data-action="nav" data-id="creditCards">View cards</button></div><div class="panel-body">'+(extras[0]?.status==='rejected'?'<div class="notice">Card information is temporarily unavailable.</div>':table(['Card','Status','Outstanding'],cards.slice(0,3).map(c=>[esc(c.PRODUCT_NAME)+' · •••• '+esc(c.DISPLAY_ENDING),badge(c.STATUS),esc(formatAmount(c.OUTSTANDING_BALANCE,'INR'))]),'No credit cards yet'))+'</div></section>':'';
  const paymentSummary=customer&&allow('PAYMENT_READ')?'<section class="panel"><div class="panel-heading"><h3>Recent payment requests</h3><button type="button" class="btn small" data-action="nav" data-id="payments">Send or track money</button></div><div class="panel-body">'+(extras[1]?.status==='rejected'?'<div class="notice">Payment status is temporarily unavailable.</div>':table(['Reference','Amount','Progress'],payments.slice(0,5).map(p=>[esc(p.paymentId),esc(formatAmount(p.amount,'INR')),esc(paymentStatus(p.status))]),'No payment requests yet'))+'</div></section>':'';
  const customerActivity=cardSummary||paymentSummary?'<div class="split">'+cardSummary+paymentSummary+'</div>':'';
  const metrics=[['customers','Customers'],['accounts','Accounts'],['transactions','Posted transactions'],['pendingPayments','Pending payments'],['pendingLoans','Pending loan applications']].filter(([key])=>typeof d[key]==='number');
  const metricCards=metrics.map(([key,title])=>'<div class="stat"><div class="stat-title">'+title+'</div><div class="stat-value">'+Number(d[key]).toLocaleString('en-IN')+'</div></div>').join('');
  return workspaceHero(getSession().user,allow)+customerSummary+customerActivity+(customer?'':'<section aria-label="Key figures"><div class="section-heading-row"><h2 class="section-heading">Current position</h2><span class="overview-mode">'+esc(d.mode)+'</span></div><div class="stats">'+metricCards+'</div></section>')+'<section aria-label="Banking areas"><h2 class="section-heading">'+(customer?'More ways to bank':'Your banking tools')+'</h2><div class="module-grid">'+Object.entries(bankingPages).filter(([k,v])=>!['banking','customerAccess'].includes(k)&&(!v.staffOnly||!customer)&&(allow(v.permission)||(k==='treasury'&&allow('TREASURY_LIQUIDITY_MANAGE')))).map(([k,v])=>'<button class="module-card" data-action="nav" data-id="'+k+'"><span class="module-icon" aria-hidden="true">'+icon(k)+'</span><strong>'+esc(customer?customerPageLabels[k]||v.title:v.title)+'</strong><span>'+esc(customer?customerPageDescriptions[k]||v.intro:v.intro)+'</span><b>Open '+esc(customer?customerPageLabels[k]||v.title:v.title)+' →</b></button>').join('')+'</div></section>';
 }
 if(page==='accounts'&&customer)return head+await customerAccountsScreen();
  if(page==='loans'&&customer)return head+await customerLoansScreen()+'<h2 class="section-heading">More loan operations</h2>'+await bankingOperationsScreen(page,config,customer,'');
 if(page==='treasury'&&!allow('TREASURY_READ')&&!allow('TREASURY_LIQUIDITY_MANAGE'))return head+'<section class="panel"><div class="panel-heading"><h3>Reserve ledger access</h3></div><div class="panel-body"><div class="notice">This bank-wide reserve ledger requires Treasury read access. Ask an administrator to assign that permission through the normal access request and approval process.</div></div></section>';
 if(page==='transactions'){
  const tabs=transactionTabs();
  if(transactionSection==='reversals'&&allow('TXN_REVERSE_APPROVE'))return head+tabs+transactionTabPanel('reversals',await pendingReversalsPanel());
  if(transactionSection==='accounts'){
   if(customer)await loadAccountOptions();
   return head+tabs+transactionTabPanel('accounts',transactionPanel());
  }
  if(transactionSection==='gl')return head+tabs+transactionTabPanel('gl',glLedgerPanel());
 }
 await loadContract();
 if(page==='treasury'){
  reserveAccounts=await api('/treasury/reserve-accounts');
  if(reserveAccounts.length)await loadReserve(reserveAccountId||reserveAccounts[0].id,reserveOffset);
  if(!allow('TREASURY_LIQUIDITY_MANAGE')&&!allow('TREASURY_RECONCILE')&&!checker())return head+treasuryPanel();
 }
  return bankingOperationsScreen(page,config,customer,head);
}
async function bankingOperationsScreen(page,config,customer,head){
 await loadContract();
 let availabilityNotice='',paymentEnabled=true,consentEnabled=true;
 if(page==='payments'){
  try{paymentEnabled=(await api('/payments/availability')).initiationEnabled===true;}
  catch(error){paymentEnabled=false;availabilityNotice='<div class="notice" role="alert">'+esc(errorText(error))+'</div>';}
  if(!paymentEnabled)availabilityNotice+='<div class="notice">Payment simulation is disabled. Existing payment records remain available. Live payment networks are not connected.</div>';
 }
 if(page==='privacy'&&customer){
  try{consentEnabled=(await api('/privacy/consents/availability')).decisionsEnabled===true;}
  catch(error){consentEnabled=false;availabilityNotice='<div class="notice" role="alert">'+esc(errorText(error))+'</div>';}
  if(!consentEnabled)availabilityNotice+='<div class="notice">Changes to privacy preferences are temporarily unavailable. Contact the bank. You can still read notices and review saved choices.</div>';
 }
 const adminAccounts=page==='accounts'&&getSession()?.user?.roles?.includes('BANK_ADMIN');
 const choices=operations.filter(o=>(paymentEnabled||!(o.method==='POST'&&/^\/payments\/(?:initiate|\{id\}\/(?:authorize-simulation|simulate-outcome))$/.test(o.path)))&&(consentEnabled||!(o.method==='POST'&&o.path.startsWith('/privacy/consents/')))&&config.prefix.some(p=>o.path===p||o.path.startsWith(p.endsWith('/')?p:p+'/'))&&workflowAllowed(o,customer)&&(page!=='transactions'||!transactionReadOperations.has(o.method+' '+o.path))&&!(adminAccounts&&o.method==='GET'&&o.path==='/accounts/by-number/{number}')&&(!checker()||o.method==='GET'||o.method==='POST'&&checkerDecisionPath.test(o.path))&&!(page==='loans'&&!customer&&o.path==='/loans/offers/{id}/accept')&&(page!=='treasury'||treasuryOperationAllowed(o)));
 const initial={accounts:'/banking/accounts',transactions:'/banking/transactions',teller:'/teller/tills',beneficiaries:'/beneficiaries',payments:'/payments',treasury:'/treasury/reserve-accounts',loans:'/banking/facilities',currency:'/fx/rates',privacy:customer?'/privacy/purposes/available':'/privacy/purposes'}[page];
 const op=choices.find(o=>o.id===selected[page])||choices.find(o=>o.method==='GET'&&o.path===initial)||choices.find(o=>o.method==='GET'&&!o.path.includes('{'))||choices[0];
 if(!op){const empty='<div class="notice">No workflow is available in this deployment.</div>';return head+(page==='transactions'?transactionTabs()+transactionTabPanel('operations',empty):empty);}
 const firstLoad=!selected[page];selected[page]=op.id;let initialError='';
 results[page]=operationResults[operationKey(op)];operationRecords=[];operationCollection='';operationReferences={};
 try{await loadOperationReferences(op);}catch(error){initialError=errorText(error);}
 if(page==='teller'&&op.path==='/teller/tills'&&op.method==='POST'&&cashLedgerAccounts===null){
  try{
   try{cashLedgerAccounts=await api('/teller/cash-ledger-accounts');}
   catch(error){
    if(error.status!==404)throw error;
    const accounts=await api('/banking/gl-accounts');
    cashLedgerAccounts=accounts.filter(account=>account.ACCOUNT_CLASS==='ASSET'&&/cash/i.test(String(account.GL_CODE||'')+' '+String(account.GL_NAME||'')));
   }
   cashLedgerError='';
  }
  catch(error){cashLedgerAccounts=null;cashLedgerError=errorText(error);}
 }
 if(page==='privacy'&&customer){
  try{customerPurposeNotices=await api('/privacy/purposes/available');if(op.path===initial)results[page]=customerPurposeNotices;}
  catch(error){customerPurposeNotices=[];results[page]=null;initialError=errorText(error);}
 }else if(op.method==='GET'){const path=requestPath(op,operationValues[operationKey(op)]||{});if(path)try{results[page]=await api(path);operationResults[operationKey(op)]=results[page];}catch(error){initialError=errorText(error);}}
 const schema=schemaFor(op);
 const simpleInquiry=op.method==='GET'&&!Object.keys(schema.properties).length;
 const operationList=(items,group)=>items.length?'<div class="operation-group"><p class="operation-group-title">'+group+'</p>'+items.map(item=>'<button type="button" class="operation-choice '+(item.id===op.id?'active':'')+'" data-bank-operation="'+esc(item.id)+'" '+(item.id===op.id?'aria-current="true"':'')+'><span class="operation-choice-label">'+esc(item.title)+'</span></button>').join('')+'</div>':'';
 const customerLoanCatalog=page==='loans'&&customer;
 const catalog='<aside class="panel operation-catalog'+(customerLoanCatalog?' customer-loan-catalog expanded':'')+'"><div class="panel-heading"><div><h3>Operations</h3><span class="muted">'+choices.length+' available</span></div><button id="operation-catalog-toggle" type="button" aria-expanded="'+(customerLoanCatalog?'true':'false')+'" aria-controls="operation-catalog-body">Choose operation</button></div><div class="panel-body" id="operation-catalog-body"><label class="field">Find an operation<input id="operation-search" type="search" placeholder="Search by name" autocomplete="off"></label><div class="operation-list">'+operationList(choices.filter(item=>item.method==='GET'),'View and inquire')+operationList(choices.filter(item=>item.method!=='GET'),'Create and maintain')+'</div><p class="operation-empty" hidden>No operations match this search.</p></div></aside>';
 const openingTill=page==='teller'&&op.path==='/teller/tills'&&op.method==='POST';
 const noCashLedger=openingTill&&!cashLedgerAccounts?.length;
 const cashLedgerNotice=noCashLedger?'<div class="notice">'+esc(cashLedgerError||'No active cash ledger account is configured. Ask an administrator to configure one before opening a till.')+'</div>':'';
 const tillOpeningNotice=openingTill?'<div class="notice">Opening a till allocates INR 5,000 from counted branch vault cash. The vault decreases, the till increases, and a balanced cash journal records the transfer. At independent close, counted cash returns to the vault. The RBI reserve balance does not change. The branch vault must be registered first.</div>':'';
 const deliveryNotice=page==='teller'&&op.path.startsWith('/teller/cash-deliveries')&&op.method==='POST'?'<div class="notice">Demo simulation: request a cash shipment against an active RBI current reserve. A different authorized checker must confirm the counted notes and receipt reference. Only confirmation moves the simulated RBI reserve and branch vault in one balanced posting. Do not use a live RBI reference.</div>':'';
 const vaultNotice=page==='teller'&&op.path==='/teller/vaults'&&op.method==='POST'?'<div class="notice">For a new empty vault, enter counted cash 0. Select a dedicated active cash asset GL that has no postings or till assignment. The subsequent RBI cash delivery funds it.</div>':'';
 const reserveOpeningNotice=page==='treasury'&&op.path.startsWith('/treasury/reserve-openings')&&op.method==='POST'?'<div class="notice">Synthetic demo opening: a bank administrator records the opening capital evidence. A different checker confirms it. Confirmation posts a balanced reserve asset and capital journal and creates the first entry in the local RBI mirror. No RBI network or physical cash movement occurs at this step.</div>':'';
 const replenishNotice=page==='teller'&&op.path.endsWith('/replenish')&&op.method==='POST'?'<div class="notice">A second authorized person counts cash moved from the branch vault into this open till. The vault decreases and the till increases; the RBI reserve does not move again.</div>':'';
 const privacyReview=customer&&page==='privacy'&&op.path.endsWith('/decisions')?'<div class="span2"><div id="customer-notice-review"></div><label><input type="checkbox" name="noticeReviewed" required> I have read the privacy notice.</label></div>':'';
 const formPanel='<section class="panel workflow-panel"><div class="panel-heading"><div><p class="eyebrow">'+(op.method==='GET'?'INQUIRY':'MAINTENANCE')+'</p><h3>'+esc(op.title)+'</h3></div></div><div class="panel-body"><p class="hint">'+esc(op.description||'Complete the fields below. Your access is checked when you submit.')+'</p>'+cashLedgerNotice+tillOpeningNotice+deliveryNotice+vaultNotice+reserveOpeningNotice+replenishNotice+'<form id="bank-workflow"><div class="grid2">'+inputs(schema,'',schema.required)+privacyReview+'</div><p class="form-error" role="alert"></p><div class="form-actions"><button type="submit" class="btn primary" '+(noCashLedger?'disabled':'')+'>'+(op.method==='GET'?'View records':'Submit operation')+'</button></div></form></div></section>';
 const resultPanel='<section class="panel result-panel" '+(op.method!=='GET'&&!results[page]?'hidden':'')+'><div class="panel-heading"><div><p class="eyebrow">'+(op.method==='GET'?'RECORDS':'TRANSACTION OUTCOME')+'</p><h3>'+esc(op.method==='GET'?op.title:'Operation result')+'</h3></div>'+(simpleInquiry?'<button class="btn small" id="bank-refresh" type="button">Refresh</button>':'<span class="muted">'+esc(op.title)+'</span>')+'</div><div class="panel-body" id="bank-result">'+(initialError?'<p class="error-panel" role="alert">'+esc(initialError)+'</p>':results[page]?renderOperationResult(op,results[page]):'<div class="empty"><h3>No result to display</h3><p>Run the selected operation to view its result.</p></div>')+'</div></section>';
 let main=simpleInquiry?resultPanel:op.method==='GET'&&results[page]?resultPanel+formPanel:formPanel+resultPanel;
 if(op.path==='/beneficiaries/{id}/verify')main=decisionQueue(operationRecords,'beneficiaries')+resultPanel;
 else if(op.path==='/payments/{id}/authorize-simulation'){
  operationRecords=await api('/payments?status=RECEIVED');
  main=decisionQueue(operationRecords,'payments')+resultPanel;
 }else if(op.method==='POST'&&checkerDecisionPath.test(op.path)&&operationCollection&&operationRecords.length){
  const actionable=operationRecords.filter(row=>!['APPROVED','REJECTED','SETTLED','CLOSED','ACTIVE','DISABLED','RESOLVED'].includes(row.status||row.STATUS));
  operationRecords=actionable;main=decisionQueue(actionable,'generic')+resultPanel;
 }
 const layout=(page==='payments'&&!customer?'<p><a class="btn" href="/employee/rbi-simulator">Open RBI / NPCI simulation portal</a></p>':'')+'<div class="operation-layout">'+catalog+'<div class="operation-main">'+main+'</div></div>';
  const loanGuide=page==='loans'&&!customer?'<section class="panel"><div class="panel-heading"><h3>Loan workflow</h3></div><div class="panel-body"><p>Review the application, record assessment, have a different checker decide, issue the offer, let the customer accept it, verify the signed document, then convert and disburse. Close offers that expire without acceptance.</p></div></section>':'';
  return head+availabilityNotice+loanGuide+(page==='transactions'?transactionTabs()+transactionTabPanel('operations',layout):page==='treasury'?treasuryPanel():'')+(page==='transactions'?'':layout);
}
function privacyNoticeLink(purpose){
 try{const url=new URL(purpose.noticeUri);if(!['http:','https:'].includes(url.protocol))return '';return '<a href="'+esc(url.href)+'" target="_blank" rel="noopener noreferrer">'+esc(t('Read privacy notice'))+'</a>';}catch{return '';}
}
function renderOperationResult(op,value){
 if(op.path==='/beneficiaries/pending'&&Array.isArray(value))return decisionQueue(value,'beneficiaries');
 if(getSession()?.user?.userType==='CUSTOMER'&&op.method==='GET'&&op.path==='/reporting/statement-requests')return table(['Period','Format','Status','Download'],value.map(r=>[esc(formatDate(r.periodStart))+' – '+esc(formatDate(r.periodEnd)),esc(r.format),badge(r.status),r.status==='COMPLETED'&&r.statementId?['PDF','CSV','HTML'].map(format=>'<button type="button" class="btn small" data-customer-statement-download="'+esc(r.statementId)+'" data-format="'+format+'">'+format+'</button>').join(' '):'<span class="muted">'+esc(t('Not available'))+'</span>']),'No statement requests yet.',{mobileCards:true});
 if(getSession()?.user?.userType==='CUSTOMER'&&op.path==='/privacy/purposes/available')return table(['Privacy choice','Notice version','Privacy notice'],value.map(p=>[esc(p.name),esc(p.version),privacyNoticeLink(p)||esc(t('Notice unavailable'))]),'No approved privacy notices are available yet.');
 return renderRows(value);
}
function decisionQueue(rows,kind){
 const op=operations.find(o=>o.id===selected[active]);const parents=(op?.parameters||[]).filter(p=>p.in==='path').slice(0,-1);
 const filters=kind==='generic'&&parents.length?'<form data-review-filters><div class="grid2">'+parents.map(p=>inputs({properties:{[p.name]:p.schema}},'',[p.name])).join('')+'</div></form>':'';
 return '<section class="panel"><div class="panel-heading"><h3>Records awaiting review</h3><button class="btn small" data-review-refresh>Refresh</button></div><div class="panel-body">'+filters+table(['Record','Status','Action'],rows.map((row,index)=>{
  const id=kind==='beneficiaries'?row.BENEFICIARY_ID:kind==='payments'?row.paymentId:recordId(row,operationCollection,op?.parameters?.filter(p=>p.in==='path').at(-1)?.name||'id');
  const title=kind==='beneficiaries'?[row.DISPLAY_NAME,row.ACCOUNT_TOKEN,row.BANK_CODE].filter(Boolean).join(' · '):recordLabel(row,id);
  const button=(text,action)=>'<button class="btn small" type="button" data-review-kind="'+kind+'" data-review-action="'+action+'" data-review-id="'+esc(id)+'" data-review-index="'+index+'">'+text+'</button>';
  return [esc(title),badge(row.status||row.STATUS||'PENDING'),kind==='beneficiaries'?button('Verified','verify')+' '+button('Not verified','not-verified'):kind==='payments'?button('Approve','approve')+' '+button('Reject','reject'):button('Review','review')];
 }),'No records awaiting review.')+'</div></section>';
}
async function loadReserve(id,offset=0){
 const [position,reconciliation,ledger]=await Promise.all([
  api('/treasury/reserve-accounts/'+encodeURIComponent(id)+'/position'),
  allow('TREASURY_READ')?api('/treasury/reserve-accounts/'+encodeURIComponent(id)+'/reconciliation'):Promise.resolve(null),
  allow('TREASURY_READ')?api('/treasury/reserve-accounts/'+encodeURIComponent(id)+'/ledger?'+new URLSearchParams({limit:'50',offset:String(offset)})):Promise.resolve(null)
 ]);
 reserveAccountId=String(id);reserveOffset=offset;
 reservePosition=position;reserveReconciliation=reconciliation;reserveLedger=ledger;
}
async function customerAccountsScreen(){
 const accountTable=(headers,rows,empty)=>table(headers,rows,empty,{mobileCards:true});
 const [accounts,availability,offerGroups,termGroups,termDeposits,facilities]=await Promise.all([
  api('/banking/accounts'),
  api('/auth/signup-availability'),
  Promise.all(getSession().user.cifIds.map(async cifId=>({cifId,offers:await api('/products/customer-offers?'+new URLSearchParams({cifId,channel:'BRANCH',currency:'INR'})).catch(()=>null)}))),
  Promise.all(getSession().user.cifIds.map(async cifId=>({cifId,offers:await api('/products/customer-offers?'+new URLSearchParams({cifId,channel:'WEB',currency:'INR'})).catch(()=>null)}))),
  api('/term-deposits/my').catch(()=>null),
  api('/banking/facilities').catch(()=>[])
 ]);
 const offers=offerGroups.flatMap(group=>(group.offers||[]).filter(o=>['SAVINGS','CURRENT'].includes(o.product.PRODUCT_TYPE)).map(o=>({...o,cifId:group.cifId})));
 const existing=accountTable(['Account','Product','Status','Action'],accounts.map(a=>[
  esc(a.ACCOUNT_NUMBER),esc(a.PRODUCT_NAME||a.PRODUCT_TYPE||a.CURRENCY_CODE),badge(a.ACCOUNT_STATUS),
  '<button class="btn small" type="button" data-customer-details="'+esc(a.ACCOUNT_ID)+'" data-account-number="'+esc(a.ACCOUNT_NUMBER)+'" data-product-name="'+esc(a.PRODUCT_NAME||a.PRODUCT_TYPE)+'" data-product-type="'+esc(a.PRODUCT_TYPE)+'">Details</button> '+
  (a.ACCOUNT_STATUS==='PENDING_OPEN'&&a.CREATED_BY_USER_ID===getSession().user.userId?'<button class="btn small" type="button" data-customer-activate="'+esc(a.ACCOUNT_ID)+'">Try activation</button>':'')+
  (a.PRODUCT_TYPE==='SAVINGS'?'<button class="btn small" type="button" data-customer-interest="'+esc(a.ACCOUNT_ID)+'">Interest credits</button>':'')+
  (a.ACCOUNT_OPERATION_MODE==='SELF_OPERATED'&&a.PRIMARY_CIF_ID&&getSession().user.cifIds.includes(a.PRIMARY_CIF_ID)&&['PENDING_OPEN','ACTIVE','DORMANT'].includes(a.ACCOUNT_STATUS)?' <button class="btn small" type="button" data-customer-nominees="'+esc(a.ACCOUNT_ID)+'" data-account-number="'+esc(a.ACCOUNT_NUMBER)+'">Nominees</button>':'')+
  (a.ACCOUNT_OPERATION_MODE==='SELF_OPERATED'&&a.PRIMARY_CIF_ID&&getSession().user.cifIds.includes(a.PRIMARY_CIF_ID)&&['ACTIVE','DORMANT'].includes(a.ACCOUNT_STATUS)?' <button class="btn small" type="button" data-customer-close="'+esc(a.ACCOUNT_ID)+'" data-account-number="'+esc(a.ACCOUNT_NUMBER)+'">Request closure</button>':'')+
  (a.ACCOUNT_STATUS==='ACTIVE'?' <button class="btn small" type="button" data-customer-limit="'+esc(a.ACCOUNT_ID)+'" data-account-number="'+esc(a.ACCOUNT_NUMBER)+'">Request limit change</button>':'')+
  (!['CANCELLED','CLOSED'].includes(a.ACCOUNT_STATUS)?' <button class="btn small" type="button" data-customer-issue="'+esc(a.ACCOUNT_ID)+'" data-account-number="'+esc(a.ACCOUNT_NUMBER)+'">Report issue</button>':'')+
  (a.ACCOUNT_OPERATION_MODE==='SELF_OPERATED'&&a.PRIMARY_CIF_ID&&getSession().user.cifIds.includes(a.PRIMARY_CIF_ID)&&a.ACCOUNT_STATUS==='ACTIVE'?' <button class="btn small" type="button" data-customer-safety="'+esc(a.ACCOUNT_ID)+'" data-account-number="'+esc(a.ACCOUNT_NUMBER)+'">Freeze / block debits</button>':'')+
  (a.ACCOUNT_STATUS==='CLOSING'?'<span class="muted">Closure pending bank review</span>':'')||'—'
 ]),'No accounts yet. Choose an eligible product below.');
 const available=!availability.enabled?'<div class="notice">Account opening is handled by a bank officer in this deployment.</div>':offerGroups.some(g=>g.offers===null)?'<div class="notice">Product offers are unavailable for your current access. Contact the bank to review your customer role.</div>':offers.length?accountTable(['Product','Type','Version','Opening terms','Action'],offers.map(o=>[
  esc(o.product.PRODUCT_NAME),esc(o.product.PRODUCT_TYPE),esc(o.version.VERSION_NO),
  'Minimum '+esc(formatAmount(o.rules?.account?.[0]?.MIN_OPENING_BALANCE??0,'INR'))+(o.rules?.account?.[0]?.NOMINEE_REQUIRED==='Y'?' · Nominee required':''),
  '<button class="btn small primary" type="button" data-customer-open="'+esc(o.cifId)+'" data-product="'+esc(o.product.PRODUCT_ID)+'" data-version="'+esc(o.version.PRODUCT_VERSION_ID)+'" data-product-name="'+esc(o.product.PRODUCT_NAME)+'">Open account</button>'
 ])):'<div class="empty">No eligible savings or current account products are available for your profile. Contact the bank to make a product available.</div>';
 const fixedOffers=termGroups.flatMap(group=>(group.offers||[]).filter(o=>o.product.PRODUCT_TYPE==='DEPOSIT'&&o.rules?.term?.length).map(o=>({...o,cifId:group.cifId})));
 const activeFunding=accounts.filter(a=>a.ACCOUNT_STATUS==='ACTIVE'&&['SAVINGS','CURRENT'].includes(a.PRODUCT_TYPE)&&a.CURRENCY_CODE==='INR');
 const fixedRows=termDeposits===null?'<div class="notice">Fixed deposits are not available until the deposit migration is installed.</div>':
  accountTable(['Deposit ID','Product','Principal','Rate','Term','Maturity','Status','Action'],termDeposits.map(t=>[esc(t.TERM_DEPOSIT_ID),esc(t.PRODUCT_NAME),esc(formatAmount(t.PRINCIPAL,'INR')),esc(formatDecimal(t.ANNUAL_RATE_PCT))+'%',esc(t.TENURE_MONTHS)+' months',esc(formatDate(t.MATURITY_DATE)),badge(t.STATUS),'<button class="btn small" type="button" data-customer-term-details="'+esc(t.TERM_DEPOSIT_ID)+'">Details</button>']),'You have no fixed deposits yet.');
 const linkedLoans=accountTable(['Facility','Branch','Status','Action'],facilities.map(f=>[
  esc(f.FACILITY_NUMBER),esc(f.BRANCH_CODE),badge(f.STATUS),
  '<button class="btn small" type="button" data-customer-linked-loan="'+esc(f.FACILITY_ID)+'">Details</button>'
 ]),'You have no linked loan facilities yet.');
 const fixedProducts=termDeposits===null?'<div class="notice">Fixed deposit opening is not enabled yet.</div>':termGroups.some(g=>g.offers===null)?'<div class="notice">Fixed deposit offers are unavailable for your profile.</div>':
  fixedOffers.length?accountTable(['Product','Rate','Amount range','Term','Action'],fixedOffers.map(o=>{
   const rule=o.rules.term[0],interest=o.rules.interest?.[0]||{};
   return [esc(o.product.PRODUCT_NAME),esc(formatDecimal(interest.FIXED_RATE_PCT))+'%',
    esc(formatAmount(rule.MIN_DEPOSIT_AMOUNT,'INR'))+' – '+esc(formatAmount(rule.MAX_DEPOSIT_AMOUNT,'INR')),
    esc(rule.MIN_TENURE_MONTHS)+'–'+esc(rule.MAX_TENURE_MONTHS)+' months',
    '<button class="btn small primary" type="button" data-customer-term-open="'+esc(o.product.PRODUCT_ID)+'" data-version="'+esc(o.version.PRODUCT_VERSION_ID)+'" data-product-name="'+esc(o.product.PRODUCT_NAME)+'" data-min-amount="'+esc(rule.MIN_DEPOSIT_AMOUNT)+'" data-max-amount="'+esc(rule.MAX_DEPOSIT_AMOUNT)+'" data-min-tenure="'+esc(rule.MIN_TENURE_MONTHS)+'" data-max-tenure="'+esc(rule.MAX_TENURE_MONTHS)+'" '+(!activeFunding.length?'disabled':'')+'>Open fixed deposit</button>'];
  })):'<div class="empty">No eligible fixed deposit products are available yet.</div>';
 return '<section class="panel"><div class="panel-heading"><h3>Your accounts</h3></div><div class="panel-body">'+existing+'</div></section>'+
  '<section class="panel"><div class="panel-heading"><h3>Open an account</h3></div><div class="panel-body"><p class="hint">Available products are checked against your verified customer profile and home branch. Accounts that require opening funding stay pending until that funding clears.</p>'+available+'</div></section>'+
  '<section class="panel"><div class="panel-heading"><h3>Your fixed deposits</h3></div><div class="panel-body">'+fixedRows+'</div></section>'+
  '<section class="panel"><div class="panel-heading"><h3>Your linked loans</h3></div><div class="panel-body">'+linkedLoans+'</div></section>'+
  '<section class="panel"><div class="panel-heading"><h3>Open a fixed deposit</h3></div><div class="panel-body"><p class="hint">Principal is funded from your active INR account. Principal and fixed interest return to that account at maturity. Early withdrawal is not available.</p>'+(!activeFunding.length?'<p class="notice">Open and activate a savings or current account before placing a fixed deposit.</p>':'')+fixedProducts+'</div></section>';
}
async function showCustomerAccountDetails(button){
 const id=button.dataset.customerDetails;
 const [account,parties,restrictions,limits,closures,majority,history,requests,accrual,transactions]=await Promise.all([
  api('/accounts/'+id),api('/accounts/'+id+'/parties'),api('/accounts/'+id+'/restrictions'),
  api('/accounts/'+id+'/limits'),api('/accounts/'+id+'/closures'),
  api('/accounts/'+id+'/majority-reviews'),api('/accounts/'+id+'/status-history'),
  api('/accounts/self/'+id+'/requests'),
  button.dataset.productType==='SAVINGS'?api('/deposit-interest/accounts/'+id+'/accrual').catch(()=>null):Promise.resolve(null),
  api('/banking/transactions?accountId='+encodeURIComponent(id)).catch(()=>[])
 ]);
 const currency=account.currencyCode||'INR';
 const details=table(['Field','Value'],[
  ['Account number',esc(account.number)],['Product',esc(button.dataset.productName)],
  ['Branch',esc(account.branchCode)],['Currency',esc(currency)],
  ['Opened',esc(formatDate(account.openedAt))],['Status',badge(account.accountStatus)],
  ['Operating mode',esc(account.operationMode)],
  ['Ledger balance','<span class="amount-value">'+esc(formatAmount(account.ledgerBalance,currency))+'</span>'],
  ['Available balance','<span class="amount-value">'+esc(formatAmount(account.availableBalance,currency))+'</span>'],
  ['Blocked balance','<span class="amount-value">'+esc(formatAmount(account.blockedBalance,currency))+'</span>'],
  ['Lien balance','<span class="amount-value">'+esc(formatAmount(account.lienBalance,currency))+'</span>'],
  ['Overdraft limit','<span class="amount-value">'+esc(formatAmount(account.overdraftLimit,currency))+'</span>'],
  ['Interest accrued (last calculation)','<span class="amount-value">'+esc(formatAmount(account.interestAccrued,currency))+'</span>'],
  ['Estimated unposted interest',accrual?.status==='ESTIMATE'?esc(formatAmount(accrual.accrued,currency))+' through '+esc(accrual.asOf):esc(accrual?.status||'Not available')],
  ['Last interest calculation',esc(formatDate(account.lastInterestCalculationAt))],
  ['Last interest posting',esc(formatDate(account.lastInterestPostingAt))],
  ['Majority review',badge(account.majorityReviewStatus)]
 ]);
 const sections=[
  '<h3>Account details and balances</h3>'+details,
  '<h3>Recent transactions</h3>'+table(['Date','Transaction','Direction','Type','Amount','Status'],transactions.slice(0,20).map(t=>[
   esc(formatDate(t.valueDate)),esc(t.transactionId),esc(t.accountRole),esc(t.type),
   t.amount==null?'—':esc(formatAmount(t.amount,currency)),badge(t.status||'RECORDED')
  ]),'No transactions yet.')+'<p class="hint">For date-range statements and downloads, open Statements & documents in the sidebar.</p>',
  '<h3>Holders, signatories and guardians</h3>'+table(['Name','Role','CIF','Operating instruction'],parties.filter(p=>p.IS_ACTIVE==='Y').map(p=>[
   esc(p.PARTY_NAME||'—'),esc(p.PARTY_ROLE),esc(p.CIF_ID),esc(p.OPERATING_INSTRUCTION)
  ]),'No active account parties.'),
  '<h3>Account controls</h3>'+table(['Control','Status','Amount','Reason','Ends'],restrictions.map(r=>[
   esc(r.RESTRICTION_TYPE),badge(r.RESTRICTION_STATUS),r.RESTRICTION_AMOUNT==null?'—':esc(formatAmount(r.RESTRICTION_AMOUNT,currency)),
   esc(r.REASON_CODE),esc(formatDate(r.ENDS_AT))
  ]),'No account controls.'),
  '<h3>Account limits</h3>'+table(['Operation','Period','Amount','Effective from','Active'],limits.map(l=>[
   esc(l.OPERATION_CODE),esc(l.PERIOD_CODE),esc(formatAmount(l.LIMIT_AMOUNT,currency)),
   esc(formatDate(l.EFFECTIVE_FROM)),badge(l.IS_ACTIVE==='Y'?'ACTIVE':'INACTIVE')
  ]),'No account-specific limits.'),
  '<h3>Your limit requests and issue reports</h3>'+table(['Type','Request','Amount','Status','Bank response'],requests.map(r=>[
   esc(r.REQUEST_TYPE),esc(r.OPERATION_CODE||r.DETAILS),r.REQUESTED_AMOUNT==null?'—':esc(formatAmount(r.REQUESTED_AMOUNT,currency)),
   badge(r.REQUEST_STATUS),esc(r.DECISION_REASON||'—')
  ]),'You have no requests.'),
  '<h3>Closure requests and decisions</h3>'+table(['Requested','Status','Reason','Decision'],closures.map(c=>[
   esc(formatDate(c.REQUESTED_AT)),badge(c.REQUEST_STATUS),esc(c.CLOSURE_REASON_CODE),
   esc(c.REJECTION_REASON||formatDate(c.DECIDED_AT))
  ]),'No closure requests.'),
  '<h3>Minor-to-major review</h3>'+table(['Due','Status','Decision'],majority.map(m=>[
   esc(formatDate(m.DUE_AT)),badge(m.REVIEW_STATUS),esc(m.DECISION_REASON||'—')
  ]),'No majority review.'),
  '<h3>Status timeline</h3>'+table(['Changed','From','To','Reason'],history.map(h=>[
   esc(formatDate(h.CHANGED_AT)),esc(h.OLD_STATUS||'—'),badge(h.NEW_STATUS),esc(h.REASON_CODE||'—')
  ]),'No status changes.')
 ];
 dialog('Account '+account.number,sections.join(''));
}
/** Shows only loan products and records available to the signed-in customer's linked CIFs. */
async function customerLoansScreen(){
 const cifs=getSession().user.cifIds||[];
 const [applications,facilities,offerGroups]=await Promise.all([
  api('/loans/my/applications'),api('/banking/facilities'),
  Promise.all(cifs.map(async cifId=>({cifId,offers:await api('/products/customer-offers?'+new URLSearchParams({cifId,channel:'WEB',currency:'INR'})).catch(()=>null)})))
 ]);
 const products=offerGroups.flatMap(g=>(g.offers||[]).filter(o=>o.product.PRODUCT_TYPE==='LOAN').map(o=>({...o,cifId:g.cifId})));
 const canApply=allow('LOAN_APPLY');
 const productRows=products.map(o=>{
  const rule=o.rules?.loan?.[0]||{};
  const interest=o.rules?.interest?.find(r=>r.INTEREST_TYPE==='FIXED'&&['SIMPLE','REDUCING_BALANCE'].includes(r.INTEREST_METHOD)&&r.COMPOUND_FREQUENCY==='NONE'&&r.PAYOUT_FREQUENCY==='MONTHLY'&&r.ROUNDING_MODE==='HALF_EVEN'&&['ACT/365','ACT_365','ACTUAL_365','30/360'].includes(r.DAY_COUNT_BASIS))||{};
  const serviceable=['FIXED','BOTH'].includes(rule.ALLOWED_INTEREST_TYPE)&&rule.REPAYMENT_FREQUENCY==='MONTHLY'&&rule.AMORTIZATION_METHOD==='REDUCING_BALANCE'&&['SINGLE','BOTH'].includes(rule.DISBURSEMENT_MODE)&&Number(rule.GRACE_DAYS||0)===0&&['SIMPLE','REDUCING_BALANCE'].includes(interest.INTEREST_METHOD)&&interest.COMPOUND_FREQUENCY==='NONE'&&interest.PAYOUT_FREQUENCY==='MONTHLY'&&interest.ROUNDING_MODE==='HALF_EVEN'&&['ACT/365','ACT_365','ACTUAL_365','30/360'].includes(interest.DAY_COUNT_BASIS)&&Number(rule.MIN_TENURE_MONTHS)<=600;
  const amount=rule.MIN_LOAN_AMOUNT==null?'—':esc(formatAmount(rule.MIN_LOAN_AMOUNT,'INR'))+' – '+esc(formatAmount(rule.MAX_LOAN_AMOUNT,'INR'));
  const tenure=rule.MIN_TENURE_MONTHS==null?'—':esc(rule.MIN_TENURE_MONTHS)+'–'+esc(rule.MAX_TENURE_MONTHS)+' months';
  const rate=interest.FIXED_RATE_PCT==null?'—':esc(formatDecimal(interest.FIXED_RATE_PCT))+'%';
  return [esc(o.product.PRODUCT_NAME),esc(o.version.VERSION_NO),amount,tenure,rate,
   canApply&&serviceable?'<button class="btn small primary" type="button" data-customer-loan-apply="'+esc(o.cifId)+'" data-product="'+esc(o.product.PRODUCT_ID)+'" data-version="'+esc(o.version.PRODUCT_VERSION_ID)+'" data-product-name="'+esc(o.product.PRODUCT_NAME)+'" data-loan-category="'+esc(rule.LOAN_CATEGORY||'PERSONAL')+'" data-min-amount="'+esc(rule.MIN_LOAN_AMOUNT||'')+'" data-max-amount="'+esc(rule.MAX_LOAN_AMOUNT||'')+'" data-min-tenure="'+esc(rule.MIN_TENURE_MONTHS||'')+'" data-max-tenure="'+esc(Math.min(Number(rule.MAX_TENURE_MONTHS)||600,600))+'">Apply for a loan</button>':canApply?'Contact the bank for this loan':'—'];
 });
 const offerStatus=offerGroups.some(g=>g.offers===null)?'<div class="notice">Loan products could not be loaded. Contact the bank if this continues.</div>':
  !canApply?'<div class="notice">Loan applications are not enabled for your customer role yet. Contact the bank.</div>':
  table(['Loan product','Version','Amount range','Term','Indicative fixed annual rate','Action'],productRows,'No eligible loan products are available for your profile. Contact the bank.');
 const applicationRows=applications.map(a=>[esc(a.number),esc(formatAmount(a.amount,'INR')),esc(a.tenureMonths)+' months',badge(a.status),
  '<button class="btn small" type="button" data-customer-loan-application="'+esc(a.id)+'">View details</button> '+
  '<button class="btn small" type="button" data-customer-loan-offers="'+esc(a.id)+'">View offers</button>']);
 const facilityRows=facilities.map(f=>[esc(f.FACILITY_NUMBER),badge(f.STATUS),
  '<button class="btn small" type="button" data-customer-loan-facility="'+esc(f.FACILITY_ID)+'">View details</button> '+
  '<button class="btn small" type="button" data-customer-loan-schedule="'+esc(f.FACILITY_ID)+'">View repayment schedule</button>']);
 return '<section class="panel"><div class="panel-heading"><h3>Apply for a loan</h3></div><div class="panel-body"><p class="hint">Choose an eligible loan product, enter an amount and term, then submit your application for bank review.</p>'+offerStatus+'</div></section>'+
  '<section class="panel"><div class="panel-heading"><h3>Your loan applications</h3></div><div class="panel-body">'+table(['Application','Amount','Term','Status','Actions'],applicationRows,'You have no loan applications yet.')+'</div></section>'+
  '<section class="panel"><div class="panel-heading"><h3>Your loan facilities</h3></div><div class="panel-body">'+table(['Facility','Status','Actions'],facilityRows,'You have no active loan facilities yet.')+'</div></section>';
}
function refreshTransactionPanel(){
 const existing=document.querySelector('#transaction-activity');
 if(!existing)return;
 existing.outerHTML=transactionPanel();
 bindTransactionForms();
}
function refreshGlLedgerPanel(){
 const existing=document.querySelector('#gl-ledger-section');
 if(!existing)return;
 existing.outerHTML=glLedgerPanel();
 bindGlLedgerForms();
}
function bindGlLedgerForms(){
 const form=document.querySelector('#gl-ledger-form');
 const input=form?.querySelector('input[name="glAccountId"]');
 input?.addEventListener('focus',async()=>{
  if(glLedgerAccounts!==null)return;
  try{glLedgerAccounts=await api('/banking/gl-accounts');const list=document.querySelector('#gl-ledger-accounts');if(list)list.innerHTML=glLedgerAccounts.map(a=>'<option value="'+esc(a.GL_ACCOUNT_ID)+'" label="'+esc(a.GL_CODE+' · '+a.GL_NAME)+'"></option>').join('');}
  catch(error){toast(errorText(error),true);}
 },{once:true});
 if(form)form.onsubmit=async e=>{e.preventDefault();const button=form.querySelector('button');button.disabled=true;document.querySelector('#gl-ledger-error').textContent='';try{
  const id=String(new FormData(form).get('glAccountId')).trim();
  const ledger=await api('/banking/gl-accounts/'+encodeURIComponent(id)+'/ledger?limit=50&offset=0');
  glLedgerAccountId=id;glLedgerOffset=0;glLedger=ledger;glLedgerJournal=null;refreshGlLedgerPanel();
 }catch(error){document.querySelector('#gl-ledger-error').textContent=errorText(error);button.disabled=false;}};
 document.querySelectorAll('[data-gl-page]').forEach(button=>button.addEventListener('click',async()=>{button.disabled=true;try{
  const offset=Math.max(0,glLedgerOffset+(button.dataset.glPage==='next'?50:-50));
  const ledger=await api('/banking/gl-accounts/'+encodeURIComponent(glLedgerAccountId)+'/ledger?'+new URLSearchParams({limit:'50',offset:String(offset)}));
  glLedgerOffset=offset;glLedger=ledger;glLedgerJournal=null;refreshGlLedgerPanel();
 }catch(error){toast(errorText(error),true);button.disabled=false;}}));
 document.querySelectorAll('[data-gl-journal]').forEach(button=>button.addEventListener('click',async()=>{button.disabled=true;try{
  glLedgerJournal=await api('/banking/journals/'+encodeURIComponent(button.dataset.glJournal));refreshGlLedgerPanel();
  document.querySelector('#gl-ledger-section .gl-journal-detail')?.scrollIntoView({block:'nearest',behavior:'smooth'});
 }catch(error){toast(errorText(error),true);button.disabled=false;}}));
 const close=document.querySelector('[data-gl-journal-close]');if(close)close.onclick=()=>{glLedgerJournal=null;refreshGlLedgerPanel();};
}
function bindTransactionForms(){
 const transactionForm=document.querySelector('#transaction-search-form');
 transactionForm?.querySelector('input[name="accountNumber"]')?.addEventListener('focus',loadAccountOptions,{once:true});
 transactionForm?.querySelector('select[name="accountNumber"]')?.addEventListener('change',event=>{
  transactionAccountNumber=event.currentTarget.value;transactionAccountId='';transactionLegacy=false;
  transactionRows=null;transactionDetail=null;transactionVisibleCount=25;refreshTransactionPanel();
 });
 document.querySelector('[data-txn-accounts-retry]')?.addEventListener('click',async event=>{
  event.currentTarget.disabled=true;await loadAccountOptions(true);refreshTransactionPanel();
 });
 if(transactionForm)transactionForm.onsubmit=async e=>{e.preventDefault();const button=transactionForm.querySelector('button');button.disabled=true;document.querySelector('#transaction-error').textContent='';try{const number=String(new FormData(transactionForm).get('accountNumber')).trim();let rows;transactionLegacy=false;transactionAccountId='';try{rows=await api('/banking/transactions?'+new URLSearchParams({accountNumber:number}));}catch(error){if(error.code!=='ACCOUNT_REQUIRED')throw error;transactionAccountId=await legacyAccountId(number);rows=await api('/banking/transactions?'+new URLSearchParams({accountId:transactionAccountId}));transactionLegacy=true;}transactionRows=rows;transactionAccountNumber=number;transactionDetail=null;transactionVisibleCount=25;refreshTransactionPanel();}catch(error){const output=document.querySelector('#transaction-error');if(output)output.textContent=errorText(error);}finally{button.disabled=false;}};
 const more=document.querySelector('[data-txn-more]');
 if(more)more.onclick=()=>{transactionVisibleCount+=25;refreshTransactionPanel();};
 const internalForm=document.querySelector('#internal-transaction-form');
 if(internalForm)internalForm.onsubmit=async e=>{e.preventDefault();const button=internalForm.querySelector('button');button.disabled=true;document.querySelector('#internal-transaction-error').textContent='';try{const id=new FormData(internalForm).get('transactionId');transactionDetail=await api('/banking/transactions/'+encodeURIComponent(id)+'/details');refreshTransactionPanel();}catch(error){const output=document.querySelector('#internal-transaction-error');if(output)output.textContent=error.code==='NOT_FOUND'?'No internal ledger record found for this ID. For an account transaction, use the list above.':errorText(error);button.disabled=false;}};
 document.querySelectorAll('[data-txn-detail]').forEach(button=>button.addEventListener('click',async()=>{button.disabled=true;try{const account=transactionLegacy?{accountId:transactionAccountId}:{accountNumber:transactionAccountNumber};transactionDetail=await api('/banking/transactions/'+encodeURIComponent(button.dataset.txnDetail)+'/details?'+new URLSearchParams(account));refreshTransactionPanel();document.querySelector('#transaction-activity .result-details')?.scrollIntoView({block:'nearest',behavior:'smooth'});}catch(error){toast(errorText(error),true);button.disabled=false;}}));
}
export function bindBankingForms(){
 bindSimulator(renderApp);document.querySelector('[data-error-refresh]')?.addEventListener('click',renderApp);
 document.querySelectorAll('[data-review-refresh]').forEach(button=>button.onclick=renderApp);
 document.querySelector('[data-review-filters]')?.addEventListener('change',async event=>{const op=operations.find(o=>o.id===selected[active]);operationValues[operationKey(op)]={...operationValues[operationKey(op)],...Object.fromEntries(new FormData(event.currentTarget))};await renderApp();});
 document.querySelectorAll('[data-review-id]').forEach(button=>button.onclick=async()=>{
  const {reviewKind:kind,reviewAction:action,reviewId:id}=button.dataset;
  try{
   if(kind==='beneficiaries'){await api('/beneficiaries/'+encodeURIComponent(id)+'/'+action,{method:'POST',body:{}});toast(action==='verify'?'Beneficiary verified':'Beneficiary not verified');await renderApp();return;}
   if(kind==='payments'){
    if(action==='reject'){dialog('Reject pending payment','<p>The payment will be rejected without entering clearing.</p>',async()=>{await api('/payments/simulator/payments/'+encodeURIComponent(id)+'/reject',{method:'POST',body:{}});await renderApp();});return;}
    const reserves=await api('/treasury/reserve-accounts');
    dialog('Approve payment',select('Reserve account','reserveAccountId',reserves.map(r=>({value:String(r.id),label:(r.code||r.accountCode||'Reserve')+' · '+r.id})),'1',true),async data=>{await api('/payments/'+encodeURIComponent(id)+'/authorize-simulation',{method:'POST',body:{reserveAccountId:data.get('reserveAccountId')}});await renderApp();});return;
   }
   const op=operations.find(o=>o.id===selected[active]),row=operationRecords[Number(button.dataset.reviewIndex)],schema=schemaFor(op);
   const body=resolve(schema.body)||{properties:{}};const version=row.version??row.versionNo??row.VERSION_NO??row.rowVersion??row.ROW_VERSION;
   const props={...body.properties};let hidden='';
   for(const key of ['expectedVersion','rowVersion','version'])if(props[key]&&version!==undefined){hidden+='<input type="hidden" name="'+key+'" value="'+esc(version)+'">';delete props[key];}
   dialog(op.title,'<p>'+esc(recordLabel(row,id))+'</p>'+hidden+inputs({...body,properties:props},'',body.required||[]),async data=>{
    const lastParameter=op.parameters?.filter(p=>p.in==='path').at(-1)?.name||'id';
    const values={...operationValues[operationKey(op)],...collect(schema,data),[lastParameter]:id};
    const path=requestPath(op,values);if(!path)throw new Error('Select the related record before submitting this decision');
    await api(path,{method:op.method,body:await resolveAccountNumbers(collect(body,data))});toast('Decision recorded');await renderApp();
   });
  }catch(error){toast(errorText(error),true);}
 });
 bindCreditCards(renderApp);
 bindCustomerPayments(renderApp);
 bindCustomerFx();
 bindTransactionForms();
 bindGlLedgerForms();
 bindReversalForms();
 const tabs=[...document.querySelectorAll('[data-transaction-tab]')];
 for(const tab of tabs){
  tab.onclick=async()=>{if(transactionSection===tab.dataset.transactionTab)return;transactionSection=tab.dataset.transactionTab;await renderApp();document.querySelector('[data-transaction-tab="'+transactionSection+'"]')?.focus();};
  tab.onkeydown=event=>{if(!['ArrowRight','ArrowLeft','Home','End'].includes(event.key))return;const index=tabs.indexOf(tab);const next=event.key==='ArrowRight'?index+1:event.key==='ArrowLeft'?index-1:event.key==='Home'?0:tabs.length-1;event.preventDefault();tabs[(next+tabs.length)%tabs.length].click();};
 }
 const reserveForm=document.querySelector('#reserve-ledger-form');
 if(reserveForm)reserveForm.onsubmit=async e=>{e.preventDefault();const button=reserveForm.querySelector('button');button.disabled=true;document.querySelector('#reserve-error').textContent='';try{await loadReserve(new FormData(reserveForm).get('reserveAccountId'));await renderApp();}catch(error){document.querySelector('#reserve-error').textContent=errorText(error);button.disabled=false;}};
 document.querySelectorAll('[data-reserve-page]').forEach(button=>button.addEventListener('click',async()=>{button.disabled=true;try{await loadReserve(reserveAccountId,Math.max(0,reserveOffset+(button.dataset.reservePage==='next'?50:-50)));await renderApp();}catch(error){toast(errorText(error),true);button.disabled=false;}}));
 const catalogToggle=document.querySelector('#operation-catalog-toggle');
 if(catalogToggle)catalogToggle.addEventListener('click',()=>{const expanded=catalogToggle.getAttribute('aria-expanded')!=='true';catalogToggle.setAttribute('aria-expanded',String(expanded));document.querySelector('.operation-catalog').classList.toggle('expanded',expanded);});
 document.querySelectorAll('[data-bank-operation]').forEach(button=>button.addEventListener('click',async()=>{selected[active]=button.dataset.bankOperation;await renderApp();}));
 const search=document.querySelector('#operation-search');
 if(search)search.addEventListener('input',()=>{let visible=0;document.querySelectorAll('.operation-group').forEach(group=>{let count=0;group.querySelectorAll('.operation-choice').forEach(button=>{button.hidden=!button.textContent.toLowerCase().includes(search.value.trim().toLowerCase());if(!button.hidden)count++;});group.hidden=count===0;visible+=count;});document.querySelector('.operation-empty').hidden=visible>0;});
 const refresh=document.querySelector('#bank-refresh');
 if(refresh)refresh.addEventListener('click',async()=>{refresh.disabled=true;try{const op=operations.find(item=>item.id===selected[active]);await renderApp();}catch(error){toast(errorText(error),true);}finally{refresh.disabled=false;}});
 const form=document.querySelector('#bank-workflow');
 if(form)form.dataset.requestKey=crypto.randomUUID();
 if(form&&operations.find(o=>o.id===selected[active])?.method==='GET')form.addEventListener('change',()=>form.requestSubmit());
 if(form&&active==='privacy'&&getSession()?.user?.userType==='CUSTOMER'){
  const purpose=form.querySelector('[name=purposeCode]'),notice=form.querySelector('[name=noticeSha256]'),review=form.querySelector('#customer-notice-review');
  const update=()=>{const selected=customerPurposeNotices.find(p=>p.code===purpose?.value);if(notice)notice.value=selected?.noticeSha256||'';if(review)review.innerHTML=selected?privacyNoticeLink(selected)||'<p class="notice">'+esc(t('Notice unavailable'))+'</p>':'';const checked=form.querySelector('[name=noticeReviewed]');if(checked)checked.checked=false;};
  purpose?.addEventListener('change',update);update();
 }
 if(form)form.onsubmit=async e=>{
  e.preventDefault();if(!await validateJet(form))return;
  const button=form.querySelector('[type=submit]');button.disabled=true;const err=form.querySelector('.form-error');err.textContent='';
  try{
   const op=operations.find(o=>o.id===selected[active]),data=new FormData(form),schema=schemaFor(op);
   const values=await resolveAccountNumbers(collect(schema,data));
   if(active==='privacy'&&getSession()?.user?.userType==='CUSTOMER'&&op.path.endsWith('/decisions')){
    if(data.get('noticeReviewed')!=='on')throw new Error(t('Read the privacy notice before saving your choice.'));
    if(!/^[0-9a-f]{64}$/i.test(String(data.get('noticeSha256')||'')))throw new Error(t('The approved notice is unavailable. Refresh this page or contact the bank.'));
   }
   if(op.path.startsWith('/accounts/{id}')&&!operationReferences.id)values.id=await legacyAccountId(String(values.id||'').trim());
   let path=op.path;const query=new URLSearchParams();
   for(const p of op.parameters||[]){
    if(p.in==='path')path=path.replace('{'+p.name+'}',encodeURIComponent(values[p.name]??''));
    if(p.in==='query'&&values[p.name]!==undefined)query.set(p.name,values[p.name]);
   }
   if(query.size)path+='?'+query;
   const collected=op.requestBody?await resolveAccountNumbers(collect(schema.body,data)):undefined;const body=collected?.__body??collected;const headers={'Idempotency-Key':body?.requestKey||body?.requestId||form.dataset.requestKey};for(const p of op.parameters||[])if(p.in==='header'&&p.name==='If-Match'&&values[p.name]!==undefined)headers[p.name]=values[p.name];
   operationValues[operationKey(op)]={...values,...body};
   const response=await api(path,{method:op.method,body,headers});
   if(op.method==='POST'&&op.path==='/teller/vaults')cashLedgerAccounts=null;
   results[active]=response??{message:'Operation completed'};operationResults[operationKey(op)]=results[active];
   document.querySelector('.result-panel').hidden=false;
   document.querySelector('#bank-result').innerHTML=renderOperationResult(op,results[active]);
   if(op.method!=='GET')toast('Operation completed');
  }catch(error){err.textContent=errorText(error);}finally{button.disabled=false;}
 };
 const keyform=document.querySelector('#customer-hash-form');if(keyform)keyform.onsubmit=e=>{e.preventDefault();setCustomerHash(new FormData(keyform).get('customerHash'));transactionRows=null;transactionDetail=null;document.querySelector('#hash-result').textContent='Key saved for this tab. Return to Transactions & ledger and load the account again to check access.';};
 const holderform=document.querySelector('#key-holder-search-form');if(holderform)holderform.onsubmit=async e=>{e.preventDefault();const button=holderform.querySelector('button');button.disabled=true;try{const number=String(new FormData(holderform).get('accountNumber')).trim();let holders;try{holders=await api('/banking/accounts/by-number/'+encodeURIComponent(number)+'/key-holders');}catch(error){if(error.code!=='NOT_FOUND')throw error;const id=await legacyAccountId(number);try{holders=await api('/banking/accounts/'+encodeURIComponent(id)+'/key-holders');}catch(inner){if(inner.code!=='NOT_FOUND')throw inner;const parties=await api('/accounts/'+encodeURIComponent(id)+'/parties');holders=parties.filter(p=>p.IS_ACTIVE==='Y'&&['PRIMARY_HOLDER','JOINT_HOLDER','AUTHORIZED_SIGNATORY'].includes(p.PARTY_ROLE)).map(p=>({...p,CAN_SIGN_IN:'UNKNOWN'}));}}keyHolderAccountNumber=number;keyHolders=holders;await renderApp();}catch(error){toast(errorText(error),true);button.disabled=false;}};
 holderform?.querySelector('input[name="accountNumber"]')?.addEventListener('focus',loadAccountOptions,{once:true});
}
document.addEventListener('click',async e=>{
 const statementDownload=e.target.closest('[data-customer-statement-download]');if(statementDownload){
  statementDownload.disabled=true;try{await api('/reporting/statements/'+encodeURIComponent(statementDownload.dataset.customerStatementDownload)+'/download?format='+encodeURIComponent(statementDownload.dataset.format));}
  catch(error){toast(errorText(error),true);}finally{statementDownload.disabled=false;}return;
 }
 const accountDetails=e.target.closest('[data-customer-details]');if(accountDetails){
  accountDetails.disabled=true;try{await showCustomerAccountDetails(accountDetails);}
  catch(error){toast(errorText(error),true);}finally{accountDetails.disabled=false;}return;
 }
 const termDetails=e.target.closest('[data-customer-term-details]');if(termDetails){
  termDetails.disabled=true;try{const deposit=await api('/term-deposits/'+termDetails.dataset.customerTermDetails);
   dialog('Fixed deposit '+termDetails.dataset.customerTermDetails,renderRows(deposit));}
  catch(error){toast(errorText(error),true);}finally{termDetails.disabled=false;}return;
 }
 const linkedLoan=e.target.closest('[data-customer-linked-loan]');if(linkedLoan){
  linkedLoan.disabled=true;try{const facility=await api('/loans/facilities/'+linkedLoan.dataset.customerLinkedLoan);
   dialog('Loan facility '+linkedLoan.dataset.customerLinkedLoan,renderRows(facility));}
  catch(error){toast(errorText(error),true);}finally{linkedLoan.disabled=false;}return;
 }
 const limitButton=e.target.closest('[data-customer-limit]');if(limitButton){
  const requestId=crypto.randomUUID();
  dialog('Request a limit change for '+limitButton.dataset.accountNumber,
   '<p class="hint">The bank reviews this request against product rules. Your current limit stays in force until an officer approves it.</p><div class="grid2">'+
   select('Operation','operationCode',[{value:'INTERNAL_TRANSFER',label:'Internal transfer'}])+
   select('Period','periodCode',[{value:'DAY',label:'Per day'},{value:'PER_TRANSACTION',label:'Per transaction'}])+
   field('Requested amount (INR)','requestedAmount','number',{min:0.01,step:'0.01'})+
   field('Reason','reason','text',{maxlength:500})+'</div>',async data=>{
    await api('/accounts/self/'+limitButton.dataset.customerLimit+'/limit-requests',{method:'POST',body:{requestId,
     operationCode:String(data.get('operationCode')).trim().toUpperCase(),periodCode:data.get('periodCode'),
     requestedAmount:data.get('requestedAmount'),reason:String(data.get('reason')).trim()}});
    toast('Limit request sent for bank review.');return true;
   });return;
 }
 const issueButton=e.target.closest('[data-customer-issue]');if(issueButton){
  const requestId=crypto.randomUUID();
  dialog('Report an issue with '+issueButton.dataset.accountNumber,
   '<p class="hint">A bank officer will review this report. To stop outgoing payments on an eligible sole-operated account, use Freeze / block debits.</p>'+field('What happened?','details','text',{maxlength:500}),async data=>{
    await api('/accounts/self/'+issueButton.dataset.customerIssue+'/issues',{method:'POST',body:{requestId,details:String(data.get('details')).trim()}});
    toast('Issue reported to the bank.');return true;
   });return;
 }
 const safetyButton=e.target.closest('[data-customer-safety]');if(safetyButton){
  const requestId=crypto.randomUUID();
  dialog('Secure '+safetyButton.dataset.accountNumber,
   '<p class="hint">The selected block takes effect after ledger acknowledgement. A bank officer must release it. This action does not move money.</p>'+
   select('Safety action','type',[{value:'DEBIT_BLOCK',label:'Block outgoing payments'},{value:'FREEZE',label:'Freeze account'}])+
   field('Reason','details','text',{maxlength:500}),async data=>{
    await api('/accounts/self/'+safetyButton.dataset.customerSafety+'/safety-blocks',{method:'POST',body:{requestId,
     type:data.get('type'),details:String(data.get('details')).trim()}});
    toast('Account safety control requested.');await renderApp();return true;
   });return;
 }
 const nomineesButton=e.target.closest('[data-customer-nominees]');if(nomineesButton){
  nomineesButton.disabled=true;
  try{
   const existing=(await api('/accounts/'+nomineesButton.dataset.customerNominees+'/nominees')).filter(n=>n.IS_ACTIVE==='Y');
   const rows=existing.length?table(['Name','Relationship','Share'],existing.map(n=>[
    esc(n.NOMINEE_NAME),esc(n.RELATIONSHIP),esc(n.SHARE_PERCENTAGE)+'%'])):'<p class="hint">No nominees are recorded for this account.</p>';
   let next=0;
   const row=(n={})=>{const key=next++;return '<div class="nominee-entry" data-nominee-key="'+key+'"><div class="grid2">'+
    field('Name','nominee.'+key+'.name','text',{value:n.NOMINEE_NAME||'',maxlength:200})+
    field('Relationship','nominee.'+key+'.relationship','text',{value:n.RELATIONSHIP||'',maxlength:100})+
    field('Share %','nominee.'+key+'.sharePercentage','number',{value:n.SHARE_PERCENTAGE??100,min:0.01,step:'0.01'})+
    field('Date of birth','nominee.'+key+'.dateOfBirth','date',{required:false,value:String(n.DATE_OF_BIRTH||'').slice(0,10)})+
    field('Mobile number','nominee.'+key+'.mobileNumber','tel',{required:false,value:n.MOBILE_NUMBER||''})+
    field('Guardian name (if nominee is a minor)','nominee.'+key+'.guardianName','text',{required:false,value:n.GUARDIAN_NAME||''})+
    field('Guardian relationship','nominee.'+key+'.guardianRelationship','text',{required:false,value:n.GUARDIAN_RELATIONSHIP||''})+
    '</div><button type="button" class="btn small" data-remove-nominee>Remove nominee</button></div>';};
   const requestId=crypto.randomUUID();
   dialog('Nominees for '+nomineesButton.dataset.accountNumber,
    rows+'<p class="hint">Saving replaces the full nominee list. Shares must total 100% when nominees are present. Some products require a nominee before activation.</p><div id="nominee-editor">'+
    (existing.length?existing.map(row).join(''):row())+'</div><button type="button" class="btn small" id="add-nominee">Add nominee</button>',async data=>{
     const nodes=[...document.querySelectorAll('#nominee-editor [data-nominee-key]')];
     if(nodes.length>5)throw new Error('Enter no more than five nominees.');
     const nominees=nodes.map(node=>{const prefix='nominee.'+node.dataset.nomineeKey+'.';
      const value=name=>String(data.get(prefix+name)||'').trim();
      return {name:value('name'),relationship:value('relationship'),sharePercentage:value('sharePercentage'),
       dateOfBirth:value('dateOfBirth')||null,mobileNumber:value('mobileNumber')||null,
       guardianName:value('guardianName')||null,guardianRelationship:value('guardianRelationship')||null};});
     const shareUnits=nominees.map(n=>/^\d{1,3}(?:\.\d{1,2})?$/.test(n.sharePercentage)?Math.round(Number(n.sharePercentage)*100):NaN);
     if(nodes.length&&(shareUnits.some(n=>!Number.isInteger(n)||n<=0)||shareUnits.reduce((sum,n)=>sum+n,0)!==10000))
      throw new Error('Nominee shares must total exactly 100%.');
     await api('/accounts/self/'+nomineesButton.dataset.customerNominees+'/nominees',{method:'PUT',body:{requestId,nominees}});
     toast('Nominees saved.');await renderApp();return true;
    });
   document.querySelector('#add-nominee').onclick=async()=>{const editor=document.querySelector('#nominee-editor');
    if(editor.children.length>=5){toast('Up to five nominees can be entered.',true);return;}
    const wrapper=document.createElement('div');wrapper.innerHTML=row({SHARE_PERCENTAGE:''});const entry=wrapper.firstElementChild;
    editor.append(entry);await enhanceForms(entry);};
   document.querySelector('#nominee-editor').onclick=event=>{if(event.target.closest('[data-remove-nominee]'))event.target.closest('[data-nominee-key]').remove();};
  }catch(error){toast(errorText(error),true);}finally{nomineesButton.disabled=false;}return;
 }
 const closeAccount=e.target.closest('[data-customer-close]');if(closeAccount){
  const requestId=crypto.randomUUID();
  dialog('Request closure of '+closeAccount.dataset.accountNumber,
   '<p class="hint">Your account must have a zero balance, no holds, and no active fixed deposit, payment or loan obligations. The bank will review this request. Once submitted, transactions on this account will be stopped while it is reviewed.</p>'+field('Reason','remarks','text',{maxlength:500}),async data=>{
    await api('/accounts/self/'+closeAccount.dataset.customerClose+'/closures',{method:'POST',body:{requestId,reasonCode:'CUSTOMER_REQUEST',remarks:String(data.get('remarks')||'').trim()}});
    toast('Closure request sent for bank review.');await renderApp();return true;
   });return;
 }
 const interestCredits=e.target.closest('[data-customer-interest]');if(interestCredits){
  interestCredits.disabled=true;try{const rows=await api('/deposit-interest/accounts/'+interestCredits.dataset.customerInterest);dialog('Savings interest credits',rows.length?renderRows(rows):'<p class="empty">No interest has been credited yet. Interest is paid after each completed month.</p>');}catch(error){toast(errorText(error),true);}finally{interestCredits.disabled=false;}return;
 }
 const termOpen=e.target.closest('[data-customer-term-open]');if(termOpen){
  const funding=(await api('/banking/accounts')).filter(a=>a.ACCOUNT_STATUS==='ACTIVE'&&['SAVINGS','CURRENT'].includes(a.PRODUCT_TYPE)&&a.CURRENCY_CODE==='INR');
  const options=funding.map(a=>({value:String(a.ACCOUNT_ID),label:a.PRODUCT_NAME+' · '+a.ACCOUNT_NUMBER}));
  const minAmount=Number(termOpen.dataset.minAmount),maxAmount=Number(termOpen.dataset.maxAmount),
   minTenure=Number(termOpen.dataset.minTenure),maxTenure=Number(termOpen.dataset.maxTenure),requestKey=crypto.randomUUID();
  dialog('Open '+termOpen.dataset.productName,
   '<p class="hint">Choose an amount from '+esc(formatAmount(minAmount,'INR'))+' to '+esc(formatAmount(maxAmount,'INR'))+' and '+esc(minTenure)+'–'+esc(maxTenure)+' months. Funds leave the selected account now and return with interest at maturity.</p><div class="grid2">'+
   select('Funding and payout account','fundingAccountId',options)+field('Deposit amount','amount','number',{required:true,min:minAmount,max:maxAmount,step:'0.01'})+
   field('Term in months','tenureMonths','number',{required:true,min:minTenure,max:maxTenure,step:'1'})+'</div>',async data=>{
    const amount=String(data.get('amount')||''),tenureMonths=Number(data.get('tenureMonths'));
    if(!/^\d+(?:\.\d{1,2})?$/.test(amount)||Number(amount)<minAmount||Number(amount)>maxAmount||
       !Number.isInteger(tenureMonths)||tenureMonths<minTenure||tenureMonths>maxTenure)throw new Error('Enter an amount and term within the product limits.');
    await api('/term-deposits',{method:'POST',body:{productId:Number(termOpen.dataset.customerTermOpen),
     productVersionId:Number(termOpen.dataset.version),fundingAccountId:Number(data.get('fundingAccountId')),
     amount,tenureMonths,requestKey}});
    toast('Fixed deposit opened.');await renderApp();return true;
   });
  return;
 }
 const apply=e.target.closest('[data-customer-loan-apply]');if(apply){
  const key=crypto.randomUUID(),cifId=apply.dataset.customerLoanApply,productId=Number(apply.dataset.product),productVersionId=Number(apply.dataset.version);
  const minAmount=Number(apply.dataset.minAmount||0),maxAmount=Number(apply.dataset.maxAmount||0);
  const minTenure=Number(apply.dataset.minTenure||1),maxTenure=Number(apply.dataset.maxTenure||9999);
  const purposeCode=apply.dataset.loanCategory||'PERSONAL';
  dialog('Apply for '+apply.dataset.productName,
   '<p class="hint">Your application will be submitted to the bank for assessment. Approval and an offer happen later. Enter an amount from '+esc(formatAmount(minAmount,'INR'))+' to '+esc(formatAmount(maxAmount,'INR'))+' and a term from '+esc(minTenure)+' to '+esc(maxTenure)+' months.</p><div class="grid2">'+
   field('Loan amount','amount','number',{min:minAmount||0.01,step:'0.01',max: maxAmount||undefined})+
   field('Term in months','tenureMonths','number',{min:minTenure,max:maxTenure,step:'1'})+
   '<input type="hidden" name="purposeCode" value="'+esc(purposeCode)+'"></div>',async data=>{
    const amount=String(data.get('amount')||''),numericAmount=Number(amount),tenureMonths=Number(data.get('tenureMonths'));
    if(!/^\d+(?:\.\d{1,2})?$/.test(amount)||!Number.isFinite(numericAmount)||numericAmount<=0||!Number.isInteger(tenureMonths)||tenureMonths<minTenure||tenureMonths>maxTenure||(maxAmount&&numericAmount>maxAmount)||(minAmount&&numericAmount<minAmount))throw new Error('Enter an amount and term within the product limits.');
    const loan=await api('/loans/my/applications',{method:'POST',headers:{'Idempotency-Key':key},body:{cifId,productId,productVersionId,amount,tenureMonths,purposeCode:data.get('purposeCode')}});
    toast('Application '+loan.number+' submitted for bank review.');await renderApp();return true;
   });
  document.querySelector('#dialog form').noValidate=true;
  document.querySelector('#dialog [type=submit]').textContent='Submit application';
  return;
 }
 const applicationDetails=e.target.closest('[data-customer-loan-application]');if(applicationDetails){
  applicationDetails.disabled=true;try{const application=await api('/loans/applications/'+applicationDetails.dataset.customerLoanApplication);dialog('Application '+application.number,renderRows(application));}catch(error){toast(errorText(error),true);}finally{applicationDetails.disabled=false;}return;
 }
 const facilityDetails=e.target.closest('[data-customer-loan-facility]');if(facilityDetails){
  facilityDetails.disabled=true;try{const facility=await api('/loans/facilities/'+facilityDetails.dataset.customerLoanFacility);dialog('Facility '+facility.FACILITY_NUMBER,renderRows(facility));}catch(error){toast(errorText(error),true);}finally{facilityDetails.disabled=false;}return;
 }
 const offers=e.target.closest('[data-customer-loan-offers]');if(offers){
  offers.disabled=true;try{
   const id=Number(offers.dataset.customerLoanOffers),[application,items,accounts]=await Promise.all([
    api('/loans/applications/'+id),api('/loans/applications/'+id+'/offers'),api('/banking/accounts')
   ]);
   const issued=items.filter(o=>o.STATUS==='ISSUED'&&(!o.EXPIRES_AT||Date.parse(o.EXPIRES_AT)>Date.now()));
   const ownAccounts=accounts.filter(a=>a.PRIMARY_CIF_ID===application.cifId&&a.ACCOUNT_STATUS==='ACTIVE'&&a.CURRENCY_CODE==='INR'&&['SELF_OPERATED','ANYONE'].includes(a.ACCOUNT_OPERATION_MODE));
   const rows=items.map(o=>[esc(o.OFFER_NUMBER),badge(o.STATUS==='ISSUED'&&o.EXPIRES_AT&&Date.parse(o.EXPIRES_AT)<=Date.now()?'EXPIRED':o.STATUS),esc(formatAmount(o.SANCTIONED_AMOUNT,'INR')),esc(o.SANCTIONED_TENURE_MONTHS)+' months',esc(formatDecimal(o.ANNUAL_RATE_PCT))+'%',esc(formatDate(o.EXPIRES_AT)),
    (issued.some(x=>x.OFFER_ID===o.OFFER_ID)||o.STATUS==='ACCEPTED'&&application.status==='ACCEPTED')?'<button class="btn small primary" type="button" data-customer-loan-accept="'+esc(o.OFFER_ID)+'" data-application="'+esc(id)+'">'+(o.STATUS==='ACCEPTED'?'Change accounts':'Accept offer')+'</button>':'—']);
   dialog('Offers for '+application.number,table(['Offer','Status','Approved amount','Term','Annual rate','Expires','Action'],rows,'No offer has been issued yet. The bank must assess and approve your application first.')+
    ((issued.length||items.some(o=>o.STATUS==='ACCEPTED'&&application.status==='ACCEPTED'))&&!ownAccounts.length?'<p class="notice">Open and activate an INR account in your name before choosing loan accounts.</p>':'')+
    (items.some(o=>o.STATUS==='ISSUED'&&o.EXPIRES_AT&&Date.parse(o.EXPIRES_AT)<=Date.now())?'<p class="notice">This offer has expired. You can submit a new application.</p>':''));
  }catch(error){toast(errorText(error),true);}finally{offers.disabled=false;}return;
 }
 const accept=e.target.closest('[data-customer-loan-accept]');if(accept){
  const offerId=Number(accept.dataset.customerLoanAccept),applicationId=Number(accept.dataset.application);
  try{
   const [application,accounts,offers]=await Promise.all([api('/loans/applications/'+applicationId),api('/banking/accounts'),api('/loans/applications/'+applicationId+'/offers')]);
   const offer=offers.find(o=>Number(o.OFFER_ID)===offerId&&(o.STATUS==='ISSUED'&&(!o.EXPIRES_AT||Date.parse(o.EXPIRES_AT)>Date.now())||o.STATUS==='ACCEPTED'&&application.status==='ACCEPTED'));
   if(!offer)throw new Error('This offer is no longer available. Reopen the offers list.');
   const own=accounts.filter(a=>a.PRIMARY_CIF_ID===application.cifId&&a.ACCOUNT_STATUS==='ACTIVE'&&a.CURRENCY_CODE==='INR'&&['SELF_OPERATED','ANYONE'].includes(a.ACCOUNT_OPERATION_MODE));
   if(!own.length)throw new Error('Open and activate an INR account in your name before accepting this offer.');
   const options=own.map(a=>({value:String(a.ACCOUNT_ID),label:a.ACCOUNT_NUMBER}));
   dialog(offer.STATUS==='ACCEPTED'?'Change loan accounts':'Accept loan offer', '<p class="hint">Offer '+esc(offer.OFFER_NUMBER)+': '+esc(formatAmount(offer.SANCTIONED_AMOUNT,'INR'))+' for '+esc(offer.SANCTIONED_TENURE_MONTHS)+' months at '+esc(formatDecimal(offer.ANNUAL_RATE_PCT))+'% annual interest. Choose where the loan will be paid and which account will repay it.</p><div class="grid2">'+select('Disbursement account','disbursementAccountId',options)+select('Repayment account','repaymentAccountId',options)+'</div>',async data=>{
    await api('/loans/offers/'+offerId+'/accept',{method:'POST',body:{disbursementAccountId:Number(data.get('disbursementAccountId')),repaymentAccountId:Number(data.get('repaymentAccountId'))}});
    toast(offer.STATUS==='ACCEPTED'?'Loan accounts updated.':'Offer accepted. The bank will complete documentation and disbursement.');await renderApp();return true;
   });
  }catch(error){toast(errorText(error),true);}return;
 }
 const schedule=e.target.closest('[data-customer-loan-schedule]');if(schedule){
  schedule.disabled=true;try{const rows=await api('/loans/facilities/'+schedule.dataset.customerLoanSchedule+'/schedule');dialog('Repayment schedule',renderRows(rows));}catch(error){toast(errorText(error),true);}finally{schedule.disabled=false;}return;
 }
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
