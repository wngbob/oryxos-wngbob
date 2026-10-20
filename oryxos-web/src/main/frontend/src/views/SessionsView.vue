<script setup>
import { onMounted, ref } from 'vue'
import { get } from '../api.js'

/**
 * 会话页（契约 admin-console.md §3-第 1 页）。
 *
 * 第 27 节起数据源是 `GET /api/v1/sessions`（最近活跃的 100 条摘要），点一行再读
 * `GET /api/v1/sessions/{id}` 拿历史正文——列表不带 `messages`，一页几十个会话的正文没必要一次拉回来。
 *
 * 仍然只读：本页只发 GET，不提供创建与归档入口（归档是写操作，属契约外）。
 */
const loading = ref(true)
const error = ref('')
const sessions = ref([])
const statusFilter = ref('')

const detailLoading = ref(false)
const detailError = ref('')
const session = ref(null)

async function load() {
  loading.value = true
  error.value = ''
  try {
    sessions.value = await get('/api/v1/sessions', statusFilter.value ? { status: statusFilter.value } : undefined)
  } catch (cause) {
    // 「读不到」与「没有数据」是两件事，页面只负责如实显示（契约 §6）
    error.value = cause.message
    sessions.value = []
  } finally {
    loading.value = false
  }
}

async function open(id) {
  detailLoading.value = true
  detailError.value = ''
  session.value = null
  try {
    session.value = await get('/api/v1/sessions/' + encodeURIComponent(id))
  } catch (cause) {
    detailError.value = cause.message
  } finally {
    detailLoading.value = false
  }
}

function formatTime(value) {
  return value ? new Date(value).toLocaleString() : '—'
}

function statusClass(status) {
  return status === 'active' ? 'tag-ok' : 'tag-warn'
}

onMounted(load)
</script>

<template>
  <section>
    <form class="card toolbar" @submit.prevent="load">
      <label class="toolbar-label" for="session-status">状态</label>
      <select id="session-status" v-model="statusFilter" class="field toolbar-select" @change="load">
        <option value="">全部</option>
        <option value="active">active</option>
        <option value="archived">archived</option>
      </select>
      <button class="btn" type="submit">刷新</button>
    </form>

    <div v-if="loading" class="card state">正在读取会话列表…</div>

    <div v-else-if="error" class="card state state-error">读取失败：{{ error }}</div>

    <div v-else-if="sessions.length === 0" class="card state">
      还没有会话。会话由业务系统调用创建端点生成，管理台只读。
    </div>

    <div v-else class="card table-wrap">
      <h2 class="card-title">会话（最近活跃的 {{ sessions.length }} 条）</h2>
      <table class="table">
        <thead>
          <tr>
            <th>会话 ID</th>
            <th>Agent</th>
            <th>状态</th>
            <th>渠道 / 用户</th>
            <th>消息数</th>
            <th>最后活跃</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in sessions"
            :key="row.sessionId"
            class="row-clickable"
            :class="{ 'row-selected': session && session.sessionId === row.sessionId }"
            @click="open(row.sessionId)"
          >
            <td class="mono">{{ row.sessionId }}</td>
            <td>{{ row.profile || '—' }}</td>
            <td>
              <span :class="['tag', statusClass(row.status)]">{{ row.status }}</span>
            </td>
            <td>
              <span class="mono">{{ row.channel }}</span> /
              <span class="mono">{{ row.userId }}</span>
            </td>
            <td class="mono">{{ row.messageCount }}</td>
            <td class="mono">{{ formatTime(row.lastActiveAt) }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="detailLoading" class="card state">正在读取会话详情…</div>

    <div v-else-if="detailError" class="card state state-error">读取失败：{{ detailError }}</div>

    <template v-else-if="session">
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
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.toolbar-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--ox-text-2);
}

.toolbar-select {
  min-width: 140px;
}

.row-clickable {
  cursor: pointer;
}

.row-selected {
  background: var(--ox-glow);
}

.message-content {
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
