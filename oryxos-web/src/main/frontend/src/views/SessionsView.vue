<script setup>
import { ref } from 'vue'
import { get } from '../api.js'

/**
 * 会话页（契约 admin-console.md §3-第 1 页）。
 *
 * 是「按会话 ID 查询」而不是「会话列表」：本节的 10 端点契约里没有 `GET /api/v1/sessions`，
 * 照课件的「会话列表」写就会造出一个契约外端点（软门禁 ①）。会话 ID 由创建会话的响应给出，
 * 在这里粘贴即可。
 */
const sessionId = ref('')
const loading = ref(false)
const error = ref('')
const session = ref(null)

async function query() {
  const id = sessionId.value.trim()
  if (!id) {
    error.value = '请输入会话 ID'
    session.value = null
    return
  }
  loading.value = true
  error.value = ''
  session.value = null
  try {
    session.value = await get('/api/v1/sessions/' + encodeURIComponent(id))
  } catch (cause) {
    // 「查不到」与「端点坏了」都由信封的 message 说明，页面只负责如实显示（契约 §6）
    error.value = cause.message
  } finally {
    loading.value = false
  }
}

function formatTime(value) {
  return value ? new Date(value).toLocaleString() : '—'
}

function statusClass(status) {
  return status === 'active' ? 'tag-ok' : 'tag-warn'
}
</script>

<template>
  <section>
    <form class="card query" @submit.prevent="query">
      <label class="query-label" for="session-id">会话 ID</label>
      <input
        id="session-id"
        v-model="sessionId"
        class="field query-input mono"
        type="text"
        placeholder="例如 web:default:daily-weather:20261019"
        autocomplete="off"
      />
      <button class="btn btn-primary" type="submit">查询</button>
    </form>

    <div v-if="loading" class="card state">正在查询…</div>

    <div v-else-if="error" class="card state state-error">查询失败：{{ error }}</div>

    <div v-else-if="!session" class="card state">
      输入会话 ID 后查询。会话 ID 由业务系统调用创建会话端点时拿到的响应给出，管理台不提供会话的制造入口。
    </div>

    <template v-else>
      <div class="card">
        <h2 class="card-title">会话概览</h2>
        <table class="table">
          <tbody>
            <tr>
              <th>会话 ID</th>
              <td class="mono">{{ session.sessionId }}</td>
            </tr>
            <tr>
              <th>Agent</th>
              <td>{{ session.profile || '—' }}</td>
            </tr>
            <tr>
              <th>状态</th>
              <td>
                <span :class="['tag', statusClass(session.status)]">{{ session.status }}</span>
              </td>
            </tr>
            <tr>
              <th>渠道 / 用户</th>
              <td>
                <span class="mono">{{ session.channel }}</span> /
                <span class="mono">{{ session.userId }}</span>
              </td>
            </tr>
            <tr>
              <th>创建 / 最后活跃</th>
              <td class="mono">
                {{ formatTime(session.createdAt) }} / {{ formatTime(session.lastActiveAt) }}
              </td>
            </tr>
            <tr>
              <th>归档时刻</th>
              <td class="mono">{{ formatTime(session.archivedAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="card">
        <h2 class="card-title">历史消息（最近 100 条，按时间正序）</h2>
        <p v-if="!session.messages || session.messages.length === 0" class="state">
          这个会话还没有消息。
        </p>
        <div v-else class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th>角色</th>
                <th>内容</th>
                <th>时间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(message, index) in session.messages" :key="index">
                <td class="mono">{{ message.role }}</td>
                <td class="message-content">{{ message.content || '（无文本内容）' }}</td>
                <td class="mono">{{ formatTime(message.at) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.query {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.query-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--ox-text-2);
}

.query-input {
  flex: 1;
  min-width: 240px;
}

.message-content {
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
