import assert from 'node:assert/strict';
import {randomUUID, createHmac} from 'node:crypto';
import {writeFile, mkdir} from 'node:fs/promises';
import path from 'node:path';

const [base, identity, reporting, banking, output] = process.argv.slice(2);
if (![base, identity, reporting, banking, output].every(Boolean)) throw new Error('Pass four local origins and a result file');
for (const value of [base, identity, reporting, banking]) if (!['127.0.0.1', 'localhost', '[::1]'].includes(new URL(value).hostname)) throw new Error('Tests require loopback services');
const results = [], evidence = {}, sessions = {};
const today = new Intl.DateTimeFormat('en-CA', {timeZone: 'Asia/Kolkata', year: 'numeric', month: '2-digit', day: '2-digit'}).format(new Date());
const tomorrow = new Date(Date.parse(today + 'T00:00:00Z') + 86400000).toISOString().slice(0, 10);
async function call(route, {token, body, raw, method = body === undefined && raw === undefined ? 'GET' : 'POST', headers = {}, origin = base} = {}) {
  const start = performance.now();
  const response = await fetch(origin + route, {method, signal: AbortSignal.timeout(25000), headers: {...(token ? {Authorization: `Bearer ${token}`} : {}), ...(body === undefined && raw === undefined ? {} : {'Content-Type': 'application/json'}), ...headers}, ...(body === undefined && raw === undefined ? {} : {body: raw ?? JSON.stringify(body)})});
  const text = await response.text(); let value; try { value = text ? JSON.parse(text) : null; } catch { value = text; }
  return {status: response.status, value, headers: response.headers, elapsedMs: performance.now() - start};
}
function status(response, expected) {
  const allowed = Array.isArray(expected) ? expected : [expected];
  assert.ok(allowed.includes(response.status), `Expected HTTP ${allowed.join('/')} but got ${response.status}; code=${response.value?.code || 'none'}`);
  return response.value;
}
async function run(category, name, action) {
  const start = performance.now();
  try { await action(); results.push({category, name, status: 'PASS', elapsedMs: Math.round(performance.now() - start)}); console.log(`PASS ${category}: ${name}`); }
  catch (error) { results.push({category, name, status: 'FAIL', elapsedMs: Math.round(performance.now() - start), error: error.message}); console.log(`FAIL ${category}: ${name}: ${error.message}`); }
}
async function login(username) { return status(await call('/api/v1/auth/login', {body: {username, password: 'LocalBanking!2026', clientId: 'EDGE_SUITE'}}), 200).data; }
const balance = async (id, token = sessions.customer.accessToken) => status(await call(`/api/v1/accounts/${id}/position`, {token}), 200);
const transfer = (overrides = {}) => ({sourceAccountId: 1, targetAccountId: 2, amount: '1.00', channelCode: 'BRANCH', requestKey: randomUUID(), correlationId: randomUUID(), valueDate: today, ...overrides});
const sendTransfer = (body, token = sessions.customer.accessToken) => call('/api/v1/transactions/transfers', {token, body, headers: {'Idempotency-Key': body.requestKey}});
const reportingBody = (overrides = {}) => ({accountId: 1, statementType: 'AD_HOC', periodStart: today, periodEnd: today, locale: 'en-IN', format: 'CSV', channel: 'WEB', ...overrides});
const createStatement = (body, key = randomUUID()) => call('/api/v1/reporting/statement-requests', {token: sessions.customer.accessToken, body, headers: {'Idempotency-Key': key}});
async function mcp(name, args, token = sessions.customer.accessToken, headers = {}) {
  return call('/api/v1/assistant/mcp', {token, headers: {'MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': 'tools/call', 'Mcp-Name': name, ...headers}, body: {jsonrpc: '2.0', id: 1, method: 'tools/call', params: {name, arguments: args}}});
}

await run('Normal', 'all services ready through the frontend', async () => { const health = status(await call('/actuator/health'), 200); assert.equal(health.status, 'UP'); assert.equal(Object.keys(health.components).length, 6); });
await run('Normal', 'customer, second customer, admin and checker sign in', async () => {
  for (const name of ['customer', 'customer2', 'admin', 'checker']) { sessions[name] = await login(name); assert.equal(sessions[name].user.username, name); }
  assert.ok(sessions.admin.user.roles.includes('BANK_ADMIN')); assert.ok(sessions.checker.user.roles.includes('BANK_CHECKER'));
});
if (!sessions.customer || !sessions.customer2 || !sessions.admin) {
  await mkdir(path.dirname(output), {recursive: true}); await writeFile(output, JSON.stringify({results, evidence}, null, 2)); process.exitCode = 1;
} else {
const token = sessions.customer.accessToken, other = sessions.customer2.accessToken, admin = sessions.admin.accessToken;
await run('Normal', 'service ownership and combined API documentation', async () => {
  const spec = status(await call('/v3/api-docs'), 200);
  assert.ok(spec.paths['/api/v1/auth/login']); assert.ok(spec.paths['/api/v1/transactions/transfers']); assert.ok(spec.paths['/api/v1/reporting/statements']);
  const i = status(await call('/v3/api-docs', {origin: identity}), 200), r = status(await call('/v3/api-docs', {origin: reporting}), 200), b = status(await call('/v3/api-docs', {origin: banking}), 200);
  assert.ok(!b.paths['/api/v1/auth/login'] && !b.paths['/api/v1/reporting/statements']); assert.ok(!i.paths['/api/v1/transactions/transfers']); assert.ok(!r.paths['/api/v1/auth/login']);
  evidence.apiPathCount = Object.keys(spec.paths).length;
});
await run('Security', 'missing and spoofed identities fail on every service', async () => {
  for (const route of ['/api/v1/auth/me', '/api/v1/banking/my-dashboard', '/api/v1/reporting/statements?accountId=1', '/api/v1/iam/users'])
    status(await call(route, {headers: {'X-User-ID': 'admin', 'X-User-Roles': 'BANK_ADMIN'}}), 401);
});
await run('Security', 'malformed, Basic and oversized bearer tokens are rejected', async () => {
  for (const authorization of ['Basic dXNlcjpwYXNz', 'Bearer not-a-session', 'Bearer ' + 'x'.repeat(140)])
    for (const route of ['/api/v1/auth/me', '/api/v1/banking/my-dashboard', '/api/v1/reporting/statements?accountId=1']) status(await call(route, {headers: {Authorization: authorization}}), 401);
});
await run('Security', 'invalid passwords and SQL-like usernames do not authenticate', async () => {
  for (const username of ['customer', "' OR '1'='1", 'nonexistent_edge_user']) status(await call('/api/v1/auth/login', {body: {username, password: 'IncorrectPassword!'}}), 401);
});
await run('Validation', 'missing login fields and malformed JSON return 400', async () => { status(await call('/api/v1/auth/login', {body: {}}), 400); status(await call('/api/v1/auth/login', {raw: '{'}), 400); });
await run('Validation', 'unsupported request media type returns 415', async () => { status(await call('/api/v1/auth/login', {raw: 'not-json', headers: {'Content-Type': 'text/plain'}}), 415); });
await run('Security', 'one customer cannot read another account or reporting history', async () => { status(await call('/api/v1/accounts/1/position', {token: other}), 403); status(await call('/api/v1/reporting/statements?accountId=1', {token: other}), 403); });
await run('Security', 'customers cannot administer IAM or global ledger reconciliation', async () => { status(await call('/api/v1/iam/users', {token}), 403); status(await call('/api/v1/reconciliation/journals', {token}), 403); });
await run('Security', 'checker cannot initiate a transfer', async () => { status(await sendTransfer(transfer(), sessions.checker.accessToken), 403); });
await run('Security', 'allowed CORS origin and complete authorization preflight', async () => {
  const origin = new URL(base).origin;
  const response = await call('/api/v1/transactions/transfers', {method: 'OPTIONS', headers: {Origin: origin, 'Access-Control-Request-Method': 'POST', 'Access-Control-Request-Headers': 'authorization,idempotency-key,x-customer-hash'}});
  status(response, 200); assert.equal(response.headers.get('access-control-allow-origin'), origin);
});
await run('Security', 'untrusted CORS origin is denied', async () => { status(await call('/api/v1/auth/login', {method: 'OPTIONS', headers: {Origin: 'https://untrusted.example', 'Access-Control-Request-Method': 'POST'}}), 403); });
await run('Validation', 'unknown endpoint and wrong method give 404 and 405', async () => { status(await call('/api/v1/no-such-endpoint', {token}), 404); status(await call('/api/v1/auth/login'), 405); });
for (const [name, amount, expected] of [['zero amount', '0', 400], ['negative amount', '-1', 400], ['sub-cent amount', '0.001', 400], ['excess precision', '1.001', 400], ['oversized money', '999999999999999999999', 409]]) {
  await run('Validation', name + ' is rejected without changing funds', async () => { const before = await balance(1); status(await sendTransfer(transfer({amount})), expected); assert.equal((await balance(1)).posted, before.posted); });
}
await run('Validation', 'self transfer and future value date are rejected atomically', async () => { const before = await balance(1); status(await sendTransfer(transfer({targetAccountId: 1})), 400); const future=await sendTransfer(transfer({valueDate: tomorrow}));status(future,[400,409]);assert.ok(['FUTURE_VALUE_DATE','BANKING_RULE'].includes(future.value.code));assert.equal((await balance(1)).posted, before.posted); });
await run('Security', 'unauthorized source and nonexistent recipient fail without a debit', async () => { const before = await balance(1); status(await sendTransfer(transfer({sourceAccountId: 2, targetAccountId: 1})), 403); status(await sendTransfer(transfer({targetAccountId: 999999})), [404, 409]); assert.equal((await balance(1)).posted, before.posted); });
await run('Financial', 'insufficient funds never partially posts a debit', async () => { const before = await balance(1); status(await sendTransfer(transfer({amount: '99999.00'})), 409); assert.equal((await balance(1)).posted, before.posted); });
await run('Financial', 'one-cent transfer preserves exact balances and correlation', async () => {
  const a = await balance(1), b = await balance(2, other), body = transfer({amount: '0.01'}), trace = randomUUID();
  const response = await call('/api/v1/transactions/transfers', {token, body, headers: {'X-Correlation-ID': trace, 'Idempotency-Key': body.requestKey}});
  const value = status(response, 200); assert.ok(value.journalId); assert.equal(response.headers.get('x-correlation-id'), trace);
  assert.equal(Math.round((a.posted - (await balance(1)).posted) * 100), 1); assert.equal(Math.round(((await balance(2, other)).posted - b.posted) * 100), 1);
  assert.equal(status(await call(`/api/v1/journals/${value.journalId}/control`, {token}), 200).IS_BALANCED, 'Y');
});
await run('Financial', 'same request replays once and changed payload conflicts', async () => {
  const body = transfer(), before = await balance(1); const first = status(await sendTransfer(body), 200), second = status(await sendTransfer(body), 200);
  assert.equal(first.transactionId, second.transactionId); status(await sendTransfer({...body, amount: '2.00'}), 409);
  assert.equal(Math.round((before.posted - (await balance(1)).posted) * 100), 100);
});
await run('Concurrency', 'eight simultaneous identical transfers post exactly once', async () => {
  const body = transfer(), before = await balance(1); const responses = await Promise.all(Array.from({length: 8}, () => sendTransfer(body)));
  const ids = responses.map(response => status(response, 200).transactionId); assert.equal(new Set(ids).size, 1);
  assert.equal(Math.round((before.posted - (await balance(1)).posted) * 100), 100);
});
await run('Concurrency', 'opposing concurrent transfers conserve total money', async () => {
  const a = await balance(1), b = await balance(2, other);
  const jobs = Array.from({length: 6}, () => sendTransfer(transfer()));
  jobs.push(...Array.from({length: 6}, () => sendTransfer(transfer({sourceAccountId: 2, targetAccountId: 1}), other)));
  for (const response of await Promise.all(jobs)) status(response, 200);
  assert.equal((await balance(1)).posted, a.posted); assert.equal((await balance(2, other)).posted, b.posted);
});
await run('Concurrency', 'concurrent overspend permits one debit and prevents negative funds', async () => {
  const before = await balance(2, other);
  const responses = await Promise.all(Array.from({length: 2}, () => sendTransfer(transfer({sourceAccountId: 2, targetAccountId: 1, amount: '15000.00'}), other)));
  assert.deepEqual(responses.map(r => r.status).sort(), [200, 409]);
  const after = await balance(2, other); assert.ok(after.posted >= 0); assert.equal(Math.round((before.posted - after.posted) * 100), 1500000);
});
await run('Financial', 'ledger journal and account position reconciliation remain clean', async () => {
  assert.deepEqual(status(await call('/api/v1/reconciliation/journals', {token: admin}), 200), []);
  assert.deepEqual(status(await call('/api/v1/reconciliation/positions', {token: admin}), 200), []);
});
await run('Reporting', 'missing idempotency header returns a client error', async () => { status(await call('/api/v1/reporting/statement-requests', {token, body: reportingBody()}), 400); });
await run('Reporting', 'future, reversed and unsupported-format statement periods are rejected', async () => {
  for (const body of [reportingBody({periodEnd: tomorrow}), reportingBody({periodStart: tomorrow}), reportingBody({format: 'EXE'})]) status(await createStatement(body), 400);
});
await run('Reporting', 'request replay is stable and changed payload conflicts', async () => {
  const key = randomUUID(), body = reportingBody(); const first = status(await createStatement(body, key), 201), second = status(await createStatement(body, key), 201);
  assert.equal(first.requestId, second.requestId); status(await createStatement({...body, format: 'PDF'}, key), 409); evidence.requestId = first.requestId;
});
await run('Reporting', 'customer can issue and read an authorized statement', async () => {
  const value = status(await call(`/api/v1/reporting/statement-requests/${evidence.requestId}/process`, {token, body: {}}), 200);
  assert.equal(value.status, 'COMPLETED'); assert.ok(value.statementId); evidence.statementId = value.statementId;
  assert.equal(status(await call(`/api/v1/reporting/statements/${value.statementId}`, {token}), 200).accountId, 1);
});
await run('Reporting', 'cross-customer downloads and invalid statement IDs are denied', async () => {
  status(await call(`/api/v1/reporting/statements/${evidence.statementId}/download`, {token: other}), 403);
  status(await call('/api/v1/reporting/statements/00000000-0000-0000-0000-000000000000', {token}), 404);
});
await run('Reporting', 'PDF, CSV and HTML downloads have data and attachment filenames', async () => {
  for (const format of ['PDF', 'CSV', 'HTML']) {
    const response = await fetch(`${base}/api/v1/reporting/statements/${evidence.statementId}/download?format=${format}`, {headers: {Authorization: `Bearer ${token}`}, signal: AbortSignal.timeout(25000)});
    assert.equal(response.status, 200); assert.ok(response.headers.get('content-disposition')); assert.ok((await response.arrayBuffer()).byteLength > 20);
  }
});
await run('Assistant', 'reporting tools cross HTTP boundaries without an AI key', async () => {
  const result = status(await mcp('get_my_statement', {statementId: evidence.statementId}), 200).result;
  assert.equal(result.isError, false); assert.equal(JSON.parse(result.content[0].text).accountId, 1);
});
await run('Assistant', 'invalid protocol and unauthorized tool are rejected safely', async () => {
  assert.ok(status(await mcp('get_my_statement', {statementId: evidence.statementId}, token, {'MCP-Protocol-Version': 'invalid'}), 200).error);
  assert.equal(status(await mcp('search_customers', {query: 'demo'}), 200).result.isError, true);
});
await run('Assistant', 'cross-customer statement tool returns an explicit denied result', async () => { assert.equal(status(await mcp('get_my_statement', {statementId: evidence.statementId}, other), 200).result.isError, true); });
await run('Load', '64 authenticated reads at concurrency eight have zero server failures', async () => {
  const timings = []; const start = performance.now();
  for (let batch = 0; batch < 8; batch++) {
    const responses = await Promise.all(Array.from({length: 8}, (_, index) => call(index % 2 ? '/api/v1/reporting/statements?accountId=1' : '/api/v1/banking/my-dashboard', {token})));
    for (const response of responses) { status(response, 200); timings.push(response.elapsedMs); }
  }
  timings.sort((a,b) => a-b); evidence.load = {requests: 64, concurrency: 8, errors: 0, elapsedMs: Math.round(performance.now()-start), p50Ms: Math.round(timings[31]), p95Ms: Math.round(timings[60]), maxMs: Math.round(timings[63])};
});
const signupName = 'edge_' + randomUUID().slice(0, 12), signup = {username: signupName, legalName: 'Synthetic Edge Test', dateOfBirth: '1990-01-01', password: 'LocalBanking!2026'};
await run('Identity', 'signup duplicate and underage validations keep least privilege', async () => {
  status(await call('/api/v1/auth/signup', {body: signup}), 200); status(await call('/api/v1/auth/signup', {body: {...signup, username: signupName.toUpperCase()}}), 409);
  status(await call('/api/v1/auth/signup', {body: {...signup, username: signupName + '_minor', dateOfBirth: today.slice(0,4) - 10 + '-01-01'}}), 400);
  const fresh = await login(signupName); assert.ok(!fresh.user.permissions.includes('IAM_USER_MANAGE')); assert.equal(fresh.user.cifIds.length, 1);
});
await run('Identity', 'five wrong passwords lock the sacrificial synthetic account', async () => {
  for (let i = 0; i < 5; i++) status(await call('/api/v1/auth/login', {body: {username: signupName, password: 'WrongPassword!'}}), 401);
  status(await call('/api/v1/auth/login', {body: {username: signupName, password: signup.password}}), 401);
});
await run('Identity', 'refresh rotation works and reuse revokes its entire session', async () => {
  const separate = await login('customer');
  const renewed = status(await call('/api/v1/auth/refresh', {body: {refreshToken: separate.refreshToken}}), 200).data;
  assert.notEqual(renewed.refreshToken, separate.refreshToken);
  status(await call('/api/v1/banking/my-dashboard', {token: renewed.accessToken}), 200);
  status(await call('/api/v1/auth/refresh', {body: {refreshToken: separate.refreshToken}}), 401);
  for (const route of ['/api/v1/auth/me', '/api/v1/banking/my-dashboard', '/api/v1/reporting/statements?accountId=1']) status(await call(route, {token: renewed.accessToken}), 401);
});
await run('Identity', 'logout invalidates the bearer on all services immediately', async () => {
  const separate = await login('customer'); status(await call('/api/v1/auth/logout', {token: separate.accessToken, body: {}}), 200);
  for (const route of ['/api/v1/auth/me', '/api/v1/banking/my-dashboard', '/api/v1/reporting/statements?accountId=1']) status(await call(route, {token: separate.accessToken}), 401);
});
const mfaName='mfa_'+randomUUID().slice(0,12);let enrollment,mfaSession;
function totp(secret,slot){
  const alphabet='ABCDEFGHIJKLMNOPQRSTUVWXYZ234567',bytes=[];let value=0,bits=0;
  for(const char of secret){value=(value<<5)|alphabet.indexOf(char);bits+=5;if(bits>=8){bits-=8;bytes.push((value>>>bits)&255);}}
  const counter=Buffer.alloc(8);counter.writeBigUInt64BE(BigInt(slot));const hash=createHmac('sha1',Buffer.from(bytes)).update(counter).digest();const offset=hash[19]&15;
  return String((hash.readUInt32BE(offset)&0x7fffffff)%1000000).padStart(6,'0');
}
await run('MFA', 'enrollment and confirmation enable a synthetic authenticator', async()=>{
  status(await call('/api/v1/auth/signup',{body:{...signup,username:mfaName}}),200);mfaSession=await login(mfaName);
  enrollment=status(await call('/api/v1/iam/factors/totp',{token:mfaSession.accessToken,body:{}}),200).data;
  assert.ok(enrollment.factorId&&enrollment.secret);const code=totp(enrollment.secret,Math.floor(Date.now()/30000));
  status(await call(`/api/v1/iam/factors/${enrollment.factorId}/confirm`,{token:mfaSession.accessToken,body:{code}}),200);
  const factors=status(await call('/api/v1/iam/factors',{token:mfaSession.accessToken}),200).data;assert.equal(factors.find(f=>f.factorId===enrollment.factorId).status,'ACTIVE');
});
await run('MFA', 'password-only and incorrect OTP login are denied',async()=>{
  const body={username:mfaName,password:'LocalBanking!2026'};const missing=await call('/api/v1/auth/login',{body});status(missing,401);assert.equal(missing.value.code,'MFA_REQUIRED');
  const slot=Math.floor(Date.now()/30000),used=new Set([-1,0,1].map(d=>totp(enrollment.secret,slot+d)));const wrong=['000000','111111','222222','333333'].find(code=>!used.has(code));
  const rejected=await call('/api/v1/auth/login',{body:{...body,otp:wrong}});status(rejected,401);assert.equal(rejected.value.code,'MFA_INVALID');
});
await run('MFA', 'valid OTP authenticates and reuse cannot create another session',async()=>{
  const otp=totp(enrollment.secret,Math.floor(Date.now()/30000)+1),body={username:mfaName,password:'LocalBanking!2026',otp};
  const authenticated=status(await call('/api/v1/auth/login',{body}),200).data;assert.ok(authenticated.accessToken);
  const replay=await call('/api/v1/auth/login',{body});status(replay,401);assert.equal(replay.value.code,'MFA_CODE_REUSED');
  status(await call('/api/v1/iam/factors/totp',{token:authenticated.accessToken,body:{}}),409);
});
evidence.balances = {source: (await balance(1)).posted, recipient: (await balance(2, other)).posted};
}
await mkdir(path.dirname(output), {recursive: true});
const summary = {passed: results.filter(r => r.status === 'PASS').length, failed: results.filter(r => r.status === 'FAIL').length, total: results.length};
await writeFile(output, JSON.stringify({generatedAt: new Date().toISOString(), summary, results, evidence}, null, 2));
console.log(JSON.stringify(summary)); if (summary.failed) process.exitCode = 1;
