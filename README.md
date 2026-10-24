# 快捷爬取 · 抖音 / B站 源视频下载工具

零依赖（Node ≥ 18），三个文件三种用法：**命令行**、**网页版**、**代码库**。

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
- 抖音图集：代码分支已实现（note/slides/gallery 链接均识别），但公开渠道找到的图文短链均已失效，未跑通活的样本——遇到图集如异常请反馈

## 限制与约定

- B站免登录上限 720p；部分番剧/课程不开放免登录流
- 私密、已删除作品无法获取
- **仅个人学习备份，尊重原作者版权，勿批量、勿商用**

## License

[MIT](./LICENSE)
