#!/usr/bin/env node
/**
 * server.js — 网页版快捷爬取（零依赖，Node >= 18）
 *
 *   node server.js            →  http://127.0.0.1:8787
 *   端口占用时：PORT=9000 node server.js
 *
 * 仅监听 127.0.0.1（本机自用，不对局域网开放）。
 * 流程：网页粘贴链接 → POST /api/info 解析（标题/封面/画质/大小）
 *      → 点击下载由 /api/dl 服务端代抓字节流并转发给浏览器另存为。
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { Readable } = require('stream');
const { resolve, pickStream } = require('./core');

const PORT = Number(process.env.PORT || 8787);
const INDEX = path.join(__dirname, 'public', 'index.html');

// 解析结果缓存：info 与下载流探测结果复用，避免同一链接重复探测
const cache = new Map();
const CACHE_TTL = 15 * 60 * 1000;
function cacheSet(v) {
  const k = crypto.randomBytes(8).toString('hex');
  cache.set(k, { ...v, ts: Date.now() });
  if (cache.size > 50) cache.delete(cache.keys().next().value);
  return k;
}
function cacheGet(k) {
  const v = cache.get(k);
  if (!v || Date.now() - v.ts > CACHE_TTL) return null;
  return v;
}

// 图片/封面代理白名单（防 SSRF，仅允许已知媒体域）
const PROXY_HOSTS = /\.(douyinpic|douyinvod|douyin|amemv|snssdk|bilivideo|biliapi|bilibili|hdslb|akamaized|bytecdn|bytetos|bytegoofy|ibyteimg|pstatp)\.(com|cn|net)$/i;

const ADDR_RE = /^\d{1,3}(\.\d{1,3}){3}$/;
function isAllowedUrl(u) {
  try {
    const p = new URL(u);
    if (!/^https?:$/.test(p.protocol)) return false;
    if (ADDR_RE.test(p.hostname)) return false; // 纯 IP 不放行
    return PROXY_HOSTS.test(p.hostname);
  } catch (_) { return false; }
}

function cd(name) {
  const ascii = name.replace(/[^\x20-\x7e]/g, '_').replace(/["\\]/g, '_');
  return `attachment; filename="${ascii}"; filename*=UTF-8''${encodeURIComponent(name)}`;
}

// 按魔数嗅探媒体类型（上游 Content-Type 常与实际不符，如抖音封面 heic/webp）
function sniffType(buf) {
  if (buf.length >= 12 && buf.toString('ascii', 0, 4) === 'RIFF' && buf.toString('ascii', 8, 12) === 'WEBP') return 'image/webp';
  if (buf.length >= 3 && buf[0] === 0xff && buf[1] === 0xd8) return 'image/jpeg';
  if (buf.length >= 8 && buf[0] === 0x89 && buf[1] === 0x50) return 'image/png';
  if (buf.length >= 4 && buf[2] === 0x66 && buf[3] === 0x74) return buf.includes(Buffer.from('ftypavif')) ? 'image/avif' : 'video/mp4';
  if (buf.length >= 6 && buf.includes(Buffer.from('GIF8'), 0)) return 'image/gif';
  return 'application/octet-stream';
}

function readBody(req) {
  return new Promise((r) => {
    let b = '';
    req.on('data', (c) => (b += c));
    req.on('end', () => r(b));
  });
}

const server = http.createServer(async (req, res) => {
  const u = new URL(req.url, `http://127.0.0.1:${PORT}`);

  // ---- 静态首页 ----
  if (req.method === 'GET' && (u.pathname === '/' || u.pathname === '/index.html')) {
    try {
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
      res.end(fs.readFileSync(INDEX));
    } catch { res.writeHead(500); res.end('index.html 缺失'); }
    return;
  }

  // ---- 解析 ----
  if (req.method === 'POST' && u.pathname === '/api/info') {
    try {
      const { url } = JSON.parse((await readBody(req)) || '{}');
      if (!url || typeof url !== 'string') throw new Error('缺少 url 字段');
      const info = await resolve(url);
      let stream = null;
      if (info.type === 'video') stream = await pickStream(info);
      const key = cacheSet({ info, stream });
      res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({
        ok: true,
        key,
        info: {
          platform: info.platform,
          id: info.id,
          type: info.type,
          title: info.title,
          author: info.author,
          cover: info.cover,
          durationMs: info.durationMs,
          stats: info.stats,
          parts: info.parts || null,
          imagesCount: info.images ? info.images.length : 0,
          images: (info.images || []).map((im) => im.urls.find(Boolean)).filter(Boolean),
          filename: info.filename,
        },
        stream: stream && { quality: stream.quality, totalBytes: stream.totalBytes, multiSegment: stream.multiSegment || 0 },
      }));
    } catch (e) {
      res.writeHead(400, { 'content-type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ ok: false, error: e.message }));
    }
    return;
  }

  // ---- 主文件下载（服务端代抓 + 转发，浏览器另存为）----
  if (req.method === 'GET' && u.pathname === '/api/dl') {
    const entry = cacheGet(u.searchParams.get('key'));
    if (!entry || !entry.stream) { res.writeHead(404, { 'content-type': 'application/json' }); res.end('{"ok":false,"error":"缓存过期，请重新解析"}'); return; }
    const { info, stream } = entry;
    try {
      const upstream = await fetch(stream.url, { headers: stream.headers, redirect: 'follow' });
      if (!upstream.ok) throw new Error('上游 HTTP ' + upstream.status);
      const total = Number(upstream.headers.get('content-length') || stream.totalBytes || 0);
      res.writeHead(200, {
        'content-type': 'video/mp4',
        'content-disposition': cd(info.filename + '.mp4'),
        ...(total ? { 'content-length': total } : {}),
      });
      Readable.fromWeb(upstream.body).pipe(res);
    } catch (e) {
      if (!res.headersSent) { res.writeHead(502, { 'content-type': 'application/json; charset=utf-8' }); res.end(JSON.stringify({ ok: false, error: e.message })); }
      else res.destroy();
    }
    return;
  }

  // ---- 图集 / 封面 等媒体代理 ----
  if (req.method === 'GET' && u.pathname === '/api/proxy') {
    const target = u.searchParams.get('u');
    const name = (u.searchParams.get('n') || 'media').replace(/[\\/:*?"<>|]/g, '_');
    if (!target || !isAllowedUrl(target)) { res.writeHead(403); res.end('forbidden'); return; }
    try {
      const upstream = await fetch(target, { headers: { 'user-agent': 'Mozilla/5.0', referer: 'https://www.bilibili.com/' }, redirect: 'follow' });
      if (!upstream.ok) throw new Error('HTTP ' + upstream.status);
      // 先读首个分块做类型嗅探，再决定响应头（字节流原样转发，长度不受影响）
      const reader = upstream.body.getReader();
      const { done, value: first } = await reader.read();
      const sniffed = done ? 'application/octet-stream' : sniffType(Buffer.from(first || []));
      const total = upstream.headers.get('content-length');
      res.writeHead(200, {
        'content-type': sniffed,
        'content-disposition': cd(name),
        ...(total ? { 'content-length': total } : {}),
      });
      const pass = new Readable({ read() {} });
      if (!done) pass.push(first);
      (async () => {
        for (;;) {
          const r = await reader.read();
          if (r.done) { pass.push(null); break; }
          pass.push(r.value);
        }
      })().catch(() => pass.destroy());
      pass.pipe(res);
    } catch (e) {
      if (!res.headersSent) { res.writeHead(502); res.end(e.message); }
      else res.destroy();
    }
    return;
  }

  res.writeHead(404, { 'content-type': 'application/json' });
  res.end('{"ok":false,"error":"not found"}');
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`✔ 网页版已启动: http://127.0.0.1:${PORT}  （Ctrl+C 停止；仅本机可访问）`);
});
