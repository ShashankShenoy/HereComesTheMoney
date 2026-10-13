// Local synthetic browser-verification fixture; deliberately refuses Oracle ports.
const base='http://127.0.0.1:8090/api/v1';
async function request(path,body,token){const response=await fetch(base+path,{method:'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},body:JSON.stringify(body)});const value=await response.json();if(!response.ok)throw new Error(value.code||value.message||'Fixture request failed');return value.data||value;}
async function login(username){return (await request('/auth/login',{username,password:'LocalBanking!2026',clientId:'MONEYBAGS_WEB'})).accessToken;}
const admin=await login('admin'),customer=await login('customer'),checker=await login('checker');
const prefix=crypto.randomUUID();
const pending=await request('/beneficiaries',{displayName:'Browser pending recipient',accountToken:'EXT123456789',bankCode:'HDFC0000001'},customer);
const active=await request('/beneficiaries',{displayName:'Browser verified recipient',accountToken:'EXT987654321',bankCode:'ICIC0000001'},customer);
await request('/beneficiaries/'+active.beneficiaryId+'/verify',{},checker);
await request('/payments/simulator/incoming',{sendingBank:'SBI',destinationAccountNumber:'MB000000001',destinationBranch:'MUM001',destinationIfsc:'MCPB0000002',railCode:'RTGS',amount:'200000',externalReference:prefix+'-fund'},admin);
const review=await request('/payments/initiate',{sourceAccountId:1,beneficiaryId:active.beneficiaryId,railCode:'NEFT',amount:'100000.01',requestKey:prefix+'-review'},customer);
const automatic=await request('/payments/initiate',{sourceAccountId:1,beneficiaryId:active.beneficiaryId,railCode:'UPI',amount:'10',requestKey:prefix+'-auto'},customer);
console.log(JSON.stringify({pendingBeneficiary:pending.beneficiaryId,pendingPayment:review.paymentId,automaticPayment:automatic.paymentId},null,2));
