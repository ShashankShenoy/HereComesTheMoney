import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';
import {formatAmount,formatDate} from './presentation.js';

// Isolate the actual audit functions from app.js's browser/router initialization.
const app=await readFile(new URL('./app.js',import.meta.url),'utf8');
const ui=await readFile(new URL('./ui.js',import.meta.url),'utf8');
const auditFunctions=app.slice(app.indexOf('async function audit(){'),app.indexOf('function sessionTable('));
const helpers=[
 ui.slice(ui.indexOf('export const esc='),ui.indexOf('export const date=')),
 ui.slice(ui.indexOf('export const badge='),ui.indexOf('export const field=')),
 ui.slice(ui.indexOf('export function table('),ui.indexOf('export const action='))
].map(source=>source.replace('export ','')).join('\n');
const load=api=>vm.runInNewContext(helpers+'\n'+auditFunctions+'\n({audit,financialAuditTable})',{
 api,formatAmount,date:formatDate,userName:id=>id,errorText:error=>error.message,
 head:title=>`<h1>${title}</h1>`,panel:(title,body)=>`<section><h2>${title}</h2>${body}</section>`,field:()=>''
});
const event={transactionId:123,type:'INTERNAL_TRANSFER',sourceAccountId:3,sourceAccountEnding:'0001',
 targetAccountId:4,targetAccountEnding:'0002',amount:null,currency:'INR',event:'POSTED',currentStatus:'POSTED',
 actor:'<script>alert(1)</script>',occurredAt:'2026-10-06T12:00:00Z'};

test('financial audit displays source and destination in direction, with masked endings and protected amount',()=>{
 const html=load().financialAuditTable([event]);
 assert.ok(html.includes('From account')&&html.includes('To account'));
 assert.ok(html.indexOf('Account #3')<html.indexOf('Account #4'));
 assert.ok(html.includes('Ending 0001')&&html.includes('Ending 0002')&&html.includes('Protected'));
 assert.ok(html.includes('&lt;script&gt;')&&!html.includes('<script>'));
});

test('financial audit displays reversal references, exact authorized amounts and empty/one-sided states',()=>{
 const render=load().financialAuditTable;
 const html=render([{...event,type:'REVERSAL',sourceAccountId:4,targetAccountId:3,amount:'0.01',originalTransactionId:122}]);
 assert.ok(html.includes('Reverses #122')&&html.includes('INR 0.01'));
 assert.ok(html.indexOf('Account #4')<html.indexOf('Account #3'));
 assert.ok(render([{...event,sourceAccountId:null}]).includes('Cash / external / ledger'));
 assert.ok(render([]).includes('No posted financial transactions yet.'));
});

test('financial audit endpoint failure preserves existing security audit and domain event panels',async()=>{
 const api=async path=>{
  if(path==='/privacy/financial-audit')throw new Error('<unavailable>');
  if(path==='/iam/audit')return [{eventType:'LOGIN',actorUserId:'staff',resourceType:'SESSION',resourceId:'session',result:'SUCCESS',occurredAt:event.occurredAt}];
  if(path==='/iam/outbox')return [{eventType:'EXISTING_DOMAIN_EVENT',aggregateType:'USER',aggregateId:'staff',occurredAt:event.occurredAt}];
  throw new Error('Unexpected endpoint');
 };
 const html=await load(api).audit();
 assert.ok(html.includes('Financial audit is unavailable: &lt;unavailable&gt;'));
 assert.ok(html.includes('LOGIN')&&html.includes('EXISTING_DOMAIN_EVENT'));
 assert.ok(html.includes('Security events')&&html.includes('Stored domain events'));
});

test('audit page loads the financial endpoint and shows its transaction',async()=>{
 const calls=[];
 const html=await load(async path=>{calls.push(path);return path==='/privacy/financial-audit'?[event]:[];}).audit();
 assert.deepEqual(calls,['/iam/audit','/iam/outbox','/privacy/financial-audit']);
 assert.ok(html.includes('financial-audit-search')&&html.includes('#123')&&html.includes('Account #3')&&html.includes('Account #4'));
});
