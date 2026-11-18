import { createHash, createHmac, randomBytes } from 'node:crypto';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { fetchAnalytics } from './analytics.mjs';

const env = process.env;
const identity = 'https://account.yaoxi.wiki';
const origin = env.HUB_PUBLIC_ORIGIN ? new URL(env.HUB_PUBLIC_ORIGIN) : null;
const client = 'yaoxi-hub-android';
const account = env.HUB_ACCOUNT_SUB;
const handshakes = new Map();
const sessions = new Map();
const notifications = [];
const callback = await readFile(new URL('./callback.html', import.meta.url));
const pairingTtl = 5 * 60_000;
const upstreamTimeout = () => AbortSignal.timeout(15_000);
const random = () => randomBytes(32).toString('hex');
const hash = value => createHash('sha256').update(value).digest('hex');
const hmac = (key, value) => createHmac('sha256', key).update(value).digest();

class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

function requireValue(condition, message) {
  if (!condition) throw new ApiError(400, message);
}

function clean(store) {
  for (const [key, value] of store) {
    if (value.expires <= Date.now()) store.delete(key);
  }
}

async function inspect(token) {
  const response = await fetch(identity + '/api/auth/introspect', {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + token },
    redirect: 'error',
    signal: upstreamTimeout()
  });
  if (!response.ok) throw new ApiError(502, '统一账号服务暂不可用');
  const claims = await response.json();
  if (!claims.active || claims.aud !== client || claims.sub !== account ||
      !Number.isFinite(claims.exp) || claims.exp * 1000 <= Date.now()) {
    throw new ApiError(401, '会话无效或账号未授权');
  }
  return claims;
}

async function tencent(service, action, payload = {}) {
  const id = env.TENCENTCLOUD_SECRET_ID;
  const secret = env.TENCENTCLOUD_SECRET_KEY;
  if (!id || !secret) throw new ApiError(503, '服务端尚未连接腾讯云');
  const host = service + '.tencentcloudapi.com';
  const type = 'application/json; charset=utf-8';
  const timestamp = Math.floor(Date.now() / 1000);
  const date = new Date(timestamp * 1000).toISOString().slice(0, 10);
  const body = JSON.stringify(payload);
  const signed = 'content-type;host;x-tc-action';
  const headers = 'content-type:' + type + '\nhost:' + host + '\nx-tc-action:' + action.toLowerCase() + '\n';
  const request = ['POST', '/', '', headers, signed, hash(body)].join('\n');
  const scope = date + '/' + service + '/tc3_request';
  const signature = hmac(hmac(hmac(hmac('TC3' + secret, date), service), 'tc3_request'),
    ['TC3-HMAC-SHA256', timestamp, scope, hash(request)].join('\n')).toString('hex');
  const response = await fetch('https://' + host, {
    method: 'POST', body, signal: upstreamTimeout(), redirect: 'error',
    headers: {
      'Content-Type': type, 'X-TC-Action': action, 'X-TC-Timestamp': String(timestamp),
      'X-TC-Version': service === 'teo' ? '2022-09-01' : '2021-03-23',
      Authorization: 'TC3-HMAC-SHA256 Credential=' + id + '/' + scope +
        ', SignedHeaders=' + signed + ', Signature=' + signature
    }
  });
  const result = (await response.json()).Response;
  if (!response.ok || !result || result.Error) {
    const code = result?.Error?.Code ?? 'UpstreamError';
    const requestId = result?.RequestId ?? '';
    throw new ApiError(502, '腾讯云 ' + code + (requestId ? ' · ' + requestId : ''));
  }
  return result;
}

async function github(path) {
  const headers = { Accept: 'application/vnd.github+json', 'User-Agent': 'YaoXi-Hub' };
  if (env.HUB_GITHUB_TOKEN) headers.Authorization = 'Bearer ' + env.HUB_GITHUB_TOKEN;
  const response = await fetch('https://api.github.com' + path, {
    headers, signal: upstreamTimeout(), redirect: 'error'
  });
  if (!response.ok) throw new ApiError(502, 'GitHub HTTP ' + response.status);
  return response.json();
}

const domainId = data => {
  requireValue(Number.isSafeInteger(data.domainId) && data.domainId > 0, '无效的域名 ID');
  return data.domainId;
};
const dnsDomain = data => {
  requireValue(typeof data.domain === 'string' && /^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?$/i.test(data.domain) &&
    data.domain.length <= 253, '无效的域名');
  return { Domain: data.domain, DomainId: domainId(data) };
};
const recordId = data => {
  requireValue(Number.isSafeInteger(data.recordId) && data.recordId > 0, '无效的记录 ID');
  return data.recordId;
};
const zone = data => {
  requireValue(typeof data.zone === 'string' && /^zone-[a-z0-9]+$/i.test(data.zone), '无效的 EdgeOne 站点 ID');
  return data.zone;
};
const edgeRecordId = data => {
  requireValue(typeof data.recordId === 'string' && /^record-[a-z0-9]+$/i.test(data.recordId), '无效的记录 ID');
  return data.recordId;
};
const offset = data => Number.isSafeInteger(data.offset) && data.offset >= 0 ? data.offset : 0;

function note(title, result) {
  notifications.unshift({ title, detail: result.JobId || result.RequestId || '', time: new Date().toISOString() });
  notifications.length = Math.min(notifications.length, 50);
  return result;
}

const operations = {
  analytics: fetchAnalytics,
  repositories: async data => {
    const page = Math.floor(offset(data) / 100) + 1;
    const path = env.HUB_GITHUB_TOKEN
      ? '/user/repos?affiliation=owner&per_page=100&page=' + page
      : '/users/yaoxiovo/repos?per_page=100&page=' + page;
    const rows = await github(path);
    return { more: rows.length === 100, items: rows.filter(row => row.owner.login === 'yaoxiovo')
      .map(({ name, html_url, private: hidden }) => ({ name, url: html_url, private: hidden })) };
  },
  messages: async () => {
    let ci = [];
    let githubError = '';
    try {
      const result = await github('/repos/yaoxiovo/douyin-bilibili-downloader/actions/runs?per_page=10');
      ci = result.workflow_runs.filter(run => run.status === 'completed' && run.conclusion === 'failure')
        .map(run => ({ title: run.name + ' · 构建失败', detail: run.display_title, url: run.html_url, time: run.updated_at }));
    } catch (error) { githubError = error.message; }
    const items = [...notifications, ...ci].sort((left, right) => right.time.localeCompare(left.time));
    return { items, githubError };
  },
  domains: data => tencent('dnspod', 'DescribeDomainList', { Offset: offset(data), Limit: 50 }),
  lines: async data => {
    const domain = dnsDomain(data);
    for (let offset = 0; ; offset += 50) {
      const result = await tencent('dnspod', 'DescribeDomainList', { Keyword: domain.Domain, Offset: offset, Limit: 50 });
      const items = result.DomainList ?? [];
      const match = items.find(item => item.DomainId === domain.DomainId);
      if (match?.Grade) return tencent('dnspod', 'DescribeRecordLineList', { ...domain, DomainGrade: match.Grade });
      if (items.length < 50) throw new ApiError(404, '未找到域名套餐');
    }
  },
  records: data => tencent('dnspod', 'DescribeRecordList', { ...dnsDomain(data), Offset: offset(data), Limit: 50 }),
  recordSave: async data => {
    requireValue(typeof data.name === 'string' && data.name.length > 0 && data.name.length <= 253 &&
      typeof data.type === 'string' && (/^[A-Z0-9]{1,10}$/.test(data.type) || ["显性URL", "隐性URL"].includes(data.type)) &&
      typeof data.value === 'string' && data.value.length > 0 && data.value.length <= 4096 &&
      typeof data.line === 'string' && data.line.length > 0 && data.line.length <= 128 &&
      Number.isSafeInteger(data.ttl) && data.ttl > 0, '记录字段不完整');
    const args = { ...dnsDomain(data), SubDomain: data.name, RecordType: data.type,
      Value: data.value, RecordLine: data.line, TTL: data.ttl };
    if (Number.isSafeInteger(data.mx) && data.mx >= 0) args.MX = data.mx;
    if (data.recordId !== undefined) {
      args.RecordId = recordId(data);
      if (['ENABLE', 'DISABLE'].includes(data.status)) args.Status = data.status;
      if (Number.isSafeInteger(data.weight) && data.weight >= 0) args.Weight = data.weight;
    }
    return note('DNS 解析已保存', await tencent('dnspod', args.RecordId ? 'ModifyRecord' : 'CreateRecord', args));
  },
  recordStatus: async data => {
    requireValue(typeof data.enabled === 'boolean', '无效的记录状态');
    return note(data.enabled ? 'DNS 记录已启用' : 'DNS 记录已暂停',
      await tencent('dnspod', 'ModifyRecordStatus', { ...dnsDomain(data),
        RecordId: recordId(data), Status: data.enabled ? 'ENABLE' : 'DISABLE' }));
  },
  zones: data => tencent('teo', 'DescribeZones', { Offset: offset(data), Limit: 50 }),
  acceleration: data => tencent('teo', 'DescribeAccelerationDomains', { ZoneId: zone(data), Offset: offset(data), Limit: 50 }),
  edgeRecords: data => tencent('teo', 'DescribeDnsRecords', { ZoneId: zone(data), Offset: offset(data), Limit: 50 }),
  edgeRecordSave: async data => {
    requireValue(typeof data.name === 'string' && data.name.length > 0 && data.name.length <= 253 &&
      ['A', 'AAAA', 'CAA', 'CNAME', 'MX', 'NS', 'SRV', 'TXT'].includes(data.type) &&
      typeof data.value === 'string' && data.value.length > 0 && data.value.length <= 4096 &&
      Number.isSafeInteger(data.ttl) && data.ttl >= 60 && data.ttl <= 86_400, 'EdgeOne 记录字段无效');
    const zoneId = zone(data);
    const record = { Name: data.name, Type: data.type, Content: data.value, TTL: data.ttl };
    if (['A', 'AAAA', 'CNAME'].includes(data.type)) {
      if (typeof data.line === 'string' && data.line.length && data.line.length <= 128) record.Location = data.line;
      if (Number.isSafeInteger(data.weight) && data.weight >= -1 && data.weight <= 100) record.Weight = data.weight;
    }
    if (data.type === 'MX' && Number.isSafeInteger(data.mx)) record.Priority = data.mx;
    return note('EdgeOne DNS 记录已保存', data.recordId === undefined
      ? await tencent('teo', 'CreateDnsRecord', { ZoneId: zoneId, ...record })
      : await tencent('teo', 'ModifyDnsRecords', { ZoneId: zoneId, DnsRecords: [{ ...record, RecordId: edgeRecordId(data) }] }));
  },
  edgeRecordStatus: async data => {
    requireValue(typeof data.enabled === 'boolean', '无效的记录状态');
    const field = data.enabled ? 'RecordsToEnable' : 'RecordsToDisable';
    return note(data.enabled ? 'EdgeOne DNS 记录已启用' : 'EdgeOne DNS 记录已暂停',
      await tencent('teo', 'ModifyDnsRecordsStatus', { ZoneId: zone(data), [field]: [edgeRecordId(data)] }));
  },
  purgeHistory: data => {
    const end = Date.now();
    return tencent('teo', 'DescribePurgeTasks', { ZoneId: zone(data), Offset: offset(data), Limit: 50,
      StartTime: new Date(end - 6 * 24 * 60 * 60_000).toISOString(), EndTime: new Date(end).toISOString() });
  },
  purge: async data => {
    let url;
    try { url = new URL(data.url); } catch { throw new ApiError(400, '请输入有效的 HTTP 或 HTTPS URL'); }
    requireValue(['https:', 'http:'].includes(url.protocol) && !url.username && !url.password &&
      !url.hash && !url.href.includes('*') && url.href.length <= 4096, '只允许清除单个 URL');
    const result = await tencent('teo', 'CreatePurgeTask', { ZoneId: zone(data), Type: 'purge_url', Targets: [url.href] });
    if (!result.JobId || result.FailedList?.length) {
      throw new ApiError(502, 'EdgeOne 未接受该 URL · ' + (result.RequestId ?? ''));
    }
    return note('EdgeOne 已提交 URL 缓存清除', result);
  }
};

async function readJson(req) {
  let text = '';
  req.setEncoding('utf8');
  for await (const chunk of req) {
    text += chunk;
    if (text.length > 16_384) throw new ApiError(413, '请求过大');
  }
  try {
    const result = JSON.parse(text || '{}');
    if (!result || typeof result !== 'object' || Array.isArray(result)) throw new Error();
    return result;
  } catch { throw new ApiError(400, '无效的请求'); }
}

function response(res, status, value, headers = {}) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer', ...headers });
  res.end(Buffer.isBuffer(value) ? value : JSON.stringify(value));
}

async function route(req, res) {
  const path = new URL(req.url, 'http://localhost').pathname;
  if (path === '/auth/callback' && req.method === 'GET') return response(res, 200, callback, {
    'Content-Type': 'text/html; charset=utf-8',
    'Content-Security-Policy': "default-src 'none'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'"
  });
  if (path === '/auth/callback.js' && req.method === 'GET') return response(res, 200,
    await readFile(new URL('./callback.js', import.meta.url)), { 'Content-Type': 'text/javascript; charset=utf-8' });
  if (req.method !== 'POST') throw new ApiError(404, '接口不存在');

  if (path === '/auth/start') {
    if (!origin || origin.protocol !== 'https:' || !account || !env.HUB_SSO_HANDSHAKE_SECRET) {
      throw new ApiError(503, '服务端尚未完成统一账号配置');
    }
    clean(handshakes);
    if (handshakes.size >= 1000) throw new ApiError(429, '登录请求过多，请稍候再试');
    const code = random();
    const state = random();
    const nonce = randomBytes(10).toString('hex');
    const time = Date.now();
    const text = 'v1.' + time + '.' + nonce + '.' + origin.hostname;
    const proof = 'crt.v1.' + time + '.' + nonce + '.' +
      hmac(env.HUB_SSO_HANDSHAKE_SECRET, text).toString('hex').slice(0, 32);
    handshakes.set(state, { codeHash: hash(code), expires: time + pairingTtl });
    const auth = new URL('/accounts-login.html', identity);
    auth.search = new URLSearchParams({ client_id: client, target_domain: origin.hostname,
      redirect_uri: new URL('/auth/callback', origin).href, client_request_token: proof,
      response_type: 'token', scope: 'openid profile', state });
    return response(res, 200, { url: auth.href, state, code });
  }
  if (path === '/auth/complete' || path === '/auth/poll') {
    const data = await readJson(req);
    const pending = handshakes.get(data.state);
    if (!pending || pending.expires < Date.now()) throw new ApiError(410, '登录请求已过期');
    if (path === '/auth/complete') {
      requireValue(typeof data.token === 'string' && data.token.length <= 10_000, '账号凭据无效');
      const claims = await inspect(data.token);
      if (handshakes.get(data.state) !== pending || pending.token) throw new ApiError(409, '登录请求已使用');
      pending.token = data.token;
      pending.claims = claims;
      return response(res, 200, { completed: true });
    }
    if (typeof data.code !== 'string' || hash(data.code) !== pending.codeHash) throw new ApiError(401, '登录请求无效');
    if (!pending.token) return response(res, 200, { pending: true });
    handshakes.delete(data.state);
    clean(sessions);
    const session = random();
    sessions.set(hash(session), { token: pending.token, expires: pending.claims.exp * 1000 });
    return response(res, 200, { session, account: pending.claims.sub });
  }
  const token = req.headers.authorization?.match(/^Bearer ([a-f0-9]{64})$/)?.[1];
  const key = token && hash(token);
  const session = key && sessions.get(key);
  if (!session || session.expires <= Date.now()) throw new ApiError(401, '请先登录账号统一中心');
  if (path === '/auth/logout') {
    sessions.delete(key);
    return response(res, 200, { ok: true });
  }
  const claims = await inspect(session.token);
  if (path === '/api/account') return response(res, 200, { account: claims.sub,
    tencentConfigured: Boolean(env.TENCENTCLOUD_SECRET_ID && env.TENCENTCLOUD_SECRET_KEY),
    githubPrivateConfigured: Boolean(env.HUB_GITHUB_TOKEN) });
  const operation = path.slice(5);
  if (!path.startsWith('/api/') || !Object.hasOwn(operations, operation)) throw new ApiError(404, '接口不存在');
  const result = await operations[operation](await readJson(req));
  return response(res, 200, result);
}

createServer((req, res) => route(req, res).catch(error => {
  if (!res.headersSent) response(res, error.status || 502, { error: error.status ? error.message : '服务暂不可用' });
})).listen(Number(env.PORT || 8788), env.HOST || '127.0.0.1');
