import test from 'node:test';
import assert from 'node:assert/strict';

const storage=new Map();
globalThis.sessionStorage={getItem:key=>storage.get(key)||null,setItem:(key,value)=>storage.set(key,value),removeItem:key=>storage.delete(key)};
const requireJet=(modules,ready)=>ready({}, {whenDocumentReady:()=>Promise.resolve()}, class {}, class {});
requireJet.config=()=>{};
globalThis.window={require:requireJet};
globalThis.document={documentElement:{lang:''},addEventListener:()=>{}};
const {saveSession}=await import('./api.js');
const {customerPaymentsScreen}=await import('./customer-payments.js');
const {customerFxScreen}=await import('./customer-fx.js');
const {bankingScreen}=await import('./banking.js');
const {productsScreen}=await import('./domains.js');

function actor(id){saveSession({accessToken:'test-token',user:{userId:id,userType:'CUSTOMER',permissions:['PAYMENT_READ','PAYMENT_CREATE','FX_READ']}});}
function responses(values){globalThis.fetch=async url=>({ok:true,status:200,headers:new Headers(),json:async()=>values[url]});}

test('customer payment screen connects only eligible accounts and verified recipients',async()=>{
  actor('customer-payment-1');
  responses({
    '/api/v1/payments/availability':{initiationEnabled:true,mode:'SIMULATION'},
    '/api/v1/banking/accounts':[
      {ACCOUNT_ID:1,ACCOUNT_NUMBER:'MB0001',PRODUCT_TYPE:'SAVINGS',PRODUCT_NAME:'Savings',ACCOUNT_STATUS:'ACTIVE',ACCOUNT_OPERATION_MODE:'SELF_OPERATED',CURRENCY_CODE:'INR'},
      {ACCOUNT_ID:2,ACCOUNT_NUMBER:'MB0002',PRODUCT_TYPE:'DEPOSIT',ACCOUNT_STATUS:'ACTIVE',CURRENCY_CODE:'INR'},
    ],
    '/api/v1/beneficiaries':[{BENEFICIARY_ID:'b1',DISPLAY_NAME:'<Recipient>',BANK_CODE:'ABCD0123456',STATUS:'ACTIVE'}],
    '/api/v1/payments':[{paymentId:3,amount:12.50,railCode:'UPI',status:'RECEIVED'}],
  });
  const page=await customerPaymentsScreen();
  assert.match(page,/Local payment simulation/);
  assert.match(page,/Review payment request/);
  assert.match(page,/MB0001/);
  assert.doesNotMatch(page,/MB0002/);
  assert.match(page,/&lt;Recipient&gt;/);
  assert.match(page,/Awaiting independent review/);
});

test('customer payment screen blocks incomplete setup before submission',async()=>{
  actor('customer-payment-2');
  responses({
    '/api/v1/payments/availability':{initiationEnabled:true,mode:'SIMULATION'},
    '/api/v1/banking/accounts':[{ACCOUNT_ID:4,ACCOUNT_NUMBER:'MB0004',PRODUCT_TYPE:'SAVINGS',ACCOUNT_STATUS:'ACTIVE',ACCOUNT_OPERATION_MODE:'JOINTLY',CURRENCY_CODE:'INR'}],
    '/api/v1/beneficiaries':[{BENEFICIARY_ID:'b2',DISPLAY_NAME:'Pending',BANK_CODE:'ABCD0123456',STATUS:'PENDING'}],
    '/api/v1/payments':[],
  });
  const page=await customerPaymentsScreen();
  assert.match(page,/Payment setup needed/);
  assert.doesNotMatch(page,/id="customer-payment-form"/);
});

test('currency comparison explicitly stays indicative',async()=>{
  actor('customer-fx-1');
  responses({
    '/api/v1/currencies':[{CURRENCY_CODE:'INR',DISPLAY_NAME:'Indian rupee',ACTIVE_FLAG:'Y'},{CURRENCY_CODE:'EUR',DISPLAY_NAME:'Euro',ACTIVE_FLAG:'Y'}],
    '/api/v1/fx/rates':[{BASE_CURRENCY:'EUR',QUOTE_CURRENCY:'INR',STATUS:'APPROVED'}],
  });
  const page=await customerFxScreen();
  assert.match(page,/Rate information only/);
  assert.match(page,/Foreign-currency accounts, conversion orders, and international transfers are unavailable/);
  assert.match(page,/INR → EUR/);
  assert.match(page,/Calculate indicative amount/);
});

test('currency comparison hides choices when no approved rate is available',async()=>{
  actor('customer-fx-2');
  responses({
    '/api/v1/currencies':[{CURRENCY_CODE:'INR',DISPLAY_NAME:'Indian rupee',ACTIVE_FLAG:'Y'},{CURRENCY_CODE:'EUR',DISPLAY_NAME:'Euro',ACTIVE_FLAG:'Y'}],
    '/api/v1/fx/rates':[],
  });
  const page=await customerFxScreen();
  assert.match(page,/No current approved comparison rate is available/);
  assert.doesNotMatch(page,/id="customer-fx-form"/);
});

test('customer overview joins account, card and payment progress without exposing another account',async()=>{
  actor('customer-overview-1');
  saveSession({accessToken:'test-token',user:{userId:'customer-overview-1',userType:'CUSTOMER',permissions:['ACCOUNT_READ','CC_READ','PAYMENT_READ']}});
  responses({
    '/api/v1/banking/overview':{mode:'CUSTOMER'},
    '/api/v1/banking/my-dashboard':{accounts:[{accountEnding:'0001',status:'ACTIVE',posted:'50.00',spendable:'50.00',currency:'INR',recentTransactions:[]}]},
    '/api/v1/auth/signup-availability':{enabled:true},
    '/api/v1/credit-cards':[{CARD_ID:'card-1',DISPLAY_ENDING:'1234',PRODUCT_NAME:'Classic',STATUS:'ACTIVE',OUTSTANDING_BALANCE:'12.00'}],
    '/api/v1/payments':[{paymentId:7,amount:'3.00',status:'RECEIVED'}],
  });
  const page=await bankingScreen('banking');
  assert.match(page,/Account ending 0001/);
  assert.match(page,/Classic · •••• 1234/);
  assert.match(page,/Awaiting independent review/);
  assert.doesNotMatch(page,/undefined/);
});

test('customer product view brings governed account and card catalogs together',async()=>{
  saveSession({accessToken:'test-token',user:{userId:'customer-products-1',userType:'CUSTOMER',permissions:['PRODUCT_READ','CC_READ','FX_READ','PAYMENT_READ','PAYMENT_CREATE'],cifIds:['cif-1']}});
  responses({
    '/api/v1/products/customer-offers?cifId=cif-1&channel=WEB&currency=INR':[{product:{PRODUCT_ID:1,PRODUCT_NAME:'Everyday Savings',PRODUCT_CODE:'SAVE',PRODUCT_TYPE:'SAVINGS',CURRENCY_CODE:'INR'},version:{VERSION_NO:1}}],
    '/api/v1/credit-cards/products':[{PRODUCT_ID:'card-product',PRODUCT_NAME:'Classic Card',PRODUCT_CODE:'CLASSIC',VERSION_NO:1}],
  });
  const page=await productsScreen();
  assert.match(page,/Everyday Savings/);
  assert.match(page,/Classic Card/);
  assert.match(page,/View terms and apply/);
  assert.match(page,/View indicative rates/);
  assert.match(page,/Send money/);
});

const workflowContract={paths:Object.fromEntries([
 ['/banking/accounts','get'],['/accounts/self','post'],['/accounts/self/{id}/requests','get'],
 ['/term-deposits/my','get'],['/term-deposits','post'],['/term-deposits/{id}','get'],
 ['/banking/facilities','get'],['/loans/my/applications','get'],['/loans/my/applications','post'],
 ['/loans/collections','get'],['/loans/applications/{id}/decisions','post'],
 ['/privacy/purposes','get'],['/privacy/purposes/{id}','get'],['/privacy/purposes/available','get'],
 ['/privacy/consents/history','get'],['/privacy/consents/decisions','post'],['/privacy/consents/withdrawals','post']
].reduce((paths,[path,method])=>{const key='/api/v1'+path;let item=paths.find(([k])=>k===key);if(!item){item=[key,{}];paths.push(item);}item[1][method]={};return paths;},[])),components:{schemas:{}}};
function workflowResponses(failCatalog=false){
 const reads=[];
 globalThis.fetch=async url=>{reads.push(url);return {ok:!(failCatalog&&url.endsWith('/purposes/available')),status:failCatalog?503:200,headers:new Headers(),json:async()=>url==='/v3/api-docs'?workflowContract:url.endsWith('/consents/availability')?{decisionsEnabled:true}:url.endsWith('/payments/availability')?{initiationEnabled:true}:failCatalog?{message:'Privacy notices are temporarily unavailable.'}:url.endsWith('/purposes/available')?[{code:'OPTIONAL_CONTACT',name:'Optional contact',version:1,legalBasisCode:'CONSENT',noticeUri:'https://bank.example/privacy',noticeSha256:'a'.repeat(64)}]:[]};};
 return reads;
}

const operationIds=page=>[...page.matchAll(/data-bank-operation="([^"]+)"/g)].map(match=>match[1]);
test('disabled payment initiation keeps existing requests and recipients readable',async()=>{
 actor('payments-disabled');responses({
  '/api/v1/payments/availability':{initiationEnabled:false,mode:'UNAVAILABLE'},
  '/api/v1/banking/accounts':[{ACCOUNT_ID:1,ACCOUNT_NUMBER:'MB0001',PRODUCT_TYPE:'SAVINGS',ACCOUNT_STATUS:'ACTIVE',ACCOUNT_OPERATION_MODE:'SELF_OPERATED',CURRENCY_CODE:'INR'}],
  '/api/v1/beneficiaries':[{BENEFICIARY_ID:'b1',DISPLAY_NAME:'Verified recipient',STATUS:'ACTIVE'}],
  '/api/v1/payments':[{paymentId:10,amount:1,railCode:'UPI',status:'RECEIVED'}]
 });
 const page=await customerPaymentsScreen();assert.match(page,/External payment simulation is unavailable/);
 assert.doesNotMatch(page,/id="customer-payment-form"/);assert.match(page,/View progress/);
 assert.match(page,/Verified recipient/);assert.match(page,/Add recipient/);
});

test('demo transfers remain available with external simulation disabled and show both account directions',async()=>{
 saveSession({accessToken:'test-token',user:{userId:'internal-payments',userType:'CUSTOMER',permissions:['PAYMENT_READ','TXN_POST','TXN_READ']}});
 responses({
  '/api/v1/payments/availability':{initiationEnabled:false,mode:'UNAVAILABLE'},
  '/api/v1/banking/accounts':[
   {ACCOUNT_ID:1,ACCOUNT_NUMBER:'OWN0001',PRODUCT_TYPE:'SAVINGS',ACCOUNT_STATUS:'ACTIVE',CURRENCY_CODE:'INR'},
   {ACCOUNT_ID:3,ACCOUNT_NUMBER:'OWN0003',PRODUCT_TYPE:'DEPOSIT',ACCOUNT_STATUS:'ACTIVE',CURRENCY_CODE:'INR'}],
  '/api/v1/beneficiaries':[], '/api/v1/payments':[],
  '/api/v1/banking/transactions?accountId=1':[
   {transactionId:8,type:'INTERNAL_TRANSFER',amount:25,status:'POSTED',accountRole:'SOURCE'},
   {transactionId:9,type:'INTERNAL_TRANSFER',amount:10,status:'POSTED',accountRole:'TARGET'},
   {transactionId:10,type:'OPENING_FUNDING',amount:100,status:'POSTED',accountRole:'TARGET'}],
  '/api/v1/banking/transactions?accountId=3':[]
 });
 const page=await customerPaymentsScreen();
 assert.match(page,/id="customer-internal-payment-form"/);assert.doesNotMatch(page,/id="customer-payment-form"/);
 assert.match(page,/Recipient account number/);assert.match(page,/Sent/);assert.match(page,/Received/);assert.match(page,/Completed/);
 assert.match(page,/data-label="Amount"/);assert.match(page,/data-label="Status"/);
 assert.doesNotMatch(page,/OPENING_FUNDING/);assert.doesNotMatch(page,/OWN0003/);
});
test('unavailable consent recording preserves notices and read operations',async()=>{
 saveSession({accessToken:'test-token',user:{userId:'privacy-disabled',userType:'CUSTOMER',permissions:['PRIVACY_CONSENT_SELF','PRIVACY_CONSENT_VIEW'],cifIds:['own-profile']}});
 workflowResponses();const fetchReady=globalThis.fetch;
 globalThis.fetch=url=>url.endsWith('/consents/availability')?Promise.resolve({ok:true,status:200,headers:new Headers(),json:async()=>({decisionsEnabled:false})}):fetchReady(url);
 const page=await bankingScreen('privacy'),ids=operationIds(page);
 assert.match(page,/Changes to privacy preferences are temporarily unavailable/);assert.match(page,/Read privacy notice/);
 assert.ok(ids.includes('get/api/v1/privacy/consents/history'));
 assert.ok(!ids.some(id=>id.startsWith('post/api/v1/privacy/consents/')));
});
test('staff catalogs retain shared inquiries and approvals while excluding customer-only routes',async()=>{
 saveSession({accessToken:'test-token',user:{userId:'menu-admin',userType:'EMPLOYEE',roles:['BANK_ADMIN'],permissions:['ACCOUNT_READ','LOAN_READ','LOAN_SERVICE','GL_POST']}});workflowResponses();
 const accounts=operationIds(await bankingScreen('accounts')),loans=operationIds(await bankingScreen('loans'));
 assert.ok(accounts.includes('get/api/v1/banking/accounts'));
 assert.ok(!accounts.some(id=>id.includes('/accounts/self')||id.includes('/term-deposits/my')||id==='post/api/v1/term-deposits'));
 assert.ok(!loans.some(id=>id.includes('/loans/my')));assert.ok(loans.includes('get/api/v1/loans/collections'));
 saveSession({accessToken:'test-token',user:{userId:'menu-checker',userType:'EMPLOYEE',roles:['BANK_CHECKER'],permissions:['ACCOUNT_READ','LOAN_READ']}});workflowResponses();
 const checkerLoans=operationIds(await bankingScreen('loans')),checkerAccounts=operationIds(await bankingScreen('accounts'));
 assert.ok(!checkerLoans.includes('get/api/v1/loans/collections'));
 assert.ok(checkerLoans.includes('post/api/v1/loans/applications/{id}/decisions'));
 assert.ok(!checkerAccounts.some(id=>id.startsWith('get/api/v1/term-deposits')));
});
test('customer privacy opens the safe current catalog without reading staff purpose history',async()=>{
 saveSession({accessToken:'test-token',user:{userId:'privacy-customer',userType:'CUSTOMER',permissions:['PRIVACY_CONSENT_SELF','PRIVACY_CONSENT_VIEW']}});const reads=workflowResponses();
 const page=await bankingScreen('privacy'),ids=operationIds(page);
 assert.match(page,/Optional contact/);assert.ok(ids.includes('get/api/v1/privacy/purposes/available'));
 assert.ok(!ids.includes('get/api/v1/privacy/purposes'));assert.ok(!ids.includes('get/api/v1/privacy/purposes/{id}'));
 assert.ok(reads.includes('/api/v1/privacy/purposes/available'));assert.ok(!reads.includes('/api/v1/privacy/purposes'));
});
test('failed initial privacy inquiry shows an error instead of an empty result',async()=>{
 saveSession({accessToken:'test-token',user:{userId:'privacy-offline',userType:'CUSTOMER',permissions:['PRIVACY_CONSENT_VIEW']}});workflowResponses(true);
 const page=await bankingScreen('privacy');assert.match(page,/Privacy notices are temporarily unavailable/);assert.doesNotMatch(page,/No result to display/);
});

 test('customer privacy lists readable notices without exposing a digest or staff controls',async()=>{
  saveSession({accessToken:'test-token',user:{userId:'privacy-notice-view',userType:'CUSTOMER',permissions:['PRIVACY_CONSENT_VIEW'],cifIds:['own-profile']}});workflowResponses();
  const page=await bankingScreen('privacy'),ids=operationIds(page);
  assert.match(page,/Read privacy notice/);assert.match(page,/href="https:\/\/bank.example\/privacy"/);
  assert.doesNotMatch(page,/a{64}/);assert.ok(!ids.includes('post/api/v1/privacy/consents/decisions'));
  assert.ok(!ids.includes('post/api/v1/privacy/consents/withdrawals'));
 });
