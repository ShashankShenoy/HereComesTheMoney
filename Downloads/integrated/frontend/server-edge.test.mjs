import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {spawn, spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

const cwd = fileURLToPath(new URL('.', import.meta.url));
async function close(server) { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
async function fixture(t, backendUrl = 'http://127.0.0.1:1') {
  const saved = {NODE_ENV: process.env.NODE_ENV, BACKEND_URL: process.env.BACKEND_URL};
  process.env.NODE_ENV = 'test'; process.env.BACKEND_URL = backendUrl;
  let server;
  try { ({server} = await import(`./server.mjs?edge=${Math.random()}`)); }
  finally { for (const [key, value] of Object.entries(saved)) { if (value === undefined) delete process.env[key]; else process.env[key] = value; } }
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => close(server));
  return `http://127.0.0.1:${server.address().port}`;
}
test('static HEAD and POST enforce read-only assets and security headers', async t => {
  const url = await fixture(t);
  const head = await fetch(url + '/index.html', {method: 'HEAD'});
  assert.equal(head.status, 200); assert.equal(await head.text(), '');
  assert.equal(head.headers.get('x-content-type-options'), 'nosniff');
  assert.equal(head.headers.get('cache-control'), 'no-store');
  assert.match(head.headers.get('content-security-policy'), /frame-ancestors 'none'/);
  assert.equal((await fetch(url + '/index.html', {method: 'POST', body: 'data'})).status, 405);
});
test('private files and encoded vendor traversal are not exposed', async t => {
  const url = await fixture(t);
  for (const path of ['/package.json', '/package-lock.json', '/server.mjs', '/.env', '/node_modules/knockout/package.json', '/vendor/jet/%2e%2e%5c%2e%2e%5cpackage.json', '/vendor/require/%2e%2e%5c%2e%2e%5cserver.mjs', '/vendor/require/%00.js'])
    assert.equal((await fetch(url + path)).status, 404, path);
  assert.equal((await fetch(url + '/vendor/require/%ZZ.js')).status, 400);
});
test('gateway outage yields JSON while static workspace stays usable', async t => {
  const url = await fixture(t);
  const response = await fetch(url + '/api/v1/auth/me'); assert.equal(response.status, 502);
  assert.equal((await response.json()).code, 'BACKEND_OFFLINE');
  assert.equal((await fetch(url + '/login')).status, 200);
});
test('cancelled downloads release the upstream connection promptly', async t => {
  let upstreamClosed;
  const released = new Promise(resolve => { upstreamClosed = resolve; });
  const backend = http.createServer((req, res) => {
    res.writeHead(200, {'Content-Type': 'application/octet-stream'}); res.write('start');
    const timer = setInterval(() => res.write('chunk'), 25);
    res.on('close', () => { clearInterval(timer); upstreamClosed(true); });
  });
  await new Promise(resolve => backend.listen(0, '127.0.0.1', resolve)); t.after(() => close(backend));
  const url = await fixture(t, `http://127.0.0.1:${backend.address().port}`);
  const controller = new AbortController();
  const response = await fetch(url + '/api/v1/download', {signal: controller.signal});
  await response.body.getReader().read(); controller.abort();
  const closed = await Promise.race([released, new Promise(resolve => setTimeout(() => resolve(false), 1000))]);
  assert.equal(closed, true, 'Frontend left the cancelled upstream response open');
});
test('truncated gateway response fails the download without crashing the frontend', async t => {
  const backend = http.createServer((req, res) => { res.writeHead(200, {'Content-Length': '100', Connection: 'close'}); res.end('short'); });
  await new Promise(resolve => backend.listen(0, '127.0.0.1', resolve)); t.after(() => close(backend));
  const child = spawn(process.execPath, ['--input-type=module', '-e', 'const {server}=await import("./server.mjs"); server.listen(0,"127.0.0.1",()=>process.stdout.write(String(server.address().port)+"\\n"));'], {cwd, env: {...process.env, NODE_ENV: 'test', BACKEND_URL: `http://127.0.0.1:${backend.address().port}`}, stdio: ['ignore', 'pipe', 'pipe']});
  t.after(() => child.kill());
  const port = await new Promise((resolve, reject) => { child.stdout.once('data', chunk => resolve(Number(String(chunk).trim()))); child.once('error', reject); });
  const start = performance.now();
  await assert.rejects(async () => { const response = await fetch(`http://127.0.0.1:${port}/api/v1/download`, {signal: AbortSignal.timeout(2000)}); await response.text(); });
  assert.ok(performance.now() - start < 1000, 'Truncated responses must fail promptly');
  assert.equal((await fetch(`http://127.0.0.1:${port}/login`, {signal: AbortSignal.timeout(2000)})).status, 200);
});
test('proxy forwards to IPv6 loopback', async t => {
  const backend = http.createServer((req, res) => res.end('{"ok":true}'));
  try { await new Promise((resolve, reject) => { backend.once('error', reject); backend.listen(0, '::1', resolve); }); }
  catch { t.skip('IPv6 loopback unavailable'); return; }
  t.after(() => close(backend));
  const url = await fixture(t, `http://[::1]:${backend.address().port}`);
  assert.equal((await fetch(url + '/api/v1/auth/me')).status, 200);
});
test('only loopback HTTP origins are accepted as gateway configuration', () => {
  for (const value of ['https://127.0.0.1', 'http://example.com', 'http://user:secret@127.0.0.1', 'http://127.0.0.1/path', 'http://127.0.0.1?q=1', 'http://127.0.0.1/#fragment']) {
    const result = spawnSync(process.execPath, ['--input-type=module', '-e', 'await import("./server.mjs")'], {cwd, env: {...process.env, NODE_ENV: 'test', BACKEND_URL: value}, encoding: 'utf8', timeout: 5000});
    assert.notEqual(result.status, 0, value);
  }
});
test('malformed request URL returns 400 and the frontend remains alive', async t => {
  const child = spawn(process.execPath, ['--input-type=module', '-e', 'const {server}=await import("./server.mjs"); server.listen(0,"127.0.0.1",()=>process.stdout.write(String(server.address().port)+"\\n"));'], {cwd, env: {...process.env, NODE_ENV: 'test'}, stdio: ['ignore', 'pipe', 'pipe']});
  t.after(() => child.kill());
  const port = await new Promise((resolve, reject) => { child.stdout.once('data', chunk => resolve(Number(String(chunk).trim()))); child.once('error', reject); });
  const response = await new Promise(resolve => {
    const req = http.get({hostname: '127.0.0.1', port, path: 'http://['}, res => { res.resume(); resolve(res.statusCode); });
    req.on('error', () => resolve(0)); req.setTimeout(3000, () => req.destroy());
  });
  assert.equal(response, 400);
  assert.equal((await fetch(`http://127.0.0.1:${port}/login`)).status, 200);
});
