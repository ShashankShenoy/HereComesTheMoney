import test from 'node:test';
import assert from 'node:assert/strict';

const storage=new Map();
globalThis.sessionStorage={getItem:key=>storage.get(key)||null,setItem:(key,value)=>storage.set(key,value),removeItem:key=>storage.delete(key)};
const requireJet=(modules,ready)=>ready({}, {whenDocumentReady:()=>Promise.resolve()}, class {}, class {});
requireJet.config=()=>{};
globalThis.window={require:requireJet};
globalThis.document={documentElement:{lang:''},addEventListener:()=>{},getElementById:()=>null};

const {saveSession}=await import('./api.js');
const {bankingScreen,transactionDisplayCells}=await import('./banking.js');

function session(userId,userType='CUSTOMER'){
 saveSession({accessToken:'test-token',user:{userId,userType,roles:userType==='EMPLOYEE'?['BANK_ADMIN']:[],permissions:['TXN_READ']}});
}
function accountResponse(accounts){
 const paths=[];
 globalThis.fetch=async url=>{
  paths.push(url);
  return {ok:true,status:200,headers:new Headers(),json:async()=>accounts};
 };
 return paths;
}

test('customers and staff select from the accounts available to their identity',async()=>{
 session('picker-customer');
 const paths=accountResponse([
  {ACCOUNT_NUMBER:'MCPALPHA0001',PRODUCT_NAME:'Savings',ACCOUNT_STATUS:'ACTIVE'},
  {ACCOUNT_NUMBER:'MCPALPHA0002',PRODUCT_NAME:'Current',ACCOUNT_STATUS:'DORMANT'}
 ]);
 const customer=await bankingScreen('transactions');
 assert.deepEqual(paths,['/api/v1/banking/accounts']);
 assert.match(customer,/<select name="accountNumber" required/);
 assert.match(customer,/<option value="MCPALPHA0001"[^>]*>MCPALPHA0001 · Savings · ACTIVE<\/option>/);
 assert.match(customer,/<option value="MCPALPHA0002"[^>]*>MCPALPHA0002 · Current · DORMANT<\/option>/);
 assert.doesNotMatch(customer,/placeholder="Enter an account number"/);
 assert.doesNotMatch(customer,/data-transaction-tab="operations"|transaction-section-operations|Send an internal transfer by account number/);

 session('picker-staff','EMPLOYEE');
 const staff=await bankingScreen('transactions');
 assert.doesNotMatch(staff,/<input name="accountNumber"/);
 assert.match(staff,/<select name="accountNumber"/);
 assert.match(staff,/data-transaction-tab="operations"/);
});

test('customers see an empty or retry state instead of a free-text account field',async()=>{
 session('picker-empty');accountResponse([]);
 const empty=await bankingScreen('transactions');
 assert.match(empty,/No linked accounts available\./);
 assert.match(empty,/<select name="accountNumber" required disabled>/);
 assert.match(empty,/type="submit" class="btn primary" disabled/);

 session('picker-error');
 globalThis.fetch=async()=>({ok:false,status:503,headers:new Headers(),json:async()=>({message:'Unavailable'})});
 const error=await bankingScreen('transactions');
 assert.match(error,/Could not load your accounts\./);
 assert.match(error,/data-txn-accounts-retry/);
 assert.doesNotMatch(error,/placeholder="Enter an account number"/);
});

test('account transaction rows preserve payment rail and progress with signed posting',()=>{
 const cells=transactionDisplayCells({transactionId:'PAYMENT-91',type:'EXTERNAL_PAYMENT_RECEIVED',amount:'50.00',
  currency:'INR',postedDelta:'50.00',status:'POSTED',paymentStatus:'SETTLED',accountRole:'TARGET',
  railCode:'IMPS',paymentId:91,valueDate:'2026-10-14'});
 assert.match(cells[1],/IMPS · Payment 91/);
 assert.match(cells[2],/External payment received/);
 assert.match(cells[3],/\+ INR 50\.00/);
 assert.match(cells[4],/Interbank settlement completed; customer credited/);
});
