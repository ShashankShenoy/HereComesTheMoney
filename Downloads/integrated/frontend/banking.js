import {api,getSession,getCustomerHash,setCustomerHash} from './api.js';
import {esc,table,field,select,badge,errorText,toast,dialog} from './ui.js';
import {enhanceForms,validateJet,jetReady} from './jet.js';
import {formatAmount,formatDecimal,formatDate,fieldKind,rowCurrency} from './presentation.js';
export const bankingPages={
 banking:{title:'Bank overview',permission:null,icon:'▦',intro:'Customer relationships, money movement, and daily operations in one workspace.',prefix:[]},
 accounts:{title:'Accounts',permission:'ACCOUNT_READ',icon:'▤',intro:'Open accounts, manage holders and nominees, apply controls, and review closure requests.',prefix:['/accounts','/banking/accounts']},
 transactions:{title:'Transactions & ledger',permission:'TXN_READ',icon:'⇄',intro:'Post transfers, review transaction references, and reconcile the general ledger.',prefix:['/transactions','/banking/transactions','/journals','/gl/','/banking/gl-accounts','/period-closes','/fees','/reconciliation/']},
 teller:{title:'Teller cash',permission:'TELLER_OPERATE',icon:'▣',intro:'Open your cash till, accept deposits, make withdrawals, and balance at close.',prefix:['/teller']},
 beneficiaries:{title:'Beneficiaries',permission:'PAYMENT_CREATE',icon:'♧',intro:'Register payment recipients and complete independent verification.',prefix:['/beneficiaries']},
 payments:{title:'Payments & clearing',permission:'PAYMENT_READ',icon:'↗',intro:'Initiate payments and follow simulated UPI, IMPS, NEFT, and RTGS processing.',prefix:['/payments','/dispatches','/clearing-batches','/reconciliation-exceptions','/approvals','/rail-messages']},
 treasury:{title:'Treasury & reserves',permission:'TREASURY_LIQUIDITY_MANAGE',icon:'◈',intro:'Review reserve positions, liquidity holds, settlement cycles, and reconciliation.',prefix:['/treasury/']},
 loans:{title:'Loans',permission:'LOAN_READ',icon:'⌂',intro:'Origination, assessment, sanction, offers, documentation, and servicing.',prefix:['/loans','/banking/facilities']},
 statements:{title:'Statements & documents',permission:'STATEMENT_READ',icon:'▧',intro:'Create date-range statements from the posted ledger and download PDF or CSV records.',prefix:['/reporting','/statements','/statement-','/catalog']},
 privacy:{title:'Privacy & compliance',permission:'PRIVACY_CONSENT_VIEW',icon:'◇',intro:'Manage processing purposes, consent, legal holds, privacy cases, and audit evidence.',prefix:['/privacy']},
 currency:{title:'Currency & rates',permission:'FX_READ',icon:'◎',intro:'Supported currencies, approved rates, explicit expiry, and indicative conversion.',prefix:['/currencies','/fx']},
 customerAccess:{title:'Account-holder access key',permission:null,icon:'⌑',intro:'Bank officers enter an account-holder key when viewing protected financial details.',prefix:[]}
};
let renderApp, contract, operations=[], selected={}, results={}, active='banking';
export function configureBanking(render){renderApp=render;}
const displayLabels={ACCOUNT_NUMBER:'Account number',ACCOUNT_STATUS:'Status',CURRENCY_CODE:'Currency',BRANCH_CODE:'Branch',PRIMARY_CIF_ID:'Primary CIF',ACCOUNT_ID:'Account ID',TXN_ID:'Transaction ID',PRODUCT_VERSION_ID:'Product version',primaryCifId:'Primary CIF ID',productId:'Product ID',productVersionId:'Product version ID',branchCode:'Branch code',operationMode:'Operation mode',requestId:'Request reference',requestKey:'Request reference'};
const label=v=>displayLabels[v]||v.replace(/([a-z])([A-Z])/g,'$1 $2').replace(/[_-]/g,' ').replace(/\b\w/g,c=>c.toUpperCase());
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
 'get /currencies':'List supported currencies','post /currencies':'Add a supported currency','get /fx/rates':'Review exchange rates','post /fx/rates':'Propose an exchange rate','post /fx/rates/{id}/decision':'Decide an exchange rate','get /fx/quote':'Calculate an indicative quote','get /fx/provider/status':'Check rate provider status','post /fx/provider/refresh':'Request a provider rate',
 'get /loans/facilities/{id}/disbursements':'List facility disbursements','post /loans/facilities/{id}/disbursements':'Request loan disbursement','post /loans/disbursements/{id}/approve':'Approve loan disbursement','post /loans/facilities/{id}/accruals':'Accrue due loan interest','post /loans/facilities/{id}/repayments':'Apply a due loan repayment','get /loans/collections':'Review overdue installments'
};
async function loadContract(){
 if(contract)return;
 const response=await fetch('/v3/api-docs');if(!response.ok)throw new Error('The workflow catalog is unavailable.');
 contract=await response.json();
 for(const [path,item]of Object.entries(contract.paths)){
  if(!path.startsWith('/api/v1/')||path.includes('/internal/')||path.includes('/controls'))continue;
  for(const [method,op]of Object.entries(item)){
   if(!['get','post','put','patch','delete'].includes(method))continue;
   operations.push({...op,path:path.slice(7),method:method.toUpperCase(),id:method+path,title:workflowTitles[method+' '+path.slice(7)]||op.summary?.split(/[;:]/)[0]||label(path.split('/').slice(3).filter(x=>!x.startsWith('{')).join(' '))+' · '+({get:'View',post:'Create or action',put:'Update',patch:'Update',delete:'Remove'}[method])});
  }
 }
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
  return field(label(name),key,s.format==='date'?'date':s.format==='password'?'password':'text',{required:req,value:!req&&['date','date-time'].includes(s.format)?'':defaults(key,s),maxlength:s.maxLength,placeholder:s.description||((s.type==='integer'||s.type==='number')?'Enter a number':'')});
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
 }else{const value=cast(s,data.get(name)||'');if(value!==undefined)out[key]=value;}
 }return out;
}
export async function bankingScreen(page){
 active=page;const config=bankingPages[page];
 const customer=getSession().user.userType==='CUSTOMER';
 const head='<div class="page-heading"><div><p class="eyebrow">'+(customer?'YOUR BANKING':'BANKING OPERATIONS')+'</p><h1>'+esc(config.title)+'</h1><p>'+esc(customer&&page==='accounts'?'View your accounts and open an eligible savings or current account.':config.intro)+'</p></div></div>';
 if(page==='customerAccess')return getSession().user.userType==='CUSTOMER'
  ?head+'<section class="panel"><div class="panel-body"><div class="notice">Your signed-in account already identifies you. Balances, transactions, and statements for your linked accounts require no extra key.</div></div></section>'
  :head+'<section class="panel"><div class="panel-body"><div class="notice">Bank officers need an account-holder key for protected financial details. The key is kept only in this browser tab and cleared when you sign out.</div><form id="customer-hash-form">'+field('Account-holder key','customerHash','password',{required:false,value:getCustomerHash(),placeholder:'Paste the account-holder key'})+'<button class="btn primary">Unlock authorized details</button><button type="button" class="btn" data-bank-clear>Clear key</button></form><div id="hash-result" role="status"></div></div></section>';
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
 await loadContract();
 const choices=operations.filter(o=>config.prefix.some(p=>o.path===p||o.path.startsWith(p.endsWith('/')?p:p+'/'))&&(!customer||customerWorkflows.has(o.method+' '+o.path)));
 const initial={accounts:'/banking/accounts',transactions:'/banking/transactions',teller:'/teller/tills',beneficiaries:'/beneficiaries',payments:'/payments',treasury:'/treasury/reserve-accounts',loans:'/banking/facilities',currency:'/fx/rates',privacy:'/privacy/purposes'}[page];
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
 return head+'<div class="operation-layout">'+catalog+'<div class="operation-main">'+main+'</div></div>';
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
export function bindBankingForms(){
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
   const values=collect(schema,data);let path=op.path;const query=new URLSearchParams();
   for(const p of op.parameters||[]){
    if(p.in==='path')path=path.replace('{'+p.name+'}',encodeURIComponent(values[p.name]??''));
    if(p.in==='query'&&values[p.name]!==undefined)query.set(p.name,values[p.name]);
   }
   if(query.size)path+='?'+query;
   const collected=op.requestBody?collect(schema.body,data):undefined;const body=collected?.__body??collected;const headers={'Idempotency-Key':body?.requestKey||body?.requestId||form.dataset.requestKey};for(const p of op.parameters||[])if(p.in==='header'&&p.name==='If-Match'&&values[p.name]!==undefined)headers[p.name]=values[p.name];
   const response=await api(path,{method:op.method,body,headers});
   results[active]=response??{message:'Operation completed'};
   document.querySelector('.result-panel').hidden=false;
   document.querySelector('#bank-result').innerHTML=renderRows(results[active]);
   if(op.method!=='GET')toast('Operation completed');
  }catch(error){err.textContent=errorText(error);}finally{button.disabled=false;}
 };
 const keyform=document.querySelector('#customer-hash-form');if(keyform)keyform.onsubmit=e=>{e.preventDefault();setCustomerHash(new FormData(keyform).get('customerHash'));toast('Account-holder key stored for this tab');};
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
 const clear=e.target.closest('[data-bank-clear]');if(clear){setCustomerHash('');await renderApp();}
 const rotate=e.target.closest('[data-bank-rotate]');if(rotate){rotate.disabled=true;try{const r=await api('/customer-access/rotate',{method:'POST'});setCustomerHash(r.customerHash);document.querySelector('#hash-result').innerHTML='<div class="notice"><strong>Save this hash now.</strong><code>'+esc(r.customerHash)+'</code></div>';}catch(error){toast(errorText(error),true);}finally{rotate.disabled=false;}}
 const add=e.target.closest('[data-bank-add]');if(add){
  const name=add.dataset.bankAdd,op=operations.find(o=>o.id===selected[active]);let schema=schemaFor(op);for(const segment of name.split('.')){schema=resolve(schema);schema=/^\d+$/.test(segment)?schema.items:schema.properties?.[segment];}schema=resolve(schema);const parent=document.querySelector('[data-array="'+CSS.escape(name)+'"]');const index=parent.children.length;
  const row=document.createElement('div');row.className='grid2 array-row';row.innerHTML=inputs(resolve(schema.items),name+'.'+index+'.',resolve(schema.items).required||[]);parent.append(row);await enhanceForms(row);
 }
});


document.addEventListener('change',e=>{const toggle=e.target.closest('[data-bank-object]');if(!toggle)return;const fieldset=document.querySelector('[data-object-body="'+CSS.escape(toggle.dataset.bankObject)+'"]');fieldset.hidden=!toggle.checked;fieldset.disabled=!toggle.checked;for(const input of fieldset.querySelectorAll('oj-input-text,oj-input-password,oj-input-date,oj-text-area,oj-select-single'))input.disabled=!toggle.checked;});
