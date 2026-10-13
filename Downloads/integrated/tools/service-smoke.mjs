import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';

const [base, identity, reporting, banking] = process.argv.slice(2);
if (![base, identity, reporting, banking].every(Boolean)) throw new Error('Pass frontend, identity, reporting and banking origins');
async function request(path, {token, body, method = body === undefined ? 'GET' : 'POST', expected = 200, headers = {}, origin = base} = {}) {
  const response = await fetch(origin + path, {method, signal: AbortSignal.timeout(30000), headers: {...(token ? {Authorization: `Bearer ${token}`} : {}), ...(body === undefined ? {} : {'Content-Type': 'application/json'}), ...headers}, ...(body === undefined ? {} : {body: JSON.stringify(body)})});
  const text = await response.text();
  assert.equal(response.status, expected, `${method} ${path}: ${text}`);
  return {value: text ? JSON.parse(text) : null, headers: response.headers};
}
async function login(username) {
  const {value} = await request('/api/v1/auth/login', {body: {username, password: 'LocalBanking!2026', clientId: 'SERVICE_SMOKE'}});
  assert.ok(value.data.accessToken); return value.data.accessToken;
}
async function tool(token, name, args) {
  const {value} = await request('/api/v1/assistant/mcp', {token, headers: {'MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': 'tools/call', 'Mcp-Name': name}, body: {jsonrpc: '2.0', id: 1, method: 'tools/call', params: {name, arguments: args}}});
  assert.equal(value.result?.isError, false, JSON.stringify(value));
  return JSON.parse(value.result.content[0].text);
}
const health = (await request('/actuator/health')).value;
assert.equal(health.status, 'UP'); assert.equal(Object.keys(health.components).length, 6);
await request('/api/v1/banking/my-dashboard', {expected: 401});
await request('/api/v1/reporting/statements?accountId=1', {expected: 401});
const customer = await login('customer'), other = await login('customer2');
assert.equal((await request('/api/v1/auth/me', {token: customer})).value.data.username, 'customer');
const position = async (id, token) => (await request(`/api/v1/accounts/${id}/position`, {token})).value;
const before = await position(1, customer), recipientBefore = await position(2, other);
await position(1, customer); await request('/api/v1/accounts/1/position', {token: other, expected: 403});
const date = new Intl.DateTimeFormat('en-CA', {timeZone: 'Asia/Kolkata', year: 'numeric', month: '2-digit', day: '2-digit'}).format(new Date());
const key = randomUUID();
const transfer = {sourceAccountId: 1, targetAccountId: 2, amount: '25.00', channelCode: 'BRANCH', requestKey: key, correlationId: key, valueDate: date};
const transaction = (await request('/api/v1/transactions/transfers', {token: customer, body: transfer, headers: {'Idempotency-Key': key, 'X-Correlation-ID': key}})).value;
assert.ok(transaction.transactionId);
const replay = (await request('/api/v1/transactions/transfers', {token: customer, body: transfer, headers: {'Idempotency-Key': key}})).value;
assert.equal(replay.transactionId, transaction.transactionId);
await request('/api/v1/transactions/transfers', {token: customer, body: {...transfer, amount: '26.00'}, expected: 409});
assert.equal(Math.round((before.posted - (await position(1, customer)).posted) * 100), 2500);
assert.equal(Math.round(((await position(2, other)).posted - recipientBefore.posted) * 100), 2500);
const spec = (await request('/v3/api-docs')).value;
assert.ok(spec.paths['/api/v1/auth/login']); assert.ok(spec.paths['/api/v1/reporting/statements']); assert.ok(spec.paths['/api/v1/transactions/transfers']);
const identitySpec = (await request('/v3/api-docs', {origin: identity})).value;
const reportingSpec = (await request('/v3/api-docs', {origin: reporting})).value;
const bankingSpec = (await request('/v3/api-docs', {origin: banking})).value;
assert.ok(identitySpec.paths['/api/v1/auth/login']); assert.ok(!identitySpec.paths['/api/v1/reporting/statements']);
assert.ok(reportingSpec.paths['/api/v1/reporting/statements']); assert.ok(!reportingSpec.paths['/api/v1/transactions/transfers']);
assert.ok(!bankingSpec.paths['/api/v1/auth/login']); assert.ok(!bankingSpec.paths['/api/v1/reporting/statements']);
await request('/api/v1/reporting/statements?accountId=1', {token: customer});
await request('/api/v1/reporting/statements?accountId=1', {token: other, expected: 403});
const generated = await tool(customer, 'generate_my_statement', {accountId: 1, periodStart: date, periodEnd: date, format: 'CSV'});
assert.ok(generated.statementId, JSON.stringify(generated));
const statement = await tool(customer, 'get_my_statement', {statementId: generated.statementId});
assert.equal(statement.accountId, 1);
await request(`/api/v1/reporting/statements/${generated.statementId}`, {token: other, expected: 403});
const download = await fetch(base + generated.downloadPath, {headers: {Authorization: `Bearer ${customer}`}, signal: AbortSignal.timeout(30000)});
assert.equal(download.status, 200); assert.ok((await download.arrayBuffer()).byteLength > 0);
assert.ok(download.headers.get('content-disposition'));
await request('/api/v1/auth/logout', {token: customer, body: {}});
await request('/api/v1/banking/my-dashboard', {token: customer, expected: 401});
await request('/api/v1/reporting/statements?accountId=1', {token: customer, expected: 401});
console.log('Separate-service smoke checks passed: authentication, ownership, transfer replay/conflict, balances, API ownership, reporting HTTP tools, downloads and session revocation.');
