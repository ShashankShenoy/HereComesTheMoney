import test from 'node:test';
import assert from 'node:assert/strict';
import {configureStaffStatements} from './configure-staff-statements.mjs';
const maker={user:{userId:'maker',roles:['BANK_ADMIN']}},checker={user:{userId:'checker',roles:['BANK_CHECKER']}};
test('staff configuration creates six independently approved records and safely reuses them',async()=>{
 const purposes=[],masks=[],narrations=[],writes=[];let count=0;
 async function call(path,actor,method='GET',body,headers={}){
  if(method==='GET')return path.startsWith('/privacy')?{items:purposes}:path.includes('masking')?masks:narrations;
  writes.push({path,actor:actor.user.userId,body,headers});
  if(path==='/privacy/purposes'){const p={...body,id:'purpose-'+(++count),versionNo:1,status:'DRAFT'};purposes.push(p);return p;}
  if(path.endsWith('/approval')){assert.equal(actor.user.userId,'checker');assert.equal(headers['If-Match'],'1');purposes[0].status='ACTIVE';return purposes[0];}
  if(path==='/catalog/masking-profiles'){const p={...body,id:'mask-'+(++count),CHANNEL_CODE:body.channel,AUDIENCE_CODE:body.audience,PROFILE_CODE:body.code,VERSION_NO:body.version,STATUS:'DRAFT'};masks.push(p);return p;}
  if(path==='/catalog/narrations'){const p={...body,id:'narration-'+(++count),NARRATION_CODE:body.code,LOCALE_CODE:body.locale,AUDIENCE_CODE:body.audience,VERSION_NO:body.version,EFFECTIVE_FROM:body.effectiveFrom,APPROVAL_STATUS:'DRAFT'};narrations.push(p);return p;}
  assert.equal(actor.user.userId,'checker');const id=path.split('/').at(-2);const m=masks.find(p=>p.id===id);if(m)m.STATUS='APPROVED';else narrations.find(p=>p.id===id).APPROVAL_STATUS='APPROVED';
 }
 const first=await configureStaffStatements(call,maker,checker,new Date('2026-10-15T08:00:00Z'));assert.equal(first.changes.length,6);assert.equal(writes.length,12);assert.ok(writes.every(w=>!w.path.includes('/statement-requests')&&!w.path.includes('/assistant')));
 const second=await configureStaffStatements(call,maker,checker,new Date('2026-10-15T08:00:00Z'));assert.equal(second.changes.length,0);assert.equal(writes.length,12);
});
test('configuration rejects the same person acting as maker and checker before any calls',async()=>{
 await assert.rejects(()=>configureStaffStatements(()=>{throw new Error('No API call expected');},maker,{user:{userId:'maker',roles:['BANK_CHECKER']}}),/Independent maker/);
});
