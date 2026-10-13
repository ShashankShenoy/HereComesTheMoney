import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {writeFile} from 'node:fs/promises';
import {serviceFor} from '../gateway/server.mjs';

const [base, identity, reporting, banking, output] = process.argv.slice(2);
const payments=new URL(banking);payments.port='18095';
const loan=new URL(banking);loan.port='18096';
const audit=new URL(banking);audit.port='18097';
const services={identity,reporting,banking,payments:payments.origin,loan:loan.origin,audit:audit.origin};
for (const value of [base, identity, reporting, banking]) {
  const url = new URL(value);
  if (url.protocol !== 'http:' || !['127.0.0.1', 'localhost'].includes(url.hostname)) throw new Error('Loopback services required');
}
// Credentials arrive through stdin, never command arguments or result files.
let input = ''; for await (const chunk of process.stdin) input += chunk;
const credentials = JSON.parse(input); input = '';
assert.equal(credentials.length, 2);
assert.deepEqual(credentials.map(c => c.username), ['mcp_demo_customer_a', 'mcp_demo_customer_b']);
const results = [], evidence = {}, sessions = [];
async function call(route, {token, body, raw, method = body === undefined && raw === undefined ? 'GET' : 'POST', headers = {}, origin = base} = {}) {
  const started = performance.now();
  const response = await fetch(origin + route, {method, signal: AbortSignal.timeout(25000), headers: {...(token ? {Authorization: `Bearer ${token}`} : {}), ...(body !== undefined || raw !== undefined ? {'Content-Type': 'application/json'} : {}), ...headers}, ...(body !== undefined || raw !== undefined ? {body: raw ?? JSON.stringify(body)} : {})});
  const text = await response.text(); let value; try {value = JSON.parse(text);} catch {value = null;}
  return {status: response.status, value, headers: response.headers, elapsedMs: performance.now() - started};
}
function status(response, expected) {assert.equal(response.status, expected, `Expected HTTP ${expected}; got ${response.status}; code=${response.value?.code || 'none'}`); return response.value;}
async function run(category, name, action) {
  const started = performance.now();
  try {await action(); results.push({category, name, status: 'PASS', elapsedMs: Math.round(performance.now()-started)}); console.log(`PASS ${category}: ${name}`);}
  catch(error) {results.push({category, name, status: 'FAIL', elapsedMs: Math.round(performance.now()-started), error: error.message}); console.log(`FAIL ${category}: ${name}: ${error.message}`);}
}
function skip(category, name, reason = 'Bundled credential or current login state failed read-only preflight') {results.push({category, name, status: 'SKIP', reason}); console.log(`SKIP ${category}: ${name}`);}
const today = new Intl.DateTimeFormat('en-CA', {timeZone: 'Asia/Kolkata', year: 'numeric', month: '2-digit', day: '2-digit'}).format(new Date());
try {
  await run('Startup', 'six independent Oracle services are ready through frontend and gateway', async () => {
    const health = status(await call('/actuator/health'), 200); assert.equal(health.status, 'UP'); assert.equal(Object.keys(health.components).length, 6);
    for (const origin of Object.values(services)) assert.equal(status(await call('/actuator/health/readiness', {origin}), 200).status, 'UP');
  });
  await run('Architecture','Oracle business APIs belong to exactly the expected service',async()=>{
    for(const [name,origin] of Object.entries(services)){
      const spec=status(await call('/v3/api-docs',{origin}),200);
      for(const route of Object.keys(spec.paths).filter(p=>p.startsWith('/api/v1/')&&!p.startsWith('/api/v1/system/')&&!p.startsWith('/api/v1/docs/')))assert.equal(serviceFor(route),name,`${name} exposes ${route}`);
    }
  });
  await run('Startup', 'combined documentation reflects service ownership', async () => {
    const spec = status(await call('/v3/api-docs'), 200); evidence.apiPathCount = Object.keys(spec.paths).length;
    for (const endpoint of ['/api/v1/auth/login', '/api/v1/transactions/transfers', '/api/v1/reporting/statements']) assert.ok(spec.paths[endpoint]);
    const i = status(await call('/v3/api-docs', {origin: identity}), 200), r = status(await call('/v3/api-docs', {origin: reporting}), 200), b = status(await call('/v3/api-docs', {origin: banking}), 200);
    assert.ok(!b.paths['/api/v1/auth/login'] && !b.paths['/api/v1/reporting/statements'] && !i.paths['/api/v1/transactions/transfers'] && !r.paths['/api/v1/auth/login']);
  });
  await run('Security', 'anonymous and spoofed requests fail on all services', async () => {
    for (const route of ['/api/v1/auth/me', '/api/v1/banking/my-dashboard', '/api/v1/reporting/statements?accountId=3','/api/v1/payments/availability','/api/v1/loans/my/applications','/api/v1/privacy/consents/availability']) status(await call(route, {headers: {'X-User-ID': 'admin', 'X-User-Roles': 'BANK_ADMIN'}}), 401);
  });
  await run('Validation', 'missing login fields and malformed JSON return 400', async () => {status(await call('/api/v1/auth/login', {body: {}}), 400); status(await call('/api/v1/auth/login', {raw: '{'}), 400);});
  await run('Validation', 'unsupported login media type returns 415', async () => {status(await call('/api/v1/auth/login', {raw: 'invalid', headers: {'Content-Type': 'text/plain'}}), 415);});
  for (const credential of credentials) {
    if(credential.canLogin === false) {skip('Identity', `${credential.username} authenticates with the supplied encrypted settings`); continue;}
    await run('Identity', `${credential.username} authenticates with the supplied encrypted settings`, async () => {
      const data = status(await call('/api/v1/auth/login', {body: {username: credential.username, password: credential.password, clientId: 'ORACLE_ENVIRONMENT_CHECK'}}), 200).data;
      assert.equal(data.user.username, credential.username); sessions.push(data); assert.ok(data.accessToken);
    });
  }
  for(const credential of credentials) {
    const session=sessions.find(s => s.user.username === credential.username);
    const name=`${credential.username} reads its own synthetic account position`;
    if(!session) {skip('Banking', name); continue;}
    await run('Banking', name, async () => {const id=credential.username.endsWith('_a') ? 3 : 4;const position=status(await call(`/api/v1/accounts/${id}/position`, {token:session.accessToken}),200);assert.ok(Number.isFinite(Number(position.posted)));});
  }
  if (sessions.length > 0) {
    const primary=sessions[0], token = primary.accessToken, ownId=primary.user.username.endsWith('_a') ? 3 : 4, otherId=ownId === 3 ? 4 : 3;
    evidence.statementReadPermission=primary.user.permissions.includes('STATEMENT_READ');
    await run('Identity', 'bearer identity is consistent across service boundaries', async () => {assert.equal(status(await call('/api/v1/auth/me', {token}), 200).data.username, primary.user.username);});
    await run('Banking', 'customer dashboard uses Oracle account ownership', async () => {const dashboard = status(await call('/api/v1/banking/my-dashboard', {token}), 200); assert.ok(dashboard.accounts.some(a => a.accountId === ownId)); assert.ok(!dashboard.accounts.some(a => a.accountId === otherId));});
    await run('Security', 'owned statement history obeys the configured statement-read grant', async () => {status(await call(`/api/v1/reporting/statements?accountId=${ownId}`, {token}), evidence.statementReadPermission ? 200 : 403);});
    if(evidence.statementReadPermission) {
      await run('Reporting', 'authorized reporting history crosses the identity HTTP boundary', async () => {assert.ok(Array.isArray(status(await call(`/api/v1/reporting/statements?accountId=${ownId}`, {token}), 200)));});
    } else {skip('Reporting', 'authorized reporting history crosses the identity HTTP boundary', 'The existing Oracle demo role intentionally has no STATEMENT_READ grant');}
    await run('Security', 'customer cannot read the other synthetic account or statements', async () => {for (const route of [`/api/v1/accounts/${otherId}/position`, `/api/v1/reporting/statements?accountId=${otherId}`]) status(await call(route, {token}), 403);});
    await run('Security', 'customers cannot administer IAM or global reconciliation', async () => {for (const route of ['/api/v1/iam/users', '/api/v1/reconciliation/journals']) status(await call(route, {token}), 403);});
    await run('Security', 'invalid bearer tokens fail on all six services', async () => {for (const route of ['/api/v1/auth/me', '/api/v1/banking/my-dashboard', '/api/v1/reporting/statements?accountId=3','/api/v1/payments/availability','/api/v1/loans/my/applications','/api/v1/privacy/consents/availability']) status(await call(route, {token: 'invalid-session'}), 401);});
    await run('Payments','Oracle payment availability honors its configured permission and simulation flag',async()=>{
      const response=await call('/api/v1/payments/availability',{token});
      if(primary.user.permissions.includes('PAYMENT_READ')){const value=status(response,200);assert.equal(value.initiationEnabled,false);assert.equal(value.mode,'UNAVAILABLE');}
      else status(response,403);
    });
    await run('Loans','Oracle customer loan directory is scoped to current ownership',async()=>{
      const response=await call('/api/v1/loans/my/applications',{token});
      if(response.status===403){assert.ok(!primary.user.permissions.includes('LOAN_READ'));}
      else assert.ok(Array.isArray(status(response,200).data));
    });
    await run('Audit','Oracle audit policy endpoints enforce the customer permission boundary',async()=>{
      status(await call('/api/v1/privacy/financial-audit',{token}),403);
      const hasConsentAccess=primary.user.permissions.some(p=>['PRIVACY_CONSENT_VIEW','PRIVACY_CONSENT_SELF','PRIVACY_CONSENT_CAPTURE'].includes(p));
      status(await call('/api/v1/privacy/consents/availability',{token}),hasConsentAccess?200:403);
    });
    await run('Architecture','loan and payments services do not expose banking controllers',async()=>{
      for(const origin of [services.loan,services.payments])status(await call('/api/v1/banking/my-dashboard',{token,origin}),404);
      status(await call('/api/v1/loans/my/applications',{token,origin:services.banking}),404);
      status(await call('/api/v1/privacy/consents/availability',{token,origin:services.reporting}),404);
    });
    for (const [name, amount] of [['zero', '0'], ['negative', '-1'], ['sub-cent', '0.001']]) {
      await run('Validation', `${name} transfer is rejected before posting`, async () => {
        const before = status(await call(`/api/v1/accounts/${ownId}/position`, {token}), 200).posted;
        const key = randomUUID(); status(await call('/api/v1/transactions/transfers', {token, body: {sourceAccountId: ownId, targetAccountId: otherId, amount, channelCode: 'WEB', requestKey: key, correlationId: randomUUID(), valueDate: today}, headers: {'Idempotency-Key': key}}), 400);
        assert.equal(status(await call(`/api/v1/accounts/${ownId}/position`, {token}), 200).posted, before);
      });
    }
    await run('Validation', 'statement creation requires an idempotency header', async () => {status(await call('/api/v1/reporting/statement-requests', {token, body: {accountId: ownId, statementType: 'AD_HOC', periodStart: today, periodEnd: today, locale: 'en-IN', format: 'CSV', channel: 'WEB'}}), 400);});
    await run('Validation', 'unknown API and wrong login method return 404 and 405', async () => {status(await call('/api/v1/no-such-endpoint', {token}), 404); status(await call('/api/v1/auth/login'), 405);});
    await run('Security', 'allowed CORS preflight and untrusted origin denial', async () => {
      const allowed = await call('/api/v1/transactions/transfers', {method: 'OPTIONS', headers: {Origin: base, 'Access-Control-Request-Method': 'POST', 'Access-Control-Request-Headers': 'authorization,idempotency-key,x-customer-hash'}});
      status(allowed, 200); assert.equal(allowed.headers.get('access-control-allow-origin'), base);
      status(await call('/api/v1/auth/login', {method: 'OPTIONS', headers: {Origin: 'https://untrusted.example', 'Access-Control-Request-Method': 'POST'}}), 403);
    });
    await run('Load', '64 authorized Oracle banking reads at concurrency eight complete without errors', async () => {
      const times = [], started = performance.now();
      for (let batch = 0; batch < 8; batch++) {
        const responses = await Promise.all(Array.from({length: 8}, (_, index) => call(index % 2 ? `/api/v1/accounts/${ownId}/position` : '/api/v1/banking/my-dashboard', {token})));
        for (const response of responses) {status(response, 200); times.push(response.elapsedMs);}
      }
      times.sort((a,b) => a-b); evidence.load = {requests: 64, concurrency: 8, errors: 0, elapsedMs: Math.round(performance.now()-started), p50Ms: Math.round(times[31]), p95Ms: Math.round(times[60]), maxMs: Math.round(times[63])};
    });
    await run('Identity', 'logout denies the same bearer on every service', async () => {
      status(await call('/api/v1/auth/logout', {token, body: {}}), 200);
      for (const route of ['/api/v1/auth/me', '/api/v1/banking/my-dashboard', '/api/v1/reporting/statements?accountId=3','/api/v1/payments/availability','/api/v1/loans/my/applications','/api/v1/privacy/consents/availability']) status(await call(route, {token}), 401);
    });
  }
} finally {
  for (const session of sessions) {try {await call('/api/v1/auth/logout', {token: session.accessToken, body: {}});} catch { /* Recorded shutdown still runs. */ }}
  credentials.fill(null);
  await writeFile(output, JSON.stringify({database: 'Oracle', generatedAt: new Date().toISOString(), results, evidence}, null, 2));
}
if (results.some(r => r.status === 'FAIL')) process.exitCode = 1;
