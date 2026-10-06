// Display-only formatting. Financial values are kept as decimal strings to avoid
// changing the precision returned by the banking services.
import {locale} from './i18n.js';
const currencyDigits=code=>{
  try{return new Intl.NumberFormat(locale,{style:'currency',currency:code}).resolvedOptions().maximumFractionDigits;}
  catch{return 2;}
};
export function formatDecimal(value,{minimumFractionDigits=0,grouping='indian'}={}){
  if(value===null||value===undefined||value==='')return '—';
  const raw=String(value).trim();
  const match=/^(-?)(\d+)(?:\.(\d+))?$/.exec(raw);
  if(!match)return raw;
  const [,sign,whole,fraction='']=match;
  const grouped=grouping==='international'
    ?whole.replace(/\B(?=(\d{3})+(?!\d))/g,',')
    :whole.length<=3?whole:whole.slice(0,-3).replace(/\B(?=(\d{2})+(?!\d))/g,',')+','+whole.slice(-3);
  const decimals=fraction.padEnd(minimumFractionDigits,'0');
  return sign+grouped+(decimals?'.'+decimals:'');
}
export function formatAmount(value,currency='INR',options={}){
  if(value===null||value===undefined||value==='')return '—';
  const code=String(currency||'').trim().toUpperCase();
  const digits=/^[A-Z]{3}$/.test(code)?currencyDigits(code):0;
  return (code?code+' ':'')+formatDecimal(value,{minimumFractionDigits:digits,grouping:options.grouping});
}
export function formatDate(value){
  if(value===null||value===undefined||value==='')return '—';
  const raw=String(value);
  if(!/^\d{4}-\d{2}-\d{2}(?:[T ]|$)/.test(raw))return raw;
  const dateOnly=/^\d{4}-\d{2}-\d{2}$/.test(raw);
  const parsed=new Date(dateOnly?raw+'T00:00:00':raw);
  if(Number.isNaN(parsed.getTime()))return raw;
  const parts=new Intl.DateTimeFormat(locale,{day:'2-digit',month:'short',year:'numeric',...(dateOnly?{}:{hour:'2-digit',minute:'2-digit',hourCycle:'h23'})}).formatToParts(parsed);
  const get=type=>parts.find(part=>part.type===type)?.value||'';
  return `${get('day')}-${get('month').slice(0,3)}-${get('year')}`+(dateOnly?'':` ${get('hour')}:${get('minute')}`);
}
export function fieldKind(key){
  const normalized=String(key).replace(/[_\s-]/g,'').toLowerCase();
  if(/(?:date|time|at|from|to|until|expires|expiry|maturity)$/.test(normalized)&&!/(?:status|rate|format|data)$/.test(normalized))return 'date';
  if(/(?:amount|balance|posted|spendable|principal|interestdue|feeamount|limit|availablefunds|debit|credit)$/.test(normalized)&&!/(?:id|code|currency)$/.test(normalized))return 'amount';
  if(/(?:rate|ratepct|percentage|percent|ratio)$/.test(normalized))return 'rate';
  if(/(?:status|state|result)$/.test(normalized))return 'status';
  return 'text';
}
export function rowCurrency(row,field=''){
  const prefix=String(field).replace(/(?:Amount|Balance|Posted|Spendable|Principal|Limit)$/i,'');
  return row?.[prefix+'Currency']||row?.[prefix+'CurrencyCode']||row?.currency||row?.currencyCode||row?.CURRENCY||row?.CURRENCY_CODE||row?.CCY||'';
}
