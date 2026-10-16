import {api,getSession} from './api.js';
import {esc,select,field,table,badge,errorText,toast} from './ui.js';
import {formatDate} from './presentation.js';
import {validateJet} from './jet.js';

let actorId='',accountId='',requests=[],periodStart='',periodEnd='';
const today=()=>new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Kolkata',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date());
const reset=()=>{
 const id=getSession()?.user?.userId;
 if(id===actorId)return;
 actorId=id;accountId='';requests=[];periodEnd=today();periodStart=periodEnd.slice(0,8)+'01';
};
export async function customerStatementsScreen(){
 reset();
 const accounts=await api('/banking/accounts');
 if(!accounts.some(a=>String(a.ACCOUNT_ID)===accountId))accountId=String(accounts[0]?.ACCOUNT_ID||'');
 requests=accountId?await api('/reporting/statement-requests?'+new URLSearchParams({accountId,limit:'100'})):[];
 const options=accounts.map(a=>({value:String(a.ACCOUNT_ID),label:[a.ACCOUNT_NUMBER,a.PRODUCT_NAME,a.ACCOUNT_STATUS].filter(Boolean).join(' · ')}));
 const records=table(['Period','Format','Status','Action'],requests.map(r=>[
  esc(formatDate(r.periodStart))+' – '+esc(formatDate(r.periodEnd)),esc(r.format),badge(r.status),
  r.status==='COMPLETED'&&r.statementId?['PDF','CSV','HTML'].map(format=>'<button type="button" class="btn small" data-statement-download="'+esc(r.statementId)+'" data-format="'+format+'">'+format+'</button>').join(' '):
  r.status==='AUTHORIZED'?'<button type="button" class="btn small" data-statement-process="'+esc(r.requestId)+'">Generate statement</button>':esc(r.failureCode||'Not available')
 ]),'No statement requests for this account yet.',{mobileCards:true});
 return '<section class="panel"><div class="panel-heading"><h3>Generate an account statement</h3></div><div class="panel-body"><p>Choose one of your accounts and a date range, then generate and download your statement.</p><form id="customer-statement-form"><div class="grid2">'+
  select('Your account','accountId',[{value:'',label:accounts.length?'Choose an account':'No linked accounts available'},...options],accountId)+
  field('From','periodStart','date',{value:periodStart})+field('Through','periodEnd','date',{value:periodEnd})+select('Download format','format',['PDF','CSV','HTML'],'PDF')+
  '</div><p class="form-error" role="alert"></p><button class="btn primary" type="submit" '+(accounts.length?'':'disabled')+'>Generate and download</button></form></div></section>'+ 
  '<section class="panel"><div class="panel-heading"><h3>Your statement requests</h3><button type="button" class="btn small" data-statements-refresh>Refresh</button></div><div class="panel-body">'+records+'</div></section>';
}

/** Reuse an existing immutable statement or retry an authorized request after a network failure. */
export async function generateCustomerStatement({accountId,periodStart,periodEnd,format},key=crypto.randomUUID()){
 const from=Date.parse(periodStart),through=Date.parse(periodEnd);
 if(!Number.isFinite(from)||!Number.isFinite(through)||through<from)throw new Error('Choose a valid date range.');
 if(periodEnd>today())throw new Error('The statement cannot end in the future.');
 if(through-from>366*86400000)throw new Error('Choose a date range of up to one year.');
 const existing=await api('/reporting/statement-requests?'+new URLSearchParams({accountId,limit:'100'}));
 let request=existing.find(r=>r.periodStart===periodStart&&r.periodEnd===periodEnd&&r.statementType==='AD_HOC'&&['COMPLETED','AUTHORIZED','PROCESSING'].includes(r.status));
 if(!request)request=await api('/reporting/statement-requests',{method:'POST',headers:{'Idempotency-Key':key},body:{accountId,statementType:'AD_HOC',periodStart,periodEnd,locale:'en-IN',format,channel:'WEB',correlationId:key}});
 if(request.status==='AUTHORIZED')request=await api('/reporting/statement-requests/'+encodeURIComponent(request.requestId)+'/process',{method:'POST'});
 if(request.status==='QUARANTINED')throw new Error('The bank must reconcile this account before a statement can be issued. Quote request '+request.requestId+' when reporting this issue.');
 if(request.status!=='COMPLETED'||!request.statementId)throw new Error('Your request is saved. Refresh the requests below to check its status or retry generation.');
 await api('/reporting/statements/'+encodeURIComponent(request.statementId)+'/download?'+new URLSearchParams({format}));
 return request;
}

export function bindCustomerStatements(render){
 const form=document.querySelector('#customer-statement-form');
 if(!form)return;
 let requestKey=crypto.randomUUID(),lastPayload='';
 form.querySelector('[name="accountId"]').addEventListener('change',async event=>{
  const data=new FormData(form);periodStart=data.get('periodStart');periodEnd=data.get('periodEnd');accountId=event.target.value;
  try{await render();}catch(error){toast(errorText(error),true);}
 });
 form.onsubmit=async event=>{
  event.preventDefault();if(!await validateJet(form))return;
  const button=form.querySelector('[type="submit"]'),error=form.querySelector('.form-error');button.disabled=true;error.textContent='';
  try{
   const data=new FormData(form);accountId=data.get('accountId');periodStart=data.get('periodStart');periodEnd=data.get('periodEnd');
   const body={accountId:Number(accountId),periodStart,periodEnd,format:data.get('format')};
   const payload=JSON.stringify(body);if(lastPayload&&payload!==lastPayload)requestKey=crypto.randomUUID();lastPayload=payload;
   await generateCustomerStatement(body,requestKey);toast('Statement downloaded.');await render();
  }catch(failure){error.textContent=errorText(failure);}finally{button.disabled=false;}
 };
 document.querySelector('[data-statements-refresh]').onclick=()=>render();
 document.querySelectorAll('[data-statement-download],[data-statement-process]').forEach(button=>button.onclick=async()=>{
  button.disabled=true;
  try{
   if(button.dataset.statementDownload)await api('/reporting/statements/'+encodeURIComponent(button.dataset.statementDownload)+'/download?'+new URLSearchParams({format:button.dataset.format}));
   else{await api('/reporting/statement-requests/'+encodeURIComponent(button.dataset.statementProcess)+'/process',{method:'POST'});await render();}
  }catch(error){toast(errorText(error),true);}finally{button.disabled=false;}
 });
}
