<script setup>
import { computed, onMounted, ref } from 'vue'
import { get } from '../api.js'

/**
 * 工具页（契约 admin-console.md §3-第 3 页）。
 *
 * 展示全量注册表，不按 Agent 过滤：这里回答的是「底座装了什么」，
 * 「某个 Agent 能用哪些」是另一个问题（要带 Profile 上下文），页面上不混算。
 */
const loading = ref(true)
const error = ref('')
const tools = ref([])
const expanded = ref('')

const total = computed(() => tools.value.length)

async function load() {
  loading.value = true
  error.value = ''
  try {
    tools.value = await get('/api/v1/tools')
  } catch (cause) {
    error.value = cause.message
    tools.value = []
  } finally {
    loading.value = false
  }
}

function originClass(origin) {
  return origin === 'builtin' ? 'tag-ok' : 'tag-warn'
}

function toggle(toolName) {
  expanded.value = expanded.value === toolName ? '' : toolName
}

onMounted(load)
</script>

<template>
  <section>
    <div v-if="loading" class="card state">正在读取工具注册表…</div>

    <div v-else-if="error" class="card state state-error">读取失败：{{ error }}</div>

    <div v-else-if="tools.length === 0" class="card state">暂无工具。</div>

    <div v-else class="card">
      <h2 class="card-title">共 {{ total }} 个工具</h2>
      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>名称</th>
              <th>描述</th>
              <th>来源</th>
              <th>入参 schema</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="tool in tools" :key="tool.name">
              <td class="mono">{{ tool.name }}</td>
              <td>{{ tool.description || '—' }}</td>
              <td>
                <span :class="['tag', originClass(tool.origin)]">{{ tool.origin }}</span>
                <div v-if="tool.origin !== tool.source" class="source-note mono">
                  {{ tool.source }}
                </div>
              </td>
              <td>
                <button class="btn btn-ghost btn-sm" type="button" @click="toggle(tool.name)">
                  {{ expanded === tool.name ? '收起' : '展开' }}
                </button>
                <pre v-if="expanded === tool.name" class="schema">{{
                  tool.inputSchema || '（无入参）'
                }}</pre>
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
  margin: 0;
}

.source-note {
  margin-top: 4px;
  font-size: 11px;
  color: var(--ox-text-2);
}

.schema {
  margin-top: 10px;
  max-width: 460px;
}
</style>
