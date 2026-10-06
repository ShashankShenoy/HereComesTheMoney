import {api,getSession} from './api.js';
import {esc,errorText} from './ui.js';
import {renderAssistantMarkdown} from './assistant-markdown.js';

let identity='';
let conversationId=null;
let messages=[];
let actions=[];
let busy=false;
let open=false;
let configured=null;

function syncIdentity(){
  const user=getSession()?.user;
  const next=user?`${user.userId}:${user.sessionId}`:'';
  if(next!==identity){identity=next;conversationId=null;messages=[];actions=[];busy=false;open=false;configured=null;}
}
export function resetAssistant(){identity='';conversationId=null;messages=[];actions=[];busy=false;open=false;configured=null;}

const welcome=()=>{
  const customer=getSession()?.user?.userType==='CUSTOMER';
  const suggestions=customer?['What is my balance?','Show my recent transactions','List my beneficiaries']
    :['Show accounts in my scope','Find a customer','List pending beneficiaries'];
  return `<div class="assistant-welcome"><span class="assistant-spark" aria-hidden="true">✦</span><h3>Banking assistant</h3><p>Ask about information and operations available to your account and permissions.</p><div class="assistant-suggestions">${suggestions.map(s=>`<button type="button" data-assistant-suggestion="${esc(s)}">${esc(s)}</button>`).join('')}</div></div>`;
};
const bubbles=()=>messages.length?messages.map(m=>`<div class="assistant-message ${m.role==='user'?'mine':'theirs'}"><span>${m.role==='user'?'You':'Moneybags'}</span><div class="assistant-markdown">${m.role==='user'?`<p>${esc(m.text).replace(/\n/g,'<br>')}</p>`:renderAssistantMarkdown(m.text)}</div></div>`).join(''):welcome();
const beneficiaryCard=a=>{
  const values=a.form||{displayName:a.displayName||'',accountToken:'',confirmToken:'',bankCode:''};
  return `<section class="assistant-action"><strong>Add a beneficiary</strong><p>Enter the recipient details here. The beneficiary will need independent bank verification before you can pay them.</p>`+
    `<form class="assistant-beneficiary-form" data-assistant-beneficiary="${esc(a.formId)}">`+
    `<label>Recipient name<input name="displayName" value="${esc(values.displayName)}" maxlength="100" required autocomplete="off"></label>`+
    `<label>Account number or UPI ID<input name="accountToken" value="${esc(values.accountToken)}" minlength="4" maxlength="120" pattern="[A-Za-z0-9._@-]{4,120}" required autocomplete="off"></label>`+
    `<label>Confirm account number or UPI ID<input name="confirmToken" value="${esc(values.confirmToken)}" minlength="4" maxlength="120" pattern="[A-Za-z0-9._@-]{4,120}" required autocomplete="off"></label>`+
    `<label>Bank IFSC<input name="bankCode" value="${esc(values.bankCode)}" maxlength="11" pattern="[A-Za-z]{4}0[A-Za-z0-9]{6}" required autocomplete="off" placeholder="ABCD0123456"></label>`+
    `<p class="form-error" role="alert"></p><div class="assistant-form-actions"><button type="submit" class="btn primary">Register beneficiary</button><button type="button" class="btn" data-assistant-cancel="${esc(a.formId)}">Cancel</button></div>`+
    `</form><small>Account details are sent directly to the banking server, not to the AI model.</small></section>`;
};
const cards=()=>actions.filter(a=>a.status==='PENDING').map(a=>a.uiAction==='ADD_BENEFICIARY'?beneficiaryCard(a):`<section class="assistant-action"><strong>Review before confirming</strong><p>${esc(a.summary)}</p><button type="button" class="btn primary" data-assistant-confirm="${esc(a.intentId)}">${a.action==='INTERNAL_TRANSFER'?'Transfer money':a.action==='PAYMENT_INITIATE'?'Submit pending payment':'Verify beneficiary'}</button><small>This proposal expires after five minutes. Your access is checked again when you confirm.</small></section>`).join('');

export function assistantWidget(){
  syncIdentity();
  const user=getSession()?.user;
  if(!user||!['CUSTOMER','EMPLOYEE'].includes(user.userType))return '';
  const audience=user.userType==='CUSTOMER'?'Customer assistant':'Bank officer assistant';
  return `<div class="assistant-dock"><section id="assistant-popover" class="assistant-popover" role="dialog" aria-label="Ask Moneybags" ${open?'':'hidden'}>`+
    `<header class="assistant-popover-header"><div class="assistant-brand"><span class="assistant-brand-icon" aria-hidden="true">✦</span><div><strong>Ask Moneybags</strong><small>${audience}</small></div></div><button id="assistant-close" type="button" aria-label="Close chat" title="Close chat">×</button></header>`+
    `<div class="assistant-status"><span class="assistant-status-dot" aria-hidden="true"></span><span id="assistant-status-text">Checking availability…</span><span class="assistant-status-scope">${user.userType==='CUSTOMER'?'Your accounts only':'Your officer scope'}</span></div>`+
    `<div id="assistant-feed" class="assistant-feed" role="log" aria-live="polite" aria-relevant="additions">${bubbles()}${cards()}</div>`+
    `<div id="assistant-config" class="assistant-config" hidden></div>`+
    `<form id="assistant-form" class="assistant-compose"><label for="assistant-input" class="sr-only">Your message</label><textarea id="assistant-input" name="message" rows="2" maxlength="2000" placeholder="Enter a banking question…" required></textarea><button id="assistant-send" type="submit" aria-label="Send message" title="Send message" disabled>➤</button></form>`+
    `<p class="assistant-hint">Actions need your separate confirmation. Never share passwords or access keys in chat.</p></section>`+
    `<button id="assistant-toggle" class="assistant-toggle" type="button" aria-controls="assistant-popover" aria-expanded="${open}"><span aria-hidden="true">✦</span> Ask Moneybags</button></div>`;
}

function paint(){
  const feed=document.querySelector('#assistant-feed');
  if(feed){feed.innerHTML=bubbles()+cards()+(busy?'<div class="assistant-thinking" role="status"><span></span><span></span><span></span><em>Working on that…</em></div>':'');feed.scrollTop=feed.scrollHeight;}
  const send=document.querySelector('#assistant-send');if(send)send.disabled=busy||configured!==true;
  const panel=document.querySelector('#assistant-popover');if(panel)panel.hidden=!open;
  const toggle=document.querySelector('#assistant-toggle');if(toggle)toggle.setAttribute('aria-expanded',String(open));
}
async function loadStatus(){
  const status=document.querySelector('#assistant-status-text');const banner=document.querySelector('#assistant-config');
  if(!status||!banner)return;
  const owner=identity;
  try{const result=await api('/assistant/status');if(!getSession()||identity!==owner)return;configured=!!result.configured;status.textContent=configured?'Ready to help':'Model setup required';banner.hidden=configured;banner.textContent=configured?'':'Chat needs a model provider and API key configured on the backend.';}
  catch(error){if(identity!==owner)return;configured=false;status.textContent='Chat unavailable';banner.hidden=false;banner.textContent=errorText(error);}
  paint();
}
export function openAssistant(){syncIdentity();open=true;paint();}

async function sendMessage(form){
  if(busy||configured!==true)return;
  const input=form.querySelector('textarea');const message=input.value.trim();if(!message)return;
  const owner=identity;
  input.value='';busy=true;messages.push({role:'user',text:message});paint();
  try{
    const response=await api('/assistant/chat',{method:'POST',body:{message,conversationId}});
    if(identity!==owner)return;
    conversationId=response.conversationId;
    messages.push({role:'assistant',text:response.answer||'I could not produce a response. Please try again.'});
    for(const a of response.pendingActions||[]){
      if(a.uiAction==='ADD_BENEFICIARY' && actions.some(openForm=>openForm.status==='PENDING' && openForm.uiAction==='ADD_BENEFICIARY' && openForm.displayName?.toLocaleLowerCase()===a.displayName?.toLocaleLowerCase()))continue;
      actions.push({...a,status:'PENDING',form:a.uiAction==='ADD_BENEFICIARY'?{displayName:a.displayName||'',accountToken:'',confirmToken:'',bankCode:''}:undefined});
    }
  }catch(error){if(identity===owner)messages.push({role:'assistant',text:errorText(error)});}
  finally{if(identity===owner){busy=false;paint();if(input.isConnected)input.focus();}}
}

export function bindAssistant(){
  const form=document.querySelector('#assistant-form');if(!form)return;
  paint();loadStatus();
  document.querySelector('#assistant-toggle').onclick=()=>{open=!open;paint();if(open){loadStatus();document.querySelector('#assistant-input')?.focus();}};
  document.querySelector('#assistant-close').onclick=()=>{open=false;paint();document.querySelector('#assistant-toggle')?.focus();};
  form.onsubmit=event=>{event.preventDefault();sendMessage(form);};
  form.querySelector('textarea').onkeydown=event=>{if(event.key==='Enter'&&!event.shiftKey){event.preventDefault();form.requestSubmit();}if(event.key==='Escape'){open=false;paint();document.querySelector('#assistant-toggle')?.focus();}};
  const feed=document.querySelector('#assistant-feed');
  feed.oninput=event=>{
    const form=event.target.closest('[data-assistant-beneficiary]');
    const action=form&&actions.find(a=>a.formId===form.dataset.assistantBeneficiary);
    if(action?.form && Object.hasOwn(action.form,event.target.name))action.form[event.target.name]=event.target.value;
  };
  feed.onsubmit=async event=>{
    const form=event.target.closest('[data-assistant-beneficiary]');if(!form)return;
    event.preventDefault();
    const action=actions.find(a=>a.formId===form.dataset.assistantBeneficiary && a.status==='PENDING');if(!action)return;
    const values=new FormData(form),name=String(values.get('displayName')||'').trim(),token=String(values.get('accountToken')||'').trim();
    const confirmToken=String(values.get('confirmToken')||'').trim(),bankCode=String(values.get('bankCode')||'').trim().toUpperCase();
    const error=form.querySelector('.form-error');
    if(token!==confirmToken){error.textContent='The account number or UPI ID entries do not match.';return;}
    const owner=identity,button=form.querySelector('[type="submit"]');button.disabled=true;error.textContent='';
    try{
      const result=await api('/beneficiaries',{method:'POST',body:{displayName:name,accountToken:token,bankCode}});
      if(identity!==owner)return;
      action.status='COMPLETED';action.form=null;
      messages.push({role:'assistant',text:`${name} was registered as beneficiary ${result.beneficiaryId}. Status: ${result.status}. An independent bank officer must verify the beneficiary before you can make a payment.`});
      paint();
    }catch(failure){if(identity===owner){error.textContent=errorText(failure);button.disabled=false;}}
  };
  feed.onclick=async event=>{
    const suggestion=event.target.closest('[data-assistant-suggestion]');
    if(suggestion){const input=form.querySelector('textarea');input.value=suggestion.dataset.assistantSuggestion;input.focus();return;}
    const cancel=event.target.closest('[data-assistant-cancel]');
    if(cancel){const action=actions.find(a=>a.formId===cancel.dataset.assistantCancel);if(action){action.status='CANCELLED';action.form=null;paint();}return;}
    const button=event.target.closest('[data-assistant-confirm]');if(!button||button.disabled)return;
    const action=actions.find(a=>a.intentId===button.dataset.assistantConfirm);if(!action)return;
    const owner=identity;
    button.disabled=true;
    try{
      const result=await api(`/assistant/intents/${encodeURIComponent(action.intentId)}/confirm`,{method:'POST'});
      if(identity!==owner)return;
      action.status='COMPLETED';
      messages.push({role:'assistant',text:result.action==='INTERNAL_TRANSFER'?`Internal transfer ${result.resultRef} was posted. The recipient account has been credited.`:result.action==='PAYMENT_INITIATE'?`Payment request ${result.resultRef} was submitted and is awaiting bank processing.`:`Beneficiary ${result.resultRef} was verified.`});
    }catch(error){if(identity===owner)messages.push({role:'assistant',text:errorText(error)});}
    finally{if(identity===owner)paint();}
  };
}
