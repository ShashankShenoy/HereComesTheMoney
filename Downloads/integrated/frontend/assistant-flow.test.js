import test from 'node:test';
import assert from 'node:assert/strict';

const storage=new Map();
globalThis.sessionStorage={getItem:key=>storage.get(key)||null,setItem:(key,value)=>storage.set(key,value),removeItem:key=>storage.delete(key)};
const requireJet=(modules,ready)=>ready({cleanNode(){}}, {whenDocumentReady:()=>Promise.resolve()}, class {}, class {});
requireJet.config=()=>{};
globalThis.window={require:requireJet};
let nodes={};
globalThis.document={documentElement:{lang:''},addEventListener:()=>{},querySelector:selector=>nodes[selector]||null};
const {saveSession}=await import('./api.js');
const {assistantWidget,bindAssistant,resetAssistant}=await import('./assistant.js');
const tick=()=>new Promise(resolve=>setImmediate(resolve));
const response=value=>({ok:true,status:200,headers:new Headers(),json:async()=>value});

async function setup(pendingActions){
  resetAssistant();
  saveSession({accessToken:'fixture',user:{userId:'customer',sessionId:'session',userType:'CUSTOMER'}});
  const input={value:'',focus(){},isConnected:true};
  nodes=Object.fromEntries(['#assistant-feed','#assistant-send','#assistant-popover','#assistant-toggle','#assistant-close','#assistant-status-text','#assistant-config'].map(key=>[key,{setAttribute(){}}]));
  nodes['#assistant-form']={querySelector:()=>input};
  nodes['#assistant-input']=input;
  globalThis.fetch=async url=>{
    if(url.endsWith('/assistant/status'))return response({configured:true});
    if(url.endsWith('/assistant/chat'))return response({conversationId:'conversation',answer:'Review the form.',pendingActions});
    throw new Error('Unexpected request: '+url);
  };
  assistantWidget();bindAssistant();await tick();
  input.value='Add a beneficiary';
  nodes['#assistant-form'].onsubmit({preventDefault(){}});await tick();
  return nodes['#assistant-feed'];
}

function beneficiaryForm(id,values){
  const error={textContent:''},button={disabled:false};
  const form={dataset:{assistantBeneficiary:id},values,querySelector:selector=>selector==='.form-error'?error:button};
  form.closest=selector=>selector==='[data-assistant-beneficiary]'?form:null;
  return {form,error,button};
}
async function withFormData(run){
  const Native=globalThis.FormData;
  globalThis.FormData=class extends Native {constructor(form){super();for(const [key,value] of Object.entries(form?.values||{}))this.set(key,value);}};
  try{await run();}finally{globalThis.FormData=Native;}
}
const submit=target=>({target,preventDefault(){}});
const values={displayName:'Ravi',accountToken:'recipient001',confirmToken:'recipient001',bankCode:'abcd0123456'};

test('beneficiary form validates confirmation and posts details directly without a model call',async()=>{
  const feed=await setup([{uiAction:'ADD_BENEFICIARY',formId:'form1',displayName:'<Ravi>'}]);
  assert.match(feed.innerHTML,/&lt;Ravi&gt;/);
  const {form,error}=beneficiaryForm('form1',{...values,confirmToken:'different'});
  const requests=[];
  globalThis.fetch=async(url,options)=>{requests.push({url,body:JSON.parse(options.body)});return response({beneficiaryId:'new1',status:'PENDING'});};
  await withFormData(async()=>{
    await feed.onsubmit(submit(form));assert.match(error.textContent,/do not match/);assert.equal(requests.length,0);
    form.values.confirmToken=values.accountToken;
    await feed.onsubmit(submit(form));
  });
  assert.deepEqual(requests,[{url:'/api/v1/beneficiaries',body:{displayName:'Ravi',accountToken:'recipient001',bankCode:'ABCD0123456'}}]);
  assert.match(feed.innerHTML,/new1/);assert.match(feed.innerHTML,/PENDING/);
  assert.doesNotMatch(feed.innerHTML,/data-assistant-beneficiary=/);
});

test('beneficiary registration blocks duplicate submissions even after chat repaints the form',async()=>{
  const feed=await setup([{uiAction:'ADD_BENEFICIARY',formId:'form2',displayName:'Ravi'}]);
  let finish,count=0;
  globalThis.fetch=async url=>{count++;if(url.endsWith('/assistant/status'))return response({configured:true});return new Promise(resolve=>{finish=resolve;});};
  const {form}=beneficiaryForm('form2',{...values});
  await withFormData(async()=>{
    const pending=feed.onsubmit(submit(form));
    // The status refresh repaints the feed; the replacement form must stay disabled.
    nodes['#assistant-toggle'].onclick();await tick();
    assert.match(feed.innerHTML,/class="btn primary" disabled/);
    await feed.onsubmit(submit(form));
    finish(response({beneficiaryId:'new2',status:'PENDING'}));await pending;
  });
  // One registration and one status refresh, with no second registration.
  assert.equal(count,2);
});

test('registration failure preserves the form and permits a retry',async()=>{
  const feed=await setup([{uiAction:'ADD_BENEFICIARY',formId:'form3',displayName:'Ravi'}]);
  const {form,button,error}=beneficiaryForm('form3',{...values});
  globalThis.fetch=async()=>{throw new Error('offline');};
  await withFormData(async()=>{
    await feed.onsubmit(submit(form));assert.equal(button.disabled,false);assert.match(error.textContent,/Cannot reach/);
    assert.match(feed.innerHTML,/Cannot reach/);assert.doesNotMatch(feed.innerHTML,/class="btn primary" disabled/);
    globalThis.fetch=async()=>response({beneficiaryId:'retry',status:'PENDING'});
    await feed.onsubmit(submit(form));assert.match(feed.innerHTML,/retry/);
  });
});

test('failed officer confirmation can be retried and success removes the action',async()=>{
  const feed=await setup([{intentId:'intent1',action:'BENEFICIARY_VERIFY',summary:'Verify Ravi'}]);
  const button={dataset:{assistantConfirm:'intent1'},disabled:false};
  const target={closest:selector=>selector==='[data-assistant-confirm]'?button:null};
  globalThis.fetch=async()=>{throw new Error('offline');};
  await feed.onclick({target});assert.equal(button.disabled,false);assert.match(feed.innerHTML,/Cannot reach/);
  globalThis.fetch=async()=>response({action:'BENEFICIARY_VERIFY',resultRef:'beneficiary1'});
  await feed.onclick({target});assert.match(feed.innerHTML,/beneficiary1 was verified/);assert.doesNotMatch(feed.innerHTML,/data-assistant-confirm=/);
});

test('no provider configuration keeps sending disabled',async()=>{
  await setup([]);
  globalThis.fetch=async()=>response({configured:false});
  nodes['#assistant-toggle'].onclick();await tick();
  assert.equal(nodes['#assistant-send'].disabled,true);assert.match(nodes['#assistant-config'].textContent,/API key/);
});

test('late chat responses cannot appear after switching sign-in',async()=>{
  await setup([]);let complete;
  globalThis.fetch=async()=>new Promise(resolve=>{complete=resolve;});
  nodes['#assistant-input'].value='Private account question';nodes['#assistant-form'].onsubmit({preventDefault(){}});
  saveSession({accessToken:'other',user:{userId:'other',sessionId:'other-session',userType:'CUSTOMER'}});
  complete(response({answer:'Private account balance',conversationId:'old',pendingActions:[]}));await tick();
  assert.doesNotMatch(assistantWidget(),/Private account balance|Private account question/);
});

test('repeated intent proposals render only one confirmation card',async()=>{
  const pendingActions=[{intentId:'same',action:'PAYMENT_INITIATE',summary:'Pay recipient'}];const feed=await setup(pendingActions);
  globalThis.fetch=async()=>response({conversationId:'conversation',answer:'Review again',pendingActions});
  nodes['#assistant-input'].value='Show again';nodes['#assistant-form'].onsubmit({preventDefault(){}});await tick();
  assert.equal((feed.innerHTML.match(/data-assistant-confirm="same"/g)||[]).length,1);
});

test('expired confirmations leave the error visible and remove the stale action',async()=>{
  const feed=await setup([{intentId:'expired',action:'BENEFICIARY_VERIFY',summary:'Expired proposal'}]);
  const button={dataset:{assistantConfirm:'expired'},disabled:false},target={closest:selector=>selector==='[data-assistant-confirm]'?button:null};
  globalThis.fetch=async()=>({ok:false,status:409,headers:new Headers(),json:async()=>({code:'INTENT_EXPIRED',message:'This action has expired; start again'})});
  await feed.onclick({target});assert.match(feed.innerHTML,/This action has expired/);assert.doesNotMatch(feed.innerHTML,/data-assistant-confirm=/);
});

function paymentDialog(){
  const button={disabled:false},error={textContent:''},form={values:{paymentPin:'134679'},querySelectorAll:()=>[]};
  const el={open:false,querySelectorAll:()=>[],showModal(){this.open=true;},close(){this.open=false;},querySelector:selector=>selector==='form'?form:selector==='.form-error'?error:button};
  nodes['#dialog']=el;return {el,form,error};
}
test('financial action needs a separate PIN confirmation and sends PIN only in a header',async()=>{
  const feed=await setup([{intentId:'transfer',action:'INTERNAL_TRANSFER',summary:'Transfer INR 2'}]);
  const {el,form}=paymentDialog();const button={dataset:{assistantConfirm:'transfer'},disabled:false};
  let requests=[];globalThis.fetch=async(url,options)=>{requests.push({url,options});return response({action:'INTERNAL_TRANSFER',resultRef:'posted'});};
  await feed.onclick({target:{closest:selector=>selector==='[data-assistant-confirm]'?button:null}});
  assert.equal(requests.length,0);assert.match(el.innerHTML,/type="password"/);assert.equal(el.open,true);
  await withFormData(()=>form.onsubmit({target:form,preventDefault(){}}));
  assert.equal(requests.length,1);assert.equal(requests[0].options.headers['X-Payment-Pin'],'134679');assert.equal(requests[0].options.body,undefined);
  assert.match(feed.innerHTML,/recipient account has been credited/);assert.equal(el.open,false);
});
test('an old financial confirmation dialog cannot submit under another sign-in',async()=>{
  const feed=await setup([{intentId:'old',action:'PAYMENT_INITIATE',summary:'Old payment'}]);const {form,error}=paymentDialog();
  const button={dataset:{assistantConfirm:'old'},disabled:false};await feed.onclick({target:{closest:selector=>selector==='[data-assistant-confirm]'?button:null}});
  saveSession({accessToken:'other',user:{userId:'other',sessionId:'other-session',userType:'CUSTOMER'}});
  let sent=false;globalThis.fetch=async()=>{sent=true;throw new Error('Unexpected request');};
  await withFormData(()=>form.onsubmit({target:form,preventDefault(){}}));
  assert.equal(sent,false);assert.match(error.textContent,/sign-in changed/);
});

test('old beneficiary forms cannot submit under another sign-in',async()=>{
  const feed=await setup([{uiAction:'ADD_BENEFICIARY',formId:'old-form',displayName:'Old recipient'}]);
  const {form}=beneficiaryForm('old-form',{...values});
  saveSession({accessToken:'other',user:{userId:'other',sessionId:'other-session',userType:'CUSTOMER'}});
  let sent=false;globalThis.fetch=async()=>{sent=true;throw new Error('Unexpected request');};
  await withFormData(()=>feed.onsubmit(submit(form)));assert.equal(sent,false);
});

test('a product card opens a secure form and completes only after a separate review',async()=>{
  const feed=await setup([{uiAction:'BANKING_WORKFLOW',workflow:'CARD_CONTROL',formId:'workflow',summary:'Review card controls'}]);
  assert.match(feed.innerHTML,/data-assistant-workflow="workflow"/);assert.doesNotMatch(feed.innerHTML,/data-assistant-confirm=/);
  const {form,el}=paymentDialog();form.values={cardId:'card',action:'FREEZE',reason:'Customer choice'};
  const card={CARD_ID:'card',STATUS:'ACTIVE',ROW_VERSION:1,DISPLAY_ENDING:'1234',PRODUCT_NAME:'Classic'};let writes=0;
  globalThis.fetch=async(url,options)=>{if(options.method==='POST'){writes++;assert.equal(url,'/api/v1/credit-cards/card/controls');return response({...card,STATUS:'FROZEN'});}return response(url.endsWith('/card')?card:[card]);};
  const button={dataset:{assistantWorkflow:'workflow'},disabled:false};
  await feed.onclick({target:{closest:selector=>selector==='[data-assistant-workflow]'?button:null}});
  assert.equal(writes,0);assert.equal(el.open,true);
  await withFormData(async()=>{
    await form.onsubmit({target:form,preventDefault(){}});assert.equal(writes,0);assert.match(el.innerHTML,/Review and confirm/);
    form.values={};await form.onsubmit({target:form,preventDefault(){}});
  });
  assert.equal(writes,1);assert.match(feed.innerHTML,/Status: FROZEN/);assert.doesNotMatch(feed.innerHTML,/data-assistant-workflow=/);
});
