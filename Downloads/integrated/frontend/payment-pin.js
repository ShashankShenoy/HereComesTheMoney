import {api,getSession,logout} from './api.js';
import {esc,errorText,toast} from './ui.js';
export const pinInput=(label='Payment PIN',name='paymentPin')=>`<label class="field">${esc(label)}<input type="password" name="${esc(name)}" inputmode="numeric" pattern="[0-9]{6}" minlength="6" maxlength="6" required autocomplete="off" placeholder="Six-digit PIN"></label>`;
export const newPinFields=(name='newPin',confirmation='confirmPin')=>pinInput('New payment PIN',name)+pinInput('Confirm payment PIN',confirmation);
export function validateNewPin(data,name='newPin',confirmation='confirmPin'){
 const pin=String(data.get(name)||'');if(!/^[0-9]{6}$/.test(pin))throw new Error('Choose a six-digit payment PIN.');
 if(pin!==data.get(confirmation))throw new Error('Payment PINs do not match.');return pin;
}
export const paymentPinField=()=>getSession()?.user?.userType==='CUSTOMER'?pinInput()+'<p class="hint">Enter your payment PIN to authorize this request. Never share your PIN in chat. Reset it in My security if forgotten.</p>':'';
export async function withPaymentPin(data,send){
 if(getSession()?.user?.userType!=='CUSTOMER')return send({});
 let pin=String(data.get('paymentPin')||'');data.delete('paymentPin');
 try{if(!/^[0-9]{6}$/.test(pin))throw new Error('Enter your six-digit payment PIN.');return await send({'X-Payment-Pin':pin});}
 finally{pin='';const field=document.querySelector('#dialog input[name="paymentPin"]');if(field)field.value='';}
}
export function showPinSetup(root,ready,signedOut){
 root.innerHTML=`<main class="login-layout access-layout"><section class="login-form-side"><div class="login-card"><p class="eyebrow">PAYMENT SECURITY</p><h2>Set up your payment PIN</h2><p>Choose a six-digit PIN for payments and transfers from any of your accounts. This is separate from your login password and authenticator code.</p><form id="payment-pin-setup">${newPinFields()}<p class="hint">Five incorrect payment PIN attempts lock payments for 15 minutes. You can reset your PIN after login using your current password.</p><p class="form-error" role="alert"></p><button type="submit" class="btn primary">Set payment PIN and continue</button></form><button type="button" class="btn" id="pin-signout">Sign out</button></div></section></main>`;
 root.querySelector('#pin-signout').onclick=async()=>{await logout();signedOut();};
 const form=root.querySelector('#payment-pin-setup');form.onsubmit=async event=>{event.preventDefault();const button=form.querySelector('[type=submit]');button.disabled=true;try{
  const data=new FormData(form),pin=validateNewPin(data);await api('/auth/payment-pin/setup',{method:'POST',body:{pin}});form.reset();await ready();
 }catch(error){if(error.code==='PIN_ALREADY_SET'){form.reset();await ready();return;}form.querySelector('.form-error').textContent=errorText(error);}finally{button.disabled=false;}};
}
export async function pinSecurityPanel(){
 if(getSession()?.user?.userType!=='CUSTOMER')return '';
 const status=await api('/auth/payment-pin');
 return `<section class="panel"><div class="panel-heading"><h3>Payment PIN</h3></div><div class="panel-body"><p>Your six-digit payment PIN authorizes internal transfers and external payments. It applies across your customer accounts.</p>${status.locked?'<p class="notice">Payments are temporarily locked after incorrect PIN attempts. Wait 15 minutes or reset your PIN here.</p>':''}<form id="payment-pin-reset"><label class="field">Current login password<input type="password" name="currentPassword" autocomplete="current-password" maxlength="72" required></label>${newPinFields()}<p class="hint">You do not need the old PIN. Reset requires your current login password. Five incorrect password attempts temporarily lock PIN reset.</p><p class="form-error" role="alert"></p><button type="submit" class="btn primary">Reset payment PIN</button></form></div></section>`;
}
export function bindPinSecurity(render){const form=document.querySelector('#payment-pin-reset');if(!form)return;form.onsubmit=async event=>{event.preventDefault();const button=form.querySelector('[type=submit]');button.disabled=true;try{
 const data=new FormData(form),newPin=validateNewPin(data);await api('/auth/payment-pin/reset',{method:'POST',body:{currentPassword:data.get('currentPassword'),newPin}});form.reset();toast('Payment PIN reset. Use the new PIN for your next payment.');await render();
 }catch(error){form.querySelector('.form-error').textContent=errorText(error);form.querySelector('[name="currentPassword"]').value='';}finally{button.disabled=false;}};}
