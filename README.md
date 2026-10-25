# 快捷爬取 · 抖音 / B站 源视频下载工具

[![Build & Release Android APK](https://github.com/yaoxiovo/douyin-bilibili-downloader/actions/workflows/build-apk.yml/badge.svg)](https://github.com/yaoxiovo/douyin-bilibili-downloader/actions/workflows/build-apk.yml)
[![GitHub Release](https://img.shields.io/github/v/release/yaoxiovo/douyin-bilibili-downloader?color=blue&label=APK%20Release)](https://github.com/yaoxiovo/douyin-bilibili-downloader/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](./LICENSE)

零依赖（Node ≥ 18），支持多种使用方式：**📱 Android 安卓 App**、**网页版**、**命令行**、**代码库**、**Cloudflare Workers 边缘函数**。

---

## 📱 Android 安卓 App 版（手机原生开箱即用）

专为手机端打造的原生 APK 应用，采用 **原生 Java + OkHttp 引擎 + 现代 Android WebKit 混合架构**，无需依赖电脑或远程服务器，在手机上即可脱机独立运行！

### 核心特性
- **⚡ 脱机纯本地运行**：内置移动端原生的解析引擎与 OkHttp 高速下载栈，无需电脑搭建 Node.js 环境，随时随地打开手机即下。
- **📋 智能剪贴板侦测**：启动或切回 App 时自动识别剪贴板中的抖音/B站分享文案（自动正则提取有效链接），告别手动反复粘贴。
- **🎬 无水印原画直下**：
  - **抖音**：移植 iOS App Feed 接口阶梯体积探测技术，直接获取 1080p App 播放流（天然无水印），支持视频与图文图集。
  - **B站**：支持 BV号 / av号 / b23.tv / 分P 链接，免登录 720p 原画 MP4 直链高速解析，提供清晰的分P 选集切换。
- **🖼️ 现代 Scoped Storage（直存系统相册）**：适配 Android 10 ~ 14 现代存储规范，下载完毕自动通过 Android `MediaStore` 登记入库，视频/图片直接显示在系统相册（DCIM/Camera）与下载目录，即刻分享至社交软件。
- **📊 实时下载进度与通知**：原生下载进度监听，通知栏实时显示进度，支持后台稳定传输。

### 下载与安装
1. **正式版 Release 下载**：前往 [Releases 页面](https://github.com/yaoxiovo/douyin-bilibili-downloader/releases) 下载最新签名的 `douyin-bilibili-downloader.apk`。
2. **CI 自动化预览版**：由 GitHub Actions 流水线自动化构建，进入 [Actions 页面](https://github.com/yaoxiovo/douyin-bilibili-downloader/actions) 选择最近的流水线即可在 Artifacts 中下载测试包。
3. **本地源码编译**：
   ```bash
   cd android
   ./gradlew assembleDebug
   # 输出 APK 路径：android/app/build/outputs/apk/debug/app-debug.apk
   ```

---

## 网页版（推荐）

```bash
node server.js
# 打开 http://127.0.0.1:8787 ，粘贴链接 → 解析 → 下载
# 端口冲突时：PORT=9000 node server.js
```

- 支持粘贴**整段分享文案**（自动提取链接），抖音 / B站 自动识别
- 解析后展示标题、作者、时长、画质、体积、封面，B站分P 可切换
- 点击下载由本机服务代抓字节流转发给浏览器另存为（不落服务器磁盘）
- 仅监听 127.0.0.1，本机自用；媒体代理带域名白名单（防 SSRF）

## 命令行

```bash
# 抖音：短链 / 长链 / 纯数字ID / 整段分享文案
node cli.js "8.88 复制打开抖音 https://v.douyin.com/xxxx/ ..."
node cli.js https://www.douyin.com/video/7574003900107537509

# B站：BV号 / av号 / b23.tv短链 / 完整网址（含 ?p=N 分P）
node cli.js BV1U3n46GEzY
node cli.js "https://www.bilibili.com/video/BV1n5na61Eev?p=2"

# 选项：-o 输出目录  --info 只看信息  --min 最小体积档  --cover 连封面一起存
```

输出到 `downloads/标题_ID.mp4` + 同名 `.meta.json` 元数据。`douyin_dl.js` 保留为兼容入口（转发到 cli.js）。

## 代码库

```js
const { resolve, pickStream } = require('./core');
const info = await resolve(anyLinkOrId);        // {platform,title,author,cover,parts,images,...}
const stream = await pickStream(info);          // {url,totalBytes,quality,headers,backupUrls}
```

## 原理（绕风控思路）

| 平台 | 路线 | 说明 |
|---|---|---|
| 抖音 | 伪装 8.8.0 旧版 iOS App 调 `aweme/v1/feed/` | 该老接口不校验签名/cookie，绕开网页端 Argus 指纹风控；`/aweme/v1/play/` 按 1080p→720p→540p 阶梯探测取最大体积档（App 播放流，天然无水印；带水印的 `download_addr` 从不触碰） |
| B站 | 公开 web API `view` + `playurl`（`platform=html5&high_quality=1`） | 免登录拿 MP4 直链，上限 **720p**；1080p+ 需登录 cookie（SESSDATA），如需可自行加 |

工程细节：双域名互备 + 指数退避重试、断点续传（重复下载幂等）、请求间随机抖动延迟、失效链接明确报错（不会把接口返回的推荐流当成目标视频下载）。

## 已实测（2026-10-24）

- 抖音：短链/长链/纯ID/带emoji整段文案，5+ 个真实视频，1080p 无水印（抽帧 AI 视觉验证）
- B站：BV/av/b23.tv、单P 与多P（GEN vs IG P2），720p MP4 完整
- 网页版：解析/分P切换/下载代理/封面代理（HEIC→自动嗅探类型）/死链报错/SSRF 拦截
- Workers 版：本地直跑 handler 冒烟 10/10（页面+内嵌JS语法解析/双平台解析/MP4 流转发/封面嗅探/SSRF/token 鉴权/坏 key），远端文件与本地字节级一致
- 抖音图集：代码分支已实现（note/slides/gallery 链接均识别），但公开渠道找到的图文短链均已失效，未跑通活的样本——遇到图集如异常请反馈

## 限制与约定

- B站免登录上限 720p；部分番剧/课程不开放免登录流
- 私密、已删除作品无法获取
- **仅个人学习备份，尊重原作者版权，勿批量、勿商用**

## 部署到 Cloudflare Workers（随时随地可用）

网页版依赖服务端伪造 `User-Agent`/`Referer` 和绕过 CORS，纯静态托管（GitHub Pages 等）不可行；`worker/` 目录是移植好的 **单文件 Cloudflare Worker 版**（解析层 + 三个 API + 页面全内嵌，本地 `node worker/test.js` 冒烟 10/10 通过）。

**零命令行部署（推荐）**：

1. Cloudflare Dashboard → Workers & Pages → Create → Worker（随意命名）→ Deploy 后点 **Edit code**
2. 把 [`worker/index.js`](./worker/index.js) 的全部内容粘贴进去覆盖 → Deploy
3. Settings → Variables → 添加 `ACCESS_TOKEN` = 你的口令（不设则任何人可用，强烈建议设）
4. 打开 `https://你的名字.workers.dev`，右上角填口令即可使用

**命令行部署**：`cd worker && wrangler deploy`（先 `wrangler secret put ACCESS_TOKEN`）

与本地版差异：Worker 出口是 Cloudflare 边缘 IP，若抖音对特定区域有限制可能解析失败（B站不受影响）；内存缓存按实例隔离，多实例下偶发“缓存过期”重解析即可。

## License

[MIT](./LICENSE)
