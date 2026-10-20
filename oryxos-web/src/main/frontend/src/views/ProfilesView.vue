<script setup>
import { onMounted, ref } from 'vue'
import { get } from '../api.js'

/**
 * Agent 页（契约 admin-console.md §3-第 2 页）。
 *
 * 装载失败的 Agent 也在列表里（`valid:false` + `error`）：管理台要回答的是「哪个 Agent 的
 * AGENT.md 写坏了」，把坏的那个藏掉等于把要查的东西藏掉（FR-018）。
 */
const loading = ref(true)
const error = ref('')
const profiles = ref([])

async function load() {
  loading.value = true
  error.value = ''
  try {
    profiles.value = await get('/api/v1/profiles')
  } catch (cause) {
    error.value = cause.message
    profiles.value = []
  } finally {
    loading.value = false
  }
}

function providerText(profile) {
  if (!profile.provider) {
    return '—'
  }
  return profile.provider.name + ' / ' + profile.provider.model
}

function scheduleText(schedule) {
  return schedule.zone ? schedule.cron + '（' + schedule.zone + '）' : schedule.cron
}

onMounted(load)
</script>

<template>
  <section>
    <div v-if="loading" class="card state">正在读取 Agent 列表…</div>

    <div v-else-if="error" class="card state state-error">读取失败：{{ error }}</div>

    <div v-else-if="profiles.length === 0" class="card state">
      工作区里还没有 Agent。在 <span class="mono">.oryxos/agents/&lt;name&gt;/AGENT.md</span> 放一份配置即可。
    </div>

    <div v-else class="card">
      <h2 class="card-title">共 {{ profiles.length }} 个 Agent（含装载失败的）</h2>
      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>名称</th>
              <th>描述</th>
              <th>厂商 / 模型</th>
              <th>工具</th>
              <th>定时任务</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="profile in profiles" :key="profile.name">
              <td class="mono">{{ profile.name }}</td>
              <td>{{ profile.description || '—' }}</td>
              <td class="mono">{{ providerText(profile) }}</td>
              <td>
                <span v-if="profile.tools.length === 0" class="muted">—</span>
                <span v-for="tool in profile.tools" :key="tool" class="tag mono">{{ tool }}</span>
              </td>
              <td class="mono">
                <span v-if="profile.schedules.length === 0" class="muted">—</span>
                <div v-for="(schedule, index) in profile.schedules" :key="index">
                  {{ scheduleText(schedule) }}
                </div>
              </td>
              <td>
                <span v-if="profile.valid" class="tag tag-ok">有效</span>
                <span v-else class="tag tag-err">无效</span>
                <div v-if="!profile.valid" class="invalid-reason">{{ profile.error }}</div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </section>
</template>

<style scoped>
.tag {
  margin: 0 4px 4px 0;
}

.invalid-reason {
  margin-top: 4px;
  font-size: 12px;
  color: var(--ox-err);
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
