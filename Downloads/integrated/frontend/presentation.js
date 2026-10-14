// Display-only formatting. Financial values are kept as decimal strings to avoid
// changing the precision returned by the banking services.
import {locale,t} from './i18n.js';
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
  if(/(?:amount|balance|posted|spendable|principal|interestdue|feeamount|limit|availablefunds|debit|credit|total|receivable|minimumdue|unbilledinterest|payoff|refund|repayment)$/.test(normalized)&&!/(?:id|code|currency)$/.test(normalized))return 'amount';
  if(/(?:rate|ratepct|percentage|percent|ratio)$/.test(normalized))return 'rate';
  if(/(?:status|state|result)$/.test(normalized))return 'status';
  return 'text';
}
export function rowCurrency(row,field=''){
  const prefix=String(field).replace(/(?:Amount|Balance|Posted|Spendable|Principal|Limit)$/i,'');
  return row?.[prefix+'Currency']||row?.[prefix+'CurrencyCode']||row?.currency||row?.currencyCode||row?.CURRENCY||row?.CURRENCY_CODE||row?.CCY||'';
}

/** Names only apply to transfers. Other transaction kinds get a plain description. */
export function transactionOtherParty(row){
 if(row.otherPartyName)return row.otherPartyName;
 const type=row.type??row.TXN_TYPE??'';
 if(['INTERNAL_TRANSFER','REVERSAL'].includes(type))return t('Name unavailable');
 const descriptions={OPENING_FUNDING:'Opening funding',DEPOSIT:'Cash deposit',WITHDRAWAL:'Cash withdrawal',FEE:'Bank fee',INTEREST:'Interest',ADJUSTMENT:'Bank adjustment',PAYMENT_DEBIT:'Payment',PAYMENT_REFUND:'Payment refund',EXTERNAL_PAYMENT_SENT:'External payment sent',EXTERNAL_PAYMENT_RECEIVED:'External payment received',SETTLEMENT:'Settlement',RETURN:'Payment return'};
 if(type.startsWith('LOAN_'))return t('Loan activity');
 if(type.startsWith('CARD_'))return t('Credit card activity');
 return t(descriptions[type]||type.replaceAll('_',' ')||'—');
}

/** Only committed account postings receive a sign; a pending or rejected request does not. */
export function transactionAccountImpact(row){
 const amount=row.amount??row.AMOUNT,currency=row.currency??row.CURRENCY_CODE;
 const delta=row.postedDelta;
 if(delta!==null&&delta!==undefined){
  const value=String(delta).trim();
  if(!/^-?\d+(?:\.\d+)?$/.test(value))return {text:formatAmount(amount,currency),note:t('Account change unavailable')};
  const unsigned=value.replace(/^-/,''),nonzero=/[1-9]/.test(unsigned);
  if(!nonzero)return {text:formatAmount(0,currency),note:t('No net change')};
  const outgoing=value.startsWith('-');
  return {text:(outgoing?'− ':'+ ')+formatAmount(unsigned,currency),note:t(outgoing?'Money out':'Money in')};
 }
 const status=row.status??row.STATUS;
 const note=['REJECTED','FAILED','CANCELLED'].includes(status)?'No balance change'
  :['POSTED','REVERSED'].includes(status)?'Account change unavailable':'Not posted';
 return {text:formatAmount(amount,currency),note:t(note)};
}
