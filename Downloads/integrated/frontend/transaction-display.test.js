import test from 'node:test';
import assert from 'node:assert/strict';
import {transactionOtherParty,transactionAccountImpact} from './presentation.js';

test('account amount direction follows posted account impact',()=>{
 assert.deepEqual(transactionAccountImpact({amount:'50.00',currency:'INR',status:'POSTED',postedDelta:'-50.00'}),
  {text:'− INR 50.00',note:'Money out'});
 assert.deepEqual(transactionAccountImpact({amount:'50.00',currency:'INR',status:'POSTED',postedDelta:'50.00'}),
  {text:'+ INR 50.00',note:'Money in'});
 assert.deepEqual(transactionAccountImpact({amount:'50.00',currency:'INR',status:'REJECTED',postedDelta:null}),
  {text:'INR 50.00',note:'No balance change'});
 assert.deepEqual(transactionAccountImpact({amount:'50.00',currency:'INR',status:'APPROVAL_PENDING',postedDelta:null}),
  {text:'INR 50.00',note:'Not posted'});
 assert.deepEqual(transactionAccountImpact({amount:'50.00',currency:'INR',status:'POSTED',postedDelta:null}),
  {text:'INR 50.00',note:'Account change unavailable'});
});

test('the other-party label is a name only when a transfer has one',()=>{
 assert.equal(transactionOtherParty({type:'INTERNAL_TRANSFER',otherPartyName:'Mira Shah'}),'Mira Shah');
 assert.equal(transactionOtherParty({type:'REVERSAL',otherPartyName:null}),'Name unavailable');
 assert.equal(transactionOtherParty({type:'FEE',otherPartyName:null}),'Bank fee');
 assert.equal(transactionOtherParty({type:'WITHDRAWAL',otherPartyName:null}),'Cash withdrawal');
});
