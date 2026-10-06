import test from 'node:test';
import assert from 'node:assert/strict';

test('English, Hindi and Kannada keep API values while translating visible labels', async () => {
  let selected='en-IN';
  globalThis.localStorage={getItem:()=>selected,setItem:(_,value)=>{selected=value;}};

  const english=await import('./i18n.js?test-en');
  assert.equal(english.t('Accounts'),'Accounts');

  selected='hi-IN';
  const hindi=await import('./i18n.js?test-hi');
  assert.equal(hindi.t('Accounts'),'खाते');
  assert.equal(hindi.tField('Account Number'),'खाता संख्या');
  assert.equal(hindi.t('MB000000001'),'MB000000001');

  selected='kn-IN';
  const kannada=await import('./i18n.js?test-kn');
  assert.equal(kannada.t('Accounts'),'ಖಾತೆಗಳು');
  assert.equal(kannada.tField('Account Number'),'ಖಾತೆ ಸಂಖ್ಯೆ');
  assert.equal(kannada.t('unknown backend phrase'),'unknown backend phrase');
});
