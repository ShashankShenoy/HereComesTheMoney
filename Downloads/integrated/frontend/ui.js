import {enhanceForms,validateJet,disposeJet} from './jet.js';
import {formatDate} from './presentation.js';
export const esc=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
export const date=formatDate;
export const badge=value=>`<span class="badge ${esc(['ACTIVE','APPROVED','SUCCESS'].includes(value)?'green':['SUBMITTED','PENDING_ACTIVATION','PENDING'].includes(value)?'amber':['DENIED','DISABLED','REVOKED','REJECTED','CLOSED','FAILED'].includes(value)?'red':'gray')}">${esc(String(value||'—').replaceAll('_',' '))}</span>`;
export const field=(label,name,type='text',options={})=>`<label class="field">${esc(label)}<input name="${esc(name)}" type="${esc(type)}" ${options.required===false?'':'required'} ${options.value!=null?`value="${esc(options.value)}"`:''} ${options.min!=null?`min="${esc(options.min)}"`:''} ${options.maxlength?`maxlength="${esc(options.maxlength)}"`:''} ${options.minlength?`minlength="${esc(options.minlength)}"`:''} ${options.step?`step="${esc(options.step)}"`:''} ${options.placeholder?`placeholder="${esc(options.placeholder)}"`:''} ${type==='password'?'autocomplete="new-password"':''}></label>`;
export const select=(label,name,items,value='',required=true)=>`<label class="field">${esc(label)}<select name="${esc(name)}" ${required?'required':''}>${items.map(i=>{const v=typeof i==='string'?i:i.value,l=typeof i==='string'?i:i.label;return `<option value="${esc(v)}" ${v===value?'selected':''}>${esc(l)}</option>`;}).join('')}</select></label>`;
export const reason=()=>'<label class="field span2">Reason<textarea name="reason" required maxlength="500" rows="3" placeholder="Why is this change needed?"></textarea></label>';
export function table(headers,rows,empty='No records yet.'){
  if(!rows.length)return `<div class="empty"><h3>${esc(empty)}</h3><p>Records will appear here when available.</p></div>`;
  const numeric=headers.map(h=>/\b(amount|balance|limit|rate|total|count|value)\b/i.test(h));
  return `<div class="table-wrap"><table><thead><tr>${headers.map((h,i)=>`<th${numeric[i]?' class="numeric"':''}>${esc(h)}</th>`).join('')}</tr></thead><tbody>${rows.map(r=>`<tr>${r.map((c,i)=>`<td${numeric[i]?' class="numeric"':''}>${c}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`;
}
export const action=(label,action,id='',cls='btn small')=>`<button class="${esc(cls)}" data-action="${esc(action)}" data-id="${esc(id)}">${esc(label)}</button>`;
export function errorText(error){return [error.message,...Object.entries(error.fieldErrors||{}).map(([key,value])=>`${key}: ${value}`),error.correlationId?`Reference: ${error.correlationId}`:''].filter(Boolean).join('\n');}
export function toast(message,error=false){const el=document.querySelector('#toast');el.textContent=message;el.className=`show ${error?'error':''}`;clearTimeout(toast.timer);toast.timer=setTimeout(()=>el.className='',error?10000:4500);}
export function closeDialog(){const el=document.querySelector('#dialog');el.close();disposeJet(el).catch(()=>{});}
export function dialog(title,content,onSubmit){
  const el=document.querySelector('#dialog');
  disposeJet(el).catch(()=>{});
  el.innerHTML=`<form><div class="dialog-heading"><div><p class="eyebrow">MONEYBAGS</p><h2 id="dialog-title">${esc(title)}</h2></div><button type="button" class="icon-btn" aria-label="Close dialog" data-close>×</button></div><div class="dialog-body">${content}<p class="form-error" role="alert"></p></div>${onSubmit?'<div class="dialog-footer"><button type="button" class="btn" data-close>Cancel</button><button class="btn primary" type="submit">Save changes</button></div>':''}</form>`;
  el.querySelectorAll('[data-close]').forEach(b=>b.onclick=closeDialog);
  el.querySelector('form').onsubmit=async event=>{event.preventDefault();if(!onSubmit||!await validateJet(event.target))return;const btn=el.querySelector('[type=submit]');btn.disabled=true;el.querySelector('.form-error').textContent='';try{if(await onSubmit(new FormData(event.target))!==false)closeDialog();}catch(error){el.querySelector('.form-error').textContent=errorText(error);}finally{btn.disabled=false;}};
  if(!el.open)el.showModal();
  enhanceForms(el).catch(e=>{el.querySelector('.form-error').textContent=errorText(e);});
}
