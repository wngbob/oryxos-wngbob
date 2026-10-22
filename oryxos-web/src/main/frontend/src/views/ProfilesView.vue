<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { del, get, post } from '../api.js'

/**
 * Agent 页（第 30 节 FR-023，契约 agent-lifecycle-api.md / workspace-memory.md）。
 *
 * 对外只有一个 Agent 列表：点一行进入详情，详情里五个页签——基本信息 / 生成 / 文件 / 会话 / 记忆。
 * 没有独立「工作区」菜单：工作区是 Agent 的目录，浏览它必须经由某个 Agent（§5.4 裁决）。
 *
 * 列表读 `GET /api/v1/agents`（已注册的那个视图，plan D13）；`/api/v1/profiles` 仍保留，
 * 它带 `valid/error`，诊断「谁写坏了」时还用它——但「能管什么」的真相源是 Agent 资源。
 *
 * 写操作只经 `api.js`：`post` 与新增的 `del`。`AdminConsoleSourceTest` 按端点白名单判卷
 * （只许 `/api/v1/agents**` 与 `/api/v1/workspace/file`），在页面里直接 fetch 会红。
 */
const loading = ref(true)
const error = ref('')
const agents = ref([])

const creating = ref(false)
const draft = reactive({ name: '', description: '' })

const detail = ref(null)
const tab = ref('basic')
const actionError = ref('')
const actionNotice = ref('')

const TABS = [
  { key: 'basic', label: '基本信息' },
  { key: 'generate', label: '生成' },
  { key: 'files', label: '文件' },
  { key: 'session', label: '会话' },
  { key: 'memory', label: '记忆' }
]

// ---- 基本信息 / 生成 ----

const description = ref('')
const generating = ref(false)
const generatedFiles = ref({})
const savingGenerated = ref(false)

// ---- 文件 ----

const tree = ref([])
const treeError = ref('')
const selectedPath = ref('')
const fileContent = ref('')
const fileLoading = ref(false)
const fileSaving = ref(false)

// ---- 会话 ----

const session = ref(null)
const sessionError = ref('')
const messageText = ref('')
const sending = ref(false)

// ---- 记忆 ----

const memory = ref({ core: '', archival: '', truncated: false })
const memoryError = ref('')
const memoryLoading = ref(false)

const memoryCoreEmpty = computed(() => !memory.value.core || memory.value.core.trim() === '')
const memoryArchivalEmpty = computed(
  () => !memory.value.archival || memory.value.archival.trim() === ''
)

async function load() {
  loading.value = true
  error.value = ''
  try {
    agents.value = await get('/api/v1/agents')
  } catch (cause) {
    error.value = cause.message
    agents.value = []
  } finally {
    loading.value = false
  }
}

function clearActionState() {
  actionError.value = ''
  actionNotice.value = ''
}

async function createAgent() {
  clearActionState()
  if (!draft.name || !draft.name.trim()) {
    actionError.value = '名称不能为空'
    return
  }
  try {
    const created = await post('/api/v1/agents', {
      name: draft.name.trim(),
      description: draft.description.trim()
    })
    creating.value = false
    draft.name = ''
    draft.description = ''
    await load()
    openAgent(created.name)
  } catch (cause) {
    actionError.value = cause.message
  }
}

async function removeAgent() {
  clearActionState()
  const name = detail.value.name
  if (!window.confirm('删除 Agent「' + name + '」？定时任务会注销、目录移入 .oryxos/archive/，不物理删除。')) {
    return
  }
  try {
    await del('/api/v1/agents/' + encodeURIComponent(name))
    detail.value = null
    generatedFiles.value = {}
    await load()
  } catch (cause) {
    actionError.value = cause.message
  }
}

/** 详情不发第二请求：列表那一条已是 `AgentView`，字段与 `GET /agents/{name}` 完全相同。 */
function openAgent(name) {
  clearActionState()
  detail.value = agents.value.find((agent) => agent.name === name) || null
  if (!detail.value) {
    actionError.value = '列表里没有这个 Agent（可能刚被删掉）：' + name
    return
  }
  tab.value = 'basic'
  description.value = detail.value.description || ''
  generatedFiles.value = {}
  selectedPath.value = ''
  fileContent.value = ''
  tree.value = []
  treeError.value = ''
  session.value = null
  sessionError.value = ''
  messageText.value = ''
  memory.value = { core: '', archival: '', truncated: false }
  memoryError.value = ''
}

function backToList() {
  clearActionState()
  detail.value = null
  generatedFiles.value = {}
}

/** 切页签时按需装载：一次进入详情就把五个页签的数据全拉一遍，等于白花四次请求。 */
function selectTab(key) {
  clearActionState()
  tab.value = key
  if (key === 'files' && tree.value.length === 0) {
    loadTree()
  }
  if (key === 'session' && session.value === null) {
    loadSession()
  }
  if (key === 'memory') {
    loadMemory()
  }
}

async function generate() {
  clearActionState()
  if (!description.value.trim()) {
    actionError.value = '先用一句话说清楚要这个 Agent 做什么'
    return
  }
  generating.value = true
  try {
    generatedFiles.value = await post(
      '/api/v1/agents/' + encodeURIComponent(detail.value.name) + '/generate-files',
      { description: description.value.trim() }
    )
    actionNotice.value = '草稿已生成，还没有落盘——检查后点「保存并生效」'
  } catch (cause) {
    actionError.value = cause.message
  } finally {
    generating.value = false
  }
}

async function saveGenerated() {
  clearActionState()
  savingGenerated.value = true
  try {
    const updated = await post(
      '/api/v1/agents/' + encodeURIComponent(detail.value.name) + '/files',
      { files: generatedFiles.value }
    )
    detail.value = updated
    generatedFiles.value = {}
    await load()
    actionNotice.value = '已保存并生效（定时任务按新定义重排）'
  } catch (cause) {
    actionError.value = cause.message
  } finally {
    savingGenerated.value = false
  }
}

/** 把树拍平成带缩进的列表：递归组件为这一点功能不值当。 */
function flatten(nodes, depth, out) {
  for (const node of nodes) {
    out.push({ name: node.name, path: node.path, directory: node.directory, depth })
    if (node.directory && node.children.length > 0) {
      flatten(node.children, depth + 1, out)
    }
  }
  return out
}

const flatTree = computed(() => flatten(tree.value, 0, []))

async function loadTree() {
  treeError.value = ''
  try {
    tree.value = await get('/api/v1/workspace/tree')
  } catch (cause) {
    treeError.value = cause.message
    tree.value = []
  }
}

async function openFile(path) {
  clearActionState()
  fileLoading.value = true
  selectedPath.value = path
  fileContent.value = ''
  try {
    const file = await get('/api/v1/workspace/file', { path })
    fileContent.value = file.content
  } catch (cause) {
    actionError.value = cause.message
  } finally {
    fileLoading.value = false
  }
}

async function saveFile() {
  clearActionState()
  fileSaving.value = true
  try {
    await post('/api/v1/workspace/file', { path: selectedPath.value, content: fileContent.value })
    // AGENT.md 走的是生命周期编排（校验 + 重注册），保存后列表可能变（比如改了 description）
    await load()
    actionNotice.value = '已保存：' + selectedPath.value
  } catch (cause) {
    actionError.value = cause.message
  } finally {
    fileSaving.value = false
  }
}

async function loadSession() {
  sessionError.value = ''
  try {
    session.value = await get('/api/v1/agents/' + encodeURIComponent(detail.value.name) + '/session')
  } catch (cause) {
    sessionError.value = cause.message
    session.value = null
  }
}

async function sendMessage() {
  clearActionState()
  if (!messageText.value.trim()) {
    return
  }
  sending.value = true
  try {
    await post(
      '/api/v1/agents/' + encodeURIComponent(detail.value.name) + '/session/messages',
      { content: messageText.value.trim() }
    )
    messageText.value = ''
    await loadSession()
  } catch (cause) {
    actionError.value = cause.message
  } finally {
    sending.value = false
  }
}

async function loadMemory() {
  memoryError.value = ''
  memoryLoading.value = true
  try {
    memory.value = await get('/api/v1/agents/' + encodeURIComponent(detail.value.name) + '/memory')
  } catch (cause) {
    memoryError.value = cause.message
  } finally {
    memoryLoading.value = false
  }
}

function providerText(agent) {
  if (!agent.providerName) {
    return '—'
  }
  return agent.providerName + ' / ' + agent.providerModel
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

    <!-- 列表 -->
    <template v-else-if="detail === null">
      <div class="card">
        <div class="toolbar">
          <h2 class="card-title">共 {{ agents.length }} 个 Agent</h2>
          <button class="btn btn-primary btn-sm" @click="creating = !creating">
            {{ creating ? '收起' : '新建 Agent' }}
          </button>
        </div>

        <div v-if="creating" class="form">
          <label class="form-row">
            <span class="form-label">名称</span>
            <input v-model="draft.name" class="field" placeholder="daily-weather（字母数字 . _ -）" />
          </label>
          <label class="form-row">
            <span class="form-label">一句话说明</span>
            <input
              v-model="draft.description"
              class="field"
              placeholder="每天早上九点查北京天气，把穿搭建议发到团队群"
            />
          </label>
          <div class="form-actions">
            <button class="btn btn-primary btn-sm" @click="createAgent">新建</button>
            <span class="muted">保存后立刻注册，不用重启；有 schedules 就到点自己跑</span>
          </div>
        </div>

        <div v-if="actionError" class="state state-error">{{ actionError }}</div>

        <p v-if="agents.length === 0" class="state">
          工作区里还没有 Agent。可以在这里新建，或往
          <span class="mono">.oryxos/agents/&lt;name&gt;/</span> 丢一个目录（几秒内自动拾取）。
        </p>

        <div v-else class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th>名称</th>
                <th>描述</th>
                <th>厂商 / 模型</th>
                <th>工具</th>
                <th>定时任务</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="agent in agents" :key="agent.name">
                <td class="mono">{{ agent.name }}</td>
                <td>{{ agent.description || '—' }}</td>
                <td class="mono">{{ providerText(agent) }}</td>
                <td>
                  <span v-if="agent.tools.length === 0" class="muted">—</span>
                  <span v-for="tool in agent.tools" :key="tool" class="tag mono">{{ tool }}</span>
                </td>
                <td class="mono">
                  <span v-if="agent.schedules.length === 0" class="muted">—</span>
                  <div v-for="(schedule, index) in agent.schedules" :key="index">
                    {{ scheduleText(schedule) }}
                  </div>
                </td>
                <td>
                  <button class="btn btn-ghost btn-sm" @click="openAgent(agent.name)">详情</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>

    <!-- 详情 -->
    <template v-else>
      <div class="card">
        <div class="toolbar">
          <div>
            <button class="btn btn-ghost btn-sm" @click="backToList">← 返回列表</button>
            <span class="detail-name mono">{{ detail.name }}</span>
            <span class="muted">{{ detail.description || '（无描述）' }}</span>
          </div>
          <button class="btn btn-ghost btn-sm danger" @click="removeAgent">删除</button>
        </div>

        <nav class="tabs">
          <button
            v-for="item in TABS"
            :key="item.key"
            :class="['tab', { 'tab-active': tab === item.key }]"
            @click="selectTab(item.key)"
          >
            {{ item.label }}
          </button>
        </nav>

        <div v-if="actionError" class="state state-error">{{ actionError }}</div>
        <div v-if="actionNotice" class="state state-notice">{{ actionNotice }}</div>
      </div>

      <!-- 基本信息 -->
      <div v-if="tab === 'basic'" class="card">
        <h2 class="card-title">基本信息（只读）</h2>
        <dl class="kv">
          <dt>名称</dt>
          <dd class="mono">{{ detail.name }}</dd>
          <dt>描述</dt>
          <dd>{{ detail.description || '—' }}</dd>
          <dt>厂商 / 模型</dt>
          <dd class="mono">{{ providerText(detail) }}</dd>
          <dt>工具</dt>
          <dd>
            <span v-if="detail.tools.length === 0" class="muted">—</span>
            <span v-for="tool in detail.tools" :key="tool" class="tag mono">{{ tool }}</span>
          </dd>
          <dt>定时任务</dt>
          <dd class="mono">
            <span v-if="detail.schedules.length === 0" class="muted">—</span>
            <div v-for="(schedule, index) in detail.schedules" :key="index">
              {{ scheduleText(schedule) }} · {{ schedule.message }}
            </div>
          </dd>
        </dl>
        <p class="muted">
          定义真相源是 <span class="mono">.oryxos/agents/{{ detail.name }}/AGENT.md</span>；这里
          只呈现配置面，运行态在「定时任务」页。
        </p>
      </div>

      <!-- 生成 -->
      <div v-if="tab === 'generate'" class="card">
        <h2 class="card-title">一句话生成（草稿不落盘，检查后保存才生效）</h2>
        <textarea
          v-model="description"
          class="field editor editor-short"
          placeholder="每天早上九点查北京天气，把穿搭建议发到团队群"
        ></textarea>
        <div class="form-actions">
          <button class="btn btn-primary btn-sm" :disabled="generating" @click="generate">
            {{ generating ? '生成中…' : '生成' }}
          </button>
          <span class="muted">
            用作者模型（oryxos.author.model）生成；没配作者模型会明确报错，不会拿空模型去打端点
          </span>
        </div>

        <template v-if="Object.keys(generatedFiles).length > 0">
          <div v-for="(content, path) in generatedFiles" :key="path" class="file-block">
            <div class="file-head mono">{{ path }}</div>
            <textarea v-model="generatedFiles[path]" class="field editor"></textarea>
          </div>
          <div class="form-actions">
            <button class="btn btn-primary btn-sm" :disabled="savingGenerated" @click="saveGenerated">
              {{ savingGenerated ? '保存中…' : '保存并生效' }}
            </button>
            <span class="muted">保存会重新校验并重新注册；schedules 变了旧定时先注销</span>
          </div>
        </template>
      </div>

      <!-- 文件 -->
      <div v-if="tab === 'files'" class="card files">
        <div class="tree-pane">
          <h2 class="card-title">工作区</h2>
          <div v-if="treeError" class="state state-error">{{ treeError }}</div>
          <ul class="tree">
            <li
              v-for="node in flatTree"
              :key="node.path"
              :class="['tree-item', { 'tree-file': !node.directory, active: node.path === selectedPath }]"
              :style="{ paddingLeft: 8 + node.depth * 14 + 'px' }"
              @click="node.directory ? null : openFile(node.path)"
            >
              <span class="mono">{{ node.directory ? '▸ ' : '' }}{{ node.name }}</span>
            </li>
          </ul>
          <p class="muted">改 <span class="mono">agents/&lt;name&gt;/AGENT.md</span> 会走生命周期编排（校验 + 重注册）。</p>
        </div>

        <div class="editor-pane">
          <div v-if="fileLoading" class="state">正在读文件…</div>
          <div v-else-if="selectedPath === ''" class="state">从左边点一个文件。</div>
          <template v-else>
            <div class="file-head mono">{{ selectedPath }}</div>
            <textarea v-model="fileContent" class="field editor"></textarea>
            <div class="form-actions">
              <button class="btn btn-primary btn-sm" :disabled="fileSaving" @click="saveFile">
                {{ fileSaving ? '保存中…' : '保存' }}
              </button>
              <span class="muted">上限 1 MiB、UTF-8</span>
            </div>
          </template>
        </div>
      </div>

      <!-- 会话 -->
      <div v-if="tab === 'session'" class="card">
        <h2 class="card-title">管理台固定会话</h2>
        <div v-if="sessionError" class="state state-error">读取失败：{{ sessionError }}</div>
        <template v-else>
          <p v-if="session" class="muted mono">
            {{ session.sessionId }} · {{ session.status }}
          </p>
          <div class="bubbles">
            <p v-if="!session || session.messages.length === 0" class="state">
              还没有消息。这一条会话是固定的（admin:console:{{ detail.name }}），上下文会累积。
            </p>
            <div
              v-for="(message, index) in session ? session.messages : []"
              :key="index"
              :class="['bubble', message.role === 'user' ? 'bubble-user' : 'bubble-agent']"
            >
              <span class="bubble-role mono">{{ message.role }}</span>
              <div class="bubble-text">{{ message.content }}</div>
            </div>
          </div>
          <div class="form-actions">
            <input
              v-model="messageText"
              class="field grow"
              placeholder="说点什么…"
              @keyup.enter="sendMessage"
            />
            <button class="btn btn-primary btn-sm" :disabled="sending" @click="sendMessage">
              {{ sending ? '发送中…' : '发送' }}
            </button>
          </div>
        </template>
      </div>

      <!-- 记忆 -->
      <div v-if="tab === 'memory'" class="card">
        <h2 class="card-title">专属记忆（只读）</h2>
        <div v-if="memoryLoading" class="state">正在读取记忆…</div>
        <div v-else-if="memoryError" class="state state-error">读取失败：{{ memoryError }}</div>
        <template v-else>
          <h3 class="sub-title">核心记忆（全量注入 prompt，永不截断）</h3>
          <p v-if="memoryCoreEmpty" class="state">核心区还是空的。</p>
          <pre v-else class="memory-block">{{ memory.core }}</pre>

          <h3 class="sub-title">
            归档记忆（关键词检索只搜这一区）
            <span v-if="memory.truncated" class="tag tag-warn">归档区已截断</span>
          </h3>
          <p v-if="memoryArchivalEmpty" class="state">归档区还是空的。</p>
          <pre v-else class="memory-block">{{ memory.archival }}</pre>
          <p class="muted">
            落点是 <span class="mono">.oryxos/agents/{{ detail.name }}/MEMORY.md</span>，每个 Agent 一份。
          </p>
        </template>
      </div>
    </template>
  </section>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.detail-name {
  margin: 0 8px;
  font-weight: 700;
}

.danger {
  color: var(--ox-err);
}

.state-notice {
  color: var(--ox-ok);
}

.form {
  margin: 12px 0;
  padding: 12px;
  background: var(--ox-bg-soft);
  border: 1px solid var(--ox-border);
  border-radius: var(--ox-radius-control);
}

.form-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.form-label {
  flex: 0 0 92px;
  font-size: 12.5px;
  color: var(--ox-text-2);
}

.form-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 10px;
}

.grow {
  flex: 1;
  min-width: 200px;
}

.tabs {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 12px;
  border-top: 1px solid var(--ox-border);
  padding-top: 12px;
}

.tab {
  padding: 6px 14px;
  font-family: inherit;
  font-size: 13px;
  color: var(--ox-text-2);
  background: transparent;
  border: 1px solid transparent;
  border-radius: var(--ox-radius-chip);
  cursor: pointer;
}

.tab:hover {
  color: var(--ox-text);
  background: var(--ox-bg-soft);
}

.tab-active {
  color: var(--ox-brand-deep);
  background: var(--ox-glow);
  border-color: var(--ox-border);
}

.kv {
  display: grid;
  grid-template-columns: 110px 1fr;
  gap: 6px 12px;
  margin: 12px 0;
}

.kv dt {
  color: var(--ox-text-2);
  font-size: 12.5px;
}

.kv dd {
  margin: 0;
}

.editor {
  width: 100%;
  min-height: 220px;
  margin-top: 10px;
  font-family: var(--vp-font-family-mono);
  font-size: 12.5px;
  line-height: 1.6;
  resize: vertical;
}

.editor-short {
  min-height: 70px;
}

.file-block {
  margin-top: 14px;
}

.file-head {
  margin-top: 10px;
  color: var(--ox-text-2);
}

.files {
  display: flex;
  gap: 18px;
  align-items: flex-start;
}

.tree-pane {
  flex: 0 0 38%;
  min-width: 0;
}

.editor-pane {
  flex: 1;
  min-width: 0;
}

.tree {
  list-style: none;
  margin: 10px 0;
  padding: 0;
  max-height: 460px;
  overflow: auto;
  background: var(--ox-bg-soft);
  border: 1px solid var(--ox-border);
  border-radius: var(--ox-radius-control);
}

.tree-item {
  padding: 3px 6px;
  font-size: 12.5px;
  color: var(--ox-text);
}

.tree-file {
  cursor: pointer;
}

.tree-file:hover {
  background: var(--ox-glow);
}

.tree-item.active {
  color: var(--ox-brand-deep);
  background: var(--ox-glow);
}

.bubbles {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin: 12px 0;
  max-height: 460px;
  overflow: auto;
}

.bubble {
  padding: 10px 12px;
  border: 1px solid var(--ox-border);
  border-radius: var(--ox-radius-control);
  background: var(--ox-bg-soft);
}

.bubble-user {
  border-color: var(--ox-brand);
  background: var(--ox-glow);
}

.bubble-role {
  display: block;
  margin-bottom: 4px;
  font-size: 11px;
  color: var(--ox-text-2);
  text-transform: uppercase;
}

.bubble-text {
  white-space: pre-wrap;
  word-break: break-word;
}

.sub-title {
  margin: 16px 0 8px;
  font-size: 14px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 8px;
}

.memory-block {
  max-height: 320px;
}
</style>
