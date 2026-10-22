<script setup>
import { computed, onMounted } from 'vue'
import logoIcon from './assets/logo-icon.svg'
import { ROUTES, currentRoute, navigate, startRouter } from './router.js'
import OverviewView from './views/OverviewView.vue'
import ProfilesView from './views/ProfilesView.vue'
import SchedulesView from './views/SchedulesView.vue'
import SessionsView from './views/SessionsView.vue'
import StatusView from './views/StatusView.vue'
import ToolsView from './views/ToolsView.vue'

/**
 * 路由 → 组件。六个页面全部静态引入：一共就六个，省下来的只有麻烦。
 *
 * 品牌图标与官网首页同源（`website/public/images/logo-icon.svg` 的逐字副本，
 * 由 `AdminThemeSourceTest` 逐字节比对——改官网不跟这里会红）。
 */
const VIEWS = {
  '/overview': OverviewView,
  '/sessions': SessionsView,
  '/profiles': ProfilesView,
  '/tools': ToolsView,
  '/schedules': SchedulesView,
  '/status': StatusView
}

const activeView = computed(() => VIEWS[currentRoute.value] || OverviewView)
const activeMeta = computed(
  () => ROUTES.find((route) => route.path === currentRoute.value) || ROUTES[0]
)

onMounted(startRouter)
</script>

<template>
  <div class="shell">
    <aside class="sidebar">
      <div class="brand">
        <img class="brand-logo" :src="logoIcon" alt="OryxOS" />
        <div class="brand-text">
          <div class="brand-name">OryxOS</div>
          <span class="brand-sub">管理台</span>
        </div>
      </div>

      <!-- 用真正的 <a href>：中键 / 新标签 / 复制链接都照常工作，点击再拦下来走 History API -->
      <nav class="nav">
        <a
          v-for="route in ROUTES"
          :key="route.path"
          :href="'/admin' + route.path"
          :class="['nav-item', { 'nav-active': route.path === currentRoute }]"
          @click.prevent="navigate(route.path)"
        >
          <span class="nav-label">{{ route.label }}</span>
          <span class="nav-hint">{{ route.hint }}</span>
        </a>
      </nav>

      <footer class="foot">数据全部来自 /api/v1 · 定时任务页与 Agent 详情可写</footer>
    </aside>

    <main class="content">
      <header class="page-head">
        <p class="eyebrow">{{ activeMeta.code }}</p>
        <h1 class="page-title">{{ activeMeta.label }}</h1>
        <p class="page-hint muted">{{ activeMeta.hint }}</p>
      </header>
      <component :is="activeView" />
    </main>
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  min-height: 100%;
}

.sidebar {
  width: 240px;
  flex: 0 0 240px;
  display: flex;
  flex-direction: column;
  padding: 22px 14px;
  background: var(--ox-card);
  border-right: 1px solid var(--ox-border);
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 8px 18px;
  margin-bottom: 14px;
  border-bottom: 1px solid var(--ox-border);
}

/* 首页徽标同款：官网 /images/logo-icon.svg 的逐字副本（AdminThemeSourceTest 逐字节比对） */
.brand-logo {
  width: 34px;
  height: 34px;
  border-radius: 9px;
  box-shadow: 0 6px 16px -8px rgba(12, 20, 36, 0.45);
}

.brand-name {
  font-size: 18px;
  font-weight: 800;
  line-height: 1.3;
  letter-spacing: -0.2px;
}

/* 首页徽标同款：品牌色文字 + 柔光底 + 999px 药丸 */
.brand-sub {
  display: inline-block;
  margin-top: 3px;
  padding: 0 10px;
  font-size: 11px;
  line-height: 1.7;
  color: var(--ox-brand-deep);
  background: var(--ox-glow);
  border: 1px solid var(--ox-border);
  border-radius: 999px;
}

.nav {
  display: flex;
  flex-direction: column;
  gap: 4px;
  flex: 1;
}

.nav-item {
  display: flex;
  flex-direction: column;
  padding: 9px 12px;
  border-left: 3px solid transparent;
  border-radius: 0 var(--ox-radius-control) var(--ox-radius-control) 0;
  color: var(--ox-text);
  transition: background 0.15s ease, color 0.15s ease;
}

.nav-item:hover {
  background: var(--ox-bg-soft);
}

/* 激活项用首页「左描边 + 柔光底」的语言，文字落品牌色 */
.nav-active {
  border-left-color: var(--ox-brand);
  background: var(--ox-glow);
  color: var(--ox-brand-deep);
}

.nav-label {
  font-weight: 600;
}

.nav-hint {
  font-size: 11.5px;
  color: var(--ox-text-2);
}

.foot {
  padding: 14px 8px 0;
  font-size: 11px;
  color: var(--ox-text-2);
  border-top: 1px solid var(--ox-border);
}

.content {
  flex: 1;
  min-width: 0;
  padding: 32px 36px 56px;
}

.page-head {
  margin-bottom: 24px;
}

.page-hint {
  margin: 6px 0 0;
  font-size: 14px;
}

/* 窄屏：导航收成顶部一行，内容区不再被挤压 */
@media (max-width: 760px) {
  .shell {
    flex-direction: column;
  }

  .sidebar {
    width: 100%;
    flex: none;
    border-right: none;
    border-bottom: 1px solid var(--ox-border);
  }

  .nav {
    flex-direction: row;
    flex-wrap: wrap;
  }

  .nav-item {
    border-left: none;
    border-bottom: 3px solid transparent;
    border-radius: var(--ox-radius-control) var(--ox-radius-control) 0 0;
  }

  .nav-active {
    border-bottom-color: var(--ox-brand);
  }

  .nav-hint {
    display: none;
  }

  .content {
    padding: 20px 16px 40px;
  }
}
</style>
