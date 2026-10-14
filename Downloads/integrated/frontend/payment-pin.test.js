import test from 'node:test';
import assert from 'node:assert/strict';
const storage=new Map();globalThis.sessionStorage={getItem:key=>storage.get(key)||null,setItem:(key,value)=>storage.set(key,value),removeItem:key=>storage.delete(key)};
const requireJet=(modules,ready)=>ready({}, {whenDocumentReady:()=>Promise.resolve()}, class {}, class {});requireJet.config=()=>{};
globalThis.window={require:requireJet};let input={value:'134679'};
globalThis.document={documentElement:{lang:''},addEventListener:()=>{},querySelector:()=>input};
const {saveSession}=await import('./api.js');
const {validateNewPin,withPaymentPin,paymentPinField,pinSecurityPanel}=await import('./payment-pin.js');
function customer(){saveSession({accessToken:'fixture',user:{userId:'customer',userType:'CUSTOMER'}});}
test('PIN validation preserves leading zeroes and requires matching six digits',()=>{
 const data=new FormData();data.set('newPin','000123');data.set('confirmPin','000123');assert.equal(validateNewPin(data),'000123');
 data.set('confirmPin','000124');assert.throws(()=>validateNewPin(data),/do not match/);data.set('newPin','1234');assert.throws(()=>validateNewPin(data),/six-digit/);
});
test('payment PIN is ephemeral, separate from the payment body and cleared after success',async()=>{
 customer();input={value:'134679'};const data=new FormData();data.set('paymentPin','134679');
 const response=await withPaymentPin(data,async headers=>{assert.equal(headers['X-Payment-Pin'],'134679');assert.equal(data.has('paymentPin'),false);return {paymentId:3};});
 assert.equal(response.paymentId,3);assert.equal(input.value,'');assert.doesNotMatch(storage.get('moneybags-session'),/134679|paymentPin/);
});
test('incorrect PIN response clears the input and missing PIN does not send the payment',async()=>{
 customer();input={value:'134679'};const data=new FormData();data.set('paymentPin','134679');await assert.rejects(withPaymentPin(data,async()=>{throw new Error('Incorrect payment PIN');}));assert.equal(input.value,'');
 let sent=false;await assert.rejects(withPaymentPin(new FormData(),async()=>{sent=true;}),/six-digit/);assert.equal(sent,false);
});
test('PIN prompt is masked and not added to employee decisions',async()=>{
 customer();assert.match(paymentPinField(),/type="password"/);assert.match(paymentPinField(),/pattern="\[0-9\]\{6\}"/);
 saveSession({accessToken:'fixture',user:{userType:'EMPLOYEE'}});assert.equal(paymentPinField(),'');await withPaymentPin(new FormData(),async headers=>assert.deepEqual(headers,{}));
});
test('reset screen asks for the login password and new PIN rather than the old PIN',async()=>{
 customer();globalThis.fetch=async()=>({ok:true,status:200,headers:new Headers(),json:async()=>({configured:true,locked:true})});
 const html=await pinSecurityPanel();assert.match(html,/name="currentPassword"/);assert.match(html,/name="newPin"/);assert.match(html,/name="confirmPin"/);assert.doesNotMatch(html,/name="oldPin"/);assert.match(html,/temporarily locked/);
});
