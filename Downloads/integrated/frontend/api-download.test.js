import test from 'node:test';
import assert from 'node:assert/strict';

test('KYC evidence download keeps its server filename and authenticated session', async () => {
  const saved = new Map();
  globalThis.sessionStorage = {
    getItem: key => saved.get(key) ?? null,
    setItem: (key, value) => saved.set(key, value),
    removeItem: key => saved.delete(key),
  };
  let clicked;
  globalThis.document = {
    createElement: () => ({ click() { clicked = { href: this.href, filename: this.download }; } }),
  };
  URL.createObjectURL = () => 'blob:kyc-evidence';
  URL.revokeObjectURL = () => {};
  globalThis.fetch = async (path, options) => {
    assert.equal(path, '/api/v1/cif/documents/doc-1/content');
    assert.equal(options.headers.Authorization, 'Bearer example-token');
    assert.ok(options.headers['X-Correlation-ID']);
    return {
      ok: true,
      status: 200,
      headers: new Headers({ 'Content-Disposition': 'attachment; filename="doc-1.pdf"' }),
      blob: async () => new Blob(['%PDF-1.4']),
    };
  };
  const { saveSession, download } = await import('./api.js');
  saveSession({ accessToken: 'example-token' });
  const result = await download('/cif/documents/doc-1/content');
  assert.deepEqual(result, { downloaded: 'doc-1.pdf' });
  assert.deepEqual(clicked, { href: 'blob:kyc-evidence', filename: 'doc-1.pdf' });
});
test('successful null responses and structured errors retain their HTTP status',async()=>{
 const {api}=await import('./api.js');
 globalThis.fetch=async()=>({ok:true,status:200,headers:new Headers(),json:async()=>null});
 assert.equal(await api('/nullable-result'),null);
 globalThis.fetch=async()=>({ok:false,status:503,headers:new Headers(),json:async()=>({code:'DATABASE_UNAVAILABLE',message:'Database unavailable'})});
 await assert.rejects(()=>api('/unavailable'),error=>error.status===503&&error.code==='DATABASE_UNAVAILABLE');
});

test('assistant statement CSV download sends authentication and keeps the CSV filename',async()=>{
 const {saveSession,download}=await import('./api.js');
 saveSession({accessToken:'statement-token'});
 const path='/reporting/statements/eeceb935-89cc-4d53-862d-ea70628969b0/download?format=CSV';
 let clicked=false;
 globalThis.document={createElement:()=>({click(){clicked=true;assert.equal(this.download,'statement.csv');}})};
 globalThis.fetch=async(url,options)=>{
  assert.equal(url,'/api/v1'+path);
  assert.equal(options.headers.Authorization,'Bearer statement-token');
  return {ok:true,status:200,headers:new Headers({'Content-Disposition':'attachment; filename=statement.csv'}),blob:async()=>new Blob(['Date,Amount'])};
 };
 assert.deepEqual(await download(path),{downloaded:'statement.csv'});
 assert.equal(clicked,true);
});
