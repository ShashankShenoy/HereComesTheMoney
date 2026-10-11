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
  assert.equal(hindi.t('GL account ledger'),'GL खाता बहीखाता');
  assert.equal(hindi.t('Account transactions'),'खाते के लेनदेन');
  assert.equal(hindi.t('Operations'),'कार्य');
  assert.equal(hindi.t('Running balance'),'चालू शेष राशि');
  assert.equal(hindi.t('View journal'),'जर्नल देखें');
  assert.equal(hindi.t('MB000000001'),'MB000000001');

  selected='kn-IN';
  const kannada=await import('./i18n.js?test-kn');
  assert.equal(kannada.t('Accounts'),'ಖಾತೆಗಳು');
  assert.equal(kannada.tField('Account Number'),'ಖಾತೆ ಸಂಖ್ಯೆ');
  assert.equal(kannada.t('GL account ledger'),'GL ಖಾತೆ ಲೆಡ್ಜರ್');
  assert.equal(kannada.t('Account transactions'),'ಖಾತೆಯ ವಹಿವಾಟುಗಳು');
  assert.equal(kannada.t('Operations'),'ಕಾರ್ಯಾಚರಣೆಗಳು');
  assert.equal(kannada.t('Running balance'),'ಚಾಲ್ತಿಯ ಬಾಕಿ');
  assert.equal(kannada.t('View journal'),'ಜರ್ನಲ್ ನೋಡಿ');
  assert.equal(kannada.t('unknown backend phrase'),'unknown backend phrase');
});

test('new customer navigation, descriptions and welcome templates translate in both languages',async()=>{
 for(const language of ['hi-IN','kn-IN']){
  globalThis.localStorage={getItem:()=>language};const {t}=await import('./i18n.js?new-copy-'+language);
  for(const phrase of ['Home','My accounts','My transactions','Payments','Recipients','My credit cards','My loans','Statements','Exchange rates','Account access key','Explore products','Privacy & consent','Sign-in & security','A good day to move forward.','Send & track money','More ways to bank','See your balances, open an eligible account, or explore fixed deposits.'])assert.notEqual(t(phrase),phrase,language+': '+phrase);
  const greeting=t('Welcome back, {name}. Your accounts, cards, and next steps, together.');assert.ok(greeting.includes('{name}'));assert.ok(!greeting.startsWith('Welcome back'));
  assert.equal(t('MB000000001'),'MB000000001');assert.ok(!t('1–60 months').includes('months'));
 }
});
