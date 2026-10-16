/** Operation results and filters belong to the selected operation, not its page. */
export const operationKey=op=>op.method+' '+op.path;
/** Version-only commands still need a catalogue when their contract has no product selector. */
export async function loadProductVersions(request,productId,loanOnly=false){
 const products=productId?[{PRODUCT_ID:productId}]:await request('/products');
 const eligible=products.filter(product=>!loanOnly||productId||['LOAN'].includes(product.PRODUCT_TYPE||product.productType));
 const details=await Promise.all(eligible.map(product=>request('/products/'+encodeURIComponent(product.PRODUCT_ID||product.productId))));
 return details.flatMap((detail,index)=>(detail.versions||[]).filter(version=>!loanOnly||(version.VERSION_STATE||version.versionState)==='ACTIVE').map(version=>({...version,
  displayName:[detail.product?.PRODUCT_NAME||detail.product?.productName||eligible[index].PRODUCT_NAME||eligible[index].productName||eligible[index].PRODUCT_CODE||'Product '+(eligible[index].PRODUCT_ID||eligible[index].productId),'Version '+(version.VERSION_NO||version.versionNo||version.PRODUCT_VERSION_ID||version.productVersionId),version.VERSION_STATE||version.versionState].filter(Boolean).join(' · ')})));
}
export function requestPath(op,values={}) {
 let path=op.path;const query=new URLSearchParams();
 for(const p of op.parameters||[]) {
  const value=values[p.name]??p.schema?.default;
  const missing=value===undefined||value===null||String(value).trim()==='';
  if((p.in==='path'||p.in==='query'&&p.required)&&missing)return null;
  if(p.in==='path')path=path.replace('{'+p.name+'}',encodeURIComponent(value));
  if(p.in==='query'&&value!==undefined&&value!==null&&value!=='')query.set(p.name,value);
 }
 return path+(query.size?'?'+query:'');
}
export function recordId(row,path,parameter='id') {
 const entity=path.split('/').filter(Boolean).filter(p=>!p.startsWith('{')).at(-1);
 const hints={payments:'paymentId',beneficiaries:'beneficiaryId','clearing-batches':'clearingBatchId',dispatches:'dispatchId',approvals:'approvalId',applications:'applicationId',facilities:'facilityId',tills:'tillId',cycles:'settlementCycleId','settlement-cycles':'settlementCycleId','work-items':'workItemId','reserve-accounts':'reserveAccountId','reserve-openings':'openingId',purposes:'purposeId',holds:'holdId',rates:'rateId',cases:'caseId',documents:'documentId','access-requests':'requestId',products:'productId','cash-deliveries':'deliveryId'};
 hints.customers=path.startsWith('/cif')?'cifId':'customerId';hints.accounts='accountId';hints['period-closes']='closeId';hints.disbursements='disbursementId';hints['evidence-exports']='exportId';hints['masking-profiles']='maskingProfileId';hints.narrations='narrationId';
 hints.versions='productVersionId';hints['gl-accounts']='glAccountId';hints['statement-requests']='requestId';hints.statements='statementId';hints['term-deposits']='depositId';hints.journals='journalId';hints['settlement-evidence']='evidenceId';
 hints.parties='accountPartyId';hints.restrictions='restrictionId';hints.closures='closureRequestId';hints['majority-reviews']='reviewId';hints['override-approvals']='approvalId';hints.deliveries='deliveryId';hints.offers='offerId';hints.assessments='assessmentId';hints.users='userId';
 const wanted=hints[entity];const normalized=s=>s.replaceAll('_','').toLowerCase();
 const key=(parameter!=='id'&&Object.keys(row).find(k=>normalized(k)===normalized(parameter)))||Object.keys(row).find(k=>wanted&&normalized(k)===normalized(wanted))||Object.keys(row).find(k=>k==='id')||Object.keys(row).find(k=>/Id$|_ID$/.test(k));
 return key?row[key]:undefined;
}
export function recordLabel(row,id) {
 if(row.JOURNAL_TYPE||row.journalType)return ['Journal '+id,row.JOURNAL_TYPE||row.journalType,row.VALUE_DATE||row.valueDate,row.DESCRIPTION||row.description].filter(Boolean).join(' · ');
 const label=row.displayName||row.DISPLAY_NAME||row.accountNumber||row.ACCOUNT_NUMBER||row.number||row.FACILITY_NUMBER||row.PRODUCT_NAME||row.PRODUCT_CODE||row.GL_NAME||row.BRANCH_CODE||row.name||row.legalName||row.LEGAL_NAME||row.username||row.USERNAME||row.BANK_NAME||row.externalSettlementRef||row.railCode||row.RAIL_CODE||'';
 const status=row.status||row.STATUS||row.BATCH_STATUS||row.evidenceStatus||'';
 const amount=row.amount||row.AMOUNT;
 return [label,status,amount!==undefined?'INR '+amount:null,'Reference '+id].filter(Boolean).join(' · ');
}
export const paymentStates=['RECEIVED','AUTHORIZED','HELD','LIQUIDITY_PENDING','DEBIT_POSTED','DISPATCHED','ACCEPTED','UNKNOWN','SETTLEMENT_PENDING','CREDIT_POSTED','SETTLED','REJECTED','REFUNDED','CANCELLED'];
export function statusChoices(path) {
 if(path.startsWith('/privacy/purposes'))return ['DRAFT','PENDING_APPROVAL','ACTIVE','SUSPENDED','RETIRED'];
 if(path.startsWith('/privacy/holds'))return ['PENDING','ACTIVE','REJECTED','RELEASED'];
 if(path.startsWith('/privacy/cases'))return ['OPEN','IDENTITY_PENDING','IN_REVIEW','WAITING_ON_SERVICE','RESPONSE_READY','CLOSED','REJECTED'];
 if(path.startsWith('/treasury/reserve-openings'))return ['PENDING','CONFIRMED','REJECTED'];
 if(path.startsWith('/treasury/work-items'))return ['OPEN','ASSIGNED','PENDING_APPROVAL','RESOLVED','WAIVED','REJECTED'];
 if(path.startsWith('/payments'))return paymentStates;
 if(path.startsWith('/dispatches'))return ['READY','SENDING','SENT','ACKNOWLEDGED','INQUIRY_REQUIRED','FAILED'];
 if(/beneficiaries/.test(path))return ['PENDING','ACTIVE','DISABLED'];
 if(/settlement-cycles|clearing-batches/.test(path))return ['OPEN','CLOSED','SUBMITTED','SETTLEMENT_PENDING','SETTLED','EXCEPTION'];
 if(/liquidity-holds/.test(path))return ['RESERVED','COMMITTED','RELEASED','EXPIRED','CONSUMED'];
 if(/approvals|access-requests|reserve-openings/.test(path))return ['PENDING','APPROVED','REJECTED'];
 if(/exceptions/.test(path))return ['OPEN','ASSIGNED','RESOLVED','WAIVED'];
 if(/applications/.test(path))return ['DRAFT','SUBMITTED','UNDER_REVIEW','REFERRED','APPROVED','REJECTED','WITHDRAWN','EXPIRED','OFFERED','ACCEPTED','CONVERTED','CANCELLED'];
 if(/tills/.test(path))return ['OPEN','CLOSED'];
 return [];
}
