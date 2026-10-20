<script setup>
import { computed, onMounted, ref } from 'vue'
import { get } from '../api.js'

/**
 * 概览页（2026-10-20 用户裁决：导航第一项，打开 /admin/ 先看这一页）。
 *
 * 它是**运行时状态的总览**，不是产品介绍——「OryxOS 是什么」在官网上讲，这里不重复。
 * 展示的是底座此刻的真实数字：多少个 Agent、多少活跃会话、注册了多少工具、跑了多久，
 * 以及健康检查的三项（整体 / 数据库 / Provider 配置就绪）。
 *
 * 数据全部来自既有只读端点（/info、/health、/profiles、/tools），**本节没有新增任何 REST 路径**。
 * 四个请求分开报错：一个端点挂了不该把其余数字一起藏起来——排障时「哪个读得到、哪个读不到」
 * 本身就是线索（与「运行状态」页同一条理由）。
 */

const loading = ref(true)
const errors = ref({})
const info = ref(null)
const health = ref(null)
const profiles = ref([])
const tools = ref([])

/** 工具按来源分组：`builtin` / `annotation` / `mcp:<server>`，与服务端给的 origin 原样一致。 */
const toolOrigins = computed(() => {
  const counts = new Map()
  for (const tool of tools.value) {
    const origin = tool.origin || 'unknown'
    counts.set(origin, (counts.get(origin) || 0) + 1)
  }
  return [...counts.entries()]
    .map(([origin, count]) => ({ origin, count }))
    .sort((left, right) => right.count - left.count || left.origin.localeCompare(right.origin))
})

/** 可用 Agent 数（`/profiles` 的口径，含装载失败的项）；`/info` 的 `agents` 不含失败的。 */
const profileStats = computed(() => {
  const valid = profiles.value.filter((profile) => profile.valid).length
  return { total: profiles.value.length, valid, invalid: profiles.value.length - valid }
})

const overallClass = computed(() => {
  if (!health.value) {
    return 'tag-warn'
  }
  return health.value.status === 'UP' ? 'tag-ok' : 'tag-err'
})

function providerText(profile) {
  if (!profile.provider) {
    return '—'
  }
  return profile.provider.name + ' / ' + profile.provider.model
}

function formatUptime(seconds) {
  if (seconds === null || seconds === undefined) {
    return '—'
  }
  const total = Number(seconds)
  const days = Math.floor(total / 86400)
  const hours = Math.floor((total % 86400) / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  if (days > 0) {
    return days + ' 天 ' + hours + ' 小时'
  }
  if (hours > 0) {
    return hours + ' 小时 ' + minutes + ' 分'
  }
  return minutes + ' 分'
}

async function loadSection(key, path, apply) {
  try {
    apply(await get(path))
  } catch (cause) {
    errors.value = { ...errors.value, [key]: cause.message }
  }
}

async function load() {
  loading.value = true
  errors.value = {}
  await Promise.all([
    loadSection('info', '/api/v1/info', (data) => (info.value = data)),
    loadSection('health', '/api/v1/health', (data) => (health.value = data)),
    loadSection('profiles', '/api/v1/profiles', (data) => (profiles.value = data || [])),
    loadSection('tools', '/api/v1/tools', (data) => (tools.value = data || []))
  ])
  loading.value = false
}

onMounted(load)
</script>

<template>
  <section>
    <p class="live-line">
      <span class="tag tag-ok">实时</span>
      <span class="muted">打开本页时读取一次，不做定时轮询；逐项明细见左侧各页。</span>
    </p>

    <!-- 四个计数卡：Agent / 活跃会话 / 工具 / 运行时长 -->
    <div class="kpis">
      <div class="kpi">
        <span class="kpi-label">Agent</span>
        <span class="kpi-value mono">{{ info ? info.agents : '—' }}</span>
        <span class="kpi-foot muted">
          <template v-if="errors.profiles">读取失败</template>
          <template v-else-if="loading">正在读取…</template>
          <template v-else>可用 {{ profileStats.valid }} / 共 {{ profileStats.total }} 个</template>
        </span>
      </div>
      <div class="kpi">
        <span class="kpi-label">活跃会话</span>
        <span class="kpi-value mono">{{ info ? info.activeSessions : '—' }}</span>
        <span class="kpi-foot muted">状态为 active 的会话</span>
      </div>
      <div class="kpi">
        <span class="kpi-label">已注册工具</span>
        <span class="kpi-value mono">{{ info ? info.registeredTools : '—' }}</span>
        <span class="kpi-foot muted">
          <template v-if="errors.tools">读取失败</template>
          <template v-else-if="loading">正在读取…</template>
          <template v-else>{{ toolOrigins.length }} 种来源</template>
        </span>
      </div>
      <div class="kpi">
        <span class="kpi-label">已运行</span>
        <span class="kpi-value mono">{{ info ? formatUptime(info.uptimeSeconds) : '—' }}</span>
        <span class="kpi-foot muted">{{ info ? 'v' + info.version : '—' }}</span>
      </div>
    </div>

    <p v-if="errors.info" class="card state state-error">
      运行信息读取失败：{{ errors.info }}
    </p>

    <!-- 健康检查三项 -->
    <div class="card">
      <h2 class="card-title">健康检查</h2>
      <p v-if="loading" class="state">正在探测…</p>
      <p v-else-if="errors.health" class="state state-error">读取失败：{{ errors.health }}</p>
      <table v-else-if="health" class="table">
        <tbody>
          <tr>
            <th>整体状态</th>
            <td><span :class="['tag', overallClass]">{{ health.status }}</span></td>
          </tr>
          <tr>
            <th>数据库</th>
            <td>
              <span :class="['tag', health.db === 'UP' ? 'tag-ok' : 'tag-err']">{{ health.db }}</span>
            </td>
          </tr>
          <tr>
            <th>Provider 配置就绪</th>
            <td>
              <span
                v-for="(value, name) in health.providers"
                :key="name"
                :class="['tag', value === 'UP' ? 'tag-ok' : 'tag-warn']"
              >
                {{ name }}：{{ value }}
              </span>
              <span v-if="!health.providers || Object.keys(health.providers).length === 0" class="muted">
                —
              </span>
            </td>
          </tr>
        </tbody>
      </table>
      <p class="footnote muted">只表达「凭证配好了没有」，不发真实网络请求。</p>
    </div>

    <div class="grid">
      <!-- Agent 清单（紧凑版） -->
      <div class="card">
        <h2 class="card-title">
          Agent 清单
          <span v-if="!loading && !errors.profiles" class="count muted">{{ profileStats.total }}</span>
        </h2>
        <p v-if="loading" class="state">正在读取…</p>
        <p v-else-if="errors.profiles" class="state state-error">读取失败：{{ errors.profiles }}</p>
        <p v-else-if="profiles.length === 0" class="state">
          工作区里还没有 Agent。明细见左侧「Agent」页。
        </p>
        <table v-else class="table">
          <thead>
            <tr>
              <th>名称</th>
              <th>厂商 / 模型</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="profile in profiles" :key="profile.name">
              <td class="mono">{{ profile.name }}</td>
              <td class="mono">{{ providerText(profile) }}</td>
              <td>
                <span :class="['tag', profile.valid ? 'tag-ok' : 'tag-err']">
                  {{ profile.valid ? '有效' : '无效' }}
                </span>
              </td>
            </tr>
          </tbody>
        </table>
        <p v-if="profileStats.invalid > 0" class="footnote muted">
          {{ profileStats.invalid }} 个 Agent 装载失败，原因见「Agent」页的「无效」行。
        </p>
      </div>

      <!-- 工具构成 -->
      <div class="card">
        <h2 class="card-title">
          工具构成
          <span v-if="!loading && !errors.tools" class="count muted">{{ tools.length }}</span>
        </h2>
        <p v-if="loading" class="state">正在读取…</p>
        <p v-else-if="errors.tools" class="state state-error">读取失败：{{ errors.tools }}</p>
        <p v-else-if="tools.length === 0" class="state">暂无工具。</p>
        <table v-else class="table">
          <thead>
            <tr>
              <th>来源</th>
              <th>数量</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="entry in toolOrigins" :key="entry.origin">
              <td class="mono">{{ entry.origin }}</td>
              <td class="mono">{{ entry.count }}</td>
            </tr>
          </tbody>
        </table>
        <p class="footnote muted">口径是全量注册表，不按 Agent 过滤；明细见左侧「工具」页。</p>
      </div>
    </div>

    <p class="foot muted">
      会话明细按 ID 查询（左侧「会话」页）——本节没有会话列表端点，本页也不虚报「全部会话」。
    </p>
  </section>
</template>

<style scoped>
.live-line {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0 0 16px;
  font-size: 12.5px;
}

/* 首页数字卡的节奏：小数标 → 大号等宽数字 → 一行说明 */
.kpis {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 18px;
}

.kpi {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 18px 20px;
  background: var(--ox-card);
  border: 1px solid var(--ox-border);
  border-radius: var(--ox-radius-card);
  transition: border-color 0.15s ease;
}

.kpi:hover {
  border-color: var(--ox-brand);
}

.kpi-label {
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 1.5px;
  color: var(--ox-brand-deep);
}

.kpi-value {
  font-size: 30px;
  font-weight: 800;
  line-height: 1.2;
}

.kpi-foot {
  font-size: 12px;
}

.grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
  margin-top: 14px;
}

/* 只作用于本页直属的卡片（网格里的两张卡由 gap 管间距） */
section > .card + .card {
  margin-top: 14px;
}

.count {
  margin-left: 6px;
  font-size: 13px;
  font-weight: 600;
}

.footnote {
  margin: 12px 0 0;
  font-size: 12px;
}

.foot {
  margin: 20px 0 0;
  font-size: 12px;
}

@media (max-width: 1100px) {
  .kpis {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .kpis,
  .grid {
    grid-template-columns: 1fr;
  }
}
</style>
