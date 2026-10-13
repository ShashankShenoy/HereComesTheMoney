import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {createGateway, serviceFor, defaults} from './server.mjs';

async function listen(server) {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  return `http://127.0.0.1:${server.address().port}`;
}
async function close(server) { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
async function fixture(t, options = {}) {
  const services = []; const upstreams = {};
  for (const name of Object.keys(defaults)) {
    const server = http.createServer(async (req, res) => {
      if (options.respond && await options.respond(name, req, res)) return;
      if (req.url === '/actuator/health/readiness') { res.end(JSON.stringify({status: options.down === name ? 'DOWN' : 'UP'})); return; }
      if (req.url === '/v3/api-docs') { res.end(JSON.stringify({paths: {[`/api/v1/${name}`]: {get: {operationId: name}}}, components: {schemas: {[name]: {type: 'object'}}}})); return; }
      if (req.url === '/hang') return;
      let body = '';
      try { for await (const chunk of req) body += chunk; }
      catch { return; } // A deliberately aborted upload is expected in the disconnect test.
      res.setHeader('X-Correlation-ID', req.headers['x-correlation-id'] || 'test');
      res.end(JSON.stringify({service: name, url: req.url, method: req.method, headers: req.headers, body}));
    });
    services.push(server); upstreams[name] = await listen(server);
  }
  const gateway = createGateway({upstreams, timeoutMs: options.timeoutMs || 200});
  const url = await listen(gateway);
  t.after(async () => { await close(gateway); for (const service of services) await close(service); });
  return {url, services};
}
test('routes use complete path segments', () => {
  for (const path of ['/api/v1/auth/login', '/api/v1/iam/users', '/api/v1/customer-access/rotate']) assert.equal(serviceFor(path), 'identity');
  for (const path of ['/api/v1/reporting/statements', '/api/v1/catalog/masking-profiles']) assert.equal(serviceFor(path), 'reporting');
  for (const path of ['/api/v1/privacy/consents', '/api/v1/privacy/financial-audit', '/api/v1/internal/privacy/domain-events']) assert.equal(serviceFor(path), 'audit');
  for (const path of ['/api/v1/payments/initiate', '/api/v1/payment-operations/health']) assert.equal(serviceFor(path), 'payments');
  assert.equal(serviceFor('/api/v1/loans/my-applications'), 'loan');
  for (const path of ['/api/v1/authentication', '/api/v1/transactions/transfers', '/api/v1/assistant/mcp']) assert.equal(serviceFor(path), 'banking');
});
test('streams methods, queries, bearer, access key and idempotency key to the owner', async t => {
  const {url} = await fixture(t);
  for (const [path, service] of [['/api/v1/auth/login', 'identity'], ['/api/v1/reporting/statement-requests', 'reporting'], ['/api/v1/transactions/transfers', 'banking'], ['/api/v1/payments/initiate','payments'], ['/api/v1/loans/applications','loan'], ['/api/v1/privacy/financial-audit','audit']]) {
    const response = await fetch(url + path + '?limit=2', {method: 'POST', headers: {Authorization: 'Bearer opaque', 'X-Customer-Hash': 'private-key', 'Idempotency-Key': 'same-key', 'X-Correlation-ID': 'trace', 'Content-Type': 'application/json'}, body: '{"amount":25}'});
    const value = await response.json(); assert.equal(value.service, service); assert.equal(value.url, path + '?limit=2');
    assert.equal(value.body, '{"amount":25}'); assert.equal(value.method, 'POST');
    assert.equal(value.headers.authorization, 'Bearer opaque'); assert.equal(value.headers['x-customer-hash'], 'private-key');
    assert.equal(value.headers['idempotency-key'], 'same-key'); assert.equal(response.headers.get('x-correlation-id'), 'trace');
  }
});
test('readiness includes every service and fails when one is down', async t => {
  const {url} = await fixture(t, {down: 'reporting'});
  const response = await fetch(url + '/actuator/health'); assert.equal(response.status, 503);
  assert.deepEqual(await response.json(), {status: 'DOWN', components: Object.fromEntries(Object.keys(defaults).map(name => [name,{status:name === 'reporting' ? 'DOWN' : 'UP'}]))});
  assert.equal((await fetch(url + '/actuator/health/liveness')).status, 200);
});
test('combines OpenAPI paths and schemas and serves gateway Swagger config', async t => {
  const {url} = await fixture(t);
  const spec = await (await fetch(url + '/v3/api-docs')).json();
  assert.equal(Object.keys(spec.paths).length, 6); assert.equal(Object.keys(spec.components.schemas).length, 6);
  assert.equal((await (await fetch(url + '/v3/api-docs/swagger-config')).json()).url, '/v3/api-docs');
});
test('unavailable and timed out upstreams return a bounded JSON error', async t => {
  const {url, services} = await fixture(t);
  assert.equal((await fetch(url + '/hang')).status, 502);
  await close(services[0]);
  const response = await fetch(url + '/api/v1/auth/me'); assert.equal(response.status, 502);
  assert.equal((await response.json()).code, 'SERVICE_UNAVAILABLE');
});

test('healthy readiness and HEAD responses agree without a body', async t => {
  const {url} = await fixture(t);
  assert.equal((await (await fetch(url + '/actuator/health/readiness')).json()).status, 'UP');
  const response = await fetch(url + '/actuator/health', {method: 'HEAD'});
  assert.equal(response.status, 200); assert.equal(await response.text(), '');
  assert.equal((await fetch(url + '/actuator/health', {method: 'POST'})).status, 405);
});
test('public documentation and redirect endpoints reject mutating methods', async t => {
  const {url} = await fixture(t);
  for (const path of ['/v3/api-docs', '/v3/api-docs/swagger-config', '/swagger']) {
    const response = await fetch(url + path, {method: 'POST', redirect: 'manual'});
    assert.equal(response.status, 405, path);
  }
});
test('upstream status, error body and correlation header survive the proxy', async t => {
  const {url} = await fixture(t, {respond: (name, req, res) => {
    if (req.url !== '/api/v1/auth/rejected') return false;
    res.writeHead(429, {'Content-Type': 'application/json', 'X-Correlation-ID': 'edge-trace', 'Retry-After': '3'});
    res.end('{"code":"THROTTLED"}'); return true;
  }});
  const response = await fetch(url + '/api/v1/auth/rejected');
  assert.equal(response.status, 429); assert.equal(response.headers.get('retry-after'), '3');
  assert.equal(response.headers.get('x-correlation-id'), 'edge-trace'); assert.equal((await response.json()).code, 'THROTTLED');
});
test('escaped query strings are forwarded exactly', async t => {
  const {url} = await fixture(t);
  const path = '/api/v1/privacy/cases?q=A%2BB%26C&offset=0&offset=1';
  assert.equal((await (await fetch(url + path)).json()).url, path);
});
test('binary downloads preserve bytes and filename', async t => {
  const bytes = Buffer.from([0, 255, 10, 128, 34, 0, 13]);
  const {url} = await fixture(t, {respond: (name, req, res) => {
    if (req.url !== '/api/v1/reporting/download') return false;
    res.writeHead(200, {'Content-Type': 'application/pdf', 'Content-Disposition': 'attachment; filename="statement.pdf"', 'Content-Length': bytes.length});
    res.end(bytes); return true;
  }});
  const response = await fetch(url + '/api/v1/reporting/download');
  assert.deepEqual(Buffer.from(await response.arrayBuffer()), bytes);
  assert.equal(response.headers.get('content-disposition'), 'attachment; filename="statement.pdf"');
});
test('large multipart upload passes through without altering bytes', async t => {
  const {url} = await fixture(t);
  const body = '--edge\r\nContent-Disposition: form-data; name="file"\r\n\r\n' + 'x'.repeat(128 * 1024) + '\r\n--edge--\r\n';
  const response = await fetch(url + '/api/v1/cif/upload', {method: 'POST', headers: {'Content-Type': 'multipart/form-data; boundary=edge'}, body});
  const result = await response.json(); assert.equal(result.body, body);
  assert.equal(result.headers['content-type'], 'multipart/form-data; boundary=edge');
});
test('hop-by-hop and Connection-nominated headers are stripped both ways', async t => {
  const {url} = await fixture(t, {respond: (name, req, res) => {
    if (req.url !== '/headers') return false;
    res.writeHead(200, {Connection: 'X-Private-Hop', 'X-Private-Hop': 'remove', 'X-End-To-End': 'keep'});
    res.end(JSON.stringify(req.headers)); return true;
  }});
  const response = await new Promise((resolve, reject) => {
    http.get(url + '/headers', {headers: {Connection: 'X-Request-Hop', 'X-Request-Hop': 'remove', 'X-User-Value': 'keep'}}, res => {
      let body = ''; res.on('data', data => body += data); res.on('end', () => resolve({headers: res.headers, body}));
    }).on('error', reject);
  });
  const values = JSON.parse(response.body);
  assert.equal(values['x-request-hop'], undefined); assert.equal(values['x-user-value'], 'keep');
  assert.equal(response.headers['x-private-hop'], undefined); assert.equal(response.headers['x-end-to-end'], 'keep');
});
test('a truncated upstream response does not crash the gateway', async t => {
  const {url} = await fixture(t, {respond: (name, req, res) => {
    if (req.url !== '/truncated') return false;
    res.writeHead(200, {'Content-Length': '100'}); res.end('short'); return true;
  }});
  await assert.rejects(async () => { const response = await fetch(url + '/truncated'); await response.text(); });
  assert.equal((await fetch(url + '/actuator/health/liveness')).status, 200);
});
test('client disconnect during an upload does not crash the gateway', async t => {
  const {url} = await fixture(t);
  await new Promise(resolve => {
    const req = http.request(url + '/upload', {method: 'POST'});
    req.on('error', () => resolve()); req.write('partial'); setTimeout(() => req.destroy(), 20);
  });
  assert.equal((await fetch(url + '/actuator/health/liveness')).status, 200);
});
test('malformed or unavailable readiness is DOWN and docs fail explicitly', async t => {
  const {url} = await fixture(t, {respond: (name, req, res) => {
    if (name !== 'reporting') return false;
    res.end('invalid-json'); return true;
  }});
  const response = await fetch(url + '/actuator/health'); assert.equal(response.status, 503);
  assert.equal((await response.json()).components.reporting.status, 'DOWN');
  assert.equal((await fetch(url + '/v3/api-docs')).status, 503);
});
test('IPv6 loopback upstreams route correctly', async t => {
  const backend = http.createServer((req, res) => res.end('{"service":"ipv6"}'));
  try { await new Promise((resolve, reject) => { backend.once('error', reject); backend.listen(0, '::1', resolve); }); }
  catch { t.skip('IPv6 loopback is unavailable'); return; }
  const target = `http://[::1]:${backend.address().port}`;
  const gateway = createGateway({upstreams: Object.fromEntries(Object.keys(defaults).map(name => [name,target]))});
  const url = await listen(gateway);
  t.after(async () => { await close(gateway); await close(backend); });
  const response = await fetch(url + '/api/v1/auth/me');
  assert.equal(response.status, 200); assert.equal((await response.json()).service, 'ipv6');
});
test('invalid upstream configuration fails before accepting requests', () => {
  for (const target of ['ftp://127.0.0.1', 'http://user:secret@127.0.0.1', 'http://127.0.0.1/path', 'http://127.0.0.1?q=1', 'http://127.0.0.1/#fragment'])
    assert.throws(() => createGateway({upstreams: {identity: target, banking: 'http://127.0.0.1', reporting: 'http://127.0.0.1'}}));
  assert.throws(() => createGateway({upstreams: {identity: 'http://127.0.0.1'}}));
});
