/**
 * Cloudflare Worker 版 — 抖音 / B站 快捷爬取（单文件，可直接粘贴到 Dashboard 部署）
 *
 * 部署：
 *   1. Cloudflare Dashboard → Workers & Pages → Create → Worker → 编辑代码 → 全部替换为本文件 → Deploy
 *   2. Settings → Variables → 添加 ACCESS_TOKEN = 你的访问口令（强烈建议；不设则任何人可用你的 Worker）
 *   3. 打开 https://xxx.workers.dev ，首次输入口令即可
 *
 * 端点（与本地 server.js 完全同构）：
 *   GET  /            页面（内嵌，无静态资源依赖）
 *   POST /api/info    解析作品 → 元数据 + 流信息 + key
 *   GET  /api/dl      代抓视频流转发给浏览器（流式，不落盘）
 *   GET  /api/proxy   图集/封面代理（域名白名单防滥用）
 *
 * 说明：Workers 出口是 Cloudflare 边缘 IP，若抖音接口对你的区域限制可能失败；
 *      CPU 时间免费版有限，解析为 IO 等待为主，实测可行。
 */

// ==================== 解析核心（与 core.js 同源移植） ====================
const DY_APP_UA = 'Aweme/8.8.0 (iPhone; iOS 7.0; Scale/2.00) CFNetwork/758.3.1 Darwin/15.4.0';
const DY_FEED_HOSTS = ['https://aweme.snssdk.com', 'https://api.amemv.com'];
const DY_PLAY_HOSTS = ['https://aweme.snssdk.com', 'https://api.amemv.com', 'https://api-play-hl.amemv.com'];
const DY_FEED_PARAMS = 'version_code=8.8.0&app_name=aweme&channel=App%20Store&device_platform=iphone&device_type=iPhone8,1&os_version=12.4&aid=1128&ac=WIFI';
const DY_RATIOS = ['1080p', '720p', '540p'];
const BILI_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
const BILI_HEADERS = { 'user-agent': BILI_UA, referer: 'https://www.bilibili.com/' };
const BILI_HOSTS = ['https://api.biliapi.net', 'https://api.bilibili.com'];

function genBuvid3() {
  const s4 = () => Math.floor((1 + Math.random()) * 0x10000).toString(16).substring(1);
  return `${s4()}${s4()}-${s4()}-${s4()}-${s4()}-${s4()}${s4()}${s4()}infoc`;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const jitter = () => sleep(500 + Math.random() * 700);

function sanitize(name) {
  return (name || '')
    .replace(/#/g, '')
    .replace(/[\\/:*?"<>|\r\n\t]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 50);
}

function isDouyinInput(input) {
  return /douyin\.com|iesdouyin\.com/i.test(input) || /^(7\d{17,19})$/.test(input.trim());
}
function isBiliInput(input) {
  return /bilibili\.com|b23\.tv|^(av\d{5,15})$/i.test(input.trim()) || /BV[0-9A-Za-z]{10}/.test(input);
}

async function douyinResolveId(input) {
  const trimmed = input.trim();
  const directId = trimmed.match(/^(7\d{17,19})$/);
  if (directId) return directId[1];
  const longUrl = trimmed.match(/https?:\/\/[^\s]*?douyin\.com[^\s]*/i);
  if (!longUrl) throw new Error('输入里找不到抖音链接或视频ID');
  let url = longUrl[0].replace(/[)），，。]+$/, '');
  for (let hop = 0; hop < 5 && /v\.douyin\.com\//.test(url); hop++) {
    const res = await fetch(url, { method: 'GET', redirect: 'manual', headers: { 'user-agent': DY_APP_UA } });
    const loc = res.headers.get('location');
    if (!loc) break;
    url = new URL(loc, url).toString();
    await sleep(150);
  }
  const m =
    url.match(/\/video\/(\d{15,20})/) ||
    url.match(/\/(?:note|notes|slides|gallery)\/(\d{15,20})/) ||
    url.match(/[?&]modal_id=(\d{15,20})/) ||
    url.match(/[?&]item_ids?=(\d{15,20})/) ||
    url.match(/\/(\d{15,20})\/?$/);
  if (!m) {
    if (/^https?:\/\/www\.douyin\.com\/?$/.test(url))
      throw new Error('抖音短链无效（服务端把它兜底跳到了主页）：多半是复制时丢了字符或链接已过期');
    throw new Error('抖音链接重定向后未能提取视频ID：' + url);
  }
  return m[1];
}

async function douyinFeed(awemeId) {
  let lastErr;
  for (const host of DY_FEED_HOSTS) {
    for (let attempt = 0; attempt < 3; attempt++) {
      try {
        const res = await fetch(host + '/aweme/v1/feed/?aweme_id=' + awemeId + '&' + DY_FEED_PARAMS, { headers: { 'user-agent': DY_APP_UA } });
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const json = await res.json();
        const list = json.aweme_list || [];
        const item = list.find((x) => String(x.aweme_id) === awemeId);
        if (item) return item;
        throw new Error(list.length ? '作品已删除或不可见（接口只返回了推荐内容）' : '接口返回空（status_code=' + json.status_code + '）');
      } catch (e) {
        lastErr = e;
        await sleep(800 * (attempt + 1));
      }
    }
    await jitter();
  }
  throw new Error('抖音 feed 接口两套域名均失败：' + lastErr.message);
}

function pickBrowserCover(video, images) {
  const friendly = /\.(webp|jpe?g|png|gif)(\?|$)/i;
  for (const pool of [video?.cover?.url_list, video?.origin_cover?.url_list]) {
    const hit = (pool || []).find((u) => u.includes('~tplv') && friendly.test(u));
    if (hit) return hit;
  }
  const dyn = video?.dynamic_cover?.url_list?.[0];
  if (dyn) return dyn;
  if (images && images[0]) return images[0].urls[0] || '';
  return (video?.cover?.url_list || [])[0] || '';
}

async function resolveDouyin(input) {
  const awemeId = await douyinResolveId(input);
  const item = await douyinFeed(awemeId);
  const images = (item.images || []).map((im) => ({
    urls: [...(im.download_url_list || []), ...(im.url_list || [])],
  }));
  return {
    platform: 'douyin',
    id: String(item.aweme_id),
    type: images.length ? 'images' : 'video',
    title: item.desc || String(item.aweme_id),
    author: item.author?.nickname || '',
    cover: pickBrowserCover(item.video, images),
    durationMs: item.video?.duration || 0,
    createTime: item.create_time || 0,
    stats: { like: item.statistics?.digg_count, comment: item.statistics?.comment_count, fav: item.statistics?.collect_count },
    images,
    videoUri: item.video?.play_addr?.uri || '',
    videoMeta: { width: item.video?.width, height: item.video?.height },
    fallbackUrls: (item.video?.play_addr?.url_list || []).slice(),
    filename: sanitize(item.desc) + '_' + item.aweme_id,
  };
}

async function douyinCandidates(videoUri) {
  const seen = new Map();
  for (const ratio of DY_RATIOS) {
    for (const host of DY_PLAY_HOSTS) {
      const url = host + '/aweme/v1/play/?video_id=' + videoUri + '&ratio=' + ratio + '&line=0';
      try {
        const res = await fetch(url, { headers: { 'user-agent': DY_APP_UA, range: 'bytes=0-0' }, redirect: 'follow' });
        if (res.status !== 206 && res.status !== 200) continue;
        const cr = res.headers.get('content-range');
        const total = cr ? Number(cr.split('/')[1]) : Number(res.headers.get('content-length') || 0);
        if (total > 0) seen.set(res.url + '|' + total, { url, total, ratio });
      } catch (_) { /* 下一个候选 */ }
      await sleep(120);
    }
  }
  return [...seen.values()].sort((a, b) => b.total - a.total);
}

async function douyinPickStream(info, { min } = {}) {
  if (!info.videoUri) throw new Error('该作品没有视频轨道');
  await jitter();
  const cands = await douyinCandidates(info.videoUri);
  const pool = cands.length ? cands : info.fallbackUrls.map((u) => ({ url: u, total: 0, ratio: 'fallback' }));
  const pick = min ? pool[pool.length - 1] : pool[0];
  return { url: pick.url, totalBytes: pick.total, quality: pick.ratio, headers: { 'user-agent': DY_APP_UA } };
}

async function biliNormalize(input) {
  const trimmed = input.trim();
  const av = trimmed.match(/^(av\d{5,15})$/i);
  if (av) return { aid: av[1].slice(2), p: 1 };
  const urlMatch = trimmed.match(/https?:\/\/[^\s]*/i);
  let url = urlMatch ? urlMatch[0].replace(/[)），，。]+$/, '') : null;
  if (url && /b23\.tv/.test(url)) {
    for (let hop = 0; hop < 4; hop++) {
      const res = await fetch(url, { redirect: 'manual', headers: BILI_HEADERS });
      const loc = res.headers.get('location');
      if (loc && res.status.toString().startsWith('3')) {
        url = new URL(loc, url).toString();
        if (!/b23\.tv/.test(url)) break;
        continue;
      }
      const bodyText = (await res.text()).slice(0, 65536);
      const bvInPage = bodyText.match(/BV[0-9A-Za-z]{10}/);
      if (bvInPage) url = 'https://www.bilibili.com/video/' + bvInPage[0];
      else if (/"code":\s*-404/.test(bodyText)) throw new Error('b23.tv 短链已失效（"啥都木有"），请重新分享获取新链接');
      break;
    }
  }
  const src = url || trimmed;
  const bv = src.match(/BV[0-9A-Za-z]{10}/);
  const aid = src.match(/[?&/?]av(\d{5,15})/i);
  const p = Number((src.match(/[?&]p=(\d+)/) || [])[1] || 1);
  if (bv) return { bvid: bv[0], p };
  if (aid) return { aid: aid[1], p };
  throw new Error('没认出 B站链接：支持 BV号 / av号 / b23.tv 短链 / 完整网址');
}

async function biliView({ bvid, aid }) {
  const q = bvid ? 'bvid=' + bvid : 'aid=' + aid;
  const headers = {
    ...BILI_HEADERS,
    cookie: 'buvid3=' + genBuvid3() + '; b_nut=' + Math.floor(Date.now() / 1000) + ';',
  };
  const errs = [];
  for (const host of BILI_HOSTS) {
    try {
      const res = await fetch(host + '/x/web-interface/view?' + q, { headers });
      const text = await res.text();
      if (!res.ok) {
        if (res.status === 412) {
          throw new Error('B站拦截了 Cloudflare 海外边缘机房 IP (HTTP 412)。抖音正常可用，B站请使用安卓 APK 或本地 Node 版解析下载');
        }
        errs.push(`${host} HTTP ${res.status}`);
        continue;
      }
      const j = JSON.parse(text);
      if (j.code === 0 && j.data) return j.data;
      errs.push(`${host} code ${j.code}`);
    } catch (e) {
      if (e.message.includes('412')) throw e;
      errs.push(`${host} err ${e.message}`);
    }
  }

  throw new Error('B站作品信息获取失败: ' + errs.join('; '));
}

async function resolveBilibili(input) {
  const { bvid, aid, p } = await biliNormalize(input);
  const v = await biliView({ bvid, aid });
  const pages = v.pages || [{ cid: v.cid, part: v.title, page: 1, duration: v.duration }];
  const page = pages[Math.min(Math.max(p, 1), pages.length) - 1];
  return {
    platform: 'bilibili',
    id: v.bvid,
    type: 'video',
    title: pages.length > 1 ? v.title + ' P' + page.page + ':' + page.part : v.title,
    author: v.owner?.name || '',
    cover: v.pic || '',
    durationMs: (page.duration || v.duration || 0) * 1000,
    createTime: v.pubdate || 0,
    stats: { like: v.stat?.like, view: v.stat?.view, fav: v.stat?.favorite },
    bili: { bvid: v.bvid, cid: page.cid, page: page.page },
    parts: pages.map((x) => ({ page: x.page, title: x.part, cid: x.cid, durationMs: (x.duration || 0) * 1000 })),
    filename: sanitize(pages.length > 1 ? v.title + '_P' + page.page + '_' + page.part : v.title) + '_' + v.bvid,
  };
}

async function biliPickStream(info) {
  const { bvid, cid } = info.bili;
  const headers = {
    ...BILI_HEADERS,
    cookie: 'buvid3=' + genBuvid3() + '; b_nut=' + Math.floor(Date.now() / 1000) + ';',
  };
  let lastErr;
  for (const host of BILI_HOSTS) {
    try {
      const pu = host + '/x/player/playurl?bvid=' + bvid + '&cid=' + cid + '&qn=80&fnval=1&platform=html5&high_quality=1';
      const res = await fetch(pu, { headers });
      if (!res.ok) continue;
      const text = await res.text();
      const j = JSON.parse(text);
      if (j.code !== 0) throw new Error('B站 playurl 接口: ' + (j.message || j.code));
      const durl = j.data.durl || [];
      if (!durl.length) throw new Error('B站未返回可下载地址（免登录上限 720p，部分番剧/课程不开放）');
      const seg = durl[0];
      const urls = [seg.url, ...(seg.backup_url || [])];
      return {
        url: urls[0],
        totalBytes: seg.size || 0,
        quality: j.data.quality >= 64 ? '720p' : String(j.data.quality),
        headers: headers,
        backupUrls: urls.slice(1),
        multiSegment: durl.length > 1 ? durl.length : 0,
      };
    } catch (e) {
      lastErr = e;
    }
  }
  throw new Error('B站流获取失败: ' + (lastErr ? lastErr.message : '未知错误'));
}

async function resolve(input) {
  const t = input.trim();
  if (isBiliInput(t)) return resolveBilibili(t);
  if (isDouyinInput(t)) return resolveDouyin(t);
  throw new Error('不支持的链接：目前支持 抖音（v.douyin.com / 视频ID）和 B站（BV / av / b23.tv）');
}
async function pickStream(info, opts = {}) {
  return info.platform === 'bilibili' ? biliPickStream(info) : douyinPickStream(info, opts);
}

// ==================== Worker 服务层 ====================
const cache = new Map(); // 每实例内存缓存（多实例间不共享，够用：key 用完即弃）
const CACHE_TTL = 15 * 60 * 1000;
function cacheSet(v) {
  const k = Math.random().toString(16).slice(2) + Date.now().toString(16);
  cache.set(k, { ...v, ts: Date.now() });
  if (cache.size > 50) cache.delete(cache.keys().next().value);
  return k;
}
function cacheGet(k) {
  const v = cache.get(k);
  if (!v || Date.now() - v.ts > CACHE_TTL) return null;
  return v;
}

const PROXY_HOSTS = /\.(douyinpic|douyinvod|douyin|amemv|snssdk|bilivideo|biliapi|bilibili|hdslb|akamaized|bytecdn|bytetos|bytegoofy|ibyteimg|pstatp)\.(com|cn|net)$/i;
const ADDR_RE = /^\d{1,3}(\.\d{1,3}){3}$/;
function isAllowedUrl(u) {
  try {
    const p = new URL(u);
    if (!/^https?:$/.test(p.protocol)) return false;
    if (ADDR_RE.test(p.hostname)) return false;
    return PROXY_HOSTS.test(p.hostname);
  } catch (_) { return false; }
}

function cd(name) {
  const ascii = name.replace(/[^\x20-\x7e]/g, '_').replace(/["\\]/g, '_');
  return 'attachment; filename="' + ascii + '"; filename*=UTF-8\'\'' + encodeURIComponent(name);
}

function sniffType(b) {
  const ascii = (s, e) => { let t = ''; for (let i = s; i < e; i++) t += String.fromCharCode(b[i]); return t; };
  if (b.length >= 12 && ascii(0, 4) === 'RIFF' && ascii(8, 12) === 'WEBP') return 'image/webp';
  if (b.length >= 3 && b[0] === 0xff && b[1] === 0xd8) return 'image/jpeg';
  if (b.length >= 8 && b[0] === 0x89 && b[1] === 0x50) return 'image/png';
  if (b.length >= 4 && b[2] === 0x66 && b[3] === 0x74) return 'video/mp4';
  return 'application/octet-stream';
}

const json = (obj, status) => new Response(JSON.stringify(obj), {
  status: status || 200,
  headers: { 'content-type': 'application/json; charset=utf-8' },
});

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const token = env.ACCESS_TOKEN || '';
    const gotToken = url.searchParams.get('token') || request.headers.get('x-token') || '';

    // 页面本身不做 token 校验（无敏感信息），API 才校验
    if (url.pathname === '/' || url.pathname === '/index.html') {
      return new Response(PAGE_HTML, { headers: { 'content-type': 'text/html; charset=utf-8' } });
    }

    if (token && gotToken !== token) {
      return json({ ok: false, error: 'token 无效：请在页面右上角设置正确的访问口令' }, 401);
    }

    // ---- 解析 ----
    if (request.method === 'POST' && url.pathname === '/api/info') {
      try {
        const { url: target } = await request.json();
        if (!target || typeof target !== 'string') throw new Error('缺少 url 字段');
        const info = await resolve(target);
        let stream = null;
        if (info.type === 'video') stream = await pickStream(info);
        const key = cacheSet({ info, stream });
        return json({
          ok: true,
          key,
          info: {
            platform: info.platform, id: info.id, type: info.type, title: info.title,
            author: info.author, cover: info.cover, durationMs: info.durationMs, stats: info.stats,
            parts: info.parts || null,
            imagesCount: info.images ? info.images.length : 0,
            images: (info.images || []).map((im) => im.urls.find(Boolean)).filter(Boolean),
            filename: info.filename,
          },
          stream: stream && { quality: stream.quality, totalBytes: stream.totalBytes, multiSegment: stream.multiSegment || 0 },
        });
      } catch (e) {
        return json({ ok: false, error: e.message }, 400);
      }
    }

    // ---- 视频流转发（流式直通）----
    if (request.method === 'GET' && url.pathname === '/api/dl') {
      const entry = cacheGet(url.searchParams.get('key'));
      if (!entry || !entry.stream) return json({ ok: false, error: '缓存过期，请重新解析' }, 404);
      try {
        const upstream = await fetch(entry.stream.url, { headers: entry.stream.headers, redirect: 'follow' });
        if (!upstream.ok) throw new Error('上游 HTTP ' + upstream.status);
        const h = {
          'content-type': 'video/mp4',
          'content-disposition': cd(entry.info.filename + '.mp4'),
        };
        const len = upstream.headers.get('content-length');
        if (len) h['content-length'] = len;
        return new Response(upstream.body, { headers: h });
      } catch (e) {
        return json({ ok: false, error: e.message }, 502);
      }
    }

    // ---- 图片代理（白名单 + 首块嗅探）----
    if (request.method === 'GET' && url.pathname === '/api/proxy') {
      const target = url.searchParams.get('u');
      const name = (url.searchParams.get('n') || 'media').replace(/[\\/:*?"<>|]/g, '_');
      if (!target || !isAllowedUrl(target)) return new Response('forbidden', { status: 403 });
      try {
        const upstream = await fetch(target, { headers: { 'user-agent': 'Mozilla/5.0', referer: 'https://www.bilibili.com/' }, redirect: 'follow' });
        if (!upstream.ok) throw new Error('HTTP ' + upstream.status);
        const [a, b] = upstream.body.tee();
        const reader = a.getReader();
        const { value } = await reader.read();
        reader.cancel();
        const h = { 'content-type': value ? sniffType(value) : 'application/octet-stream', 'content-disposition': cd(name) };
        const len = upstream.headers.get('content-length');
        if (len) h['content-length'] = len;
        return new Response(b, { headers: h });
      } catch (e) {
        return new Response(e.message, { status: 502 });
      }
    }

    return json({ ok: false, error: 'not found' }, 404);
  },
};

// ==================== 内嵌页面（无模板字面量，纯字符串拼接） ====================
const PAGE_HTML = [
'<!DOCTYPE html>',
'<html lang="zh-CN"><head><meta charset="utf-8">',
'<meta name="viewport" content="width=device-width, initial-scale=1">',
'<title>快捷爬取 · 抖音 / B站（Workers）</title>',
'<style>',
'  :root{--bg:#0f1115;--card:#171a21;--card2:#1d212b;--line:#2a2f3c;--tx:#e8eaf0;--tx2:#9aa3b5;--acc:#4f8cff;--acc2:#7c5cff;--ok:#3ecf8e;--dy:#fe2c55;--bili:#00a1d6}',
'  *{box-sizing:border-box;margin:0;padding:0}',
'  body{background:radial-gradient(1200px 500px at 50% -100px,#1b2233 0%,var(--bg) 60%);color:var(--tx);font:15px/1.6 "Segoe UI","Microsoft YaHei",system-ui,sans-serif;min-height:100vh;display:flex;flex-direction:column;align-items:center}',
'  header{margin:42px 16px 10px;text-align:center}',
'  header h1{font-size:26px;letter-spacing:1px}',
'  header h1 .g{background:linear-gradient(90deg,var(--acc),var(--acc2));-webkit-background-clip:text;background-clip:text;color:transparent}',
'  header p{color:var(--tx2);margin-top:6px;font-size:13px}',
'  main{width:min(720px,94vw)}',
'  .search{display:flex;gap:10px;margin:22px 0 8px}',
'  .search input{flex:1;background:var(--card);border:1px solid var(--line);color:var(--tx);border-radius:12px;padding:13px 16px;font-size:15px;outline:none}',
'  .search input:focus{border-color:var(--acc)}',
'  .search button{background:linear-gradient(90deg,var(--acc),var(--acc2));color:#fff;border:0;border-radius:12px;padding:0 26px;font-size:15px;cursor:pointer;font-weight:600}',
'  .search button:disabled{opacity:.55;cursor:wait}',
'  .tk{display:flex;gap:8px;justify-content:flex-end;margin-bottom:4px}',
'  .tk input{background:var(--card);border:1px solid var(--line);color:var(--tx);border-radius:8px;padding:5px 10px;font-size:13px;width:180px;outline:none}',
'  .tk button{background:var(--card2);border:1px solid var(--line);color:var(--tx2);border-radius:8px;padding:5px 12px;font-size:13px;cursor:pointer}',
'  .hint{color:var(--tx2);font-size:12.5px;text-align:center;margin-bottom:14px}',
'  .hint b.dy{color:var(--dy)}.hint b.bl{color:var(--bili)}',
'  .status{text-align:center;color:var(--tx2);padding:30px 0;font-size:14px}',
'  .status.err{color:#ff7b72}',
'  .card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:18px;display:flex;gap:16px}',
'  .card .cover{width:168px;height:100px;object-fit:cover;border-radius:10px;background:var(--card2);flex-shrink:0}',
'  .card .body{flex:1;min-width:0;display:flex;flex-direction:column}',
'  .badge{display:inline-block;font-size:11px;font-weight:700;border-radius:5px;padding:1px 7px;margin-right:8px;vertical-align:2px}',
'  .badge.dy{background:var(--dy);color:#fff}.badge.bl{background:var(--bili);color:#fff}',
'  .title{font-size:15.5px;font-weight:600;line-height:1.45;word-break:break-all}',
'  .meta{color:var(--tx2);font-size:12.5px;margin-top:4px}',
'  .q{margin-top:auto;padding-top:10px;font-size:13px;color:var(--tx2)}',
'  .q b{color:var(--ok);font-weight:600}',
'  .actions{display:flex;gap:10px;margin-top:12px;flex-wrap:wrap}',
'  .actions a,.actions button{text-decoration:none;border:0;border-radius:10px;padding:9px 20px;font-size:14px;cursor:pointer;font-weight:600;color:#fff}',
'  .btn-main{background:linear-gradient(90deg,var(--acc),var(--acc2))}',
'  .parts{display:flex;flex-wrap:wrap;gap:6px;margin-top:10px}',
'  .parts button{background:var(--card2);border:1px solid var(--line);color:var(--tx2);border-radius:7px;padding:2px 10px;font-size:12px;cursor:pointer}',
'  .parts button.on{color:#fff;border-color:var(--acc);background:#22304d}',
'  .gal{display:grid;grid-template-columns:repeat(auto-fill,minmax(96px,1fr));gap:8px;margin-top:12px}',
'  .gal img{width:100%;height:96px;object-fit:cover;border-radius:8px;background:var(--card2)}',
'  footer{margin:auto 0 26px;color:var(--tx2);font-size:12px;text-align:center}',
'  .spin{display:inline-block;width:13px;height:13px;border:2px solid var(--tx2);border-top-color:transparent;border-radius:50%;animation:r .8s linear infinite;vertical-align:-2px;margin-right:7px}',
'  @keyframes r{to{transform:rotate(360deg)}}',
'</style></head><body>',
'<header><h1>⚡ 快捷<span class="g">爬取</span>源视频 <span style="font-size:12px;color:var(--tx2)">Workers 版</span></h1>',
'<p>粘贴分享链接 → 解析 → 下载最高画质（无水印）</p></header>',
'<main>',
'  <div class="tk"><input id="tk" placeholder="访问口令（未设置可留空）"><button id="tkSave">保存口令</button></div>',
'  <div class="search"><input id="q" placeholder="粘贴抖音分享文案 / v.douyin.com 短链 / B站 BV·av·b23.tv 链接，回车解析" autofocus><button id="go">解析</button></div>',
'  <div class="hint">支持 <b class="dy">抖音</b>（含图集）与 <b class="bl">B站</b>（含分P）· B站免登录最高 720P · 仅个人学习备份，尊重原作者版权</div>',
'  <div class="status" id="status">等待输入链接…</div>',
'  <div id="result"></div>',
'</main>',
'<footer>Cloudflare Workers 边缘运行 · 建议设置 ACCESS_TOKEN 环境变量防滥用</footer>',
'<script>',
'var $=function(s){return document.querySelector(s)};',
'var input=$("q"),go=$("go"),status=$("status"),result=$("result"),tk=$("tk");',
'var T=localStorage.getItem("wk_token")||"";tk.value=T;',
'function api(p){return p+(p.indexOf("?")>=0?"&":"?")+"token="+encodeURIComponent(T)};',
'function fmtMB(n){return n>=1048576?(n/1048576).toFixed(2)+" MB":Math.ceil(n/1024)+" KB"};',
'function fmtDur(ms){var s=Math.round(ms/1000);return (s>=60?Math.floor(s/60)+"分":"")+(s%60)+"秒"};',
'function esc(s){return String(s==null?"":s).replace(/[&<>"]/g,function(c){return c==="&"?"&amp;":c==="<"?"&lt;":c===">"?"&gt;":"&quot;"})};',
'function setStatus(html,err){status.className="status"+(err?" err":"");status.innerHTML=html};',
'$("tkSave").onclick=function(){T=tk.value.trim();localStorage.setItem("wk_token",T);setStatus("口令已保存")};',
'async function run(){',
'  var v=input.value.trim();if(!v)return;',
'  go.disabled=true;result.innerHTML="";',
'  setStatus("<span class=spin></span>解析中…（抖音画质探测约需几秒）");',
'  try{',
'    var r=await fetch(api("/api/info"),{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({url:v})});',
'    var j=await r.json();',
'    if(r.status===401){setStatus("✗ "+j.error+"（右上角设置口令）",true);return}',
'    if(!j.ok)throw new Error(j.error||"解析失败");',
'    render(j);setStatus("");',
'  }catch(e){setStatus("✗ "+e.message,true)}finally{go.disabled=false}',
'}',
'function render(j){',
'  var info=j.info,stream=j.stream,isDy=info.platform==="douyin";',
'  var html="<div class=card>";',
'  if(info.cover)html+=\'<img class=cover src="\'+esc(api("/api/proxy?u="+encodeURIComponent(info.cover)))+\'" alt="封面">\';',
'  html+=\'<div class=body><div><span class="badge \'+(isDy?"dy":"bl")+\'">\'+(isDy?"抖音":"B站")+"</span>";',
'  if(info.type==="images")html+=\'<span class=badge style="background:#5c6370">图集 \'+info.imagesCount+" 张</span>";',
'  html+="</div><div class=title>"+esc(info.title)+"</div>";',
'  html+=\'<div class=meta>UP：\'+esc(info.author||"未知");',
'  if(info.durationMs)html+=" · "+fmtDur(info.durationMs);',
'  if(info.stats&&info.stats.like!=null)html+=" · 赞 "+info.stats.like;',
'  if(info.stats&&info.stats.view!=null)html+=" · 播放 "+info.stats.view;',
'  html+="</div><div class=q>";',
'  if(info.type==="video"){html+="画质 <b>"+esc(stream.quality)+"</b>";if(stream.totalBytes)html+=" · 约 "+fmtMB(stream.totalBytes);if(stream.multiSegment)html+=" · 免登录流共 "+stream.multiSegment+" 段，下载第 1 段"}',
'  else html+="点击图片可逐张保存";',
'  html+=\'</div><div class=actions id=acts></div>\';',
'  if(info.parts&&info.parts.length>1)html+=\'<div class=parts id=parts></div>\';',
'  if(info.type==="images")html+=\'<div class=gal id=gal></div>\';',
'  html+="</div></div>";',
'  result.innerHTML=html;',
'  var acts=result.querySelector("#acts");',
'  if(info.type==="video"){',
'    var a=document.createElement("a");a.className="btn-main";a.textContent="⇩ 下载视频";',
'    a.href=api("/api/dl?key="+j.key);acts.appendChild(a);',
'  }',
'  if(info.parts&&info.parts.length>1){',
'    var box=result.querySelector("#parts");',
'    info.parts.forEach(function(p){',
'      var b=document.createElement("button");b.textContent="P"+p.page+" "+(p.title||"").slice(0,14);',
'      if(info.title.indexOf("P"+p.page+":")>=0)b.classList.add("on");',
'      b.onclick=function(){input.value="https://www.bilibili.com/video/"+info.id+"?p="+p.page;run()};',
'      box.appendChild(b);',
'    });',
'  }',
'  if(info.type==="images"&&info.images&&info.images.length){',
'    var gal=result.querySelector("#gal");',
'    info.images.forEach(function(u,i){',
'      var a=document.createElement("a");',
'      a.href=api("/api/proxy?u="+encodeURIComponent(u)+"&n="+encodeURIComponent(info.filename+"_"+("0"+(i+1)).slice(-2)+".jpg"));',
'      a.title="点击保存第 "+(i+1)+" 张";',
'      var img=document.createElement("img");img.loading="lazy";',
'      img.src=api("/api/proxy?u="+encodeURIComponent(u));',
'      a.appendChild(img);gal.appendChild(a);',
'    });',
'  }',
'}',
'go.onclick=run;',
'input.addEventListener("keydown",function(e){if(e.key==="Enter")run()});',
'</script></body></html>',
].join('\n');
