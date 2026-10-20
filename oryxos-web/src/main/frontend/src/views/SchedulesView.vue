<script setup>
import { computed, onMounted, ref } from 'vue'
import { get, post, put } from '../api.js'

/**
 * 定时任务页（第 28 节；管理台第一页带写操作）。
 *
 * 数据源是 `GET /api/v1/schedules`：任务定义来自 `AGENT.md` 的 `schedules`，运行状态
 * （下次触发 / 上次结果 / 次数 / 启用与否）来自 `scheduled_tasks`，两侧由 `AgentScheduler`
 * 注册时对齐。任务 id 的派生规则与 `AgentScheduler` 一致：`<Agent 名>#<序号>`。
 *
 * 写操作只有两个、且都指向 `/api/v1/schedules/**`：
 * - 「立即执行」→ `POST .../{id}/run`（不等 cron、无视启用状态，同步跑完才返回）
 * - 「启用 / 停用」→ `PUT .../{id}`（只改运行期状态，定义仍以 `AGENT.md` 为唯一真相源）
 */
const loading = ref(true)
const error = ref('')
const actionError = ref('')
const tasks = ref([])
const busyId = ref('')
const historyOpenId = ref('')
const history = ref({})

const enabledCount = computed(() => tasks.value.filter((task) => task.enabled).length)

async function load() {
  loading.value = true
  error.value = ''
  try {
    tasks.value = (await get('/api/v1/schedules')) || []
  } catch (cause) {
    error.value = cause.message
    tasks.value = []
  } finally {
    loading.value = false
  }
}

/**
 * 立即执行一次，并用返回的任务状态替换本行——同步执行，返回时 runCount 已含本次。
 *
 * @param {object} task 任务行
 */
async function runTask(task) {
  actionError.value = ''
  busyId.value = task.taskId
  try {
    const updated = await post('/api/v1/schedules/' + encodeURIComponent(task.taskId) + '/run')
    replace(updated)
    if (historyOpenId.value === task.taskId) {
      history.value[task.taskId] = await get(
        '/api/v1/schedules/' + encodeURIComponent(task.taskId) + '/executions'
      )
    }
  } catch (cause) {
    actionError.value = cause.message
  } finally {
    busyId.value = ''
  }
}

/**
 * 启用 / 停用。
 *
 * @param {object} task 任务行
 */
async function toggleTask(task) {
  actionError.value = ''
  busyId.value = task.taskId
  try {
    const updated = await put('/api/v1/schedules/' + encodeURIComponent(task.taskId), {
      enabled: !task.enabled
    })
    replace(updated)
  } catch (cause) {
    actionError.value = cause.message
  } finally {
    busyId.value = ''
  }
}

/**
 * 展开 / 收起某任务的执行历史。
 *
 * @param {object} task 任务行
 */
async function toggleHistory(task) {
  actionError.value = ''
  if (historyOpenId.value === task.taskId) {
    historyOpenId.value = ''
    return
  }
  try {
    history.value[task.taskId] = await get(
      '/api/v1/schedules/' + encodeURIComponent(task.taskId) + '/executions'
    )
    historyOpenId.value = task.taskId
  } catch (cause) {
    actionError.value = cause.message
  }
}

/** 用服务端返回的最新视图替换列表里对应的一行。 */
function replace(updated) {
  const index = tasks.value.findIndex((task) => task.taskId === updated.taskId)
  if (index >= 0) {
    tasks.value[index] = updated
  }
}

/** ISO 时刻 → 本地可读字符串；没有值就显示破折号，不编造。 */
function formatTime(value) {
  return value ? new Date(value).toLocaleString() : '—'
}

/** 上次结果的中文标签与样式。 */
function statusLabel(status) {
  if (status === 'success') {
    return { text: '成功', css: 'tag-ok' }
  }
  if (status === 'failed') {
    return { text: '失败', css: 'tag-err' }
  }
  return { text: '未执行', css: 'tag-idle' }
}

onMounted(load)
</script>

<template>
  <section>
    <div v-if="loading" class="card state">正在读取定时任务…</div>

    <div v-else-if="error" class="card state state-error">读取失败：{{ error }}</div>

    <template v-else>
      <div v-if="actionError" class="card state state-error">操作失败：{{ actionError }}</div>

      <div v-if="tasks.length === 0" class="card state">
        还没有已登记的定时任务。在 <span class="mono">AGENT.md</span> 的 frontmatter 里写
        <span class="mono">schedules: [{cron, zone, message}]</span>，重启
        <span class="mono">serve</span> / <span class="mono">gateway</span> 后即登记。
      </div>

      <template v-else>
        <div class="card">
          <h2 class="card-title">
            共 {{ tasks.length }} 条定时任务，其中 {{ enabledCount }} 条启用
          </h2>
          <div class="table-wrap">
            <table class="table">
              <thead>
                <tr>
                  <th>任务 ID</th>
                  <th>Profile</th>
                  <th>cron</th>
                  <th>下次触发</th>
                  <th>上次结果</th>
                  <th>次数</th>
                  <th>状态</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <template v-for="task in tasks" :key="task.taskId">
                  <tr>
                    <td class="mono">{{ task.taskId }}</td>
                    <td class="mono">{{ task.profileName }}</td>
                    <td class="mono">{{ task.cron }}</td>
                    <td class="time">{{ formatTime(task.nextRunAt) }}</td>
                    <td>
                      <span class="tag" :class="statusLabel(task.lastStatus).css">
                        {{ statusLabel(task.lastStatus).text }}
                      </span>
                      <div class="sub">{{ formatTime(task.lastRunAt) }}</div>
                    </td>
                    <td class="mono">{{ task.runCount }}</td>
                    <td>
                      <span v-if="task.enabled" class="tag tag-ok">启用</span>
                      <span v-else class="tag tag-idle">停用</span>
                    </td>
                    <td class="actions">
                      <button
                        class="btn"
                        :disabled="busyId === task.taskId"
                        @click="runTask(task)"
                      >
                        {{ busyId === task.taskId ? '执行中…' : '立即执行' }}
                      </button>
                      <button
                        class="btn btn-ghost"
                        :disabled="busyId === task.taskId"
                        @click="toggleTask(task)"
                      >
                        {{ task.enabled ? '停用' : '启用' }}
                      </button>
                      <button class="btn btn-ghost" @click="toggleHistory(task)">历史</button>
                    </td>
                  </tr>
                  <tr v-if="historyOpenId === task.taskId" class="history-row">
                    <td colspan="8">
                      <div v-if="!(history[task.taskId] || []).length" class="sub">
                        这条任务还没有执行记录。
                      </div>
                      <table v-else class="table nested">
                        <thead>
                          <tr>
                            <th>开始时间</th>
                            <th>会话</th>
                            <th>结果</th>
                            <th>耗时</th>
                            <th>失败原因</th>
                          </tr>
                        </thead>
                        <tbody>
                          <tr v-for="entry in history[task.taskId]" :key="entry.id">
                            <td class="time">{{ formatTime(entry.startedAt) }}</td>
                            <td class="mono">{{ entry.sessionId || '—' }}</td>
                            <td>
                              <span class="tag" :class="entry.success ? 'tag-ok' : 'tag-err'">
                                {{ entry.success ? '成功' : '失败' }}
                              </span>
                            </td>
                            <td class="mono">{{ entry.durationMs }} ms</td>
                            <td class="message">{{ entry.errorMessage || '—' }}</td>
                          </tr>
                        </tbody>
                      </table>
                    </td>
                  </tr>
                </template>
              </tbody>
            </table>
          </div>
        </div>

        <div class="card">
          <h2 class="card-title">这页能回答什么、不能回答什么</h2>
          <ul class="notes">
            <li>
              <strong>定义源是 <span class="mono">AGENT.md</span></strong
              >：cron / 时区 / 消息改动 frontmatter 后下一次常驻启动即生效，本页不提供定义修改。
            </li>
            <li>
              <strong>「立即执行」不等 cron、无视启用状态</strong
              >：它是运维的显式动作，同步跑完才返回，返回后本行计数已更新。
            </li>
            <li>
              <strong>「停用」只管到点触发</strong>：停用后到点跳过、不记历史，但「立即执行」仍可手动跑。
            </li>
            <li>
              <strong>状态与历史在 SQLite</strong
              >：<span class="mono">scheduled_tasks</span> / <span class="mono">task_executions</span>
              跨重启保留；定义本身仍从 <span class="mono">AGENT.md</span> 重新登记。
            </li>
          </ul>
        </div>
      </template>
    </template>
  </section>
</template>

<style scoped>
.message {
  max-width: 420px;
  white-space: pre-wrap;
  word-break: break-word;
}

.time {
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

.sub {
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--ox-text-2);
}

.actions {
  white-space: nowrap;
}

.btn + .btn {
  margin-left: 6px;
}

.history-row > td {
  background: var(--ox-bg-soft);
}

.nested {
  margin-top: 4px;
}

.tag-idle {
  color: var(--ox-text-2);
  background: var(--ox-bg-soft);
  border-color: var(--ox-border);
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
