#!/usr/bin/env node
/**
 * cli.js — 抖音 / B站 源视频下载命令行（零依赖，Node >= 18）
 *
 * 用法：
 *   node cli.js <抖音或B站链接/ID/整段分享文案> [选项]
 * 选项：
 *   -o <dir>   输出目录（默认 ./downloads）
 *   --info     只解析信息不下载
 *   --min      选最小体积档位
 *   --cover    同时保存封面图
 */
const fs = require('fs');
const path = require('path');
const { resolve, pickStream, sanitize, sleep } = require('./core');

const fmtMB = (n) => (n / 1048576).toFixed(2) + ' MB';
const log = (...a) => console.log(...a);

function fmtDuration(ms) {
  const s = Math.round(ms / 1000);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), ss = s % 60;
  return (h ? h + '时' : '') + (m ? m + '分' : '') + ss + '秒';
}

async function download(url, filePath, totalHint, headers, backupUrls = []) {
  const candidates = [url, ...backupUrls];
  let lastErr;
  for (const cand of candidates) {
    try {
      return await downloadOne(cand, filePath, totalHint, headers);
    } catch (e) {
      lastErr = e;
      if (fs.existsSync(filePath)) fs.unlinkSync(filePath); // 换镜像重下
      log(`    镜像失败（${e.message}），尝试下一个候选…`);
    }
  }
  throw lastErr;
}

async function downloadOne(url, filePath, totalHint, headers) {
  let have = 0;
  if (fs.existsSync(filePath)) have = fs.statSync(filePath).size;

  for (let attempt = 0; attempt < 4; attempt++) {
    const h = { ...headers };
    if (have > 0) h.range = `bytes=${have}-`;
    const res = await fetch(url, { headers: h, redirect: 'follow' });
    if (res.status === 416 && have > 0) {
      // 本地文件已不短于远端（上次已下全），幂等返回
      process.stdout.write('\n');
      return have;
    }
    if (!res.ok && res.status !== 206) throw new Error('HTTP ' + res.status);

    const cr = res.headers.get('content-range');
    const total = cr ? Number(cr.split('/')[1]) : totalHint;
    if (res.status === 200) have = 0;

    const out = fs.createWriteStream(filePath, { flags: res.status === 206 && have > 0 ? 'a' : 'w' });
    let lastLog = 0;
    await new Promise((resolveP, rejectP) => {
      (async () => {
        try {
          const reader = res.body.getReader();
          for (;;) {
            const { done, value } = await reader.read();
            if (done) break;
            out.write(value);
            have += value.length;
            if (have - lastLog > 512 * 1024 || (total && have === total)) {
              const pct = total ? ((have / total) * 100).toFixed(1) : '?';
              process.stdout.write(`\r    ⇣ ${fmtMB(have)} / ${total ? fmtMB(total) : '?'}（${pct}%）   `);
              lastLog = have;
            }
          }
          out.end(resolveP);
        } catch (e) { out.destroy(); rejectP(e); }
      })();
    }).catch(async (e) => {
      if (attempt === 3) throw e;
      await sleep(1200 * (attempt + 1)); // 网络抖动：断点续传重试
    });

    if (!total || have >= total) {
      process.stdout.write('\n');
      return have;
    }
  }
  throw new Error('多次续传后仍未下全');
}

(async () => {
  const args = process.argv.slice(2);
  const opts = { info: args.includes('--info'), min: args.includes('--min'), cover: args.includes('--cover') };
  const oIdx = args.indexOf('-o');
  const outDir = oIdx >= 0 && args[oIdx + 1] ? args[oIdx + 1] : './downloads';
  const input = args.filter((a, i) => a && !a.startsWith('-') && !(oIdx >= 0 && i === oIdx + 1)).join(' ');
  if (!input) {
    log('用法: node cli.js <抖音/B站链接、ID或整段分享文案> [-o 输出目录] [--info] [--min] [--cover]');
    process.exit(1);
  }

  const info = await resolve(input);
  const badge = info.platform === 'bilibili' ? 'B站' : '抖音';
  log(`[1/3] [${badge}] 《${info.title}》  作者: ${info.author || '未知'}`);
  const st = info.stats || {};
  const stStr = st.like != null ? `赞:${st.like ?? '-'} ` : '';
  log(`      时长 ${fmtDuration(info.durationMs)}  ${stStr}${st.view != null ? '播放:' + st.view + ' ' : ''}${info.createTime ? '| ' + new Date(info.createTime * 1000).toLocaleString('zh-CN') : ''}`);

  fs.mkdirSync(outDir, { recursive: true });
  fs.writeFileSync(path.join(outDir, info.filename + '.meta.json'), JSON.stringify(info, null, 2));

  if (opts.info) { log('[info] 元数据已保存，跳过下载。'); return; }

  if (info.type === 'images') {
    log(`[2/3] 图集作品，共 ${info.images.length} 张`);
    let ok = 0;
    for (let i = 0; i < info.images.length; i++) {
      const fp = path.join(outDir, `${info.filename}_${String(i + 1).padStart(2, '0')}.jpeg`);
      const urls = info.images[i].urls;
      for (const u of urls) {
        try {
          const r = await fetch(u, { headers: { 'user-agent': 'Mozilla/5.0' } });
          if (!r.ok) continue;
          const buf = Buffer.from(await r.arrayBuffer());
          if (buf.length < 1024) continue;
          fs.writeFileSync(fp, buf);
          log(`    图 ${i + 1}/${info.images.length} ✓ ${fmtMB(buf.length)}`);
          ok++;
          break;
        } catch (_) { /* 下一个镜像 */ }
      }
      await sleep(300);
    }
    log(`[3/3] 完成：${ok}/${info.images.length} 张 → ${path.resolve(outDir)}`);
    return;
  }

  if (opts.cover && info.cover) {
    try {
      const r = await fetch(info.cover, { headers: { 'user-agent': 'Mozilla/5.0' } });
      fs.writeFileSync(path.join(outDir, info.filename + '_cover.jpg'), Buffer.from(await r.arrayBuffer()));
      log('      封面已保存');
    } catch (_) { /* 封面失败不阻塞 */ }
  }

  const stream = await pickStream(info, opts);
  log(`[2/3] 选定画质 ${stream.quality}${stream.totalBytes ? '（' + fmtMB(stream.totalBytes) + '）' : ''}${stream.multiSegment ? ` 注意：免登录流分 ${stream.multiSegment} 段，本次取第 1 段` : ''}`);

  const filePath = path.join(outDir, info.filename + '.mp4');
  const size = await download(stream.url, filePath, stream.totalBytes, stream.headers, stream.backupUrls);
  log(`[3/3] 完成: ${filePath}`);
  log(`      大小 ${fmtMB(size)}  时长 ${fmtDuration(info.durationMs)}${info.videoMeta ? '  原片规格 ' + info.videoMeta.width + 'x' + info.videoMeta.height : ''}`);
})().catch((e) => { console.error('✗ 出错:', e.message); process.exit(1); });
