import { request } from 'node:https';

const blogOrigin = 'https://blog.yaoxi.wiki';
const blogAdmin = blogOrigin + '/admin/';
const blogApi = 'https://blog-api.yaoxi.wiki';
const githubOwner = 'yaoxiovo';
const day = 86_400_000;

const projects = [
  { id: 'blog', name: 'Astro 博客', url: blogOrigin, host: 'blog.yaoxi.wiki', repo: 'astro', ownerDomain: 'yaoxi.wiki', subdomain: 'blog', analytics: true, admin: blogAdmin },
  { id: 'wallpapers', name: 'LumiTape 壁纸站', url: 'https://pic.yaoxi.wiki', host: 'pic.yaoxi.wiki', ownerDomain: 'yaoxi.wiki', subdomain: 'pic' },
  { id: 'hub', name: 'Yaoxi Hub', url: 'https://hub.yaoxi.wiki', host: 'hub.yaoxi.wiki', repo: 'douyin-bilibili-downloader', ownerDomain: 'yaoxi.wiki', subdomain: 'hub',
    services: [{ name: '自建 API', url: 'https://hub-api.yaoxi.wiki/auth/callback' }] },
  { id: 'home', name: 'Yaoxi 主站', url: 'https://yaoxi.wiki', host: 'yaoxi.wiki', ownerDomain: 'yaoxi.wiki', subdomain: '@', analytics: true }
];

const projectsById = new Map(projects.map(item => [item.id, item]));

function cache(load, interval) {
  let value;
  let expires = 0;
  let pending;
  return () => {
    if (expires > Date.now()) return Promise.resolve(value);
    pending ??= load().then(result => {
      value = result;
      expires = Date.now() + interval;
      return result;
    }).finally(() => { pending = undefined; });
    return pending;
  };
}

function probe(name, url) {
  return new Promise(resolve => {
    const started = performance.now();
    const failure = error => resolve({ name, url, status: 'unavailable',
      error: error.code || 'NETWORK_ERROR', checkedAt: Date.now() });
    const connector = request(url, { method: 'HEAD', signal: AbortSignal.timeout(6500) }, response => {
      const expiresAt = Date.parse(response.socket?.getPeerCertificate()?.valid_to || '');
      const latency = Math.round(performance.now() - started);
      resolve({ name, url, status: response.statusCode >= 200 && response.statusCode < 400
        ? latency > 2000 ? 'slow' : 'available' : 'unavailable',
      http: response.statusCode, latency, expiresAt: Number.isFinite(expiresAt) ? expiresAt : null, checkedAt: Date.now() });
      response.resume();
    });
    connector.on('error', failure);
    connector.end();
  });
}

const loadStatus = cache(() => Promise.all(projects.map(async project => {
  const endpoints = [{ name: '网站', url: project.url }, ...(project.services ?? [])];
  const checks = await Promise.all(endpoints.map(item => probe(item.name, item.url)));
  return { id: project.id, name: project.name, url: project.url, checks };
})), 4 * 60_000);

export function startMonitoring() {
  loadStatus().catch(() => {});
  setInterval(() => loadStatus().catch(() => {}), 5 * 60_000).unref();
}

export async function publicStatus() {
  return { items: await loadStatus() };
}

async function json(url, options = {}) {
  const response = await fetch(url, { ...options, redirect: 'error', signal: AbortSignal.timeout(7000) });
  if (!response.ok) throw Object.assign(new Error('上游 HTTP ' + response.status), { status: response.status });
  return response.json();
}

const recentWallpapers = cache(async () => {
  const source = await json('https://download.yaoxi.wiki/api/v1/wallpapers?limit=3&sort=newest');
  if (!source.success || !Array.isArray(source.data)) throw new Error('壁纸目录不可用');
  return { items: source.data.slice(0, 3).filter(item =>
    typeof item.id === 'string' && /^[a-f0-9-]{36}$/i.test(item.id)).map(item => ({
    title: typeof item.title === 'string' ? item.title.slice(0, 120) : '',
    createdAt: item.createdAt,
    image: 'https://download.yaoxi.wiki/media/' + item.id,
    url: 'https://pic.yaoxi.wiki/wallpaper/' + item.id
  })) };
}, 10 * 60_000);

export function publicWallpapers() {
  return recentWallpapers();
}

const readBlog = cache(async () => {
  const response = await fetch(blogAdmin, { redirect: 'error', signal: AbortSignal.timeout(7000) });
  if (!response.ok) throw new Error('博客后台暂不可用');
  const html = await response.text();
  const script = html.match(/<script\b[^>]*\bid=["']initial-posts-data["'][^>]*>([\s\S]*?)<\/script>/i);
  const items = script && JSON.parse(script[1]);
  if (!Array.isArray(items)) throw new Error('博客文章列表暂不可用');
  const metadata = items.filter(item => typeof item.title === 'string' && typeof item.slug === 'string')
    .map(({ title, slug, published, draft }) => ({ title, slug, published, draft }));
  const drafts = metadata.filter(item => item.draft).slice(0, 10);
  const published = metadata.filter(item => !item.draft && Number.isFinite(Date.parse(item.published)))
    .sort((left, right) => Date.parse(right.published) - Date.parse(left.published)).slice(0, 5);
  return { drafts, published };
}, 5 * 60_000);

async function comments(path, options = {}) {
  if (!process.env.HUB_BLOG_ADMIN_TOKEN) throw new Error('博客审核尚未配置');
  return json(blogApi + '/api/comments/' + path, {
    ...options, headers: {
      Authorization: 'Bearer ' + process.env.HUB_BLOG_ADMIN_TOKEN, 'Content-Type': 'application/json'
    }
  });
}

const summary = result => result.status === 'fulfilled'
  ? { status: 'available', ...result.value } : { status: 'unavailable' };

export function projectOperations(github, tencent) {
  const infrastructure = cache(async () => {
    const [dns, edge] = await Promise.allSettled([
      tencent('dnspod', 'DescribeDomainList', { Keyword: 'yaoxi.wiki', Limit: 50 }),
      tencent('teo', 'DescribeZones', { Limit: 100 })
    ]);
    return { dns, edge };
  }, 5 * 60_000);

  const build = new Map(projects.filter(item => item.repo).map(project => [project.id, cache(async () => {
    const prefix = '/repos/' + githubOwner + '/' + project.repo;
    const runsPath = project.id === 'hub'
      ? '/actions/workflows/yaoxi-hub.yml/runs?per_page=10&branch=main'
      : '/actions/runs?per_page=10';
    const [actions, release] = await Promise.allSettled([
      github(prefix + runsPath), github(prefix + '/releases?per_page=10')
    ]);
    const runs = actions.status === 'fulfilled' && Array.isArray(actions.value.workflow_runs)
      ? actions.value.workflow_runs : null;
    const releases = release.status === 'fulfilled' && Array.isArray(release.value)
      ? release.value.filter(item => !item.draft && (project.id !== 'hub' || item.tag_name.startsWith('yaoxi-hub-v'))) : null;
    const failed = runs?.find(run => run.conclusion === 'failure');
    let failureStep = null;
    if (failed) {
      try {
        const result = await github(prefix + '/actions/runs/' + failed.id + '/jobs');
        const job = result.jobs?.find(entry => entry.conclusion === 'failure');
        const step = job?.steps?.find(entry => entry.conclusion === 'failure');
        if (job) failureStep = { name: step ? job.name + ' / ' + step.name : job.name, url: job.html_url };
      } catch {}
    }
    const workflow = run => ({ name: run.name, title: run.display_title,
      status: run.status, conclusion: run.conclusion, updatedAt: run.updated_at, url: run.html_url });
    const latest = releases?.[0];
    return {
      repoUrl: 'https://github.com/' + githubOwner + '/' + project.repo,
      latestRun: runs?.length ? workflow(runs[0]) : null,
      latestFailure: failed ? { ...workflow(failed), step: failureStep } : null,
      release: latest ? { tag: latest.tag_name, publishedAt: latest.published_at, url: latest.html_url } : null,
      workflowStatus: runs === null ? 'unavailable' : 'available',
      releaseStatus: releases === null ? 'unavailable' : 'available'
    };
  }, 10 * 60_000)]));

  async function dnsRecord(project, source) {
    if (source.status !== 'fulfilled') return { status: 'unavailable' };
    const domain = source.value.DomainList?.find(item => item.Name === project.ownerDomain);
    if (!domain) return { status: 'no-data' };
    try {
      const result = await tencent('dnspod', 'DescribeRecordList', {
        Domain: project.ownerDomain, DomainId: domain.DomainId, Subdomain: project.subdomain, Limit: 30
      });
      const records = (result.RecordList ?? []).filter(item => item.Name === project.subdomain)
        .map(({ Type, Value, TTL, Status }) => ({ type: Type, value: Value, ttl: TTL, status: Status }));
      return { status: 'available', domain, records };
    } catch (error) {
      if (error.message.includes('NoDataOfRecord')) return { status: 'available', domain, records: [] };
      return { status: 'unavailable', domain };
    }
  }

  async function edgeHost(project, source) {
    if (source.status !== 'fulfilled') return { status: 'unavailable' };
    const matches = (source.value.Zones ?? []).filter(zone => project.host === zone.ZoneName || project.host.endsWith('.' + zone.ZoneName));
    if (!matches.length) return { status: 'no-data' };
    const results = await Promise.allSettled(matches.map(async site => {
      const result = await tencent('teo', 'DescribeAccelerationDomains', {
        ZoneId: site.ZoneId, Filters: [{ Name: 'domain-name', Values: [project.host], Fuzzy: false }], Limit: 10
      });
      const domain = result.AccelerationDomains?.find(item => item.DomainName === project.host);
      return domain ? { site, enabled: domain.DomainStatus } : null;
    }));
    const found = results.find(item => item.status === 'fulfilled' && item.value);
    return found ? { status: 'available', ...found.value } :
      { status: results.some(item => item.status === 'rejected') ? 'unavailable' : 'no-data' };
  }

  return {
    projects: async () => ({ items: projects.map(({ id, name, url }) => ({ id, name, url })) }),
    project: async data => {
      const project = projectsById.get(data.id);
      if (!project) throw Object.assign(new Error('项目不存在'), { status: 404 });
      const [{ dns, edge }, status, githubInfo] = await Promise.all([
        infrastructure(), loadStatus(), build.has(project.id) ? build.get(project.id)() : null
      ]);
      const [dnsInfo, edgeInfo] = await Promise.all([dnsRecord(project, dns), edgeHost(project, edge)]);
      return { project, monitor: status.find(item => item.id === project.id), github: githubInfo, dns: dnsInfo, edge: edgeInfo };
    },
    blog: async () => {
      const [posts, moderation] = await Promise.allSettled([readBlog(), comments('pending')]);
      const pending = moderation.status === 'fulfilled' && Array.isArray(moderation.value.items)
        ? moderation.value.items.slice(0, 30).map(({ id, kind, author, body, ts }) => ({ id, kind, author, body, ts })) : null;
      return { posts: summary(posts), pending, pendingStatus: moderation.status };
    },
    moderate: async data => {
      if (!/^[a-z0-9]{6,24}$/.test(data.id) || !['approve', 'reject'].includes(data.action)) {
        throw Object.assign(new Error('无效的审核操作'), { status: 400 });
      }
      const result = await comments('moderate', { method: 'POST', body: JSON.stringify({ id: data.id, action: data.action }) });
      if (!result.ok) throw new Error('审核没有成功');
      return { ok: true };
    },
    projectAlerts: async () => {
      const [status, runs] = await Promise.all([
        loadStatus(), Promise.allSettled([...build].map(async ([projectId, load]) => ({ projectId, info: await load() })))
      ]);
      const alerts = [];
      for (const project of status) for (const check of project.checks) {
        if (check.status !== 'available') {
          const label = check.status === 'slow' ? '响应较慢' : '访问异常';
          alerts.push({ projectId: project.id, title: project.name + ' · ' + label,
            detail: check.name + ' · ' + (check.latency === undefined ? check.error : check.latency + ' ms · HTTP ' + check.http),
            time: new Date(check.checkedAt).toISOString() });
        }
        if (check.expiresAt && check.expiresAt - Date.now() <= 14 * day) {
          alerts.push({ projectId: project.id, title: project.name + ' · 证书到期提醒',
            detail: new Date(check.expiresAt).toISOString().slice(0, 10), time: new Date(check.checkedAt).toISOString() });
        }
      }
      for (const result of runs) {
        if (result.status !== 'fulfilled') continue;
        const { projectId, info } = result.value;
        const project = projectsById.get(projectId);
        const failure = info.latestFailure;
        const release = info.release;
        if (failure && Date.parse(failure.updatedAt) >= Date.now() - 7 * day) {
          alerts.push({ title: project.name + ' · 构建失败', detail: failure.step?.name ?? failure.title,
            time: failure.updatedAt, url: failure.step?.url ?? failure.url });
        }
        if (release && Date.parse(release.publishedAt) >= Date.now() - 14 * day) {
          alerts.push({ title: project.name + ' · 新版本', detail: release.tag, time: release.publishedAt, url: release.url });
        }
      }
      return alerts;
    }
  };
}
