<script setup>
import { computed, onMounted, ref } from 'vue'
import { get } from '../api.js'

/**
 * 定时任务页（契约 admin-console.md §3-第 6 页）。
 *
 * 数据源是**既有的** `GET /api/v1/profiles`：定时任务的定义源是 `AGENT.md` frontmatter 的
 * `schedules`，`ProfileView` 已经把它原样带出来了，故本页不需要也没有新增 REST 路径。
 *
 * 任务 id 的派生规则与 `AgentScheduler` 一致：`<Agent 名>#<序号>`（序号从 1 起、按声明顺序），
 * 页面上直接显示这个 id，运维才能在日志里对上「哪一条任务报错了」。
 */
const loading = ref(true)
const error = ref('')
const profiles = ref([])

const rows = computed(() => {
  const result = []
  for (const profile of profiles.value) {
    const schedules = profile.schedules || []
    schedules.forEach((schedule, index) => {
      result.push({
        id: profile.name + '#' + (index + 1),
        profile: profile.name,
        valid: profile.valid,
        cron: schedule.cron,
        zone: schedule.zone,
        message: schedule.message
      })
    })
  }
  return result
})

const declaringAgents = computed(() => profiles.value.filter((p) => (p.schedules || []).length > 0))

async function load() {
  loading.value = true
  error.value = ''
  try {
    profiles.value = (await get('/api/v1/profiles')) || []
  } catch (cause) {
    error.value = cause.message
    profiles.value = []
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section>
    <div v-if="loading" class="card state">正在读取定时任务…</div>

    <div v-else-if="error" class="card state state-error">读取失败：{{ error }}</div>

    <div v-else-if="rows.length === 0" class="card state">
      没有 Agent 声明定时任务。在 <span class="mono">AGENT.md</span> 的 frontmatter 里写
      <span class="mono">schedules: [{cron, zone, message}]</span> 即可。
    </div>

    <template v-else>
      <div class="card">
        <h2 class="card-title">
          共 {{ rows.length }} 条定时任务（来自 {{ declaringAgents.length }} 个 Agent）
        </h2>
        <div class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th>任务 ID</th>
                <th>cron</th>
                <th>时区</th>
                <th>消息</th>
                <th>Agent 状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in rows" :key="row.id">
                <td class="mono">{{ row.id }}</td>
                <td class="mono">{{ row.cron }}</td>
                <td class="mono">{{ row.zone || '系统时区' }}</td>
                <td class="message">{{ row.message }}</td>
                <td>
                  <span v-if="row.valid" class="tag tag-ok">有效</span>
                  <span v-else class="tag tag-err">无效</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="card">
        <h2 class="card-title">这页能回答什么、不能回答什么</h2>
        <ul class="notes">
          <li>
            <strong>定义源是 <span class="mono">AGENT.md</span></strong>：改动 frontmatter 后下一次
            <span class="mono">serve</span> / <span class="mono">gateway</span> 启动即生效，不需要重新编译。
          </li>
          <li>
            <strong>只有常驻模式注册</strong>：<span class="mono">serve</span> 与
            <span class="mono">gateway</span> 启动时注册，交互式 <span class="mono">chat</span> 不注册。
          </li>
          <li>
            <strong>cron 5 段为准、兼容 6 段</strong>：5 段按 Unix 习惯（分 时 日 月 周），6 段时首段是秒。
          </li>
          <li>
            <strong>运行时状态不在这里</strong>：最近执行、成功失败、下次触发时刻属于调度运行态，
            本节没有对应端点，也刻意不虚报——本页只呈现配置面。
          </li>
        </ul>
      </div>
    </template>
  </section>
</template>

<style scoped>
.message {
  max-width: 420px;
  white-space: pre-wrap;
  word-break: break-word;
}

.notes {
  margin: 0;
  padding-left: 18px;
  color: var(--ox-text-2);
}

.notes li + li {
  margin-top: 6px;
}
</style>
