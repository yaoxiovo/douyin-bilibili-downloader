<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { Button, Card, Tabs, Input, Select, Alert, Dialog, Image as FxImage, Statistic, Empty, Skeleton } from 'fuxsto-design-pro'
import { request } from './bridge.js'

const tabs = [{label:'首页',value:'home'}, {label:'工作台',value:'work'}, {label:'消息',value:'messages'}, {label:'我的',value:'account'}]
const titles = { projects:'项目详情', project:'项目详情', blog:'博客运营', status:'站点监控', analytics:'数据概览',
  dns:'DNS 解析', records:'DNS 记录', edge:'EdgeOne', zone:'EdgeOne 站点', edgeRecords:'EdgeOne 托管 DNS' }
const sites = [
  ['个人主页','https://yaoxi.wiki'], ['个人博客','https://blog.yaoxi.wiki'],
  ['壁纸站','https://pic.yaoxi.wiki'], ['网站状态页','https://status.yaoxi.wiki'],
]
const cloud = [['DNS 解析','腾讯云 DNSPod','dns'], ['EdgeOne','站点、解析与缓存','edge']]
const consoleUrls = { dns:'https://console.cloud.tencent.com/cns', edge:'https://console.cloud.tencent.com/edgeone' }
const account = reactive({ origin:'', account:'', pending:false })
const signedIn = computed(() => Boolean(account.account))
const current = ref({ name:'home' })
const name = computed(() => current.value.name)
const selected = computed(() => ['home','status','analytics'].includes(name.value) ? 'home'
  : ['messages','account'].includes(name.value) ? name.value : 'work')
const trail = []
const view = ref({})
const error = ref('')
const toast = ref('')
const loading = ref(false)
const query = ref('')
const bingSite = ref('yaoxi.wiki')
const period = ref('7d')
const zoneTab = ref('acceleration')
const dialog = ref(null)
const form = ref({})
const saving = ref(false)
let revision = 0
let polling = false

const service = (operation, data = {}) => request('api', { operation, data })
const feed = resource => request('public', { resource })
const errorText = issue => issue?.message || '暂时无法读取'
const statusOf = item => ({ available:'访问正常', slow:'响应较慢', unavailable:'访问异常' })[item?.status] || '暂无数据'
const sourceStatus = item => ({ 'not-configured':'服务端尚未配置数据源', 'no-data':'当前没有报表' })[item?.status] || '暂时无法读取'
const host = url => { try { return new URL(url).host } catch { return url || '' } }
const localized = value => typeof value === 'number' ? new Intl.NumberFormat('zh-CN').format(value) : '—'
const date = value => {
  if (!value) return '—'
  const result = new Date(value)
  return Number.isFinite(result.getTime()) ? result.toISOString().slice(0, 10) : '—'
}
const dateRange = item => `${date(item?.startAt)} 至 ${date(item?.endAt)}`
const projectChecks = check => [check.name, check.http ? 'HTTP ' + check.http : check.error,
  check.latency == null ? '' : check.latency + ' ms', check.expiresAt ? '证书到期 ' + date(check.expiresAt) : ''].filter(Boolean).join(' · ')
const runState = run => ({ success:'通过', failure:'失败', queued:'排队中', in_progress:'构建中', cancelled:'已取消', completed:'已结束' })[run?.conclusion || run?.status] || '未知'
const umami = computed(() => view.value.analytics?.umami)
const bing = computed(() => view.value.analytics?.bing)
const bingOptions = computed(() => (bing.value?.sites || []).map(item => ({ label:item.name, value:item.name })))
const selectedBing = computed(() => bing.value?.sites?.find(item => item.name === bingSite.value))
const bingStats = computed(() => selectedBing.value?.periods?.[period.value])
const visibleRepos = computed(() => (view.value.repositories || []).filter(item => item.name.toLowerCase().includes(query.value.trim().toLowerCase())))

function open(url) {
  request('open', { url }).catch(issue => { toast.value = errorText(issue) })
}

function enter(route, item = null, primary = false) {
  if (current.value.name === route && !item) return
  if (primary) trail.splice(0, trail.length)
  else trail.push(current.value)
  current.value = { name:route, item }
  if (name.value === 'zone') zoneTab.value = 'acceleration'
  window.scrollTo(0, 0)
  load()
}

function back() {
  if (dialog.value && !saving.value) { dialog.value = null; return true }
  if (saving.value) return true
  if (name.value === 'home' && !trail.length) return false
  current.value = trail.pop() || { name:'home' }
  window.scrollTo(0, 0)
  load()
  return true
}

window.HubNavBack = back

async function info() {
  Object.assign(account, await request('info'))
  if (account.pending) poll(1)
}

async function load() {
  const ticket = ++revision
  view.value = {}
  error.value = ''
  loading.value = true
  const active = current.value
  const add = async (key, response) => {
    try {
      const result = await response
      if (ticket === revision) view.value = { ...view.value, [key]:result }
    } catch (issue) {
      if (ticket === revision) view.value = { ...view.value, [key + 'Error']:errorText(issue) }
    }
  }
  try {
    switch (active.name) {
      case 'home':
        await Promise.all([add('gallery', feed('wallpapers')), add('status', feed('status')),
          signedIn.value ? add('analytics', service('analytics')) : Promise.resolve()])
        break
      case 'work': {
        let offset = 0
        let result
        do {
          result = signedIn.value ? await service('repositories', { offset }) : await request('repositories', { offset })
          if (ticket !== revision) return
          view.value.repositories = [...(view.value.repositories || []), ...(result.items || [])]
          offset += 100
        } while (result.more)
        break
      }
      case 'messages': viewIf(ticket, await (signedIn.value ? service('messages') : request('messages'))); break
      case 'account': if (signedIn.value) viewIf(ticket, await service('account')); break
      case 'status': viewIf(ticket, await feed('status')); break
      case 'analytics': if (signedIn.value) await add('analytics', service('analytics')); break
      case 'projects': if (signedIn.value) viewIf(ticket, await service('projects')); break
      case 'project':
        if (signedIn.value) {
          const result = await service('project', { id:active.item })
          viewIf(ticket, result)
          if (ticket === revision && result.project?.analytics) await add('analytics', service('analytics'))
        }
        break
      case 'blog': if (signedIn.value) viewIf(ticket, await service('blog')); break
      case 'dns': if (signedIn.value) await list('domains', 'DomainList'); break
      case 'records': if (signedIn.value) await list('records', 'RecordList', domainPayload()); break
      case 'edge': if (signedIn.value) await list('zones', 'Zones'); break
      case 'zone': if (signedIn.value) await zoneList(); break
      case 'edgeRecords': if (signedIn.value) await list('edgeRecords', 'DnsRecords', zonePayload()); break
    }
  } catch (issue) {
    if (ticket === revision) error.value = errorText(issue)
  } finally {
    if (ticket === revision) loading.value = false
  }
}

function viewIf(ticket, result) {
  if (ticket === revision) view.value = result
}

async function list(operation, key, data = {}, offset = 0) {
  const ticket = revision
  const result = await service(operation, { ...data, offset })
  if (ticket !== revision) return
  const items = result[key] || []
  view.value = { items:offset ? [...(view.value.items || []), ...items] : items,
    more:items.length === 50, next:offset + items.length, list:{ operation, key, data } }
}

function domainPayload() { return { domain:current.value.item?.Name, domainId:current.value.item?.DomainId } }
function zonePayload() { return { zone:current.value.item?.ZoneId } }
function zoneList() {
  return zoneTab.value === 'acceleration' ? list('acceleration', 'AccelerationDomains', zonePayload())
    : list('purgeHistory', 'Tasks', zonePayload())
}

function changeZone(value) {
  zoneTab.value = value
  load()
}

async function nextPage() {
  if (loading.value || !view.value.list) return
  const ticket = revision
  const currentList = view.value.list
  loading.value = true
  try { await list(currentList.operation, currentList.key, currentList.data, view.value.next) }
  catch (issue) { if (ticket === revision) error.value = errorText(issue) }
  finally { if (ticket === revision) loading.value = false }
}

function report(hostname = 'yaoxi.wiki') {
  bingSite.value = hostname
  enter('analytics')
}

function editRecord(record) {
  const edge = name.value === 'edgeRecords'
  form.value = { name:record?.Name || '', type:record?.Type || 'A',
    value:edge ? record?.Content || '' : record?.Value || '',
    line:edge ? record?.Location || 'Default' : record?.Line || '默认',
    ttl:record?.TTL == null ? '' : String(record.TTL),
    mx:(edge ? record?.Priority : record?.MX) == null ? '' : String(edge ? record.Priority : record.MX) }
  dialog.value = { type:'record', title:record ? '编辑记录' : '新增记录', record, edge }
}

function recordStatus(record) {
  const edge = name.value === 'edgeRecords'
  const enabled = String(record.Status).toLowerCase() === 'disable'
  confirm(enabled ? '启用 DNS 记录' : '暂停 DNS 记录', record.Name, () =>
    service(edge ? 'edgeRecordStatus' : 'recordStatus', { ...(edge ? zonePayload() : domainPayload()), recordId:record.RecordId, enabled }))
}

function confirm(title, description, perform) { dialog.value = { type:'confirm', title, description, perform } }

function possibleLines() {
  service('lines', domainPayload()).then(value => {
    dialog.value = { type:'lines', title:'可用线路', description:(value.LineList || []).map(line => line.Name).join('、') || '暂无线路' }
  }).catch(issue => { toast.value = errorText(issue) })
}

function approve(item, action) {
  confirm(action === 'approve' ? '确认通过' : '确认拒绝', item.body, () => service('moderate', { id:item.id, action }))
}

const originDialog = () => { form.value = { origin:account.origin }; dialog.value = {type:'origin', title:'Hub API 地址'} }
const purgeDialog = () => { form.value = { url:'' }; dialog.value = {type:'purge', title:'清除 URL 缓存'} }

async function submit() {
  if (saving.value || !dialog.value) return
  const panel = dialog.value
  if (panel.type === 'record') {
    const { ttl, mx, ...values } = form.value
    if (!values.name || !values.type || !values.value || !values.line ||
        !/^\d+$/.test(String(ttl)) || Number(ttl) <= 0 || (mx !== '' && !/^\d+$/.test(String(mx)))) {
      toast.value = '请填写完整的 DNS 记录。'
      return
    }
    const data = { ...(panel.edge ? zonePayload() : domainPayload()), ...values, type:values.type.toUpperCase(), ttl:Number(ttl) }
    if (mx !== '') data.mx = Number(mx)
    if (panel.record) {
      data.recordId = panel.record.RecordId
      data.status = panel.record.Status
      data.weight = panel.record.Weight
    }
    const existing = panel.record ? '原记录：' + panel.record.Name + ' → ' + (panel.edge ? panel.record.Content : panel.record.Value) + '\n' : ''
    confirm('确认 DNS 变更', existing + values.name + ' · ' + values.type + ' → ' + values.value +
      '\n线路 ' + values.line + ' · TTL ' + ttl, () => service(panel.edge ? 'edgeRecordSave' : 'recordSave', data))
    return
  }
  if (panel.type === 'purge') {
    const target = form.value.url.trim()
    if (!/^https?:\/\//i.test(target)) { toast.value = '请输入完整 URL。'; return }
    confirm('确认清除缓存', target, () => service('purge', { ...zonePayload(), url:target }))
    return
  }
  saving.value = true
  try {
    if (panel.type === 'origin') Object.assign(account, await request('configure', { url:form.value.origin }))
    else if (panel.type === 'confirm') {
      const result = await panel.perform()
      toast.value = result.JobId ? '已提交任务 ' + result.JobId : '操作成功'
    }
    dialog.value = null
    await load()
  } catch (issue) { toast.value = errorText(issue) }
  finally { saving.value = false }
}

async function login() {
  try {
    await request('signIn')
    account.pending = true
  } catch (issue) { toast.value = errorText(issue) }
}

async function poll(retries = 8) {
  if (!account.pending || polling) return
  polling = true
  try {
    const result = await request('poll')
    Object.assign(account, result)
    if (!result.pending) await load()
    else if (retries > 1) setTimeout(() => poll(retries - 1), 1000)
  } catch (issue) {
    account.pending = false
    toast.value = errorText(issue)
  } finally { polling = false }
}

async function logout() {
  try { Object.assign(account, await request('logout')); await load() }
  catch (issue) { toast.value = errorText(issue) }
}

function points(rows) {
  if (!rows?.length) return ''
  const max = Math.max(1, ...rows.map(row => row.value))
  return rows.map((row, i) => `${3 + i * 294 / Math.max(1, rows.length - 1)},${54 - (row.value / max) * 49}`).join(' ')
}

onMounted(async () => {
  try { await info() } catch (issue) { error.value = errorText(issue) }
  await load()
})
window.addEventListener('hub-resume', () => poll())
</script>

<template>
  <header class="header">
    <div class="brand"><span class="brand-mark" aria-hidden="true">Yx</span>Yaoxi Hub</div>
    <Button label="yaoxi.wiki ↗" variant="ghost" @click="open('https://yaoxi.wiki')" />
  </header>
  <main class="content">
    <Button v-if="titles[name]" class="pager" label="← 返回" variant="ghost" @click="back" />
    <h1 v-if="name !== 'home'">{{ titles[name] || ({ work:'工作台', messages:'消息', account:'账号统一中心' })[name] }}</h1>
    <Alert v-if="toast" :description="toast" closable @close="toast = ''" />
    <Alert v-if="error" type="error" :description="error"><Button label="重试" variant="outline" @click="load" /></Alert>
    <Skeleton v-if="loading && name !== 'home' && !view.items" />

    <template v-if="name === 'home'">
      <Card class="hero" padding="lg" :border="true">
        <span class="tape">Yaoxi 的手账</span><h1>所有项目，一个入口</h1><p>站点、项目与云服务</p>
        <Button label="打开工作台 →" @click="enter('work', null, true)" />
      </Card>
      <h2>站点直达</h2>
      <div class="grid"><Card v-for="[label,url] in sites" :key="url" class="tile" :title="label" :description="host(url)" interactive @click="open(url)" /></div>
      <h2>壁纸精选</h2>
      <div v-if="view.gallery?.items?.length" class="photos">
        <Card v-for="item in view.gallery.items" :key="item.url" padding="none" interactive @click="open(item.url)">
          <div class="photo-frame"><FxImage :src="item.image" :alt="item.title?.startsWith('file_') ? '壁纸预览' : item.title" width="100%" height="100%" fit="cover" /></div>
        </Card>
      </div>
      <Skeleton v-else-if="loading && !view.galleryError" />
      <Alert v-else :description="view.galleryError || '暂无公开壁纸'" />
      <h2>站点监控</h2>
      <Card title="站点状态" :description="view.status ? view.status.items.length + ' 个项目' : view.statusError || '正在读取探测结果…'" interactive @click="enter('status')" />
      <h2>数据概览</h2>
      <div class="grid">
        <Card class="tile" title="Umami" :description="umami?.status === 'available' ? '近 7 天浏览 ' + localized(umami.pageviews) : signedIn ? view.analyticsError || (umami ? sourceStatus(umami) : '正在读取') : '登录后查看'" interactive @click="report()" />
        <Card class="tile" title="Bing" :description="bing?.sites?.[0]?.periods ? '点击 ' + localized(bing.sites[0].periods['7d'].clicks) + ' · ' + date(bing.sites[0].periods['7d'].endAt) : signedIn ? view.analyticsError || (bing ? sourceStatus(bing) : '正在读取') : '登录后查看'" interactive @click="report()" />
      </div>
      <h2>快捷管理</h2><div class="grid"><Card v-for="[label,detail,page] in cloud" :key="page" :title="label" :description="detail" interactive @click="enter(page)" /></div>
    </template>

    <template v-else-if="name === 'work'">
      <div class="grid">
        <Card title="项目详情" description="站点与发布" interactive @click="enter('projects')" />
        <Card title="博客运营" description="草稿、发布与审核" interactive @click="enter('blog')" />
        <Card v-for="[label,detail,page] in cloud" :key="page" :title="label" :description="detail" interactive @click="enter(page)" />
      </div>
      <h2>GitHub 仓库</h2><Input v-model="query" placeholder="搜索仓库名称" aria-label="搜索仓库名称" clearable />
      <p v-if="view.repositories" class="muted">{{ visibleRepos.length }} 个仓库</p>
      <Card v-for="item in visibleRepos" :key="item.name" :title="item.name" :description="item.private ? 'GitHub · 私有' : 'GitHub'" interactive @click="open(item.url)" />
    </template>

    <template v-else-if="name === 'messages'">
      <Card v-for="item in view.items || []" :key="item.title + item.time" class="tile"
        :title="item.title" :description="[item.detail, date(item.time)].filter(Boolean).join(' · ')"
        :interactive="Boolean(item.url || item.projectId)" @click="item.url ? open(item.url) : item.projectId && enter('project', item.projectId)" />
      <Empty v-if="view.items && !view.items.length" description="暂无站点、构建或云操作提醒" />
    </template>

    <template v-else-if="name === 'account'">
      <Card :title="account.account || '统一账号'" :description="signedIn ? '已登录' : '使用系统安全标签页登录'">
        <div class="row">
          <Button v-if="signedIn" label="退出登录" variant="outline" @click="logout" />
          <Button v-else label="登录" @click="login" />
          <Button v-if="account.pending" label="检查登录结果" variant="outline" @click="poll(1)" />
        </div>
      </Card>
      <Card title="Hub API 地址" :description="account.origin" interactive @click="originDialog" />
      <Card v-if="signedIn" title="服务授权" :description="'腾讯云 · ' + (view.tencentConfigured ? '已配置' : '未配置') + '\nGitHub · ' + (view.githubPrivateConfigured ? '已授权私有仓库' : '公开仓库')" />
    </template>

    <template v-else-if="name === 'status'">
      <Card v-for="item in view.items || []" :key="item.id" :title="item.name" interactive @click="enter('project',item.id)">
        <p v-for="check in item.checks" :key="check.url" class="aside">{{ statusOf(check) }} · {{ projectChecks(check) }}</p>
      </Card>
    </template>

    <template v-else-if="name === 'analytics'">
      <div class="row"><Button label="Umami 大盘 ↗" variant="outline" @click="open('https://umami.yaoxi.wiki')" /><Button label="Bing 大盘 ↗" variant="outline" @click="open('https://bing.yaoxi.wiki')" /></div>
      <template v-if="signedIn">
        <Alert v-if="view.analyticsError" type="error" :description="view.analyticsError" />
        <Card v-if="umami" title="Umami · 博客访问" :description="umami.status === 'available' ? dateRange(umami) : sourceStatus(umami)">
          <template v-if="umami.status === 'available'">
            <div class="grid"><Statistic title="页面浏览" :value="localized(umami.pageviews)" /><Statistic title="访客" :value="localized(umami.visitors)" /><Statistic title="会话" :value="localized(umami.visits)" /></div>
            <h3>每日浏览量</h3>
            <svg v-if="umami.trend?.length" class="trend" viewBox="0 0 300 60" preserveAspectRatio="none" role="img" aria-label="Umami 每日浏览趋势"><polyline :points="points(umami.trend)" /></svg>
            <p v-else class="muted">{{ umami.trend ? '暂无报表' : '暂时无法读取趋势' }}</p>
            <h3>热门页面</h3><p v-for="item in umami.topPages || []" :key="item.name" class="aside">{{ item.name }} · {{ localized(item.value) }}</p>
            <p v-if="!umami.topPages?.length" class="muted">{{ umami.topPages ? '暂无排行' : '暂时无法读取排行' }}</p>
          </template>
        </Card>
        <Card v-if="bing" title="Bing · 搜索表现" :description="bing.status === 'available' ? dateRange(bingStats) : sourceStatus(bing)">
          <template v-if="bing.status === 'available'">
            <div class="row">
              <Select v-model="bingSite" :options="bingOptions" placeholder="选择站点" />
              <Select v-model="period" :options="[{label:'7 天',value:'7d'},{label:'28 天',value:'28d'}]" />
            </div>
            <template v-if="bingStats">
              <div class="grid"><Statistic title="点击" :value="localized(bingStats.clicks)" /><Statistic title="展示" :value="localized(bingStats.impressions)" /><Statistic title="点击率" :value="bingStats.ctr == null ? '—' : bingStats.ctr.toFixed(2) + '%'" /></div>
              <h3>每日点击量</h3><svg v-if="bingStats.trend?.length" class="trend" viewBox="0 0 300 60" preserveAspectRatio="none" role="img" aria-label="Bing 每日点击趋势"><polyline :points="points(bingStats.trend)" /></svg>
              <h3>热门搜索词</h3><p v-for="item in bingStats.topQueries || []" :key="item.name" class="aside">{{ item.name }} · {{ localized(item.value) }} 次点击</p>
              <p v-if="!bingStats.topQueries?.length" class="muted">{{ bingStats.topQueries ? '暂无排行' : '暂时无法读取排行' }}</p>
            </template>
            <p v-else class="muted">{{ sourceStatus(selectedBing) }}</p>
          </template>
        </Card>
      </template>
    </template>

    <template v-else-if="name === 'projects'">
      <Card v-for="item in view.items || []" :key="item.id" :title="item.name" :description="host(item.url)" interactive @click="enter('project',item.id)" />
    </template>

    <template v-else-if="name === 'project' && view.project">
      <Card :title="view.project.name" :description="view.project.host" interactive @click="open(view.project.url)" />
      <Card v-if="view.monitor" title="站点监控"><p v-for="check in view.monitor.checks" :key="check.url" class="aside">{{ statusOf(check) }} · {{ projectChecks(check) }}</p></Card>
      <template v-if="view.github">
        <Card title="GitHub 仓库" :description="view.github.repoUrl" interactive @click="open(view.github.repoUrl)" />
        <Card v-if="view.github.latestRun" :title="'最近构建 · ' + runState(view.github.latestRun)" :description="view.github.latestRun.title" interactive @click="open(view.github.latestRun.url)" />
        <Card v-else-if="view.github.workflowStatus === 'unavailable'" title="构建记录" description="暂时无法读取" />
        <Card v-if="view.github.latestFailure" title="最近失败步骤" :description="view.github.latestFailure.step?.name || view.github.latestFailure.title" interactive @click="open(view.github.latestFailure.step?.url || view.github.latestFailure.url)" />
        <Card v-if="view.github.release" :title="'最新版本 · ' + view.github.release.tag" :description="date(view.github.release.publishedAt)" interactive @click="open(view.github.release.url)" />
      </template>
      <Card title="DNSPod" interactive @click="enter(view.dns?.domain ? 'records' : 'dns', view.dns?.domain)">
        <p v-for="record in view.dns?.records || []" :key="record.type + record.value" class="aside">{{ record.type }} · {{ record.value }} · TTL {{ record.ttl }}</p>
        <p v-if="!view.dns?.records?.length" class="muted">{{ view.dns?.status === 'unavailable' ? '暂时无法读取' : '未找到关联记录' }}</p>
      </Card>
      <Card title="EdgeOne" :description="view.edge?.site ? view.edge.site.ZoneName + ' · ' + view.edge.enabled : view.edge?.status === 'unavailable' ? '暂时无法读取' : '未找到对应加速域名'" interactive @click="enter(view.edge?.site ? 'zone' : 'edge', view.edge?.site)" />
      <Card v-if="view.project.analytics" title="流量与搜索" interactive @click="report(view.project.host)">
        <p v-if="umami?.status === 'available' && umami.name === view.project.host" class="aside">Umami · 近 7 天浏览 {{ localized(umami.pageviews) }} · 访客 {{ localized(umami.visitors) }}</p>
        <p v-for="item in bing?.sites?.filter(entry => entry.name === view.project.host && entry.periods) || []" :key="item.name" class="aside">Bing · 7 天点击 {{ localized(item.periods['7d'].clicks) }} · 展示 {{ localized(item.periods['7d'].impressions) }}</p>
      </Card>
      <Card v-if="view.project.id === 'blog'" title="博客运营" interactive @click="enter('blog')" />
    </template>

    <template v-else-if="name === 'blog'">
      <Button label="打开发布编辑器 ↗" variant="outline" @click="open('https://blog.yaoxi.wiki/admin/')" />
      <template v-if="signedIn && view.posts">
        <h2>仓库草稿</h2><Empty v-if="!view.posts.drafts?.length" :description="view.posts.status === 'available' ? '暂无已同步的仓库草稿' : '暂时无法读取草稿'" />
        <Card v-for="item in view.posts.drafts || []" :key="item.slug" :title="item.title" interactive @click="open('https://blog.yaoxi.wiki/admin/')" />
        <h2>最近发布</h2><Empty v-if="!view.posts.published?.length" description="暂无发布记录" />
        <Card v-for="item in view.posts.published || []" :key="item.slug" :title="item.title" :description="date(item.published)" interactive @click="open('https://blog.yaoxi.wiki/posts/' + item.slug.split('/').map(encodeURIComponent).join('/') + '/')" />
        <h2>待审评论与弹幕</h2><Empty v-if="!view.pending?.length" :description="view.pending ? '暂无待审条目' : '暂时无法读取队列'" />
        <Card v-for="item in view.pending || []" :key="item.id" :title="item.author" :description="item.body">
          <div class="row"><Button label="通过" @click="approve(item,'approve')" /><Button label="拒绝" variant="outline" @click="approve(item,'reject')" /></div>
        </Card>
      </template>
    </template>

    <template v-else-if="name === 'dns' || name === 'edge'">
      <Button :label="name === 'dns' ? 'DNSPod 控制台 ↗' : 'EdgeOne 控制台 ↗'" variant="outline" @click="open(consoleUrls[name])" />
      <Card v-for="item in view.items || []" :key="item.DomainId || item.ZoneId" :title="item.Name || item.ZoneName"
        :description="item.Status || item.ActiveStatus" interactive @click="enter(name === 'dns' ? 'records' : 'zone',item)" />
    </template>

    <template v-else-if="name === 'records' || name === 'edgeRecords'">
      <div class="row"><Button label="新增记录" @click="editRecord(null)" /><Button v-if="name === 'records'" label="查看可用线路" variant="outline" @click="possibleLines" /></div>
      <Card v-for="item in view.items || []" :key="item.RecordId" class="tile"
        :title="item.Name + ' · ' + item.Type"
        :description="(item.Value || item.Content) + '\n' + (item.Line || item.Location || '') + ' · TTL ' + item.TTL + ' · ' + item.Status">
        <div v-if="!item.DefaultNS" class="row"><Button label="编辑" variant="outline" @click="editRecord(item)" />
          <Button :label="String(item.Status).toLowerCase() === 'disable' ? '启用' : '暂停'" variant="outline" @click="recordStatus(item)" /></div>
      </Card>
    </template>

    <template v-else-if="name === 'zone'">
      <Card :title="current.item.ZoneName" :description="'接入方式 · ' + current.item.Type">
        <div class="row"><Button label="清除 URL 缓存" @click="purgeDialog" />
          <Button v-if="current.item.Type === 'full'" label="托管 DNS" variant="outline" @click="enter('edgeRecords',current.item)" /></div>
      </Card>
      <Tabs :model-value="zoneTab" :options="[{ label:'加速域名', value:'acceleration' },{ label:'近 6 天缓存任务', value:'history' }]" @update:model-value="changeZone" />
      <Card v-for="(item,index) in view.items || []" :key="(item.DomainName || item.JobId) + index"
        :title="item.DomainName || item.Target || item.JobId" :description="item.DomainStatus || item.Status" />
    </template>
    <Card v-if="['projects','project','analytics','blog','dns','edge'].includes(name) && !signedIn"
      title="登录后查看" description="前往账号统一中心" interactive @click="enter('account',null,true)" />
    <Empty v-if="['dns','edge','records','edgeRecords','zone'].includes(name) && view.items && !view.items.length && !loading" description="暂无记录" />
    <Button v-if="view.more" label="加载更多" variant="outline" :loading="loading" @click="nextPage" />
  </main>
  <nav class="navigation" aria-label="底部导航">
    <Tabs :model-value="selected" :options="tabs" variant="pill" :appear="false" @update:model-value="enter($event,null,true)" />
  </nav>

  <Dialog :open="Boolean(dialog)" :title="dialog?.title" :description="dialog?.description" :loading="saving"
    :mask-closable="!saving" :closable="!saving" :show-confirm="dialog?.type !== 'lines'"
    :confirm-text="dialog?.type === 'record' || dialog?.type === 'purge' ? '预览变更' : '确认'"
    cancel-text="取消" @update:open="value => { if (!value && !saving) dialog = null }" @confirm="submit">
    <div v-if="dialog?.type === 'origin'" class="form"><label>服务地址<Input v-model="form.origin" placeholder="https://hub-api.yaoxi.wiki" /></label></div>
    <div v-if="dialog?.type === 'purge'" class="form"><label>缓存目标<Input v-model="form.url" placeholder="https://" /></label></div>
    <div v-if="dialog?.type === 'record'" class="form">
      <label>主机记录<Input v-model="form.name" /></label><label>记录类型<Input v-model="form.type" /></label>
      <label>记录值<Input v-model="form.value" /></label><label>线路<Input v-model="form.line" /></label>
      <label>TTL（秒）<Input v-model="form.ttl" inputmode="numeric" /></label>
      <label>MX 优先级<Input v-model="form.mx" inputmode="numeric" /></label>
    </div>
  </Dialog>
</template>
