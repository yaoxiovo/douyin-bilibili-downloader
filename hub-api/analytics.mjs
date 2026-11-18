const day = 86_400_000;
const umamiOrigin = 'https://umami.yaoxi.wiki';
const bingSites = ['yaoxi.wiki', 'blog.yaoxi.wiki', 'yaoxi.cloud'];

async function fetchJson(url, options = {}) {
  const response = await fetch(url, { ...options, redirect: 'error', signal: AbortSignal.timeout(7000) });
  if (!response.ok) throw new Error('Upstream unavailable');
  return response.json();
}

function metric(value) {
  const raw = value && typeof value === 'object' ? value.value : value;
  if (typeof raw !== 'number' && !(typeof raw === 'string' && /^\d+$/.test(raw))) return null;
  const number = Number(raw);
  return Number.isSafeInteger(number) && number >= 0 ? number : null;
}

function cache(load, ttl) {
  let stored;
  let expires = 0;
  let pending;
  return () => {
    if (Date.now() < expires) return Promise.resolve(stored);
    pending ??= load().then(result => {
      stored = result;
      expires = Date.now() + ttl;
      return result;
    }).finally(() => { pending = undefined; });
    return pending;
  };
}

const loadUmami = cache(async () => {
  const shareId = process.env.HUB_UMAMI_SHARE_ID;
  const source = await fetchJson(umamiOrigin + '/api/share/' + encodeURIComponent(shareId));
  if (!/^[a-f\d]{8}(?:-[a-f\d]{4}){3}-[a-f\d]{12}$/i.test(source.websiteId) ||
      typeof source.token !== 'string' || !source.token) throw new Error('Invalid Umami share');
  const endAt = Date.now();
  const startAt = endAt - 7 * day;
  const headers = { 'x-umami-share-token': source.token, 'x-umami-share-context': shareId };
  const report = (operation, options = {}) => {
    const url = new URL('/api/websites/' + source.websiteId + '/' + operation, umamiOrigin);
    url.search = new URLSearchParams({ startAt, endAt, ...options });
    return fetchJson(url, { headers });
  };
  const [stats, trend, pages] = await Promise.allSettled([
    report('stats'), report('pageviews', { unit: 'day', timezone: 'Asia/Shanghai' }),
    report('metrics', { type: 'path', limit: 5 })
  ]);
  if (stats.status !== 'fulfilled') throw new Error('Missing Umami metrics');
  const result = stats.value;
  const values = { pageviews: metric(result.pageviews), visitors: metric(result.visitors), visits: metric(result.visits) };
  if (Object.values(values).every(value => value === null)) throw new Error('Missing Umami metrics');
  const timeSeries = trend.status === 'fulfilled' && Array.isArray(trend.value.pageviews)
    ? trend.value.pageviews.filter(item => typeof item.x === 'string' && metric(item.y) !== null)
      .map(item => ({ day: item.x.slice(0, 10), value: metric(item.y) })) : null;
  const rankings = pages.status === 'fulfilled' && Array.isArray(pages.value)
    ? pages.value.filter(item => typeof item.x === 'string' && metric(item.y) !== null)
      .map(item => ({ name: item.x.slice(0, 200), value: metric(item.y) })) : null;
  return { name: 'blog.yaoxi.wiki', startAt, endAt, ...values, trend: timeSeries, topPages: rankings };
}, 5 * 60_000);

function reportingDay(value) {
  if (typeof value !== 'string') return null;
  const unix = value.match(/^\/Date\((\d+)(?:[+-]\d+)?\)\/$/);
  const time = unix ? Number(unix[1]) : Date.parse(value);
  return Number.isFinite(time) && time > 0 && time <= Date.now() + day
    ? Math.floor(time / day) * day : null;
}

function bingPeriod(reports, days, queries) {
  const endAt = reports.at(-1).time;
  const period = reports.filter(row => row.time >= endAt - (days - 1) * day);
  const clicks = period.reduce((total, row) => total + row.clicks, 0);
  const impressions = period.reduce((total, row) => total + row.impressions, 0);
  const trend = period.map(row => ({ day: new Date(row.time).toISOString().slice(0, 10), value: row.clicks }));
  const grouped = new Map();
  for (const row of queries ?? []) {
    const time = reportingDay(row.Date);
    const value = metric(row.Clicks);
    const impressions = metric(row.Impressions);
    const name = typeof row.Query === 'string' ? row.Query.trim().slice(0, 160) : '';
    if (!name || value === null || impressions === null || time === null || time < endAt - (days - 1) * day || time > endAt) continue;
    const previous = grouped.get(name) ?? { name, value: 0, impressions: 0 };
    previous.value += value;
    previous.impressions += impressions;
    grouped.set(name, previous);
  }
  const topQueries = queries === null ? null : [...grouped.values()].sort((left, right) =>
    right.value - left.value || right.impressions - left.impressions).slice(0, 5);
  return { startAt: period[0].time, endAt, clicks, impressions, ctr: impressions > 0 ? clicks * 100 / impressions : null, trend, topQueries };
}

async function bingSite(name) {
  const report = operation => {
    const url = new URL('https://ssl.bing.com/webmaster/api.svc/json/' + operation);
    url.search = new URLSearchParams({ siteUrl: 'https://' + name + '/', apikey: process.env.HUB_BING_API_KEY });
    return fetchJson(url);
  };
  const [traffic, rankings] = await Promise.allSettled([report('GetRankAndTrafficStats'), report('GetQueryStats')]);
  if (traffic.status !== 'fulfilled' || !Array.isArray(traffic.value.d)) throw new Error('Invalid Bing response');
  const response = traffic.value;
  const queries = rankings.status === 'fulfilled' && Array.isArray(rankings.value.d) ? rankings.value.d : null;
  const daily = new Map();
  for (const row of response.d) {
    const time = reportingDay(row.Date);
    const clicks = metric(row.Clicks);
    const impressions = metric(row.Impressions);
    if (time !== null && clicks !== null && impressions !== null) daily.set(time, { time, clicks, impressions });
  }
  const reports = [...daily.values()].sort((left, right) => left.time - right.time);
  if (!reports.length) return { name, status: 'no-data' };
  return { name, status: 'available', periods: { '7d': bingPeriod(reports, 7, queries), '28d': bingPeriod(reports, 28, queries) } };
}

const loadBing = cache(async () => {
  const sites = await Promise.allSettled(bingSites.map(bingSite));
  if (sites.every(result => result.status === 'rejected')) throw new Error('No Bing sites reachable');
  return { sites: sites.map((result, index) => result.status === 'fulfilled'
    ? result.value : { name: bingSites[index], status: 'unavailable' }) };
}, 15 * 60_000);

async function series(configured, load) {
  if (!configured) return { status: 'not-configured' };
  try { return { status: 'available', ...await load() }; }
  catch { return { status: 'unavailable' }; }
}

export async function fetchAnalytics() {
  const [umami, bing] = await Promise.all([
    series(process.env.HUB_UMAMI_SHARE_ID, loadUmami),
    series(process.env.HUB_BING_API_KEY, loadBing)
  ]);
  return { umami, bing };
}
