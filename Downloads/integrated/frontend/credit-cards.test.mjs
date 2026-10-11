import test from 'node:test';
import assert from 'node:assert/strict';

// Exercise the real public asset allowlist: a missing ES module blanks the whole app.
test('the credit card module and stylesheet are served with the correct content types',async()=>{
  process.env.NODE_ENV='test';
  const {server}=await import('./server.mjs');
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  try{
    const root=`http://127.0.0.1:${server.address().port}`;
    for(const [path,mime]of [['/credit-cards.js','text/javascript'],['/credit-cards.css','text/css'],['/design.js','text/javascript'],['/oracle-red.css','text/css'],['/customer-payments.js','text/javascript'],['/customer-fx.js','text/javascript']]){
      const response=await fetch(root+path);
      assert.equal(response.status,200,path);
      assert.ok(response.headers.get('content-type').startsWith(mime));
      assert.ok((await response.text()).length>100);
    }
    for(const path of ['/login','/employee/login','/employee','/employee/login/']){
      const response=await fetch(root+path);
      assert.equal(response.status,200,path);
      assert.match(response.headers.get('content-type'),/^text\/html/);
      assert.match(await response.text(),/src="\/app.js"/);
    }
    const font=await fetch(root+'/assets/InterVariable.woff2');
    assert.equal(font.status,200);
    assert.match(font.headers.get('content-type'),/^font\/woff2/);
    assert.deepEqual([...new Uint8Array(await font.arrayBuffer()).slice(0,4)],[119,79,70,50]);
  }finally{await new Promise(resolve=>server.close(resolve));}
});
