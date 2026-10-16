import test from 'node:test';
import assert from 'node:assert/strict';
globalThis.sessionStorage={getItem:()=>null,setItem:()=>{},removeItem:()=>{}};
const {validatePrepayment,bindLoanPrepayment}=await import('./loan-prepayments.js');
const quote={partialPrepaymentAllowed:true,foreclosureAllowed:true,minimumPartialPayment:'39.46',foreclosureAmount:'12039.45'};
test('partial payment must cover interest and leave principal outstanding',()=>{
 validatePrepayment(quote,'PARTIAL','1039.45');
 assert.throws(()=>validatePrepayment(quote,'PARTIAL','39.45'),/quoted minimum/);
 assert.throws(()=>validatePrepayment(quote,'PARTIAL','12039.45'),/foreclosure/);
});
test('foreclosure uses the exact latest quote',()=>{
 validatePrepayment(quote,'FORECLOSURE','12039.45');
 assert.throws(()=>validatePrepayment(quote,'FORECLOSURE','12039.44'),/quote changed/);
});
test('disabled product modes and malformed amounts are rejected',()=>{
 assert.throws(()=>validatePrepayment({...quote,partialPrepaymentAllowed:false},'PARTIAL','100'),/not enabled/);
 assert.throws(()=>validatePrepayment({...quote,foreclosureAllowed:false},'FORECLOSURE','12039.45'),/not enabled/);
 for(const amount of ['1.001','-1','1e3','',null])assert.throws(()=>validatePrepayment(quote,'PARTIAL',amount));
 assert.throws(()=>validatePrepayment(quote,'UNKNOWN','100'),/Choose/);
});
test('a changed foreclosure quote is shown for review before any payment submission',async()=>{
 const handlers={};const input=value=>({value,dispatchEvent(){},addEventListener:(event,fn)=>handlers[value+event]=fn});
 const id=input('7'),mode=input('FORECLOSURE');mode.options=[{value:'PARTIAL'},{value:'FORECLOSURE'}];
 const amount=input(''),notice={},submit={},error={},key=input('initial-key');let posted=0;
 const nodes={'[name=id]':id,'[name=mode]':mode,'[name=amount]':amount,'[data-loan-prepayment-quote]':notice,'[type=submit]':submit,'.form-error':error,'[name=requestKey]':key};
 const form={dataset:{},querySelector:selector=>nodes[selector],onsubmit:async()=>posted++};
 let current={...quote,valueDate:'2026-10-15'};bindLoanPrepayment(form,async()=>current);await new Promise(setImmediate);
 assert.equal(amount.value,'12039.45');assert.equal(amount.readOnly,true);
 current={...current,foreclosureAmount:'12040.00'};
 await form.onsubmit({preventDefault(){}});assert.equal(posted,0);assert.match(error.textContent,/quote changed/);assert.equal(amount.value,'12040.00');
 await form.onsubmit({preventDefault(){}});assert.equal(posted,1);
});

test('mode changes synchronize the visible JET amount and prevent editing the payoff',async()=>{
 const handlers={},visible={value:null,readonly:false};
 const id={value:'7',addEventListener:(event,fn)=>handlers.id=fn};
 const mode={value:'PARTIAL',options:[{value:'PARTIAL'},{value:'FORECLOSURE'}],addEventListener:(event,fn)=>handlers.mode=fn};
 const amount={value:'',closest:()=>({querySelector:()=>visible}),dispatchEvent(event){assert.equal(event.type,'change');visible.value=this.value;}};
 const nodes={'[name=id]':id,'[name=mode]':mode,'[name=amount]':amount,'[data-loan-prepayment-quote]':{},'[type=submit]':{},'.form-error':{}};
 bindLoanPrepayment({querySelector:selector=>nodes[selector],onsubmit:async()=>{}},async()=>({...quote,valueDate:'2026-10-15'}));
 await new Promise(setImmediate);
 mode.value='FORECLOSURE';handlers.mode();
 assert.equal(visible.value,'12039.45');assert.equal(visible.readonly,true);
 mode.value='PARTIAL';handlers.mode();assert.equal(visible.readonly,false);
 handlers.id();assert.equal(visible.value,'');
});
