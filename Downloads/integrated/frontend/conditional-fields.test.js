import test from 'node:test';
import assert from 'node:assert/strict';
const requireJet=(modules,ready)=>ready({}, {whenDocumentReady:()=>Promise.resolve()}, class {}, class {});
requireJet.config=()=>{};
globalThis.window={require:requireJet};
globalThis.document={documentElement:{lang:''},addEventListener:()=>{}};
const {syncConditionalField}=await import('./jet.js');

test('branch scope enables and requires the visible JET field without invalidating its hidden source',()=>{
  const component={disabled:true,required:false};
  const input={disabled:true,required:false,dataset:{jetSource:'true'},closest:()=>({querySelector:()=>component})};
  syncConditionalField(input,{required:true,disabled:false});
  assert.equal(component.disabled,false);
  assert.equal(component.required,true);
  assert.equal(input.disabled,false);
  assert.equal(input.required,false);
});

test('an inapplicable field clears the submitted value and notifies its visible binding',()=>{
  const component={disabled:false,required:true,value:'MUM001'};
  const input={value:'MUM001',disabled:false,required:false,dataset:{jetSource:'true'},closest:()=>({querySelector:()=>component}),dispatchEvent:event=>{assert.equal(event.type,'change');}};
  syncConditionalField(input,{required:false,disabled:true,clear:true});
  assert.equal(input.value,'');assert.equal(component.value,null);
  assert.equal(input.disabled,true);assert.equal(component.disabled,true);assert.equal(component.required,false);
});

test('native conditional fields retain their required constraint before enhancement',()=>{
  const input={disabled:true,required:false,dataset:{},closest:()=>({querySelector:()=>null})};
  syncConditionalField(input,{required:true,disabled:false});
  assert.equal(input.required,true);assert.equal(input.disabled,false);
});
