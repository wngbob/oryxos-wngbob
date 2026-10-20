<script setup>
import { computed, onMounted, ref } from 'vue'
import { get } from '../api.js'

/**
 * 运行状态页（契约 admin-console.md §3-第 5 页）。
 *
 * 两个端点分开取、分开报错：健康检查挂了不该把版本信息一起藏起来——
 * 排障时「哪个读得到、哪个读不到」本身就是线索。
 */
const loadingHealth = ref(true)
const loadingInfo = ref(true)
const healthError = ref('')
const infoError = ref('')
const health = ref(null)
const info = ref(null)

const overallClass = computed(() => {
  if (!health.value) {
    return 'tag-err'
  }
  return health.value.status === 'UP' ? 'tag-ok' : 'tag-err'
})

async function loadHealth() {
  loadingHealth.value = true
  healthError.value = ''
  try {
    health.value = await get('/api/v1/health')
  } catch (cause) {
    healthError.value = cause.message
  } finally {
    loadingHealth.value = false
  }
}

async function loadInfo() {
  loadingInfo.value = true
  infoError.value = ''
  try {
    info.value = await get('/api/v1/info')
  } catch (cause) {
    infoError.value = cause.message
  } finally {
    loadingInfo.value = false
  }
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

onMounted(() => {
  loadHealth()
  loadInfo()
})
</script>

<template>
  <section>
    <div class="card">
      <h2 class="card-title">健康检查</h2>
      <p v-if="loadingHealth" class="state">正在探测…</p>
      <p v-else-if="healthError" class="state state-error">读取失败：{{ healthError }}</p>
      <template v-else-if="health">
        <table class="table">
          <tbody>
            <tr>
              <th>整体状态</th>
              <td><span :class="['tag', overallClass]">{{ health.status }}</span></td>
            </tr>
            <tr>
              <th>数据库</th>
              <td>
                <span :class="['tag', health.db === 'UP' ? 'tag-ok' : 'tag-err']">
                  {{ health.db }}
                </span>
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
        <p class="footnote muted">
          Provider 一列只表达「凭证配好了没有」，不发真实网络请求（健康检查不该有副作用）。
        </p>
      </template>
    </div>

    <div class="card">
      <h2 class="card-title">运行信息</h2>
      <p v-if="loadingInfo" class="state">正在读取…</p>
      <p v-else-if="infoError" class="state state-error">读取失败：{{ infoError }}</p>
      <table v-else-if="info" class="table">
        <tbody>
          <tr>
            <th>名称 / 版本</th>
            <td class="mono">{{ info.name }} / {{ info.version }}</td>
          </tr>
          <tr>
            <th>Java 版本</th>
            <td class="mono">{{ info.javaVersion }}</td>
          </tr>
          <tr>
            <th>Agent 数</th>
            <td class="mono">{{ info.agents }}</td>
          </tr>
          <tr>
            <th>活跃会话数</th>
            <td class="mono">{{ info.activeSessions }}</td>
          </tr>
          <tr>
            <th>已注册工具数</th>
            <td class="mono">{{ info.registeredTools }}</td>
          </tr>
          <tr>
            <th>已运行</th>
            <td class="mono">{{ formatUptime(info.uptimeSeconds) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.tag {
  margin: 0 6px 4px 0;
}

.footnote {
  margin: 12px 0 0;
  font-size: 12px;
}
</style>
