const KEY='moneybags-session';
let session=null;
let customerHash='';
export const getCustomerHash=()=>customerHash;
export const setCustomerHash=value=>{customerHash=String(value||'').trim();};
try{session=JSON.parse(sessionStorage.getItem(KEY)||'null');}catch{sessionStorage.removeItem(KEY);}
export function getSession(){return session;}
export function saveSession(value){session=value;if(!value)customerHash='';if(value)sessionStorage.setItem(KEY,JSON.stringify(value));else sessionStorage.removeItem(KEY);}
export class ApiFailure extends Error{constructor(error){super(error.message||'Request failed');Object.assign(this,error);}}
export async function api(path,{method='GET',body,headers:extraHeaders={}}={}){
  const headers={'Accept':'application/json','X-Correlation-ID':crypto.randomUUID()};
  Object.assign(headers,extraHeaders);if(customerHash)headers['X-Customer-Hash']=customerHash;if(method!=='GET'&&!headers['Idempotency-Key'])headers['Idempotency-Key']=body?.requestKey||body?.requestId||crypto.randomUUID();
  if(session?.accessToken)headers.Authorization=`Bearer ${session.accessToken}`;
  const multipart=body instanceof FormData;if(body!==undefined&&!multipart)headers['Content-Type']='application/json';
  let response;
  try{response=await fetch(`/api/v1${path}`,{method,headers,body:body===undefined?undefined:multipart?body:JSON.stringify(body)});}
  catch{throw new ApiFailure({code:'NETWORK_ERROR',message:'Cannot reach the local server. Check that frontend and backend are running.'});}
  if(response.status===204)return null;
  if(response.ok&&response.headers.get('content-disposition')?.startsWith('attachment')){
   const filename=response.headers.get('content-disposition').split('filename=')[1]?.replaceAll('"','')||'statement';
   const blob=await response.blob(),url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download=filename;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);return {downloaded:filename};
  }
  let value;try{value=await response.json();}catch{throw new ApiFailure({status:response.status,message:response.status===403?`Request rejected (HTTP 403). Check the backend frontend-origin setting for ${window.location.origin}.`:'Server returned an unexpected response.'});}
  if(!response.ok){
    if(response.status===401&&['SESSION_INVALID','LOGIN_REQUIRED','REFRESH_REUSE'].includes(value.code)){saveSession(null);window.dispatchEvent(new Event('session-ended'));}
    throw new ApiFailure(value);
  }
  return Object.hasOwn(value,'data')?value.data:value;
}
export async function login(username,password,otp){const value=await api('/auth/login',{method:'POST',body:{username,password,otp:otp||null,clientId:'MONEYBAGS_WEB',deviceRef:navigator.userAgent.slice(0,160)}});saveSession(value);return value;}
export async function refresh(){if(!session)throw new Error('Sign in first');const value=await api('/auth/refresh',{method:'POST',body:{refreshToken:session.refreshToken}});saveSession(value);return value;}
export async function logout(){try{await api('/auth/logout',{method:'POST'});}finally{saveSession(null);}}
/** Downloads authorized evidence through a bearer header; tokens never appear in URLs. */
export async function download(path){const response=await fetch(`/api/v1${path}`,{headers:{Authorization:`Bearer ${session.accessToken}`,'X-Customer-Hash':customerHash}});if(!response.ok)throw new ApiFailure(await response.json());const blob=await response.blob();const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download='evidence';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
