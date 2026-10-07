import test from 'node:test';
import assert from 'node:assert/strict';

// Exercise the real public asset allowlist: a missing ES module blanks the whole app.
test('the credit card module and stylesheet are served with the correct content types',async()=>{
  process.env.NODE_ENV='test';
  const {server}=await import('./server.mjs');
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  try{
    const root=`http://127.0.0.1:${server.address().port}`;
    for(const [path,mime]of [['/credit-cards.js','text/javascript'],['/credit-cards.css','text/css']]){
      const response=await fetch(root+path);
      assert.equal(response.status,200,path);
      assert.ok(response.headers.get('content-type').startsWith(mime));
      assert.ok((await response.text()).length>100);
    }
  }finally{await new Promise(resolve=>server.close(resolve));}
});
