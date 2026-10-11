import test from 'node:test';
import assert from 'node:assert/strict';
const requireJet=(modules,ready)=>ready({}, {whenDocumentReady:()=>Promise.resolve()}, class {}, class {});
requireJet.config=()=>{};
globalThis.window={require:requireJet};
globalThis.document={documentElement:{lang:''},addEventListener:()=>{}};
const {entryContext,workspacePath,workspaceRole,accessStory}=await import('./design.js');

test('employee entry does not consume retail product intents',()=>{
  for(const path of ['/employee','/employee/','/employee/login','/employee/login/']){
    assert.deepEqual(entryContext(path,'?product=loans'),{employee:true,compact:true,product:null});
  }
});
test('retail entry only accepts supported product destinations',()=>{
  assert.equal(entryContext('/login','?product=creditCards').product,'creditCards');
  for(const product of ['https://example.com','users','audit','__proto__','constructor'])assert.equal(entryContext('/login','?product='+encodeURIComponent(product)).product,null);
  assert.equal(entryContext('/').compact,false);
});
test('workspace follows the authenticated account and identifies checkers',()=>{
  assert.equal(workspacePath('CUSTOMER'),'/login');
  assert.equal(workspacePath('EMPLOYEE'),'/employee/login');
  assert.equal(workspaceRole({userType:'EMPLOYEE',roles:['BANK_CHECKER']}),'Bank checker');
  assert.equal(workspaceRole({userType:'CUSTOMER',roles:[]}),'Personal banking');
  assert.doesNotMatch(accessStory(true,null),/Create a customer account|href="\/login\?product/);
});
