/**
 * core.js — 抖音 / B站 统一解析层（零依赖，Node >= 18）
 *
 * 对外两个入口：
 *   await resolve(input)            → 作品信息（标题/作者/封面/时长/可选流）
 *   await pickStream(info, {min})   → 最佳下载流 { url, totalBytes, quality, headers }
 *
 * 风控策略：
 *   抖音：伪装 8.8.0 旧版 iOS 客户端走 aweme/v1/feed（免签名），ratio 阶梯取最高画质播放流（无水印）。
 *   B站：公开 web API + html5 平台参数拿免登录 MP4 直链（上限 720p，1080p+ 需登录 cookie）。
 */

// ---------- 通用 ----------
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

// ================= 抖音 =================
const DY_APP_UA = 'Aweme/8.8.0 (iPhone; iOS 7.0; Scale/2.00) CFNetwork/758.3.1 Darwin/15.4.0';
const DY_FEED_HOSTS = ['https://aweme.snssdk.com', 'https://api.amemv.com'];
const DY_PLAY_HOSTS = ['https://aweme.snssdk.com', 'https://api.amemv.com', 'https://api-play-hl.amemv.com'];
const DY_FEED_PARAMS = 'version_code=8.8.0&app_name=aweme&channel=App%20Store&device_platform=iphone&device_type=iPhone8,1&os_version=12.4&aid=1128&ac=WIFI';
const DY_RATIOS = ['1080p', '720p', '540p'];

function isDouyinInput(input) {
  return /douyin\.com|iesdouyin\.com/i.test(input) || /^(7\d{17,19})$/.test(input.trim());
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
      throw new Error('抖音短链无效（服务端把它兜底跳到了主页）：多半是复制时丢了字符或链接已过期，请重新「分享→复制链接」');
    throw new Error('抖音链接重定向后未能提取视频ID：' + url);
  }
  return m[1];
}

async function douyinFeed(awemeId) {
  let lastErr;
  for (const host of DY_FEED_HOSTS) {
    for (let attempt = 0; attempt < 3; attempt++) {
      try {
        const res = await fetch(`${host}/aweme/v1/feed/?aweme_id=${awemeId}&${DY_FEED_PARAMS}`, { headers: { 'user-agent': DY_APP_UA } });
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const json = await res.json();
        const list = json.aweme_list || [];
        // 必须精确匹配 aweme_id：视频已删除时接口会返回推荐流（随机热门视频），不能拿来充数
        const item = list.find((x) => String(x.aweme_id) === awemeId);
        if (item) return item;
        throw new Error(list.length ? '作品已删除或不可见（接口只返回了推荐内容）' : '接口返回空（status_code=' + json.status_code + '），作品可能已删除或不可见');
      } catch (e) {
        lastErr = e;
        await sleep(800 * (attempt + 1));
      }
    }
    await jitter();
  }
  throw new Error('抖音 feed 接口两套域名均失败：' + lastErr.message);
}

// 封面选择：旧版 App 参数下 cover/origin_cover 常是 heic（浏览器显示不了），
// 优先找 tplv 模板的 webp/jpeg 地址，其次 dynamic_cover（实为 JPEG），最后兜底。
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
  const info = {
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
  return info;
}

// ratio 阶梯探测，返回去重后的候选 [{url,total,ratio}]（按体积降序）
async function douyinCandidates(videoUri) {
  const seen = new Map();
  for (const ratio of DY_RATIOS) {
    for (const host of DY_PLAY_HOSTS) {
      const url = `${host}/aweme/v1/play/?video_id=${videoUri}&ratio=${ratio}&line=0`;
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

// ================= B站 =================
const BILI_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
const BILI_HEADERS = { 'user-agent': BILI_UA, referer: 'https://www.bilibili.com/' };

function isBiliInput(input) {
  return /bilibili\.com|b23\.tv|^(av\d{5,15})$/i.test(input.trim()) || /BV[0-9A-Za-z]{10}/.test(input);
}

async function biliNormalize(input) {
  const trimmed = input.trim();

  const av = trimmed.match(/^(av\d{5,15})$/i);
  if (av) return { aid: av[1].slice(2), p: 1 };

  const urlMatch = trimmed.match(/https?:\/\/[^\s]*/i);
  let url = urlMatch ? urlMatch[0].replace(/[)），，。]+$/, '') : null;

  if (url && /b23\.tv/.test(url)) {
    // 短链三种形态：30x 跳转 / HTML 落地页内嵌 BV / JSON 404（已失效）
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
      if (bvInPage) { url = 'https://www.bilibili.com/video/' + bvInPage[0]; }
      else if (/"code":\s*-404/.test(bodyText)) throw new Error('b23.tv 短链已失效（"啥都木有"），请重新分享获取新链接');
      break;
    }
  }

  const src = url || trimmed;
  const bv = src.match(/BV[0-9A-Za-z]{10}/);
  const aid = src.match(/[&/?]av(\d{5,15})/i);
  const p = Number((src.match(/[?&]p=(\d+)/) || [])[1] || 1);
  if (bv) return { bvid: bv[0], p };
  if (aid) return { aid: aid[1], p };
  throw new Error('没认出 B站链接：支持 BV号 / av号 / b23.tv 短链 / 完整网址');
}

async function biliView({ bvid, aid }) {
  const q = bvid ? 'bvid=' + bvid : 'aid=' + aid;
  const res = await fetch('https://api.bilibili.com/x/web-interface/view?' + q, { headers: BILI_HEADERS });
  const j = await res.json();
  if (j.code !== 0) throw new Error('B站 view 接口：' + (j.message || j.code));
  return j.data;
}

async function resolveBilibili(input) {
  const { bvid, aid, p } = await biliNormalize(input);
  const v = await biliView({ bvid, aid });
  const pages = v.pages || [{ cid: v.cid, part: v.title, page: 1, duration: v.duration }];
  const page = pages[Math.min(Math.max(p, 1), pages.length) - 1];

  const info = {
    platform: 'bilibili',
    id: v.bvid,
    type: 'video',
    title: pages.length > 1 ? `${v.title} P${page.page}:${page.part}` : v.title,
    author: v.owner?.name || '',
    cover: v.pic || '',
    durationMs: (page.duration || v.duration || 0) * 1000,
    createTime: v.pubdate || 0,
    stats: { like: v.stat?.like, view: v.stat?.view, fav: v.stat?.favorite },
    bili: { bvid: v.bvid, cid: page.cid, page: page.page },
    parts: pages.map((x) => ({ page: x.page, title: x.part, cid: x.cid, durationMs: (x.duration || 0) * 1000 })),
    filename: sanitize(pages.length > 1 ? `${v.title}_P${page.page}_${page.part}` : v.title) + '_' + v.bvid,
  };
  return info;
}

async function biliPickStream(info) {
  const { bvid, cid } = info.bili;
  const pu = `https://api.bilibili.com/x/player/playurl?bvid=${bvid}&cid=${cid}&qn=80&fnval=1&platform=html5&high_quality=1`;
  const res = await fetch(pu, { headers: BILI_HEADERS });
  const j = await res.json();
  if (j.code !== 0) throw new Error('B站 playurl 接口：' + (j.message || j.code));
  const durl = j.data.durl || [];
  if (!durl.length) throw new Error('B站未返回可下载地址（免登录上限 720p，部分番剧/课程不开放）');
  const seg = durl[0];
  const urls = [seg.url, ...(seg.backup_url || [])];
  return {
    url: urls[0],
    totalBytes: seg.size || 0,
    quality: j.data.quality >= 64 ? '720p' : String(j.data.quality),
    headers: BILI_HEADERS,
    backupUrls: urls.slice(1),
    multiSegment: durl.length > 1 ? durl.length : 0,
  };
}

// ================= 统一入口 =================
async function resolve(input) {
  const t = input.trim();
  if (isBiliInput(t)) return resolveBilibili(t);
  if (isDouyinInput(t)) return resolveDouyin(t);
  throw new Error('不支持的链接：目前支持 抖音（v.douyin.com / 视频ID）和 B站（BV / av / b23.tv）');
}

async function pickStream(info, opts = {}) {
  return info.platform === 'bilibili' ? biliPickStream(info) : douyinPickStream(info, opts);
}

module.exports = { resolve, pickStream, sanitize, sleep };
