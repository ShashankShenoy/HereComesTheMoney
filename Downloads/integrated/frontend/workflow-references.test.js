import test from 'node:test';
import assert from 'node:assert/strict';
const storage=new Map();
globalThis.sessionStorage={getItem:k=>storage.get(k)||null,setItem:(k,v)=>storage.set(k,v),removeItem:k=>storage.delete(k)};
const requireJet=(modules,ready)=>ready({}, {whenDocumentReady:()=>Promise.resolve()}, class {}, class {});requireJet.config=()=>{};
globalThis.window={require:requireJet};
let button=null,transactionTab=null;
globalThis.document={documentElement:{lang:''},addEventListener:()=>{},getElementById:()=>null,querySelector:()=>null,querySelectorAll:selector=>selector==='[data-bank-operation]'&&button?[button]:selector==='[data-transaction-tab]'&&transactionTab?[transactionTab]:[]};
const {saveSession}=await import('./api.js');
const {bankingScreen,bindBankingForms,configureBanking}=await import('./banking.js');
const contract={paths:{
 '/api/v1/banking/facilities':{get:{}},
 '/api/v1/reporting/statement-requests':{post:{requestBody:{content:{'application/json':{schema:{type:'object',properties:{accountId:{type:'integer'},purposeCode:{type:'string'}},required:['accountId']}}}}}},
 '/api/v1/loans/facilities/{id}/prepayments':{
  post:{
   parameters:[{in:'path',name:'id',required:true,schema:{type:'integer'}}],
   requestBody:{content:{'application/json':{schema:{$ref:'#/components/schemas/LoanPrepay'}}}}
  }
 },
 '/api/v1/banking/journals':{get:{}},
 '/api/v1/journals/{journalId}/control':{get:{parameters:[{in:'path',name:'journalId',required:true,schema:{type:'integer'}}]}},
 '/api/v1/treasury/settlement-evidence':{get:{}},
 '/api/v1/treasury/settlement-evidence/{id}':{get:{parameters:[{in:'path',name:'id',required:true,schema:{type:'integer'}}]}},
 '/api/v1/treasury/work-owners':{get:{}},
 '/api/v1/treasury/work-items':{get:{},post:{requestBody:{content:{'application/json':{schema:{type:'object',properties:{ownerId:{type:'string'}},required:['ownerId']}}}}}},
 '/api/v1/teller/vaults':{post:{requestBody:{content:{'application/json':{schema:{$ref:'#/components/schemas/OpenVault'}}}}}},
 '/api/v1/teller/tills':{get:{},post:{requestBody:{content:{'application/json':{schema:{$ref:'#/components/schemas/OpenTill'}}}}}},
 '/api/v1/teller/cash':{post:{requestBody:{content:{'application/json':{schema:{$ref:'#/components/schemas/Cash'}}}}}},
 '/api/v1/teller/tills/{id}/close':{post:{parameters:[{in:'path',name:'id',required:true,schema:{type:'string'}}],requestBody:{content:{'application/json':{schema:{$ref:'#/components/schemas/CloseTill'}}}}}},
 '/api/v1/loans/applications':{get:{parameters:[{in:'query',name:'branch',schema:{type:'string'}}]},post:{requestBody:{content:{'application/json':{schema:{$ref:'#/components/schemas/LoanCreate'}}}}}},
 '/api/v1/loans/applications/{id}/decisions':{post:{parameters:[{in:'path',name:'id',required:true,schema:{type:'integer'}}],requestBody:{content:{'application/json':{schema:{$ref:'#/components/schemas/LoanDecision'}}}}}}
},components:{schemas:{
 LoanPrepay:{type:'object',properties:{requestKey:{type:'string'},amount:{type:'number'},mode:{type:'string',enum:['PARTIAL','FORECLOSURE']}},required:['requestKey','amount','mode']},
 OpenVault:{type:'object',properties:{branchCode:{type:'string'},vaultGlId:{type:'integer'},countedCash:{type:'number'}},required:['branchCode','vaultGlId','countedCash']},
 Cash:{type:'object',properties:{tillId:{type:'string'},accountId:{type:'integer'},amount:{type:'number'}},required:['tillId','accountId','amount']},
 OpenTill:{type:'object',properties:{branchCode:{type:'string'},cashGlId:{type:'integer'}},required:['branchCode','cashGlId']},
 CloseTill:{type:'object',properties:{countedCash:{type:'number'},reason:{type:'string'}},required:['countedCash','reason']},
 LoanCreate:{type:'object',properties:{cifId:{type:'string'},productId:{type:'integer'},productVersionId:{type:'integer'},branchCode:{type:'string'},amount:{type:'number'},purposeCode:{type:'string'}},required:['cifId','productId','productVersionId','branchCode','amount','purposeCode']},
 LoanDecision:{type:'object',properties:{code:{type:'string',enum:['APPROVE','REJECT','REFER']},authorityCode:{type:'string'},sanctionedAmount:{type:'number'},sanctionedTenureMonths:{type:'integer'},annualRatePct:{type:'number'}},required:['code','authorityCode']}
}}};
const rows={
 '/api/v1/banking/facilities':[{FACILITY_ID:17,FACILITY_NUMBER:'LOAN-FAC-017',STATUS:'ACTIVE'},{FACILITY_ID:18,FACILITY_NUMBER:'PENDING-018',STATUS:'PENDING_DISBURSEMENT'}],
 '/api/v1/privacy/purposes?status=ACTIVE&size=200':{items:[
  {id:'reporting-purpose',code:'REPORTING',name:'Statement assistance',status:'ACTIVE',ownerService:'M10_STATEMENTS',effectiveFrom:'2020-01-01'},
  {id:'unrelated-purpose',code:'MARKETING',name:'Marketing',status:'ACTIVE',ownerService:'M09_CONSENT',effectiveFrom:'2020-01-01'},
  {id:'expired-purpose',code:'EXPIRED',name:'Expired statements',status:'ACTIVE',ownerService:'M10_STATEMENTS',effectiveFrom:'2020-01-01',effectiveTo:'2020-02-01'}
 ]},
 '/api/v1/banking/journals':[{JOURNAL_ID:227,JOURNAL_TYPE:'OPENING_FUNDING',VALUE_DATE:'2026-10-15',DESCRIPTION:'Demo opening funds'}],
 '/api/v1/treasury/settlement-evidence':[{id:56,reserveAccountId:1,railCode:'IMPS',status:'VERIFIED',amount:5000,externalSettlementRef:'SIM-C-25'}],
 '/api/v1/treasury/work-owners':[{userId:'treasury-maker',username:'admin'},{userId:'treasury-reviewer',username:'checker'}],
 '/api/v1/banking/gl-accounts':[{GL_ACCOUNT_ID:4,GL_CODE:'VAULT_CASH',GL_NAME:'Vault cash',ACCOUNT_CLASS:'ASSET',ACTIVE_FLAG:'Y',CURRENCY_CODE:'INR'},{GL_ACCOUNT_ID:5,GL_CODE:'OLD_CASH',GL_NAME:'Retired cash',ACCOUNT_CLASS:'ASSET',ACTIVE_FLAG:'N',CURRENCY_CODE:'INR'}],
 '/api/v1/banking/accounts':[{ACCOUNT_ID:21,ACCOUNT_NUMBER:'OWN00021',PRODUCT_NAME:'Savings',ACCOUNT_STATUS:'ACTIVE'},{ACCOUNT_ID:22,ACCOUNT_NUMBER:'NEW00022',PRODUCT_NAME:'Savings',ACCOUNT_STATUS:'PENDING_OPEN'}],
 '/api/v1/teller/tills':[{TILL_ID:'till-open',STATUS:'OPEN',BRANCH_CODE:'BR01',CAN_POST_CASH:true},{TILL_ID:'till-closed',STATUS:'CLOSED',CAN_POST_CASH:false},{TILL_ID:'till-yesterday',STATUS:'OPEN',CAN_POST_CASH:false},{TILL_ID:'till-other-teller',STATUS:'OPEN',CAN_POST_CASH:false}],
 '/api/v1/banking/branches':[{BRANCH_CODE:'BR01'}],
 '/api/v1/cif/customers':[{CIF_ID:'customer-cif',LEGAL_NAME:'Customer reference',STATUS:'ACTIVE'}],
 '/api/v1/products':[{PRODUCT_ID:1,PRODUCT_NAME:'Savings',PRODUCT_TYPE:'SAVINGS'},{PRODUCT_ID:2,PRODUCT_NAME:'Personal loan',PRODUCT_TYPE:'LOAN'}],
 '/api/v1/loans/applications':[{id:7,number:'LOAN-007',status:'UNDER_REVIEW',amount:10000},{id:8,number:'LOAN-008',status:'DRAFT'},{id:9,number:'LOAN-009',status:'APPROVED'}],
 '/api/v1/iam/roles':[{roleId:'checker-role',roleCode:'BANK_CHECKER'}],
 '/api/v1/iam/roles/checker-role':{authorities:[{authorityCode:'LOAN_SANCTION',currencyCode:'INR',maxAmount:5000000,validFrom:'2020-01-01'}]}
};
const calls=[];
function actor(id,checker=false){calls.length=0;saveSession({accessToken:'fixture',user:{userId:id,userType:'EMPLOYEE',roles:[checker?'BANK_CHECKER':'BANK_ADMIN'],permissions:['ACCOUNT_READ','LOAN_READ','TELLER_READ','TXN_READ','GL_RECONCILE','TREASURY_READ','TREASURY_RECONCILE',...(checker?['LOAN_DECIDE','TELLER_APPROVE']:['TELLER_OPERATE','LOAN_CREATE','LOAN_SERVICE','GL_ADMIN'])]}});globalThis.fetch=async url=>{calls.push(url);if(url.startsWith('/api/v1/iam/users'))throw new Error('General user directory must not be used for treasury owners');return {ok:true,status:200,headers:new Headers(),json:async()=>url==='/v3/api-docs'?contract:rows[url]||[]};};}
async function choose(page,path,method='post'){
 let html;configureBanking(async()=>html=await bankingScreen(page));await bankingScreen(page);
 if(page==='transactions'){transactionTab={dataset:{transactionTab:'operations'}};bindBankingForms();await transactionTab.onclick();transactionTab=null;}
 button={dataset:{bankOperation:method+'/api/v1'+path},addEventListener:(name,callback)=>button[name]=callback};
 bindBankingForms();await button.click();button=null;return html;
}
test('teller cash uses account and open-till dropdowns rather than raw IDs',async()=>{
 actor('teller-maker');const html=await choose('teller','/teller/cash');
 assert.match(html,/<select name="accountId" required>/);assert.match(html,/<option value="21"[^>]*>OWN00021/);
 assert.match(html,/<select name="tillId" required>/);assert.match(html,/<option value="till-open"/);assert.doesNotMatch(html,/<option value="till-closed"/);
 assert.doesNotMatch(html,/<input name="accountId"|<input name="tillId"/);
 assert.doesNotMatch(html,/<option value="till-yesterday"|<option value="till-other-teller"/);
 assert.match(html,/<option value="22"[^>]*>NEW00022/);
 assert.match(html,/For a pending account, this records opening funding/);
});
test('checker with teller permissions can open a till and collect cash',async()=>{
 actor('cash-checker',true);
 const {getSession}=await import('./api.js');
 const session=getSession();session.user.permissions.push('TELLER_OPERATE','TXN_POST');saveSession(session);
 const html=await choose('teller','/teller/cash');
 assert.match(html,/data-bank-operation="post\/api\/v1\/teller\/tills"/);
 assert.match(html,/<select name="accountId" required>/);
 assert.match(html,/For a pending account, this records opening funding/);
});

test('older teller services still offer only the current actor and business date',async()=>{
 actor('legacy-teller');
 const original=rows['/api/v1/teller/tills'];
 const today=new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Kolkata',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date());
 try{
  rows['/api/v1/teller/tills']=[
   {TILL_ID:'legacy-own',STATUS:'OPEN',TELLER_USER_ID:'legacy-teller',BUSINESS_DATE:today+'T00:00:00'},
   {TILL_ID:'legacy-other',STATUS:'OPEN',TELLER_USER_ID:'another-teller',BUSINESS_DATE:today},
   {TILL_ID:'legacy-expired',STATUS:'OPEN',TELLER_USER_ID:'legacy-teller',BUSINESS_DATE:'2000-01-01'}];
  const html=await choose('teller','/teller/cash');
  assert.match(html,/<option value="legacy-own"/);
  assert.doesNotMatch(html,/<option value="legacy-other"|<option value="legacy-expired"/);
 }finally{rows['/api/v1/teller/tills']=original;}
});

test('loan creation shows customer, branch and loan-product dropdowns',async()=>{
 actor('loan-maker');const html=await choose('loans','/loans/applications');
 for(const name of ['cifId','productId','productVersionId','branchCode','purposeCode'])assert.match(html,new RegExp('<select name="'+name+'"'));
 assert.match(html,/<option value="2"[^>]*>Personal loan/);assert.doesNotMatch(html,/<option value="1"[^>]*>Savings/);
 assert.match(html,/Customer reference/);assert.match(html,/<option value="BR01"/);
 assert.match(html,/<option value="PERSONAL"/);
});
test('prepayment selects an existing loan and displays its quote panel',async()=>{
 actor('loan-servicer');const html=await choose('loans','/loans/facilities/{id}/prepayments');
 assert.match(html,/<select name="id" required>/);assert.match(html,/<option value="17"[^>]*>LOAN-FAC-017/);
 assert.match(html,/data-loan-prepayment-quote/);assert.match(html,/<select name="mode" required>/);assert.doesNotMatch(html,/<input name="id"/);
 assert.doesNotMatch(html,/<option value="18"/);
});
test('staff statement purpose selector excludes expired and unrelated purposes',async()=>{
 actor('statement-maker');const html=await choose('statements','/reporting/statement-requests');
 assert.match(html,/<select name="purposeCode" required>/);assert.match(html,/<option value="REPORTING"/);
 assert.doesNotMatch(html,/<option value="MARKETING"|<option value="EXPIRED"/);
});
test('vault setup offers the active cash ledger instead of an empty or invalid selector',async()=>{
 actor('vault-maker');const html=await choose('teller','/teller/vaults');
 assert.match(html,/<select name="vaultGlId" required>/);assert.match(html,/<option value="4"[^>]*>Vault cash/);
 assert.doesNotMatch(html,/Retired cash/);
});

test('checker opens loan and teller review queues with actionable records',async()=>{
 actor('loan-checker',true);const loans=await bankingScreen('loans');
 assert.match(loans,/Records awaiting review/);assert.match(loans,/LOAN-007/);assert.doesNotMatch(loans,/LOAN-008|LOAN-009/);
 assert.match(loans,/data-review-id="7"/);
 const teller=await bankingScreen('teller');assert.match(teller,/Records awaiting review/);assert.match(teller,/data-review-id="till-open"/);assert.doesNotMatch(teller,/data-review-id="till-closed"/);
});

test('journal control selects the journal ID rather than another record identifier',async()=>{
 actor('reconciler');const html=await choose('transactions','/journals/{journalId}/control','get');
 assert.match(html,/<select name="journalId" required>/);
 assert.match(html,/<option value="227"[^>]*>Journal 227 · OPENING_FUNDING · 2026-10-15 · Demo opening funds/);
 assert.doesNotMatch(html,/<input name="journalId"/);
});

test('settlement evidence inquiry offers identifiable evidence instead of a typed ID',async()=>{
 actor('treasury-reader');const html=await choose('treasury','/treasury/settlement-evidence/{id}','get');
 assert.match(html,/<select name="id" required>/);
 assert.match(html,/<option value="56"[^>]*>SIM-C-25 · VERIFIED · INR 5000 · Reference 56/);
 assert.doesNotMatch(html,/<input name="id"/);
});

test('treasury owners come from eligible staff without general user-directory access',async()=>{
 actor('treasury-maker');const html=await choose('treasury','/treasury/work-items');
 assert.match(html,/<select name="ownerId" required>/);
 assert.match(html,/<option value="treasury-maker"[^>]*>admin/);
 assert.match(html,/<option value="treasury-reviewer"[^>]*>checker/);
 assert.ok(calls.includes('/api/v1/treasury/work-owners'));
 assert.ok(!calls.some(path=>path.startsWith('/api/v1/iam/users')));
});

