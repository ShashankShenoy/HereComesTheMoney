import http from 'node:http';
import https from 'node:https';
import {pathToFileURL} from 'node:url';

export const defaults = {identity: 'http://127.0.0.1:8092', reporting: 'http://127.0.0.1:8093', banking: 'http://127.0.0.1:8094', payments: 'http://127.0.0.1:8095', loan: 'http://127.0.0.1:8096', audit: 'http://127.0.0.1:8097'};
const hopHeaders = new Set(['connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization', 'te', 'trailer', 'transfer-encoding', 'upgrade']);
function headers(values) {
  const excluded = new Set([...hopHeaders, ...(values.connection || '').toLowerCase().split(',').map(s => s.trim())]);
  return Object.fromEntries(Object.entries(values).filter(([name]) => !excluded.has(name.toLowerCase())));
}
export function serviceFor(path) {
  if (/^\/api\/v1\/internal\/privacy(\/|$)/.test(path)) return 'audit';
  if (/^\/api\/v1\/(auth|iam|customer-access)(\/|$)/.test(path)) return 'identity';
  if (/^\/api\/v1\/(reporting|catalog)(\/|$)/.test(path)) return 'reporting';
  if (/^\/api\/v1\/privacy(\/|$)/.test(path)) return 'audit';
  if (/^\/api\/v1\/(payments|payment-operations|clearing-batches|reconciliation-exceptions|approvals|dispatches)(\/|$)/.test(path)) return 'payments';
  if (/^\/api\/v1\/loans(\/|$)/.test(path)) return 'loan';
  return 'banking';
}
function sendJson(res, status, value) {
  res.writeHead(status, {'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff'});
  res.end(JSON.stringify(value));
}
// Component names are local to a service (for example, loan and card Decision).
// Rewrite every local reference before merging so generated forms keep their fields.
export function mergeApiSpecs(specs) {
  const result = {openapi: '3.0.1', info: {title: 'Moneybags microservices API', version: '1.0.0'}, servers: [{url: '/'}], paths: {}, components: {}, tags: []};
  for (const [service, spec] of Object.entries(specs)) {
    const pointer = value => value.replace(/^#\/components\/([^/]+)\/([^/]+)/, (_, kind, name) => `#/components/${kind}/${service}__${name}`);
    const rewrite = (value, key = '') => {
      if (Array.isArray(value)) return value.map(item => rewrite(item, key));
      if (!value || typeof value !== 'object') return typeof value === 'string' ? pointer(value) : value;
      return Object.fromEntries(Object.entries(value).map(([name, item]) => [key === 'security' ? `${service}__${name}` : name, rewrite(item, name)]));
    };
    for (const [route, methods] of Object.entries(spec.paths || {})) {
      // Only the service that handles this route supplies its API contract.
      const owner = serviceFor(route);
      if (specs[owner]?.paths?.[route] && owner !== service) continue;
      const rewritten = rewrite(methods);
      if (spec.security) for (const method of ['get','post','put','patch','delete','head','options','trace']) {
        if (rewritten[method] && rewritten[method].security === undefined) rewritten[method].security = rewrite(spec.security, 'security');
      }
      result.paths[route] = {...result.paths[route], ...rewritten};
    }
    for (const [kind, values] of Object.entries(spec.components || {})) {
      result.components[kind] ||= {};
      for (const [name, value] of Object.entries(values)) result.components[kind][`${service}__${name}`] = rewrite(value);
    }
    for (const tag of spec.tags || []) if (!result.tags.some(t => t.name === tag.name)) result.tags.push(tag);
  }
  return result;
}
export function createGateway({upstreams = defaults, timeoutMs = 30000} = {}) {
  const targets = Object.fromEntries(Object.entries(upstreams).map(([name, value]) => {
    const url = new URL(value);
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash || url.pathname !== '/')
      throw new Error(`${name} URL must be an HTTP(S) origin`);
    return [name, url];
  }));
  for (const name of Object.keys(defaults)) if (!targets[name]) throw new Error(`Missing ${name} upstream`);
  async function fetchJson(name, path, timeout = 3000) {
    const response = await fetch(new URL(path, targets[name]), {signal: AbortSignal.timeout(timeout), redirect: 'error'});
    if (!response.ok) throw new Error('Upstream unavailable');
    return response.json();
  }
  return http.createServer(async (req, res) => {
    let path;
    try { path = new URL(req.url, 'http://localhost').pathname; }
    catch { sendJson(res, 400, {code: 'INVALID_REQUEST', message: 'Invalid URL'}); return; }
    if (['/v3/api-docs', '/v3/api-docs/swagger-config', '/swagger'].includes(path) && req.method !== 'GET' && req.method !== 'HEAD') {
      sendJson(res, 405, {code: 'METHOD_NOT_ALLOWED'}); return;
    }
    if (path === '/actuator/health' || path === '/actuator/health/readiness' || path === '/actuator/health/liveness') {
      if (req.method !== 'GET' && req.method !== 'HEAD') { sendJson(res, 405, {code: 'METHOD_NOT_ALLOWED'}); return; }
      if (path.endsWith('/liveness')) { sendJson(res, 200, {status: 'UP'}); return; }
      const entries = await Promise.all(Object.keys(defaults).map(async name => {
        try { const health = await fetchJson(name, '/actuator/health/readiness'); return [name, {status: health.status === 'UP' ? 'UP' : 'DOWN'}]; }
        catch { return [name, {status: 'DOWN'}]; }
      }));
      const up = entries.every(([, value]) => value.status === 'UP');
      sendJson(res, up ? 200 : 503, {status: up ? 'UP' : 'DOWN', components: Object.fromEntries(entries)}); return;
    }
    if (path === '/v3/api-docs/swagger-config') {
      sendJson(res, 200, {url: '/v3/api-docs', configUrl: '/v3/api-docs/swagger-config', validatorUrl: ''}); return;
    }
    if (path === '/v3/api-docs') {
      try {
        const specs = await Promise.all(Object.keys(defaults).map(async name => [name, await fetchJson(name, '/v3/api-docs', 30000)]));
        sendJson(res, 200, mergeApiSpecs(Object.fromEntries(specs)));
      } catch { sendJson(res, 503, {code: 'DOCUMENTATION_UNAVAILABLE', message: 'A service is unavailable'}); }
      return;
    }
    if (path === '/swagger') { res.writeHead(302, {Location: '/swagger-ui/index.html'}); res.end(); return; }
    const target = targets[serviceFor(path)];
    const transport = target.protocol === 'https:' ? https : http;
    const upstream = transport.request({hostname: target.hostname, port: target.port || (target.protocol === 'https:' ? 443 : 80), path: req.url, method: req.method, headers: {...headers(req.headers), host: target.host}}, response => {
      res.writeHead(response.statusCode, headers(response.headers)); response.pipe(res);
      response.on('error', () => res.destroy());
    });
    upstream.setTimeout(path === '/api/v1/assistant/chat' ? 90000 : timeoutMs, () => upstream.destroy(new Error('Service timeout')));
    upstream.on('error', () => {
      if (res.headersSent) res.destroy();
      else sendJson(res, 502, {status: 502, code: 'SERVICE_UNAVAILABLE', message: `${serviceFor(path)} service is unavailable`, fieldErrors: {}});
    });
    req.on('aborted', () => upstream.destroy());
    req.on('error', () => upstream.destroy());
    res.on('close', () => { if (!res.writableFinished) upstream.destroy(); });
    req.pipe(upstream);
  });
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const port = Number(process.env.GATEWAY_PORT || 8090);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid GATEWAY_PORT');
  const server = createGateway({upstreams: Object.fromEntries(Object.entries(defaults).map(([name,url]) => [name,process.env[name.toUpperCase()+'_URL'] || url]))});
  server.listen(port, '127.0.0.1', () => console.log(`Moneybags API gateway: http://127.0.0.1:${port}`));
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { server.close(() => process.exit(0)); server.closeIdleConnections(); });
}
