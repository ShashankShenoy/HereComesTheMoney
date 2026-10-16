import test from 'node:test';
import assert from 'node:assert/strict';
import {requestPath,operationKey,recordId,statusChoices,loadProductVersions} from './operation-state.js';
test('inquiries retain filters and selected records when switching operations',()=>{
 const payments={method:'GET',path:'/payments',parameters:[{in:'query',name:'status',schema:{}}]};
 const evidence={method:'GET',path:'/payments/{id}/rail-evidence',parameters:[{in:'path',name:'id',schema:{}}]};
 const saved={[operationKey(payments)]:{status:'SETTLEMENT_PENDING'},[operationKey(evidence)]:{id:17}};
 assert.equal(requestPath(payments,saved[operationKey(payments)]),'/payments?status=SETTLEMENT_PENDING');
 assert.equal(requestPath(evidence,saved[operationKey(evidence)]),'/payments/17/rail-evidence');
 assert.equal(requestPath(payments,saved[operationKey(payments)]),'/payments?status=SETTLEMENT_PENDING');
 assert.equal(requestPath(evidence,{}),null);
});
test('queue identifiers select the resource rather than its owner',()=>{
 assert.equal(recordId({OWNER_USER_ID:'owner',BENEFICIARY_ID:'recipient'},'/beneficiaries'),'recipient');
 assert.equal(recordId({ORIGINATOR_ID:'originator',PAYMENT_ID:42},'/payments'),42);
 assert.equal(recordId({id:7,rail:'UPI'},'/treasury/settlement-cycles'),7);
 assert.ok(statusChoices('/payments').includes('SETTLEMENT_PENDING'));
});
test('account inquiries wait for required query selections while preserving optional defaults',()=>{
 const op={path:'/reporting/statement-requests',parameters:[{in:'query',name:'accountId',required:true},{in:'query',name:'limit',schema:{default:25}}]};
 for(const accountId of [undefined,null,'','  '])assert.equal(requestPath(op,{accountId}),null);
 assert.equal(requestPath(op,{accountId:6}),'/reporting/statement-requests?accountId=6&limit=25');
});
test('version-only commands list labelled versions instead of requiring an absent product field',async()=>{
 const request=async path=>path==='/products'?[{PRODUCT_ID:2,PRODUCT_NAME:'Savings'}]:{versions:[{PRODUCT_VERSION_ID:9,VERSION_NO:2,VERSION_STATE:'ACTIVE'}]};
 const rows=await loadProductVersions(request);
 assert.equal(rows[0].PRODUCT_VERSION_ID,9);assert.match(rows[0].displayName,/Savings.*Version 2.*ACTIVE/);
});
