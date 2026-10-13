import test from 'node:test';
import assert from 'node:assert/strict';
import {requestPath,operationKey,recordId,statusChoices} from './operation-state.js';
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
