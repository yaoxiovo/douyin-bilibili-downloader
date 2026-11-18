# YaoXi Hub API 自建部署

Android「工作台」和「消息」可以直接调用公开 GitHub API。启用统一登录、腾讯云功能和私有仓库时，需要独立的 Hub API。后端使用 Node.js 22，无第三方运行依赖。

## 服务端配置

通过运行环境的私密配置设置以下变量。将 Hub API 部署到统一账号中心允许的 HTTPS 子域名，并在统一账号中心登记客户端 `yaoxi-hub-android`，回调地址为该域名下的 `/auth/callback`。

| 环境变量 | 用途 |
| --- | --- |
| `HUB_PUBLIC_ORIGIN` | 此服务的 HTTPS 源，供统一账号回调使用 |
| `HUB_ACCOUNT_SUB` | 唯一获准操作此 Hub 的统一账号用户名 |
| `HUB_SSO_HANDSHAKE_SECRET` | 自建环境与统一账号服务约定的握手密钥 |
| `TENCENTCLOUD_SECRET_ID`、`TENCENTCLOUD_SECRET_KEY` | 专用 CAM 子账号凭据，限制为下列 API 所需权限和资源 |
| `HUB_GITHUB_TOKEN` | 可选。由 `yaoxiovo` 授权，限定可读取的仓库元数据与 Actions |
| `HOST`、`PORT` | 默认监听 `127.0.0.1:8788` |

DNSPod 使用 `DescribeDomainList`、`DescribeRecordLineList`、`DescribeRecordList`、`CreateRecord`、`ModifyRecord`、`ModifyRecordStatus`。EdgeOne 使用 `DescribeZones`、`DescribeAccelerationDomains`、`DescribeDnsRecords`、`CreateDnsRecord`、`ModifyDnsRecords`、`ModifyDnsRecordsStatus`、`CreatePurgeTask` 和 `DescribePurgeTasks`。实际写入以专用账号权限为准。

使用进程管理器执行 `node /absolute/path/hub-api/server.mjs`，在 HTTPS 反向代理上转发到本机端口。完成服务端配置后，在 Android「我的 → 账号统一中心 → Hub API 地址」填写相同的 HTTPS 源，然后使用系统浏览器登录。登录步骤使用一次性配对，Android 只接收临时 Hub 会话。会话和最近 50 条云操作消息保存在服务进程中，重启后需要重新登录，腾讯云任务历史仍从腾讯云读取。

## 检查

访问 `/auth/callback` 应返回完成登录的页面。未登录请求 `POST /api/account` 应返回 HTTP 401。使用已配置的安卓客户端登录后，应能看到账号和服务配置状态。建议先核对只读查询，再在预先选定的记录和 URL 上验证写入。
