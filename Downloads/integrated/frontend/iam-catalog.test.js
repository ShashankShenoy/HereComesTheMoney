import test from 'node:test';
import assert from 'node:assert/strict';
import {loadUserCatalogue} from './iam-catalog.js';

test('access catalogue includes an identity beyond the first 100 directory rows', async () => {
  const users=Array.from({length:101},(_,i)=>({userId:String(i),username:`qa_user_${i}`}));
  const calls=[];
  const result=await loadUserCatalogue(async path=>{
    calls.push(path);
    const page=Number(new URL(path,'http://localhost').searchParams.get('page'));
    return {items:users.slice(page*100,(page+1)*100),total:101};
  });
  assert.equal(result.length,101);
  assert.equal(result.find(user=>user.userId==='100').username,'qa_user_100');
  assert.deepEqual(calls,['/iam/users?page=0&size=100','/iam/users?page=1&size=100']);
});

test('catalogue deduplicates rows if the directory changes between pages',async()=>{
  let page=0;
  const first=Array.from({length:100},(_,i)=>({userId:String(i)}));
  const result=await loadUserCatalogue(async()=>page++===0?{items:first,total:101}:{items:[first[99]],total:101});
  assert.equal(result.length,100);
});

test('catalogue stops on an empty result and propagates authorization failures',async()=>{
  assert.deepEqual(await loadUserCatalogue(async()=>({items:[],total:0})),[]);
  await assert.rejects(loadUserCatalogue(async()=>{throw new Error('Oracle authorization denied');}),/authorization denied/);
});
