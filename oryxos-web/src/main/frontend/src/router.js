import { ref } from 'vue'

/**
 * 极简路由：不引 vue-router。
 *
 * 七个页面（概览 + 五个查询页 + 定时任务管理页）、零嵌套、零守卫、零懒加载——引一个路由库只为做
 * 「切换一个组件」，换来的依赖体积与升级面都不划算（契约 §1 把依赖钉死在 vue / vite / @vitejs/plugin-vue 三条）。
 *
 * 走 History API 而不是 hash：URL 是 `/admin/tools` 这种干净形状，
 * 刷新能不能不 404 由服务端的 /admin/** 回落保证（WebConfig + 契约 §2）。
 */

/** 管理台的部署前缀；与 vite `base` 一致。 */
export const BASE_PATH = '/admin/'

/**
 * 页面清单（第一项是落地页）。`code` 是页头那行小号色块字，取首页区块标签（WHY / CAPABILITIES）的同一形态。
 *
 * 「概览」放第一：打开 /admin/ 先看底座此刻的整体状态（Agent / 会话 / 工具 / 运行时长 / 健康），
 * 再往下钻具体查询页（2026-10-20 用户裁决；**不是**产品介绍页——那部分在官网上）。
 */
export const ROUTES = [
  { path: '/overview', label: '概览', hint: 'Agent、会话、工具与运行状态', code: 'OVERVIEW' },
  { path: '/sessions', label: '会话', hint: '最近活跃的会话与历史', code: 'SESSIONS' },
  { path: '/profiles', label: 'Agent', hint: 'Agent 列表与配置有效性', code: 'AGENTS' },
  { path: '/tools', label: '工具', hint: '已注册工具与来源', code: 'TOOLS' },
  {
    path: '/schedules',
    label: '定时任务',
    hint: 'cron 任务、运行状态与手动触发',
    code: 'SCHEDULES'
  },
  { path: '/memory', label: '长期记忆', hint: '核心区与归档区', code: 'MEMORY' },
  { path: '/status', label: '运行状态', hint: '健康检查与版本信息', code: 'STATUS' }
]

/** 当前路由（相对 BASE_PATH，形如 `/tools`）。 */
export const currentRoute = ref(ROUTES[0].path)

/** 把浏览器地址归一化成上面六个之一；认不出来就落回第一页，不给「空白页」这种结局。 */
function normalize(pathname) {
  const index = pathname.indexOf(BASE_PATH)
  if (index < 0) {
    return ROUTES[0].path
  }
  const tail = pathname.slice(index + BASE_PATH.length - 1).split('?')[0].replace(/\/+$/, '')
  return ROUTES.some((route) => route.path === tail) ? tail : ROUTES[0].path
}

/** 按当前地址初始化一次。 */
export function resolveInitialRoute() {
  currentRoute.value = normalize(window.location.pathname)
}

/**
 * 切到某一页。
 *
 * @param {string} path ROUTES 里的 path
 */
export function navigate(path) {
  if (path === currentRoute.value) {
    return
  }
  window.history.pushState({}, '', BASE_PATH.replace(/\/+$/, '') + path)
  currentRoute.value = path
  window.scrollTo(0, 0)
}

/** 接管浏览器前进/后退。 */
export function startRouter() {
  resolveInitialRoute()
  window.addEventListener('popstate', resolveInitialRoute)
}
