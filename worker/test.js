import worker from './index.js';

// Node 24 具备 Request/Response/fetch 全局，可直接调用 Worker 的 fetch handler 做冒烟测试
const base = 'http://wk.test';
const call = (path, init, env) => worker.fetch(new Request(base + path, init), env || {});

const ok = (name, cond, extra) => console.log((cond ? '✓' : '✗ FAIL'), name, extra || '');

(async () => {
  // 1. 首页
  const page = await call('/');
  const pageText = await page.text();
  ok('GET / 返回页面', page.status === 200 && pageText.includes('快捷爬取'), page.headers.get('content-type'));

  // 2. 抖音解析（无 token）
  const dyRes = await call('/api/info', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ url: '7.43 复制打开抖音极速版 https://v.douyin.com/59JSFMQOqxk/' }),
  });
  const dy = await dyRes.json();
  ok('POST /api/info 抖音', dy.ok && dy.stream.quality === '1080p', dy.info && dy.info.title.slice(0, 20));

  // 3. B站解析
  const blRes = await call('/api/info', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ url: 'https://www.bilibili.com/video/BV1n5na61Eev?p=2' }),
  });
  const bl = await blRes.json();
  ok('POST /api/info B站分P', bl.ok && bl.info.parts.length === 2, bl.stream && bl.stream.quality);

  // 4. 下载转发（流式）
  const dl = await call('/api/dl?key=' + bl.key);
  const head = Buffer.from((await dl.arrayBuffer()).slice(0, 8));
  ok('GET /api/dl 转发MP4', dl.status === 200 && head.slice(4, 8).toString() === 'ftyp', (dl.headers.get('content-disposition') || '').slice(0, 40));

  // 5. 封面代理 + 类型嗅探
  const px = await call('/api/proxy?u=' + encodeURIComponent(dy.info.cover));
  ok('GET /api/proxy 封面嗅探', px.status === 200 && (px.headers.get('content-type') || '').startsWith('image/'), px.headers.get('content-type'));

  // 6. SSRF 拦截
  const ssrf = await call('/api/proxy?u=' + encodeURIComponent('http://127.0.0.1:8787/'));
  ok('SSRF 白名单拦截', ssrf.status === 403);

  // 7. token 鉴权
  const env = { ACCESS_TOKEN: 'sec123' };
  const noTk = await call('/api/info', { method: 'POST', body: '{}' }, env);
  ok('无 token → 401', noTk.status === 401);
  const withTk = await call('/api/info?token=sec123', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ url: 'BV1U3n46GEzY' }),
  }, env);
  const wj = await withTk.json();
  ok('带 token → 放行', withTk.status === 200 && wj.ok, wj.info && wj.info.title.slice(0, 16));

  // 8. 坏 key
  const bad = await call('/api/dl?key=deadbeef');
  ok('坏 key → 404', bad.status === 404);
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
