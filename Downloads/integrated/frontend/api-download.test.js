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
